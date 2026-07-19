//! Prism / MultiMC family importer: `mmc-pack.json` (component list) +
//! `instance.cfg` (INI) + a scanned mods folder (corpus: prism-launcher,
//! multimc dossiers).

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
struct MmcPack {
    #[serde(default)]
    components: Vec<MmcComponent>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct MmcComponent {
    uid: String,
    #[serde(default)]
    version: Option<String>,
}

/// Minimal INI value lookup (`key=value`, sections ignored) — the whole of
/// what `instance.cfg` needs.
fn ini_value(text: &str, key: &str) -> Option<String> {
    text.lines()
        .map(str::trim)
        .filter(|line| !line.starts_with('[') && !line.starts_with('#'))
        .filter_map(|line| line.split_once('='))
        .find(|(k, _)| k.trim() == key)
        .map(|(_, v)| v.trim().to_owned())
}

fn loader_from_components(components: &[MmcComponent]) -> Option<(String, String)> {
    for component in components {
        let kind = match component.uid.as_str() {
            "net.fabricmc.fabric-loader" => "fabric",
            "org.quiltmc.quilt-loader" => "quilt",
            "net.neoforged" => "neoforge",
            "net.minecraftforge" => "forge",
            _ => continue,
        };
        return Some((kind.to_owned(), component.version.clone().unwrap_or_default()));
    }
    None
}

/// The Prism/MultiMC importer.
pub struct PrismImporter;

impl Importer for PrismImporter {
    fn format(&self) -> ImportFormat {
        ImportFormat::PrismMultiMc
    }

    fn detect(&self, path: &Path) -> Option<Confidence> {
        if path.join("mmc-pack.json").is_file() {
            Some(Confidence::Certain)
        } else if path.join("instance.cfg").is_file() {
            Some(Confidence::Possible)
        } else {
            None
        }
    }

    fn enumerate(&self, path: &Path) -> Result<Vec<Candidate>, CoreError> {
        if !path.join("mmc-pack.json").is_file() {
            return Ok(Vec::new());
        }
        let name = std::fs::read_to_string(path.join("instance.cfg"))
            .ok()
            .and_then(|cfg| ini_value(&cfg, "name"))
            .unwrap_or_else(|| {
                path.file_name().map_or_else(String::new, |n| n.to_string_lossy().into_owned())
            });
        Ok(vec![Candidate {
            format: ImportFormat::PrismMultiMc,
            name,
            root: path.to_path_buf(),
            key: None,
        }])
    }

    fn import(&self, db: &Db, candidate: &Candidate) -> Result<ImportReport, CoreError> {
        let pack_path = candidate.root.join("mmc-pack.json");
        let bytes = std::fs::read(&pack_path).map_err(|e| CoreError::Io {
            action: "read mmc-pack.json",
            path: pack_path,
            source: e,
        })?;
        let pack: MmcPack = serde_json::from_slice(&bytes)
            .map_err(|source| CoreError::Json { context: "mmc-pack.json", source })?;

        let mut skipped = Vec::new();
        let mut notes = Vec::new();
        let game_version = pack
            .components
            .iter()
            .find(|component| component.uid == "net.minecraft")
            .and_then(|component| component.version.clone())
            .ok_or_else(|| CoreError::Json {
                context: "mmc-pack.json",
                source: serde::de::Error::custom("no net.minecraft component"),
            })?;
        let loader = loader_from_components(&pack.components);
        for component in &pack.components {
            let known = matches!(
                component.uid.as_str(),
                "net.minecraft"
                    | "net.fabricmc.fabric-loader"
                    | "org.quiltmc.quilt-loader"
                    | "net.neoforged"
                    | "net.minecraftforge"
                    | "org.lwjgl3"
                    | "org.lwjgl"
                    | "net.fabricmc.intermediary"
            );
            if !known {
                skipped.push(Skipped {
                    item: component.uid.clone(),
                    reason: "unmapped OneSix component (recorded for review)".into(),
                });
            }
        }
        if let Some((kind, version)) = &loader {
            notes.push(format!("loader {kind} {version} recorded in settings"));
        }

        // MultiMC family keeps the game dir in `.minecraft/` (older) or
        // `minecraft/` (newer Prism).
        let game_root = ["minecraft", ".minecraft"]
            .iter()
            .map(|dir| candidate.root.join(dir))
            .find(|p| p.is_dir());

        let instance_id = create_imported_instance(
            db,
            &InstanceSeed {
                name: candidate.name.clone(),
                game_version,
                loader,
                memory_mib: std::fs::read_to_string(candidate.root.join("instance.cfg"))
                    .ok()
                    .and_then(|cfg| ini_value(&cfg, "MaxMemAlloc"))
                    .and_then(|v| v.parse().ok()),
                format: ImportFormat::PrismMultiMc,
                source: candidate.root.clone(),
            },
        )?;

        let mut mods_imported = 0_usize;
        if let Some(game_root) = game_root {
            for scanned in scan_mods_dir(&game_root.join("mods"))? {
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
            notes.push(
                "mods hashed for platform recovery (sha1/sha512/murmur2); identify via \
                 the platform APIs when the content clients ship (LNC-9)"
                    .into(),
            );
        } else {
            skipped.push(Skipped {
                item: "minecraft/".into(),
                reason: "no game directory found in the instance".into(),
            });
        }
        Ok(ImportReport { instance_id, mods_imported, skipped, notes })
    }
}
