//! Official launcher importer: `launcher_profiles.json` inside a
//! `.minecraft` directory — one candidate per concrete profile (black-box
//! on-disk surface per ADR-0002; the shared game dir is *read*, never
//! adopted as shared mutable state — MUST NOT #4).

use std::collections::BTreeMap;
use std::path::Path;

use serde::Deserialize;

use crate::db::Db;
use crate::error::CoreError;
use crate::import::hash::scan_mods_dir;
use crate::import::{
    Candidate, Confidence, ImportFormat, ImportReport, Importer, InstanceSeed, Skipped,
    create_imported_instance,
};
use crate::store::{NewMod, PortStatus};

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LauncherProfiles {
    #[serde(default)]
    profiles: BTreeMap<String, Profile>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Profile {
    name: Option<String>,
    #[serde(default)]
    last_version_id: Option<String>,
    #[serde(default)]
    game_dir: Option<String>,
}

fn read_profiles(root: &Path) -> Result<LauncherProfiles, CoreError> {
    let path = root.join("launcher_profiles.json");
    let bytes = std::fs::read(&path)
        .map_err(|e| CoreError::Io { action: "read launcher_profiles.json", path, source: e })?;
    serde_json::from_slice(&bytes)
        .map_err(|source| CoreError::Json { context: "launcher_profiles.json", source })
}

/// The official-launcher importer.
pub struct OfficialImporter;

impl Importer for OfficialImporter {
    fn format(&self) -> ImportFormat {
        ImportFormat::OfficialLauncher
    }

    fn detect(&self, path: &Path) -> Option<Confidence> {
        if path.join("launcher_profiles.json").is_file() {
            Some(Confidence::Certain)
        } else {
            None
        }
    }

    fn enumerate(&self, path: &Path) -> Result<Vec<Candidate>, CoreError> {
        if self.detect(path).is_none() {
            return Ok(Vec::new());
        }
        let profiles = read_profiles(path)?;
        let mut candidates = Vec::new();
        for (key, profile) in &profiles.profiles {
            candidates.push(Candidate {
                format: ImportFormat::OfficialLauncher,
                name: profile
                    .name
                    .clone()
                    .filter(|name| !name.is_empty())
                    .unwrap_or_else(|| key.clone()),
                root: path.to_path_buf(),
                key: Some(key.clone()),
            });
        }
        Ok(candidates)
    }

    fn import(&self, db: &Db, candidate: &Candidate) -> Result<ImportReport, CoreError> {
        let profiles = read_profiles(&candidate.root)?;
        let key = candidate.key.clone().unwrap_or_default();
        let profile = profiles.profiles.get(&key).ok_or(CoreError::NotFound {
            what: "launcher profile",
            id: 0,
        })?;
        let mut skipped = Vec::new();
        let mut notes = Vec::new();

        let version = profile.last_version_id.clone().unwrap_or_default();
        if version.is_empty() || version.starts_with("latest-") {
            // Dynamic profiles have no pinned version to import faithfully.
            return Err(CoreError::Json {
                context: "launcher_profiles.json",
                source: serde::de::Error::custom(format!(
                    "profile {key:?} uses dynamic version {version:?}; create a new \
                     Omni instance and pick a concrete version instead"
                )),
            });
        }
        // Modded version ids (e.g. fabric-loader-0.16.9-1.21.1) are recorded
        // whole; the vanilla core version is the trailing MC-version segment.
        let (game_version, loader) = split_version_id(&version);
        if let Some((kind, loader_version)) = &loader {
            notes.push(format!("loader {kind} {loader_version} recorded in settings"));
        }

        let instance_id = create_imported_instance(
            db,
            &InstanceSeed {
                name: candidate.name.clone(),
                game_version,
                loader,
                memory_mib: None,
                format: ImportFormat::OfficialLauncher,
                source: candidate.root.clone(),
            },
        )?;

        // The profile's game dir (default: the shared .minecraft) is scanned
        // read-only; its mods become entities of the NEW per-instance dir.
        let game_dir = profile
            .game_dir
            .as_ref()
            .map_or_else(|| candidate.root.clone(), std::path::PathBuf::from);
        let mut mods_imported = 0_usize;
        for scanned in scan_mods_dir(&game_dir.join("mods"))? {
            db.with_store(|store| {
                store.upsert_mod(&NewMod {
                    instance_id,
                    file_name: scanned.file_name.clone(),
                    sha1: Some(scanned.sha1.clone()),
                    sha512: Some(scanned.sha512.clone()),
                    murmur2: Some(scanned.murmur2.clone()),
                    display_name: None,
                    version: None,
                    ecosystem: scanned.ecosystem,
                    enabled: scanned.enabled,
                    platform: None,
                    platform_project_id: None,
                    platform_file_id: None,
                    port_status: PortStatus::Unknown,
                })
            })?;
            mods_imported += 1;
        }
        if mods_imported == 0 {
            skipped.push(Skipped {
                item: "mods/".into(),
                reason: "no mods folder in the profile's game directory".into(),
            });
        }
        notes.push(
            "worlds and settings stay in the source .minecraft; the new instance has \
             its own game dir (shared mutable dirs are a corpus MUST NOT)"
                .into(),
        );
        Ok(ImportReport { instance_id, mods_imported, skipped, notes })
    }
}

/// Splits a `lastVersionId` into (vanilla version, optional loader record):
/// `1.21.1` → plain; `fabric-loader-0.16.9-1.21.1` / `neoforge-21.1.221` →
/// loader + trailing/embedded MC version where derivable.
fn split_version_id(version_id: &str) -> (String, Option<(String, String)>) {
    for (prefix, kind) in [("fabric-loader-", "fabric"), ("quilt-loader-", "quilt")] {
        if let Some(rest) = version_id.strip_prefix(prefix)
            && let Some((loader_version, mc)) = rest.rsplit_once('-')
        {
            return (mc.to_owned(), Some((kind.to_owned(), loader_version.to_owned())));
        }
    }
    if let Some(rest) = version_id.strip_prefix("neoforge-") {
        // NeoForge ids carry no MC version; 21.x maps to 1.21.x by convention,
        // but the mapping is left to the loader record (the vanilla version
        // cannot be derived reliably here).
        return (version_id.to_owned(), Some(("neoforge".into(), rest.to_owned())));
    }
    (version_id.to_owned(), None)
}
