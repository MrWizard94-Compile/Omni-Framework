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
        let (game_version, loader) = resolve_version(&candidate.root, &version)?;
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

/// The vanilla version a modded version JSON extends (`inheritsFrom`) — the
/// authoritative answer the official launcher itself uses. `None` when the
/// version JSON is absent or carries no `inheritsFrom` (vanilla profiles).
fn inherits_from(root: &Path, version_id: &str) -> Option<String> {
    let path = root.join("versions").join(version_id).join(format!("{version_id}.json"));
    let bytes = std::fs::read(path).ok()?;
    let value: serde_json::Value = serde_json::from_slice(&bytes).ok()?;
    value
        .get("inheritsFrom")
        .and_then(|inherits| inherits.as_str())
        .filter(|inherits| !inherits.is_empty())
        .map(str::to_owned)
}

/// The loader recorded by a `lastVersionId`, when its shape names one.
/// Recognized shapes (all four are launcher-installer conventions):
/// `fabric-loader-<ver>-<mc>`, `quilt-loader-<ver>-<mc>`, `neoforge-<ver>`,
/// `<mc>-forge-<ver>`.
fn loader_from_version_id(version_id: &str) -> Option<(String, String)> {
    for (prefix, kind) in [("fabric-loader-", "fabric"), ("quilt-loader-", "quilt")] {
        if let Some(rest) = version_id.strip_prefix(prefix)
            && let Some((loader_version, _mc)) = rest.rsplit_once('-')
        {
            return Some((kind.to_owned(), loader_version.to_owned()));
        }
    }
    if let Some(rest) = version_id.strip_prefix("neoforge-") {
        return Some(("neoforge".to_owned(), rest.to_owned()));
    }
    if let Some((_mc, loader_version)) = version_id.split_once("-forge-") {
        return Some(("forge".to_owned(), loader_version.to_owned()));
    }
    None
}

/// The vanilla version a `lastVersionId` implies **by shape alone**, used only
/// when no version JSON is available. Deliberately conservative: shapes that
/// do not embed the game version (NeoForge) yield `None` rather than a guess,
/// because a wrong `game_version` produces an instance that can never resolve
/// against the meta service.
fn game_version_from_shape(version_id: &str) -> Option<String> {
    for prefix in ["fabric-loader-", "quilt-loader-"] {
        if let Some(rest) = version_id.strip_prefix(prefix)
            && let Some((_loader_version, mc)) = rest.rsplit_once('-')
        {
            return Some(mc.to_owned());
        }
    }
    if let Some((mc, _)) = version_id.split_once("-forge-") {
        return Some(mc.to_owned());
    }
    if loader_from_version_id(version_id).is_none() {
        // A plain vanilla profile: the id *is* the version.
        return Some(version_id.to_owned());
    }
    None
}

/// Resolves a profile's `lastVersionId` into (vanilla version, loader record).
/// `inheritsFrom` from the installed version JSON wins; shape parsing is the
/// offline fallback.
///
/// # Errors
///
/// [`CoreError::Json`] with an actionable message when the vanilla version
/// cannot be determined — importing a bogus `game_version` would create an
/// instance that can never install or launch.
fn resolve_version(
    root: &Path,
    version_id: &str,
) -> Result<(String, Option<(String, String)>), CoreError> {
    let loader = loader_from_version_id(version_id);
    let game_version = inherits_from(root, version_id)
        .or_else(|| game_version_from_shape(version_id))
        .ok_or_else(|| CoreError::Json {
            context: "launcher_profiles.json",
            source: serde::de::Error::custom(format!(
                "cannot determine the Minecraft version behind {version_id:?}: its \
                 version JSON (versions/{version_id}/{version_id}.json) is missing or \
                 has no inheritsFrom. Create a new Omni instance and pick the version \
                 explicitly instead."
            )),
        })?;
    Ok((game_version, loader))
}
