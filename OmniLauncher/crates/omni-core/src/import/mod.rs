//! Migration importers (LNC-7, D7): all four MVP surfaces.
//!
//! One contract, four implementations: CurseForge App
//! (`minecraftinstance.json`), Prism/MultiMC (`mmc-pack.json` +
//! `instance.cfg`), Modrinth `.mrpack`, official launcher
//! (`launcher_profiles.json`). Foreign formats are **read-only surfaces** —
//! they are parsed leniently, mapped into the canonical store (instances as
//! component stacks, mods as entities), and never written back (LNC-2).
//!
//! Every import produces an [`ImportReport`]: what mapped, what didn't, and
//! why — typed, never log-only. Loader information that OmniLoader will later
//! satisfy through compat layers is recorded verbatim in the instance settings
//! under [`IMPORTED_LOADER_KEY`] (the honest state until bricks land it as a
//! stack component).

mod curseforge;
mod hash;
mod mrpack;
mod official;
mod prism;
mod stage;

pub use curseforge::CurseForgeImporter;
pub use hash::{murmur2_fingerprint, scan_mods_dir, ScannedMod};
pub use mrpack::{
    extract_mrpack_overrides, pending_downloads_from_settings, store_pending_downloads,
    MrpackImporter, PendingDownload, PENDING_DOWNLOADS_KEY,
};
pub use official::OfficialImporter;
pub use prism::PrismImporter;
pub use stage::{
    copy_mod_jars, materialize_instance_mods, source_mods_dirs, StageReport,
};

use std::fmt;
use std::path::{Path, PathBuf};

use crate::db::Db;
use crate::error::CoreError;
use crate::store::{ComponentSource, InstanceKind, NewInstance, StackEntryInput};

/// Settings key holding the imported loader record
/// (`{"kind": "neoforge", "version": "21.1.221"}`).
pub const IMPORTED_LOADER_KEY: &str = "importedLoader";
/// Settings key holding the import provenance (`{"format": ..., "source": ...}`).
pub const IMPORT_SOURCE_KEY: &str = "importedFrom";

/// How sure a detector is about a path (LNC-7 `detect -> Confidence`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Confidence {
    /// Structure present but incomplete (e.g. folder layout without manifest).
    Possible,
    /// The format's defining manifest parsed.
    Certain,
}

/// The four MVP import formats.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ImportFormat {
    /// CurseForge App instance folder.
    CurseForge,
    /// Prism / MultiMC family instance folder.
    PrismMultiMc,
    /// Modrinth modpack file (`.mrpack`).
    Mrpack,
    /// Official launcher `.minecraft` (profiles).
    OfficialLauncher,
}

impl fmt::Display for ImportFormat {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::CurseForge => "curseforge",
            Self::PrismMultiMc => "prism-multimc",
            Self::Mrpack => "mrpack",
            Self::OfficialLauncher => "official-launcher",
        })
    }
}

/// One importable thing found by `enumerate` (an instance folder, a pack
/// file, a profile).
#[derive(Debug, Clone)]
pub struct Candidate {
    /// Which importer produced it.
    pub format: ImportFormat,
    /// Display name from the source format.
    pub name: String,
    /// The path `import` will read (folder, file, or profiles file).
    pub root: PathBuf,
    /// Format-internal key when one path holds several candidates
    /// (official-launcher profile id).
    pub key: Option<String>,
}

/// One thing that could not be mapped, with the reason (typed issues, LNC-7).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Skipped {
    /// What was skipped (file name, profile id, field).
    pub item: String,
    /// Why.
    pub reason: String,
}

/// The outcome of one import.
#[derive(Debug, Clone)]
pub struct ImportReport {
    /// The created instance row id.
    pub instance_id: i64,
    /// Mods mapped into entities.
    pub mods_imported: usize,
    /// Everything that did not map, with reasons.
    pub skipped: Vec<Skipped>,
    /// Human-relevant notes (loader recorded for compat, dirs to copy, …).
    pub notes: Vec<String>,
}

/// The importer contract (LNC-7): `detect -> enumerate -> import`.
pub trait Importer {
    /// This importer's format.
    fn format(&self) -> ImportFormat;

    /// Confidence that `path` is this importer's surface (`None` = not ours).
    fn detect(&self, path: &Path) -> Option<Confidence>;

    /// Importable candidates at `path` (an instances root or a single item).
    ///
    /// # Errors
    ///
    /// [`CoreError::Io`]/[`CoreError::Json`] when the surface is unreadable.
    fn enumerate(&self, path: &Path) -> Result<Vec<Candidate>, CoreError>;

    /// Imports one candidate into the store.
    ///
    /// # Errors
    ///
    /// [`CoreError`] variants on store/parse failures; partial mappings are
    /// NOT errors — they land in [`ImportReport::skipped`].
    fn import(&self, db: &Db, candidate: &Candidate) -> Result<ImportReport, CoreError>;
}

/// Runs every shipped detector against a path, best confidence first.
pub fn detect_all(path: &Path) -> Vec<(ImportFormat, Confidence)> {
    let importers: [&dyn Importer; 4] = [
        &CurseForgeImporter,
        &PrismImporter,
        &MrpackImporter,
        &OfficialImporter,
    ];
    let mut found: Vec<(ImportFormat, Confidence)> = importers
        .iter()
        .filter_map(|importer| importer.detect(path).map(|c| (importer.format(), c)))
        .collect();
    found.sort_by_key(|(_, confidence)| std::cmp::Reverse(*confidence));
    found
}

/// Shared: creates the instance row + stack + provenance settings.
pub(crate) struct InstanceSeed {
    pub name: String,
    pub game_version: String,
    pub loader: Option<(String, String)>,
    pub memory_mib: Option<u64>,
    pub format: ImportFormat,
    pub source: PathBuf,
}

pub(crate) fn create_imported_instance(db: &Db, seed: &InstanceSeed) -> Result<i64, CoreError> {
    let mut settings = serde_json::json!({
        IMPORT_SOURCE_KEY: {
            "format": seed.format.to_string(),
            "source": seed.source.to_string_lossy(),
        },
    });
    if let Some((kind, version)) = &seed.loader {
        settings[IMPORTED_LOADER_KEY] = serde_json::json!({ "kind": kind, "version": version });
    }
    // A memory value of 0 is not "allocate zero" — it is how CurseForge (and
    // others) spell "the user set no per-instance override, use the app
    // default". Persisting it verbatim produced `-Xmx0M` and an instant JVM
    // crash on the first imported pack that had never had its memory set (ATM10
    // To The Sky, 2026-07-20). A non-positive value is dropped so the launch
    // read site falls back to the default, exactly as a missing key does.
    if let Some(memory) = seed.memory_mib.filter(|&mib| mib > 0) {
        settings["memoryMib"] = serde_json::json!(memory);
    }
    db.with_store(|store| {
        let instance = store.create_instance(&NewInstance {
            name: seed.name.clone(),
            icon: None,
            kind: InstanceKind::Client,
            game_version: seed.game_version.clone(),
        })?;
        store.update_instance_settings(instance.id, &settings)?;
        store.set_component_stack(
            instance.id,
            &[StackEntryInput {
                uid: "net.minecraft".to_owned(),
                version: seed.game_version.clone(),
                pinned: false,
                source: ComponentSource::Meta,
                custom: None,
            }],
        )?;
        Ok(instance.id)
    })
}
