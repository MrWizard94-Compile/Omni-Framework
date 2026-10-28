//! Modrinth `.mrpack` importer: a ZIP whose `modrinth.index.json` describes
//! the pack (corpus: modrinth-app dossier; format documented publicly by
//! Modrinth).
//!
//! CDN-hosted files are recorded as [`PENDING_DOWNLOADS_KEY`] entries so the
//! install job can hash-verify them into the instance game dir (ROADMAP
//! "mrpack remote download"). Overrides inside the ZIP are extracted on import.

use std::io::Read as _;
use std::path::{Path, PathBuf};

use serde::Deserialize;
use serde_json::json;

use crate::db::Db;
use crate::error::CoreError;
use crate::import::{
    Candidate, Confidence, ImportFormat, ImportReport, Importer, InstanceSeed, Skipped,
    create_imported_instance,
};
use crate::store::{Ecosystem, NewMod, PortStatus};

/// Settings key: array of pending CDN downloads for this instance.
pub const PENDING_DOWNLOADS_KEY: &str = "pendingDownloads";

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct MrpackIndex {
    name: Option<String>,
    #[serde(default)]
    dependencies: std::collections::BTreeMap<String, String>,
    #[serde(default)]
    files: Vec<MrpackFile>,
}

#[derive(Debug, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
struct MrpackFile {
    path: String,
    #[serde(default)]
    hashes: std::collections::BTreeMap<String, String>,
    #[serde(default)]
    env: Option<MrpackEnv>,
    /// Official CDN (or mirror) URLs; first working URL wins on download.
    #[serde(default)]
    downloads: Vec<String>,
    #[serde(default)]
    file_size: Option<u64>,
}

#[derive(Debug, Deserialize, Clone)]
struct MrpackEnv {
    #[serde(default)]
    client: Option<String>,
    /// Present in the format; reserved for server-instance import filtering.
    #[serde(default)]
    #[allow(dead_code)]
    server: Option<String>,
}

/// One file the install job must still fetch.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct PendingDownload {
    /// Relative path under the instance game dir (`mods/foo.jar`, `config/x.json`).
    pub path: String,
    /// Preferred download URL (first entry from the index).
    pub url: String,
    /// Alternate URLs when the primary fails.
    #[serde(default)]
    pub mirrors: Vec<String>,
    /// Lowercase hex sha1 when present (primary integrity check).
    pub sha1: Option<String>,
    /// Lowercase hex sha512 when present (secondary / fallback integrity).
    pub sha512: Option<String>,
    /// Declared size in bytes, when known.
    pub size: Option<u64>,
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

fn env_skips_client(env: Option<&MrpackEnv>) -> bool {
    env.and_then(|e| e.client.as_deref()) == Some("unsupported")
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
        let mut notes = Vec::new();
        let game_version =
            index.dependencies.get("minecraft").cloned().ok_or_else(|| CoreError::Json {
                context: "modrinth.index.json",
                source: serde::de::Error::custom("dependencies.minecraft missing"),
            })?;
        let loader = resolve_loader(&index.dependencies);
        if let Some((kind, version)) = &loader {
            notes.push(format!("loader {kind} {version} recorded in settings"));
        }
        let ecosystem = ecosystem_from_loader(loader.as_ref());
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
        let (mods_imported, skipped, pending) =
            ingest_index_files(db, instance_id, ecosystem, &index.files)?;
        persist_pending(db, instance_id, &pending, &mut notes)?;
        Ok(ImportReport { instance_id, mods_imported, skipped, notes })
    }
}

fn resolve_loader(
    deps: &std::collections::BTreeMap<String, String>,
) -> Option<(String, String)> {
    ["fabric-loader", "quilt-loader", "neoforge", "forge"].iter().find_map(|key| {
        deps.get(*key).map(|version| {
            let kind = key.trim_end_matches("-loader").to_owned();
            (kind, version.clone())
        })
    })
}

fn ecosystem_from_loader(loader: Option<&(String, String)>) -> Ecosystem {
    loader.map_or(Ecosystem::Unknown, |(kind, _)| match kind.as_str() {
        "fabric" | "quilt" => Ecosystem::Fabric,
        "neoforge" => Ecosystem::Neoforge,
        "forge" => Ecosystem::Forge,
        _ => Ecosystem::Unknown,
    })
}

fn ingest_index_files(
    db: &Db,
    instance_id: i64,
    ecosystem: Ecosystem,
    files: &[MrpackFile],
) -> Result<(usize, Vec<Skipped>, Vec<PendingDownload>), CoreError> {
    let mut skipped = Vec::new();
    let mut pending = Vec::new();
    let mut mods_imported = 0_usize;
    for file in files {
        if env_skips_client(file.env.as_ref()) {
            skipped.push(Skipped {
                item: file.path.clone(),
                reason: "server-only file (client env: unsupported)".into(),
            });
            continue;
        }
        let sha1 = file.hashes.get("sha1").map(|h| h.to_lowercase());
        let sha512 = file.hashes.get("sha512").map(|h| h.to_lowercase());
        if let Some(file_name) = file.path.strip_prefix("mods/").map(str::to_owned) {
            db.with_store(|store| {
                store.upsert_mod(&NewMod {
                    instance_id,
                    file_name,
                    sha1: sha1.clone(),
                    sha512: sha512.clone(),
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
        if file.downloads.is_empty() {
            let reason = if file.path.starts_with("mods/") {
                "mod has no downloads[] URL and is not in overrides/ — jar will be missing \
                 until the pack is repaired"
            } else {
                "non-mod pack file without downloads[] (expect overrides/ extraction or \
                 install adoption)"
            };
            skipped.push(Skipped { item: file.path.clone(), reason: reason.into() });
            continue;
        }
        let mut urls = file.downloads.clone();
        let primary = urls.remove(0);
        pending.push(PendingDownload {
            path: file.path.clone(),
            url: primary,
            mirrors: urls,
            sha1,
            sha512,
            size: file.file_size,
        });
    }
    Ok((mods_imported, skipped, pending))
}

fn persist_pending(
    db: &Db,
    instance_id: i64,
    pending: &[PendingDownload],
    notes: &mut Vec<String>,
) -> Result<(), CoreError> {
    if pending.is_empty() {
        notes.push("no CDN-hosted files in index (overrides-only or empty pack)".into());
        return Ok(());
    }
    db.with_store(|store| {
        let mut settings =
            store.instance(instance_id)?.map_or_else(|| json!({}), |i| i.settings);
        settings[PENDING_DOWNLOADS_KEY] = serde_json::to_value(pending)
            .map_err(|source| CoreError::Json { context: "pendingDownloads", source })?;
        store.update_instance_settings(instance_id, &settings)
    })?;
    notes.push(format!(
        "{} file(s) queued for hash-verified download on Install (CDN URLs from \
         modrinth.index.json)",
        pending.len()
    ));
    Ok(())
}

/// Extracts `overrides/` and `client-overrides/` from an `.mrpack` into the
/// instance game directory. Server-only packs would use `server-overrides/`;
/// client import prefers client-overrides when both exist for the same path.
///
/// # Errors
///
/// [`CoreError::Io`] when the pack cannot be read or files cannot be written.
pub fn extract_mrpack_overrides(pack_path: &Path, game_dir: &Path) -> Result<usize, CoreError> {
    let file = std::fs::File::open(pack_path).map_err(|e| CoreError::Io {
        action: "open mrpack for overrides",
        path: pack_path.to_path_buf(),
        source: e,
    })?;
    let mut archive = zip::ZipArchive::new(file).map_err(|e| CoreError::Io {
        action: "read mrpack zip for overrides",
        path: pack_path.to_path_buf(),
        source: std::io::Error::other(e.to_string()),
    })?;
    let mut extracted = 0_usize;
    for i in 0..archive.len() {
        let mut entry = archive.by_index(i).map_err(|e| CoreError::Io {
            action: "read mrpack entry",
            path: pack_path.to_path_buf(),
            source: std::io::Error::other(e.to_string()),
        })?;
        let name = entry.name().replace('\\', "/");
        let relative = if let Some(rest) = name.strip_prefix("overrides/") {
            rest
        } else if let Some(rest) = name.strip_prefix("client-overrides/") {
            rest
        } else {
            continue;
        };
        if relative.is_empty() || relative.ends_with('/') {
            continue;
        }
        // Refuse zip-slip.
        let dest = safe_join(game_dir, relative)?;
        if let Some(parent) = dest.parent() {
            std::fs::create_dir_all(parent).map_err(|e| CoreError::Io {
                action: "create override parent",
                path: parent.to_path_buf(),
                source: e,
            })?;
        }
        let mut out = std::fs::File::create(&dest).map_err(|e| CoreError::Io {
            action: "create override file",
            path: dest.clone(),
            source: e,
        })?;
        std::io::copy(&mut entry, &mut out).map_err(|e| CoreError::Io {
            action: "write override file",
            path: dest,
            source: e,
        })?;
        extracted += 1;
    }
    Ok(extracted)
}

fn safe_join(root: &Path, relative: &str) -> Result<PathBuf, CoreError> {
    let mut dest = root.to_path_buf();
    for component in relative.split('/') {
        if component.is_empty() || component == "." {
            continue;
        }
        if component == ".." {
            return Err(CoreError::Io {
                action: "reject zip-slip path",
                path: root.join(relative),
                source: std::io::Error::new(
                    std::io::ErrorKind::InvalidInput,
                    "override path escapes game directory",
                ),
            });
        }
        dest.push(component);
    }
    Ok(dest)
}

/// Loads pending downloads from instance settings.
///
/// # Errors
///
/// [`CoreError::Json`] when the stored value is not a valid pending list.
pub fn pending_downloads_from_settings(
    settings: &serde_json::Value,
) -> Result<Vec<PendingDownload>, CoreError> {
    match settings.get(PENDING_DOWNLOADS_KEY) {
        None | Some(serde_json::Value::Null) => Ok(Vec::new()),
        Some(value) => serde_json::from_value(value.clone()).map_err(|source| CoreError::Json {
            context: "pendingDownloads",
            source,
        }),
    }
}

/// Writes the remaining pending list back (empty clears the key).
///
/// # Errors
///
/// Store or JSON serialization failures as [`CoreError`].
pub fn store_pending_downloads(
    db: &Db,
    instance_id: i64,
    pending: &[PendingDownload],
) -> Result<(), CoreError> {
    db.with_store(|store| {
        let mut settings =
            store.instance(instance_id)?.map_or_else(|| json!({}), |i| i.settings);
        if pending.is_empty() {
            if let Some(obj) = settings.as_object_mut() {
                obj.remove(PENDING_DOWNLOADS_KEY);
            }
        } else {
            settings[PENDING_DOWNLOADS_KEY] = serde_json::to_value(pending)
                .map_err(|source| CoreError::Json { context: "pendingDownloads", source })?;
        }
        store.update_instance_settings(instance_id, &settings)
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write as _;
    use std::sync::Arc;

    use crate::clock::FixedClock;
    use crate::db::Db;
    use tempfile::TempDir;

    fn write_mrpack(path: &Path, index: &serde_json::Value, extra: &[(&str, &[u8])]) {
        let file = std::fs::File::create(path).expect("create");
        let mut zip = zip::ZipWriter::new(file);
        zip.start_file("modrinth.index.json", zip::write::SimpleFileOptions::default())
            .unwrap();
        zip.write_all(index.to_string().as_bytes()).unwrap();
        for (name, bytes) in extra {
            zip.start_file(*name, zip::write::SimpleFileOptions::default()).unwrap();
            zip.write_all(bytes).unwrap();
        }
        zip.finish().unwrap();
    }

    #[test]
    fn import_queues_downloads_from_index() {
        let dir = TempDir::new().unwrap();
        let pack = dir.path().join("pack.mrpack");
        write_mrpack(
            &pack,
            &json!({
                "formatVersion": 1,
                "name": "CDN Pack",
                "dependencies": { "minecraft": "1.21.1", "fabric-loader": "0.16.9" },
                "files": [{
                    "path": "mods/sodium.jar",
                    "hashes": { "sha1": "aa11", "sha512": "bb22" },
                    "downloads": [
                        "https://cdn.modrinth.com/data/sodium/versions/1/sodium.jar",
                        "https://mirror.example/sodium.jar"
                    ],
                    "fileSize": 12
                }]
            }),
            &[],
        );
        let db = Db::open_in_memory(Arc::new(FixedClock::new(1_800_000_000))).unwrap();
        let report = MrpackImporter
            .import(
                &db,
                &Candidate {
                    format: ImportFormat::Mrpack,
                    name: "CDN Pack".into(),
                    root: pack,
                    key: None,
                },
            )
            .unwrap();
        assert_eq!(report.mods_imported, 1);
        let settings = db
            .with_store(|s| Ok(s.instance(report.instance_id)?.unwrap().settings))
            .unwrap();
        let pending = pending_downloads_from_settings(&settings).unwrap();
        assert_eq!(pending.len(), 1);
        assert_eq!(pending[0].path, "mods/sodium.jar");
        assert!(pending[0].url.contains("cdn.modrinth.com"));
        assert_eq!(pending[0].mirrors.len(), 1);
        assert_eq!(pending[0].sha1.as_deref(), Some("aa11"));
    }

    #[test]
    fn extract_overrides_writes_into_game_dir() {
        let dir = TempDir::new().unwrap();
        let pack = dir.path().join("pack.mrpack");
        write_mrpack(
            &pack,
            &json!({
                "formatVersion": 1,
                "name": "O",
                "dependencies": { "minecraft": "1.21.1" },
                "files": []
            }),
            &[("overrides/config/demo.txt", b"hello"), ("client-overrides/options.txt", b"opt")],
        );
        let game = dir.path().join("game");
        let n = extract_mrpack_overrides(&pack, &game).unwrap();
        assert_eq!(n, 2);
        assert_eq!(std::fs::read_to_string(game.join("config/demo.txt")).unwrap(), "hello");
        assert_eq!(std::fs::read_to_string(game.join("options.txt")).unwrap(), "opt");
    }
}
