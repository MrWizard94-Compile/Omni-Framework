//! Installation (LNC-5/6): meta-resolved, hash-verified, resumable.
//!
//! Layout under the launcher data root (MUST NOT #4: game dirs are strictly
//! per-instance; everything shared is immutable and content-addressed):
//!
//! ```text
//! <data>/objects/<2>/<sha1>          shared blob cache (libraries, jars, java files)
//! <data>/assets/indexes/<id>.json    official asset layout (immutable, shared)
//! <data>/assets/objects/<2>/<hash>
//! <data>/java/<component>/<ver>/     installed Mojang runtimes (LNC-6)
//! <data>/instances/<id>/game/        the instance's mutable game dir
//! <data>/instances/<id>/natives/     natives dir (created; modern LWJGL self-extracts)
//! <data>/instances/<id>/logs/        captured process logs
//! ```

mod blob;
mod executor;
mod plan;

pub use blob::{BlobError, BlobSource, ContentCache, UreqBlobSource, sha1_hex};
pub use executor::{InstallExecutor, InstallSpec, BATCH_SIZE};
pub use plan::{
    AssetIndexDoc, FileKind, JavaManifestDoc, PlannedFile, java_platform_key, plan_assets,
    plan_game_files, plan_java_files, select_java_offer,
};

use std::path::PathBuf;

use omni_meta_client::{MetaClient, MetaSource};
use omni_meta_format::ComponentPayload;

use crate::error::CoreError;
use crate::store::StackEntry;

/// All filesystem locations, derived from one data root.
#[derive(Debug, Clone)]
pub struct Paths {
    /// The launcher data root.
    pub data_root: PathBuf,
}

impl Paths {
    /// Creates the path map (nothing is touched on disk).
    pub fn new(data_root: impl Into<PathBuf>) -> Self {
        Self { data_root: data_root.into() }
    }

    /// Shared content-addressed blob store.
    pub fn objects_root(&self) -> PathBuf {
        self.data_root.join("objects")
    }

    /// Official-layout asset root (shared, immutable).
    pub fn assets_root(&self) -> PathBuf {
        self.data_root.join("assets")
    }

    /// `assets/objects` (content-addressed by the asset hash itself).
    pub fn asset_objects_root(&self) -> PathBuf {
        self.assets_root().join("objects")
    }

    /// `assets/indexes`.
    pub fn asset_indexes_dir(&self) -> PathBuf {
        self.assets_root().join("indexes")
    }

    /// Root of installed Mojang java runtimes.
    pub fn java_root(&self) -> PathBuf {
        self.data_root.join("java")
    }

    /// Installation dir of one runtime.
    pub fn java_install_dir(&self, component: &str, version: &str) -> PathBuf {
        self.java_root().join(component).join(version)
    }

    /// The java executable inside a runtime installation.
    pub fn java_exe(&self, component: &str, version: &str) -> PathBuf {
        let bin = self.java_install_dir(component, version).join("bin");
        if cfg!(windows) { bin.join("java.exe") } else { bin.join("java") }
    }

    /// An instance's private directory.
    pub fn instance_dir(&self, instance_id: i64) -> PathBuf {
        self.data_root.join("instances").join(instance_id.to_string())
    }

    /// The instance's mutable game directory (MUST NOT #4: never shared).
    pub fn game_dir(&self, instance_id: i64) -> PathBuf {
        self.instance_dir(instance_id).join("game")
    }

    /// The instance's natives directory (exists for `${natives_directory}`;
    /// modern LWJGL extracts its own natives from classpath jars).
    pub fn natives_dir(&self, instance_id: i64) -> PathBuf {
        self.instance_dir(instance_id).join("natives")
    }

    /// Captured-log directory for the instance's processes.
    pub fn logs_dir(&self, instance_id: i64) -> PathBuf {
        self.instance_dir(instance_id).join("logs")
    }
}

/// Everything install/launch needs from the meta service for one instance:
/// the resolved stack payloads (requires-closure included) and the java
/// runtime catalog payloads.
#[derive(Debug, Clone)]
pub struct ResolvedStack {
    /// Stack payloads in resolution order (stack entries first, then their
    /// `requires` closure, deduplicated).
    pub payloads: Vec<ComponentPayload>,
    /// Every `net.minecraft.java` version payload, newest first.
    pub java_payloads: Vec<ComponentPayload>,
}

impl ResolvedStack {
    /// The `net.minecraft` payload of the stack.
    ///
    /// # Errors
    ///
    /// [`CoreError::NotFound`] when the stack has no game component.
    pub fn game(&self) -> Result<&ComponentPayload, CoreError> {
        self.payloads
            .iter()
            .find(|payload| payload.uid == "net.minecraft")
            .ok_or(CoreError::NotFound { what: "net.minecraft stack component", id: 0 })
    }
}

/// Wraps a meta-client failure with the context the user needs: what we were
/// doing, and *which* service could not answer. Naming the source matters —
/// "the meta service is unreachable" is far less useful without the URL that
/// was actually tried.
fn meta_err(source: &str, err: &omni_meta_client::MetaError) -> CoreError {
    CoreError::Io {
        action: "resolve this instance's components",
        path: PathBuf::from(source),
        source: std::io::Error::other(err.to_string()),
    }
}

/// Resolves an instance's component stack (plus the exact-version `requires`
/// closure — the LWJGL split, future loader deps) and the java catalog.
///
/// # Errors
///
/// Meta transport/verification failures as [`CoreError::Io`] (`action =
/// "resolve meta"`); absent components/versions as reported by the client.
pub fn resolve_stack<S: MetaSource>(
    meta: &MetaClient<S>,
    stack: &[StackEntry],
) -> Result<ResolvedStack, CoreError> {
    let source = meta.describe_source();
    let (root, _) = meta.root().map_err(|err| meta_err(&source, &err))?;
    let mut payloads: Vec<ComponentPayload> = Vec::new();
    let mut queue: Vec<(String, String)> = stack
        .iter()
        .map(|entry| (entry.uid.clone(), entry.version.clone()))
        .collect();
    let mut seen: std::collections::BTreeSet<(String, String)> = std::collections::BTreeSet::new();
    while let Some((uid, version)) = queue.pop() {
        if !seen.insert((uid.clone(), version.clone())) {
            continue;
        }
        let index = meta.component_index(&root, &uid).map_err(|err| meta_err(&source, &err))?;
        let payload = meta.payload(&index, &version).map_err(|err| meta_err(&source, &err))?;
        for requirement in &payload.requires {
            // v1 range dialect: exact pins resolve; open ranges are satisfied
            // by the stack itself (validated at stack-build time).
            if let Some(range) = &requirement.range {
                queue.push((requirement.uid.clone(), range.clone()));
            }
        }
        payloads.push(payload);
    }

    let java_index = meta.component_index(&root, "net.minecraft.java").map_err(|err| meta_err(&source, &err))?;
    let mut java_payloads = Vec::new();
    for summary in &java_index.versions {
        java_payloads.push(meta.payload(&java_index, &summary.version).map_err(|err| meta_err(&source, &err))?);
    }
    Ok(ResolvedStack { payloads, java_payloads })
}
