//! Modrinth `.mrpack` importer: a ZIP whose `modrinth.index.json` describes
//! the pack (corpus: modrinth-app dossier; format documented publicly by
//! Modrinth).

use std::io::Read as _;
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
struct MrpackIndex {
    name: Option<String>,
    #[serde(default)]
    dependencies: std::collections::BTreeMap<String, String>,
    #[serde(default)]
    files: Vec<MrpackFile>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct MrpackFile {
    path: String,
    #[serde(default)]
    hashes: std::collections::BTreeMap<String, String>,
    #[serde(default)]
    env: Option<MrpackEnv>,
}

#[derive(Debug, Deserialize)]
struct MrpackEnv {
    #[serde(default)]
    client: Option<String>,
}

/// The `.mrpack` importer.
pub struct MrpackImporter;

fn read_index(path: &Path) -> Result<MrpackIndex, CoreError> {
    let file = std::fs::File::open(path).map_err(|e| CoreError::Io {
        action: "open mrpack",
        path: path.to_path_buf(),
        source: e,
    })?;
    let mut archive = zip::ZipArchive::new(file).map_err(|e| CoreError::Io {
        action: "read mrpack zip",
        path: path.to_path_buf(),
        source: std::io::Error::other(e.to_string()),
    })?;
    let mut entry = archive.by_name("modrinth.index.json").map_err(|e| CoreError::Io {
        action: "find modrinth.index.json",
        path: path.to_path_buf(),
        source: std::io::Error::other(e.to_string()),
    })?;
    let mut bytes = Vec::new();
    entry.read_to_end(&mut bytes).map_err(|e| CoreError::Io {
        action: "read modrinth.index.json",
        path: path.to_path_buf(),
        source: e,
    })?;
    serde_json::from_slice(&bytes)
        .map_err(|source| CoreError::Json { context: "modrinth.index.json", source })
}

impl Importer for MrpackImporter {
    fn format(&self) -> ImportFormat {
        ImportFormat::Mrpack
    }

    fn detect(&self, path: &Path) -> Option<Confidence> {
        if path.extension().is_some_and(|ext| ext == "mrpack") && path.is_file() {
            Some(Confidence::Certain)
        } else {
            None
        }
    }

    fn enumerate(&self, path: &Path) -> Result<Vec<Candidate>, CoreError> {
        if self.detect(path).is_none() {
            return Ok(Vec::new());
        }
        let index = read_index(path)?;
        Ok(vec![Candidate {
            format: ImportFormat::Mrpack,
            name: index.name.unwrap_or_else(|| {
                path.file_stem().map_or_else(String::new, |n| n.to_string_lossy().into_owned())
            }),
            root: path.to_path_buf(),
            key: None,
        }])
    }

    fn import(&self, db: &Db, candidate: &Candidate) -> Result<ImportReport, CoreError> {
        let index = read_index(&candidate.root)?;
        let mut skipped = Vec::new();
        let mut notes = Vec::new();

        let game_version =
            index.dependencies.get("minecraft").cloned().ok_or_else(|| CoreError::Json {
                context: "modrinth.index.json",
                source: serde::de::Error::custom("dependencies.minecraft missing"),
            })?;
        let loader = ["fabric-loader", "quilt-loader", "neoforge", "forge"]
            .iter()
            .find_map(|key| {
                index.dependencies.get(*key).map(|version| {
                    let kind = key.trim_end_matches("-loader").to_owned();
                    (kind, version.clone())
                })
            });
        if let Some((kind, version)) = &loader {
            notes.push(format!("loader {kind} {version} recorded in settings"));
        }
        let ecosystem = loader.as_ref().map_or(Ecosystem::Unknown, |(kind, _)| match kind.as_str()
        {
            "fabric" | "quilt" => Ecosystem::Fabric,
            "neoforge" => Ecosystem::Neoforge,
            "forge" => Ecosystem::Forge,
            _ => Ecosystem::Unknown,
        });

        let instance_id = create_imported_instance(
            db,
            &InstanceSeed {
                name: candidate.name.clone(),
                game_version,
                loader,
                memory_mib: None,
                format: ImportFormat::Mrpack,
                source: candidate.root.clone(),
            },
        )?;

        let mut mods_imported = 0_usize;
        for file in &index.files {
            if file.env.as_ref().and_then(|env| env.client.as_deref()) == Some("unsupported") {
                skipped.push(Skipped {
                    item: file.path.clone(),
                    reason: "server-only file (client env: unsupported)".into(),
                });
                continue;
            }
            let Some(file_name) = file.path.strip_prefix("mods/").map(str::to_owned) else {
                skipped.push(Skipped {
                    item: file.path.clone(),
                    reason: "non-mod pack file (packs/shaders/configs land with overrides \
                             adoption in the install job)"
                        .into(),
                });
                continue;
            };
            db.with_store(|store| {
                store.upsert_mod(&NewMod {
                    instance_id,
                    file_name: file_name.clone(),
                    sha1: file.hashes.get("sha1").map(|h| h.to_lowercase()),
                    sha512: file.hashes.get("sha512").map(|h| h.to_lowercase()),
                    murmur2: None,
                    display_name: None,
                    version: None,
                    ecosystem,
                    enabled: true,
                    platform: Some("modrinth".into()),
                    platform_project_id: None,
                    platform_file_id: None,
                    port_status: PortStatus::Unknown,
                })
            })?;
            mods_imported += 1;
        }
        notes.push("mod files download on first install job (hash-verified)".into());
        Ok(ImportReport { instance_id, mods_imported, skipped, notes })
    }
}
