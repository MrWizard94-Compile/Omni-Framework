//! Ensure intermediary + Mojang mapping files for OmniLoader Fabric remap.

use std::fs;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};

use omni_meta_format::ComponentPayload;

use crate::error::CoreError;
use crate::install::Paths;

/// Paths the bootstrap understands for intermediary→official composition.
#[derive(Debug, Clone)]
pub struct MappingFiles {
    /// Fabric intermediary Tiny v2 extracted from the intermediary jar.
    pub intermediary_tiny: PathBuf,
    /// Mojang client mappings (proguard text).
    pub mojang_proguard: PathBuf,
}

/// Ensures mapping files for `game_version` under the data root cache.
///
/// # Errors
///
/// [`CoreError::Io`] / network failures when downloads fail.
pub fn ensure_mapping_files(
    paths: &Paths,
    game: &ComponentPayload,
    game_version: &str,
) -> Result<MappingFiles, CoreError> {
    let root = paths.data_root.join("mappings").join(game_version);
    fs::create_dir_all(&root).map_err(|source| CoreError::Io {
        action: "create mappings cache",
        path: root.clone(),
        source,
    })?;

    let tiny = root.join("intermediary.tiny");
    if !tiny.is_file() {
        download_intermediary_tiny(game_version, &tiny)?;
    }

    let proguard = root.join("client_mappings.txt");
    if !proguard.is_file() {
        let url = mojang_client_mappings_url(game).ok_or_else(|| {
            CoreError::NotFound {
                what: "client_mappings url on game payload",
                id: 0,
            }
        })?;
        download_to_file(&url, &proguard)?;
    }

    Ok(MappingFiles {
        intermediary_tiny: tiny,
        mojang_proguard: proguard,
    })
}

fn mojang_client_mappings_url(game: &ComponentPayload) -> Option<String> {
    // Prefer net.minecraft data.downloads.client_mappings
    let downloads = game.data.get("downloads")?;
    let cm = downloads.get("client_mappings")?;
    cm.get("url")?.as_str().map(str::to_owned)
}

fn download_intermediary_tiny(game_version: &str, dest: &Path) -> Result<(), CoreError> {
    let url = format!(
        "https://maven.fabricmc.net/net/fabricmc/intermediary/{game_version}/intermediary-{game_version}-v2.jar"
    );
    let jar_path = dest.with_extension("jar");
    download_to_file(&url, &jar_path)?;
    extract_tiny_from_jar(&jar_path, dest)?;
    let _ = fs::remove_file(&jar_path);
    Ok(())
}

fn download_to_file(url: &str, dest: &Path) -> Result<(), CoreError> {
    let agent = ureq::AgentBuilder::new()
        .user_agent("OmniLauncher/0.1 (+https://wpaistudio.net)")
        .timeout(std::time::Duration::from_mins(2))
        .build();
    let response = agent.get(url).call().map_err(|err| CoreError::Io {
        action: "download mappings",
        path: dest.to_path_buf(),
        source: std::io::Error::other(err.to_string()),
    })?;
    let mut bytes = Vec::new();
    response
        .into_reader()
        .read_to_end(&mut bytes)
        .map_err(|source| CoreError::Io {
            action: "read mappings download",
            path: dest.to_path_buf(),
            source,
        })?;
    if let Some(parent) = dest.parent() {
        fs::create_dir_all(parent).map_err(|source| CoreError::Io {
            action: "create mappings parent",
            path: parent.to_path_buf(),
            source,
        })?;
    }
    let mut file = fs::File::create(dest).map_err(|source| CoreError::Io {
        action: "write mappings file",
        path: dest.to_path_buf(),
        source,
    })?;
    file.write_all(&bytes).map_err(|source| CoreError::Io {
        action: "write mappings bytes",
        path: dest.to_path_buf(),
        source,
    })?;
    Ok(())
}

fn extract_tiny_from_jar(jar: &Path, dest_tiny: &Path) -> Result<(), CoreError> {
    let file = fs::File::open(jar).map_err(|source| CoreError::Io {
        action: "open intermediary jar",
        path: jar.to_path_buf(),
        source,
    })?;
    let mut archive = zip::ZipArchive::new(file).map_err(|err| CoreError::Io {
        action: "read intermediary jar",
        path: jar.to_path_buf(),
        source: std::io::Error::other(err.to_string()),
    })?;
    // Common paths inside Fabric intermediary jars.
    for name in ["mappings/mappings.tiny", "mappings.tiny"] {
        if let Ok(mut entry) = archive.by_name(name) {
            let mut out = fs::File::create(dest_tiny).map_err(|source| CoreError::Io {
                action: "create intermediary.tiny",
                path: dest_tiny.to_path_buf(),
                source,
            })?;
            std::io::copy(&mut entry, &mut out).map_err(|source| CoreError::Io {
                action: "extract intermediary.tiny",
                path: dest_tiny.to_path_buf(),
                source,
            })?;
            return Ok(());
        }
    }
    Err(CoreError::Io {
        action: "find mappings.tiny in intermediary jar",
        path: jar.to_path_buf(),
        source: std::io::Error::new(std::io::ErrorKind::NotFound, "mappings.tiny missing"),
    })
}
