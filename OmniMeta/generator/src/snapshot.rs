//! Pinned upstream snapshots: the only stage that touches the network.
//!
//! A snapshot directory is a complete, self-contained capture of the upstream
//! inputs, with provenance (`snapshot.json`: source URLs + sha256 of every
//! captured file + retrieval time). Generation is a pure function of a
//! snapshot, so re-running `generate` against the same snapshot is
//! byte-identical (SOUL §21), and snapshots can be archived to reproduce any
//! published tree.
//!
//! Layout:
//!
//! ```text
//! <dir>/snapshot.json                 provenance manifest (not a generation input)
//! <dir>/version_manifest_v2.json     Mojang version manifest v2, raw bytes
//! <dir>/versions/<id>.json           per-version client.json, raw bytes
//! <dir>/java_runtime_all.json        Mojang java-runtime all.json, raw bytes
//! ```

use std::collections::{BTreeMap, BTreeSet};
use std::io::Read as _;
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use sha1::{Digest as _, Sha1};

use omni_meta_format::{sanitize_file_stem, sha256_hex};

use crate::error::GenError;
use crate::mojang::{JavaRuntimeAll, PistonVersion, VersionManifest};

/// Default Mojang version manifest URL (COMPLIANCE §1: official CDN).
pub const DEFAULT_VERSION_MANIFEST_URL: &str =
    "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

/// Default Mojang java-runtime catalog URL. The path hash is Mojang's stable
/// publication id for the `all.json` product (unchanged for years); it is
/// overridable via `--java-url` should Mojang ever republish.
pub const DEFAULT_JAVA_RUNTIME_URL: &str = "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

/// Inputs for [`fetch`].
#[derive(Debug, Clone)]
pub struct FetchOptions {
    /// Snapshot directory to create/populate.
    pub out: PathBuf,
    /// Version ids to capture; `None` captures every version in the manifest.
    pub versions: Option<BTreeSet<String>>,
    /// Version manifest URL.
    pub manifest_url: String,
    /// Java runtime catalog URL.
    pub java_url: String,
}

/// What [`fetch`] captured.
#[derive(Debug, Clone)]
pub struct FetchSummary {
    /// Number of per-version client.json documents captured.
    pub versions_fetched: usize,
    /// Total bytes written (raw upstream documents).
    pub bytes_written: u64,
}

/// Provenance manifest written as `snapshot.json`.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SnapshotManifest {
    /// Retrieval time (Unix seconds). Provenance only — never a generation input.
    retrieved_at_unix: u64,
    /// Where the version manifest came from.
    manifest_url: String,
    /// Where the java-runtime catalog came from.
    java_runtime_url: String,
    /// Every captured file with its origin and content hash.
    entries: Vec<SnapshotEntry>,
}

/// One captured file.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SnapshotEntry {
    /// Path relative to the snapshot directory (forward slashes).
    path: String,
    /// Source URL.
    url: String,
    /// Lowercase-hex sha256 of the captured bytes.
    sha256: String,
}

/// A loaded snapshot, ready for normalization.
#[derive(Debug, Clone)]
pub struct Snapshot {
    /// The version manifest.
    pub manifest: VersionManifest,
    /// Captured client.json documents, keyed by their own `id` field.
    pub versions: BTreeMap<String, PistonVersion>,
    /// The java-runtime catalog.
    pub java: JavaRuntimeAll,
}

fn sha1_hex(bytes: &[u8]) -> String {
    let digest = Sha1::digest(bytes);
    let mut out = String::with_capacity(40);
    for byte in digest {
        use std::fmt::Write as _;
        write!(out, "{byte:02x}").expect("writing to String cannot fail");
    }
    out
}

fn http_get(agent: &ureq::Agent, url: &str) -> Result<Vec<u8>, GenError> {
    let response = agent
        .get(url)
        .call()
        .map_err(|err| GenError::Http { url: url.to_owned(), message: err.to_string() })?;
    let mut bytes = Vec::new();
    response
        .into_reader()
        .take(256 * 1024 * 1024) // hard ceiling: no upstream document is near this
        .read_to_end(&mut bytes)
        .map_err(|err| GenError::Http { url: url.to_owned(), message: err.to_string() })?;
    Ok(bytes)
}

fn write_captured(
    dir: &Path,
    rel: &str,
    bytes: &[u8],
    url: &str,
    entries: &mut Vec<SnapshotEntry>,
) -> Result<u64, GenError> {
    let path = dir.join(rel.replace('/', std::path::MAIN_SEPARATOR_STR));
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)
            .map_err(|e| GenError::io("create snapshot directory", parent, e))?;
    }
    std::fs::write(&path, bytes).map_err(|e| GenError::io("write snapshot file", &path, e))?;
    entries.push(SnapshotEntry {
        path: rel.to_owned(),
        url: url.to_owned(),
        sha256: sha256_hex(bytes),
    });
    Ok(bytes.len() as u64)
}

/// Captures a snapshot from the live upstreams.
///
/// Every per-version document is integrity-checked against the sha1 the
/// manifest declares for it before being written (fail-fast at the network
/// boundary; local loads trust the captured bytes).
///
/// # Errors
///
/// [`GenError::Http`] on transport/status failures, [`GenError::Integrity`] on
/// hash mismatches, [`GenError::Usage`] when a requested version id is not in
/// the manifest, [`GenError::Io`]/[`GenError::Json`] on write/parse failures.
pub fn fetch(options: &FetchOptions) -> Result<FetchSummary, GenError> {
    let agent = ureq::AgentBuilder::new()
        .timeout(Duration::from_mins(2))
        .user_agent(concat!("omni-meta-gen/", env!("CARGO_PKG_VERSION"), " (wpaistudio.net)"))
        .build();

    let mut entries = Vec::new();
    let mut bytes_written = 0_u64;

    eprintln!("[snapshot] fetching version manifest: {}", options.manifest_url);
    let manifest_bytes = http_get(&agent, &options.manifest_url)?;
    let manifest: VersionManifest = serde_json::from_slice(&manifest_bytes)
        .map_err(|source| GenError::Json { origin: options.manifest_url.clone(), source })?;
    bytes_written += write_captured(
        &options.out,
        "version_manifest_v2.json",
        &manifest_bytes,
        &options.manifest_url,
        &mut entries,
    )?;

    if let Some(requested) = &options.versions {
        let known: BTreeSet<&str> = manifest.versions.iter().map(|v| v.id.as_str()).collect();
        let missing: Vec<&str> = requested
            .iter()
            .map(String::as_str)
            .filter(|id| !known.contains(id))
            .collect();
        if !missing.is_empty() {
            return Err(GenError::Usage(format!(
                "requested version id(s) not in the Mojang manifest: {}",
                missing.join(", ")
            )));
        }
    }

    let selected: Vec<_> = manifest
        .versions
        .iter()
        .filter(|v| options.versions.as_ref().is_none_or(|set| set.contains(&v.id)))
        .collect();
    let total = selected.len();
    let mut versions_fetched = 0_usize;
    for (index, version) in selected.iter().enumerate() {
        eprintln!("[snapshot] {}/{}: {} <- {}", index + 1, total, version.id, version.url);
        let bytes = http_get(&agent, &version.url)?;
        let actual_sha1 = sha1_hex(&bytes);
        if actual_sha1 != version.sha1.to_lowercase() {
            return Err(GenError::Integrity {
                detail: format!(
                    "client.json for {}: manifest declares sha1 {}, fetched bytes hash to {}",
                    version.id, version.sha1, actual_sha1
                ),
            });
        }
        let stem = sanitize_file_stem(&version.id)?;
        bytes_written += write_captured(
            &options.out,
            &format!("versions/{stem}.json"),
            &bytes,
            &version.url,
            &mut entries,
        )?;
        versions_fetched += 1;
    }

    eprintln!("[snapshot] fetching java-runtime catalog: {}", options.java_url);
    let java_bytes = http_get(&agent, &options.java_url)?;
    // Parse now so a broken upstream fails the snapshot, not a later generate.
    let _: JavaRuntimeAll = serde_json::from_slice(&java_bytes)
        .map_err(|source| GenError::Json { origin: options.java_url.clone(), source })?;
    bytes_written += write_captured(
        &options.out,
        "java_runtime_all.json",
        &java_bytes,
        &options.java_url,
        &mut entries,
    )?;

    let retrieved_at_unix =
        SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs());
    let provenance = SnapshotManifest {
        retrieved_at_unix,
        manifest_url: options.manifest_url.clone(),
        java_runtime_url: options.java_url.clone(),
        entries,
    };
    let provenance_bytes = serde_json::to_vec_pretty(&provenance).map_err(|source| {
        GenError::Json { origin: "snapshot.json (provenance)".to_owned(), source }
    })?;
    let provenance_path = options.out.join("snapshot.json");
    std::fs::write(&provenance_path, provenance_bytes)
        .map_err(|e| GenError::io("write snapshot provenance", &provenance_path, e))?;

    Ok(FetchSummary { versions_fetched, bytes_written })
}

fn read_file(path: &Path) -> Result<Vec<u8>, GenError> {
    std::fs::read(path).map_err(|e| GenError::io("read snapshot file", path, e))
}

/// Loads a snapshot directory into memory.
///
/// Version documents are keyed by their **own** `id` field (not the file
/// name), and iteration everywhere downstream is over sorted maps, so load
/// order can never influence output.
///
/// # Errors
///
/// [`GenError::Io`] for unreadable files, [`GenError::Json`] for documents
/// that no longer parse, [`GenError::Integrity`] for duplicate version ids.
pub fn load(dir: &Path) -> Result<Snapshot, GenError> {
    let manifest_path = dir.join("version_manifest_v2.json");
    let manifest: VersionManifest = serde_json::from_slice(&read_file(&manifest_path)?)
        .map_err(|source| GenError::Json { origin: manifest_path.display().to_string(), source })?;

    let versions_dir = dir.join("versions");
    let mut file_names: Vec<PathBuf> = std::fs::read_dir(&versions_dir)
        .map_err(|e| GenError::io("list snapshot versions", &versions_dir, e))?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| GenError::io("list snapshot versions", &versions_dir, e))?
        .into_iter()
        .map(|entry| entry.path())
        .filter(|path| path.extension().is_some_and(|ext| ext == "json"))
        .collect();
    file_names.sort();

    let mut versions = BTreeMap::new();
    for path in file_names {
        let version: PistonVersion = serde_json::from_slice(&read_file(&path)?)
            .map_err(|source| GenError::Json { origin: path.display().to_string(), source })?;
        if let Some(previous) = versions.insert(version.id.clone(), version) {
            return Err(GenError::Integrity {
                detail: format!(
                    "duplicate client.json id {:?} in snapshot (second file: {})",
                    previous.id,
                    path.display()
                ),
            });
        }
    }

    let java_path = dir.join("java_runtime_all.json");
    let java: JavaRuntimeAll = serde_json::from_slice(&read_file(&java_path)?)
        .map_err(|source| GenError::Json { origin: java_path.display().to_string(), source })?;

    Ok(Snapshot { manifest, versions, java })
}
