//! Instance export writers (Phase 5 / ADR-0014 / LNC-7 SHOULD).
//!
//! Completes the bidirectional-migration trust posture: import is the Phase 4
//! MVP surface; export writes the two platform lingua francas plus Omni's own
//! pack format. Foreign formats are produced only as **export artifacts** —
//! they are never the launcher store (LNC-2).
//!
//! | Format | Artifact | Notes |
//! |--------|----------|-------|
//! | [`ExportFormat::Mrpack`] | `.mrpack` ZIP | `modrinth.index.json` + `overrides/` for jars |
//! | [`ExportFormat::CurseForge`] | folder | `manifest.json` + optional `overrides/mods/` |
//! | [`ExportFormat::Omni`] | `.omnipack.json` | schema-versioned native dump |

use std::fs::{self, File};
use std::io::{Read as _, Write as _};
use std::path::{Path, PathBuf};

use serde::Serialize;
use zip::write::SimpleFileOptions;
use zip::ZipWriter;

use crate::error::CoreError;
use crate::store::{Instance, InstanceKind, Mod, StackEntry};

/// Supported export surfaces (ADR-0014).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ExportFormat {
    /// Modrinth `.mrpack` (ZIP + `modrinth.index.json`).
    Mrpack,
    /// CurseForge modpack folder (`manifest.json` + overrides).
    CurseForge,
    /// Omni native pack document (`omni.pack.json` schema 0).
    Omni,
}

impl ExportFormat {
    /// Parses a format slug (`mrpack`, `curseforge`, `omni`).
    ///
    /// # Errors
    ///
    /// [`CoreError::UnknownEnum`] when the slug is not one of the three.
    pub fn parse(value: &str) -> Result<Self, CoreError> {
        match value.trim().to_ascii_lowercase().as_str() {
            "mrpack" | "modrinth" => Ok(Self::Mrpack),
            "curseforge" | "cf" => Ok(Self::CurseForge),
            "omni" | "omnipack" => Ok(Self::Omni),
            other => Err(CoreError::UnknownEnum {
                context: "export.format",
                value: other.to_owned(),
            }),
        }
    }

    /// Canonical slug for IPC / docs.
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Mrpack => "mrpack",
            Self::CurseForge => "curseforge",
            Self::Omni => "omni",
        }
    }
}

impl std::fmt::Display for ExportFormat {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.as_str())
    }
}

/// Report from one export (typed, never log-only).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExportReport {
    /// Format written.
    pub format: ExportFormat,
    /// Primary output path (file or folder).
    pub path: PathBuf,
    /// Mods included in the export document.
    pub mods_exported: usize,
    /// Mods skipped with reasons (e.g. missing file for overrides).
    pub skipped: Vec<String>,
    /// Human notes (loader mapping, overrides count, …).
    pub notes: Vec<String>,
}

/// Inputs for a pure export (no DB access — caller loads from the store).
#[derive(Debug, Clone)]
pub struct ExportInput<'a> {
    /// Instance row.
    pub instance: &'a Instance,
    /// Component stack (order preserved).
    pub stack: &'a [StackEntry],
    /// Mod entities.
    pub mods: &'a [Mod],
    /// Optional on-disk mods folder; when present, jars are packed into overrides.
    pub mods_dir: Option<&'a Path>,
    /// Destination path (file for mrpack/omni, directory for curseforge).
    pub dest: &'a Path,
}

/// Exports one instance to `input.dest` in the requested format.
///
/// # Errors
///
/// [`CoreError::Io`] / [`CoreError::Json`] on write failures;
/// [`CoreError::UnknownEnum`] is not used here (format is typed).
pub fn export_instance(
    format: ExportFormat,
    input: &ExportInput<'_>,
) -> Result<ExportReport, CoreError> {
    match format {
        ExportFormat::Mrpack => export_mrpack(input),
        ExportFormat::CurseForge => export_curseforge(input),
        ExportFormat::Omni => export_omni(input),
    }
}

// ── shared helpers ───────────────────────────────────────────────────────────

#[derive(Debug, Clone)]
struct LoaderRef {
    kind: String,
    version: String,
}

fn loader_from_settings(settings: &serde_json::Value) -> Option<LoaderRef> {
    let kind = settings["importedLoader"]["kind"].as_str()?.to_owned();
    if kind.is_empty() || kind.eq_ignore_ascii_case("vanilla") {
        return None;
    }
    let version = settings["importedLoader"]["version"]
        .as_str()
        .unwrap_or("")
        .to_owned();
    Some(LoaderRef { kind, version })
}

fn pack_version(instance: &Instance) -> String {
    // Prefer an explicit pack version setting; fall back to game version.
    instance.settings["packVersion"]
        .as_str()
        .filter(|s| !s.is_empty())
        .map_or_else(|| instance.game_version.clone(), str::to_owned)
}

fn enabled_mods(mods: &[Mod]) -> impl Iterator<Item = &Mod> {
    mods.iter().filter(|m| m.enabled)
}

fn read_file_bytes(path: &Path) -> Result<Vec<u8>, CoreError> {
    fs::read(path).map_err(|source| CoreError::Io {
        action: "read mod jar for export",
        path: path.to_path_buf(),
        source,
    })
}

// ── Omni native ──────────────────────────────────────────────────────────────

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct OmniPack {
    schema: u32,
    name: String,
    game_version: String,
    kind: String,
    pack_version: String,
    notes: String,
    settings: serde_json::Value,
    stack: Vec<OmniStackEntry>,
    mods: Vec<OmniModEntry>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct OmniStackEntry {
    position: i64,
    uid: String,
    version: String,
    pinned: bool,
    source: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct OmniModEntry {
    file_name: String,
    sha1: Option<String>,
    sha512: Option<String>,
    murmur2: Option<String>,
    display_name: String,
    version: String,
    ecosystem: String,
    enabled: bool,
    platform: Option<String>,
    platform_project_id: Option<String>,
    platform_file_id: Option<String>,
    port_status: String,
}

fn export_omni(input: &ExportInput<'_>) -> Result<ExportReport, CoreError> {
    let instance = input.instance;
    let pack = OmniPack {
        schema: 0,
        name: instance.name.clone(),
        game_version: instance.game_version.clone(),
        kind: instance.kind.as_str().to_owned(),
        pack_version: pack_version(instance),
        notes: instance.notes.clone(),
        settings: instance.settings.clone(),
        stack: input
            .stack
            .iter()
            .map(|e| OmniStackEntry {
                position: e.position,
                uid: e.uid.clone(),
                version: e.version.clone(),
                pinned: e.pinned,
                source: e.source.as_str().to_owned(),
            })
            .collect(),
        mods: input
            .mods
            .iter()
            .map(|m| OmniModEntry {
                file_name: m.file_name.clone(),
                sha1: m.sha1.clone(),
                sha512: m.sha512.clone(),
                murmur2: m.murmur2.clone(),
                display_name: m
                    .display_name
                    .clone()
                    .unwrap_or_else(|| m.file_name.clone()),
                version: m.version.clone().unwrap_or_default(),
                ecosystem: m.ecosystem.as_str().to_owned(),
                enabled: m.enabled,
                platform: m.platform.clone(),
                platform_project_id: m.platform_project_id.clone(),
                platform_file_id: m.platform_file_id.clone(),
                port_status: m.port_status.as_str().to_owned(),
            })
            .collect(),
    };
    if let Some(parent) = input.dest.parent() {
        fs::create_dir_all(parent).map_err(|source| CoreError::Io {
            action: "create export parent",
            path: parent.to_path_buf(),
            source,
        })?;
    }
    let json = serde_json::to_vec_pretty(&pack)
        .map_err(|source| CoreError::Json { context: "omni.pack.json", source })?;
    fs::write(input.dest, json).map_err(|source| CoreError::Io {
        action: "write omni pack",
        path: input.dest.to_path_buf(),
        source,
    })?;
    Ok(ExportReport {
        format: ExportFormat::Omni,
        path: input.dest.to_path_buf(),
        mods_exported: input.mods.len(),
        skipped: Vec::new(),
        notes: vec![format!(
            "omni.pack.json schema 0; {} stack entries",
            input.stack.len()
        )],
    })
}

// ── CurseForge ───────────────────────────────────────────────────────────────

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct CfManifest {
    minecraft: CfMinecraft,
    manifest_type: String,
    manifest_version: u32,
    name: String,
    version: String,
    author: String,
    files: Vec<CfFile>,
    overrides: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct CfMinecraft {
    version: String,
    mod_loaders: Vec<CfModLoader>,
}

#[derive(Serialize)]
struct CfModLoader {
    id: String,
    primary: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct CfFile {
    #[serde(rename = "projectID")]
    project_id: i64,
    #[serde(rename = "fileID")]
    file_id: i64,
    required: bool,
}

fn parse_cf_id(value: Option<&str>) -> Option<i64> {
    value?.parse::<i64>().ok()
}

fn pack_cf_override(dest: &Path, mods_dir: &Path, file_name: &str) -> Result<bool, CoreError> {
    let jar = mods_dir.join(file_name);
    if !jar.is_file() {
        return Ok(false);
    }
    let overrides_mods = dest.join("overrides").join("mods");
    fs::create_dir_all(&overrides_mods).map_err(|source| CoreError::Io {
        action: "create overrides/mods",
        path: overrides_mods.clone(),
        source,
    })?;
    let target = overrides_mods.join(file_name);
    fs::copy(&jar, &target).map_err(|source| CoreError::Io {
        action: "copy mod into overrides",
        path: jar,
        source,
    })?;
    Ok(true)
}

fn collect_cf_files(
    input: &ExportInput<'_>,
) -> Result<(Vec<CfFile>, Vec<String>, usize), CoreError> {
    let mut files = Vec::new();
    let mut skipped = Vec::new();
    let mut overrides_count = 0usize;
    for m in enabled_mods(input.mods) {
        let project_id = parse_cf_id(m.platform_project_id.as_deref());
        let file_id = parse_cf_id(m.platform_file_id.as_deref());
        match (project_id, file_id) {
            (Some(project_id), Some(file_id)) if project_id > 0 && file_id > 0 => {
                files.push(CfFile { project_id, file_id, required: true });
            }
            _ => match input.mods_dir {
                Some(mods_dir) if pack_cf_override(input.dest, mods_dir, &m.file_name)? => {
                    overrides_count += 1;
                }
                Some(mods_dir) => skipped.push(format!(
                    "{}: no CurseForge project/file id and jar missing at {}",
                    m.file_name,
                    mods_dir.join(&m.file_name).display()
                )),
                None => skipped.push(format!(
                    "{}: no CurseForge project/file id (pass mods_dir to pack overrides)",
                    m.file_name
                )),
            },
        }
    }
    Ok((files, skipped, overrides_count))
}

fn export_curseforge(input: &ExportInput<'_>) -> Result<ExportReport, CoreError> {
    let dest = input.dest;
    fs::create_dir_all(dest).map_err(|source| CoreError::Io {
        action: "create curseforge export dir",
        path: dest.to_path_buf(),
        source,
    })?;

    let (files, skipped, overrides_count) = collect_cf_files(input)?;
    let mut mod_loaders = Vec::new();
    if let Some(loader) = loader_from_settings(&input.instance.settings) {
        let id = if loader.version.is_empty() {
            loader.kind
        } else {
            format!("{}-{}", loader.kind, loader.version)
        };
        mod_loaders.push(CfModLoader { id, primary: true });
    }

    let file_count = files.len();
    let manifest = CfManifest {
        minecraft: CfMinecraft {
            version: input.instance.game_version.clone(),
            mod_loaders,
        },
        manifest_type: "minecraftModpack".to_owned(),
        manifest_version: 1,
        name: input.instance.name.clone(),
        version: pack_version(input.instance),
        author: "OmniLauncher".to_owned(),
        files,
        overrides: "overrides".to_owned(),
    };

    let manifest_path = dest.join("manifest.json");
    let json = serde_json::to_vec_pretty(&manifest)
        .map_err(|source| CoreError::Json { context: "manifest.json", source })?;
    fs::write(&manifest_path, json).map_err(|source| CoreError::Io {
        action: "write manifest.json",
        path: manifest_path,
        source,
    })?;

    let mods_exported = enabled_mods(input.mods).count() - skipped.len();
    let mut notes = vec![format!(
        "manifest.json with {file_count} CF file ref(s); {overrides_count} override jar(s)"
    )];
    if input.instance.kind == InstanceKind::Server {
        notes.push(
            "server instance exported as a client-shaped CF pack (manifest has no server flag)"
                .to_owned(),
        );
    }

    Ok(ExportReport {
        format: ExportFormat::CurseForge,
        path: dest.to_path_buf(),
        mods_exported,
        skipped,
        notes,
    })
}

// ── Modrinth .mrpack ─────────────────────────────────────────────────────────

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct MrpackIndex {
    format_version: u32,
    game: String,
    version_id: String,
    name: String,
    summary: String,
    files: Vec<MrpackFile>,
    dependencies: std::collections::BTreeMap<String, String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct MrpackFile {
    path: String,
    hashes: std::collections::BTreeMap<String, String>,
    env: MrpackEnv,
    downloads: Vec<String>,
    file_size: u64,
}

#[derive(Serialize)]
struct MrpackEnv {
    client: String,
    server: String,
}

fn mrpack_dependencies(
    instance: &Instance,
) -> std::collections::BTreeMap<String, String> {
    let mut dependencies = std::collections::BTreeMap::new();
    dependencies.insert("minecraft".to_owned(), instance.game_version.clone());
    if let Some(loader) = loader_from_settings(&instance.settings) {
        let key = match loader.kind.as_str() {
            "fabric" => "fabric-loader",
            "quilt" => "quilt-loader",
            "forge" => "forge",
            "neoforge" => "neoforge",
            other => other,
        };
        if !loader.version.is_empty() {
            dependencies.insert(key.to_owned(), loader.version);
        }
    }
    dependencies
}

type MrpackOverrides = (Vec<MrpackFile>, Vec<(String, Vec<u8>)>, Vec<String>);

fn collect_mrpack_overrides(input: &ExportInput<'_>) -> Result<MrpackOverrides, CoreError> {
    let mut skipped = Vec::new();
    let mut override_entries: Vec<(String, Vec<u8>)> = Vec::new();
    let mut index_files: Vec<MrpackFile> = Vec::new();
    // Jars go into overrides/ so the pack is self-contained without inventing CDN URLs.
    for m in enabled_mods(input.mods) {
        let rel = format!("mods/{}", m.file_name);
        let Some(mods_dir) = input.mods_dir else {
            skipped.push(format!(
                "{}: no mods_dir; cannot pack jar into overrides",
                m.file_name
            ));
            continue;
        };
        let jar = mods_dir.join(&m.file_name);
        if !jar.is_file() {
            skipped.push(format!("{}: jar missing at {}", m.file_name, jar.display()));
            continue;
        }
        let bytes = read_file_bytes(&jar)?;
        let mut hashes = std::collections::BTreeMap::new();
        if let Some(sha1) = m.sha1.as_ref().filter(|s| !s.is_empty()) {
            hashes.insert("sha1".to_owned(), sha1.to_ascii_lowercase());
        }
        if let Some(sha512) = m.sha512.as_ref().filter(|s| !s.is_empty()) {
            hashes.insert("sha512".to_owned(), sha512.to_ascii_lowercase());
        }
        index_files.push(MrpackFile {
            path: rel.clone(),
            hashes,
            env: MrpackEnv {
                client: "required".to_owned(),
                server: "required".to_owned(),
            },
            downloads: Vec::new(),
            file_size: bytes.len() as u64,
        });
        override_entries.push((format!("overrides/{rel}"), bytes));
    }
    Ok((index_files, override_entries, skipped))
}

fn write_mrpack_zip(
    dest: &Path,
    index_json: &[u8],
    override_entries: &[(String, Vec<u8>)],
) -> Result<(), CoreError> {
    let file = File::create(dest).map_err(|source| CoreError::Io {
        action: "create mrpack",
        path: dest.to_path_buf(),
        source,
    })?;
    let mut zip = ZipWriter::new(file);
    let options = SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
    zip.start_file("modrinth.index.json", options).map_err(|e| CoreError::Io {
        action: "write modrinth.index.json entry",
        path: dest.to_path_buf(),
        source: std::io::Error::other(e.to_string()),
    })?;
    zip.write_all(index_json).map_err(|source| CoreError::Io {
        action: "write modrinth.index.json body",
        path: dest.to_path_buf(),
        source,
    })?;
    for (name, bytes) in override_entries {
        zip.start_file(name.as_str(), options).map_err(|e| CoreError::Io {
            action: "write override entry",
            path: dest.to_path_buf(),
            source: std::io::Error::other(e.to_string()),
        })?;
        zip.write_all(bytes).map_err(|source| CoreError::Io {
            action: "write override body",
            path: dest.to_path_buf(),
            source,
        })?;
    }
    zip.finish().map_err(|e| CoreError::Io {
        action: "finish mrpack zip",
        path: dest.to_path_buf(),
        source: std::io::Error::other(e.to_string()),
    })?;
    Ok(())
}

fn verify_mrpack_index(dest: &Path, index_json: &[u8]) -> Result<(), CoreError> {
    let file = File::open(dest).map_err(|source| CoreError::Io {
        action: "reopen mrpack for verify",
        path: dest.to_path_buf(),
        source,
    })?;
    let mut archive = zip::ZipArchive::new(file).map_err(|e| CoreError::Io {
        action: "verify mrpack zip",
        path: dest.to_path_buf(),
        source: std::io::Error::other(e.to_string()),
    })?;
    let mut entry = archive.by_name("modrinth.index.json").map_err(|e| CoreError::Io {
        action: "verify modrinth.index.json present",
        path: dest.to_path_buf(),
        source: std::io::Error::other(e.to_string()),
    })?;
    let mut buf = Vec::new();
    entry.read_to_end(&mut buf).map_err(|source| CoreError::Io {
        action: "verify modrinth.index.json readable",
        path: dest.to_path_buf(),
        source,
    })?;
    if buf != index_json {
        return Err(CoreError::Io {
            action: "verify modrinth.index.json byte identity",
            path: dest.to_path_buf(),
            source: std::io::Error::other("packed index differs from written document"),
        });
    }
    Ok(())
}

fn export_mrpack(input: &ExportInput<'_>) -> Result<ExportReport, CoreError> {
    if let Some(parent) = input.dest.parent() {
        fs::create_dir_all(parent).map_err(|source| CoreError::Io {
            action: "create mrpack parent",
            path: parent.to_path_buf(),
            source,
        })?;
    }

    let (index_files, override_entries, skipped) = collect_mrpack_overrides(input)?;
    let index = MrpackIndex {
        format_version: 1,
        game: "minecraft".to_owned(),
        version_id: pack_version(input.instance),
        name: input.instance.name.clone(),
        summary: input.instance.notes.clone(),
        files: index_files,
        dependencies: mrpack_dependencies(input.instance),
    };
    let index_json = serde_json::to_vec_pretty(&index)
        .map_err(|source| CoreError::Json { context: "modrinth.index.json", source })?;

    write_mrpack_zip(input.dest, &index_json, &override_entries)?;
    verify_mrpack_index(input.dest, &index_json)?;

    Ok(ExportReport {
        format: ExportFormat::Mrpack,
        path: input.dest.to_path_buf(),
        mods_exported: override_entries.len(),
        skipped,
        notes: vec![format!(
            "mrpack formatVersion 1; {} override jar(s); jars self-contained (no CDN URLs invented)",
            override_entries.len()
        )],
    })
}
