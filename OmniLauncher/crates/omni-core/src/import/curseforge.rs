//! CurseForge App importer: one instance folder = one `minecraftinstance.json`
//! (shape validated against a real ATM10 instance, 2026-07-19 — black-box
//! on-disk surface per ADR-0002).

use std::path::Path;

use serde::Deserialize;

use crate::db::Db;
use crate::error::CoreError;
use crate::import::{
    Candidate, Confidence, ImportFormat, ImportReport, Importer, InstanceSeed, Skipped,
    create_imported_instance,
};
use crate::store::{Ecosystem, NewMod, PortStatus};

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct MinecraftInstance {
    name: Option<String>,
    game_version: Option<String>,
    #[serde(default)]
    base_mod_loader: Option<BaseModLoader>,
    #[serde(default)]
    allocated_memory: Option<u64>,
    #[serde(default)]
    installed_addons: Vec<InstalledAddon>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct BaseModLoader {
    /// e.g. `neoforge-21.1.221`, `forge-47.3.0`, `fabric-0.16.9-...`.
    name: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct InstalledAddon {
    /// Upstream spells this `addonID` (capital ID), which `camelCase` would
    /// render as `addonId` — hence the explicit rename.
    #[serde(default, rename = "addonID")]
    addon_id: Option<i64>,
    name: Option<String>,
    #[serde(default)]
    is_enabled: Option<bool>,
    #[serde(default)]
    installed_file: Option<InstalledFile>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct InstalledFile {
    id: Option<i64>,
    file_name: Option<String>,
    file_name_on_disk: Option<String>,
    #[serde(default)]
    package_fingerprint: Option<u64>,
    #[serde(default)]
    hashes: Vec<CfHash>,
}

#[derive(Debug, Deserialize)]
struct CfHash {
    /// 1 = sha1, 2 = md5 (CurseForge dialect).
    #[serde(rename = "type")]
    hash_type: u32,
    value: String,
}

/// Splits `neoforge-21.1.221` into (`neoforge`, `21.1.221`).
fn parse_loader(name: &str) -> (String, String) {
    match name.split_once('-') {
        Some((kind, version)) => (kind.to_owned(), version.to_owned()),
        None => (name.to_owned(), String::new()),
    }
}

fn loader_ecosystem(kind: &str) -> Ecosystem {
    match kind {
        "fabric" | "quilt" => Ecosystem::Fabric,
        "neoforge" => Ecosystem::Neoforge,
        "forge" => Ecosystem::Forge,
        _ => Ecosystem::Unknown,
    }
}

/// The CurseForge App importer.
pub struct CurseForgeImporter;

impl Importer for CurseForgeImporter {
    fn format(&self) -> ImportFormat {
        ImportFormat::CurseForge
    }

    fn detect(&self, path: &Path) -> Option<Confidence> {
        if path.join("minecraftinstance.json").is_file() {
            Some(Confidence::Certain)
        } else if path.is_dir()
            && std::fs::read_dir(path).is_ok_and(|mut entries| {
                entries.any(|e| {
                    e.is_ok_and(|e| e.path().join("minecraftinstance.json").is_file())
                })
            })
        {
            // An instances root containing instance folders.
            Some(Confidence::Possible)
        } else {
            None
        }
    }

    fn enumerate(&self, path: &Path) -> Result<Vec<Candidate>, CoreError> {
        let mut roots = Vec::new();
        if path.join("minecraftinstance.json").is_file() {
            roots.push(path.to_path_buf());
        } else if path.is_dir() {
            let mut entries: Vec<_> = std::fs::read_dir(path)
                .map_err(|e| CoreError::Io {
                    action: "list instances root",
                    path: path.to_path_buf(),
                    source: e,
                })?
                .filter_map(Result::ok)
                .map(|e| e.path())
                .filter(|p| p.join("minecraftinstance.json").is_file())
                .collect();
            entries.sort();
            roots.extend(entries);
        }
        let mut candidates = Vec::new();
        for root in roots {
            let manifest = parse_manifest(&root)?;
            candidates.push(Candidate {
                format: ImportFormat::CurseForge,
                name: manifest.name.unwrap_or_else(|| {
                    root.file_name().map_or_else(String::new, |n| n.to_string_lossy().into_owned())
                }),
                root,
                key: None,
            });
        }
        Ok(candidates)
    }

    fn import(&self, db: &Db, candidate: &Candidate) -> Result<ImportReport, CoreError> {
        let manifest = parse_manifest(&candidate.root)?;
        let mut skipped = Vec::new();
        let mut notes = Vec::new();

        let game_version = manifest.game_version.clone().ok_or_else(|| CoreError::Json {
            context: "minecraftinstance.json",
            source: serde::de::Error::custom("missing gameVersion"),
        })?;
        let loader = manifest
            .base_mod_loader
            .as_ref()
            .and_then(|loader| loader.name.as_deref())
            .map(parse_loader);
        let loader_kind = loader.as_ref().map(|(kind, _)| kind.clone());
        if let Some((kind, version)) = &loader {
            notes.push(format!(
                "loader {kind} {version} recorded in settings; it will map to an \
                 OmniLoader compat component when compat layers ship"
            ));
        }
        let instance_id = create_imported_instance(
            db,
            &InstanceSeed {
                name: candidate.name.clone(),
                game_version,
                loader,
                memory_mib: manifest.allocated_memory,
                format: ImportFormat::CurseForge,
                source: candidate.root.clone(),
            },
        )?;

        let ecosystem =
            loader_kind.as_deref().map_or(Ecosystem::Unknown, loader_ecosystem);
        let mut mods_imported = 0_usize;
        for addon in &manifest.installed_addons {
            let Some(file) = &addon.installed_file else {
                skipped.push(Skipped {
                    item: addon.name.clone().unwrap_or_else(|| "unnamed addon".into()),
                    reason: "no installedFile record".into(),
                });
                continue;
            };
            let Some(file_name) =
                file.file_name_on_disk.clone().or_else(|| file.file_name.clone())
            else {
                skipped.push(Skipped {
                    item: addon.name.clone().unwrap_or_else(|| "unnamed addon".into()),
                    reason: "no file name recorded".into(),
                });
                continue;
            };
            let sha1 = file
                .hashes
                .iter()
                .find(|hash| hash.hash_type == 1)
                .map(|hash| hash.value.to_lowercase());
            db.with_store(|store| {
                store.upsert_mod(&NewMod {
                    instance_id,
                    file_name: file_name.clone(),
                    sha1: sha1.clone(),
                    sha512: None,
                    murmur2: file.package_fingerprint.map(|fp| fp.to_string()),
                    display_name: addon.name.clone(),
                    version: None,
                    ecosystem,
                    enabled: addon.is_enabled.unwrap_or(true),
                    platform: Some("curseforge".into()),
                    platform_project_id: addon.addon_id.map(|id| id.to_string()),
                    platform_file_id: file.id.map(|id| id.to_string()),
                    port_status: PortStatus::Unknown,
                })
            })?;
            mods_imported += 1;
        }
        notes.push(
            "mod jars, worlds, and configs remain in the source folder; file adoption \
             runs with the first install/repair job"
                .into(),
        );
        Ok(ImportReport { instance_id, mods_imported, skipped, notes })
    }
}

fn parse_manifest(root: &Path) -> Result<MinecraftInstance, CoreError> {
    let path = root.join("minecraftinstance.json");
    let bytes = std::fs::read(&path)
        .map_err(|e| CoreError::Io { action: "read minecraftinstance.json", path, source: e })?;
    serde_json::from_slice(&bytes)
        .map_err(|source| CoreError::Json { context: "minecraftinstance.json", source })
}
