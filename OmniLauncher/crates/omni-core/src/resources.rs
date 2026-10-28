//! Instance folder resources (LNC-10): resource packs, shader packs, data packs, worlds.
//!
//! Disk is the source of truth for presence; enablement uses the same
//! `.disabled` suffix convention as mods. Listing always rescans the instance
//! game directory (watch-equivalent refresh on each call).

use std::fs;
use std::path::PathBuf;

use crate::error::CoreError;
use crate::install::Paths;

/// Kind of folder resource under an instance game dir.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ResourceKind {
    /// `resourcepacks/`
    ResourcePack,
    /// `shaderpacks/`
    ShaderPack,
    /// `datapacks/` (global + world datapacks under worlds/*/datapacks are separate)
    DataPack,
    /// `saves/` (client worlds)
    World,
}

impl ResourceKind {
    /// Folder name under the instance game directory.
    #[must_use]
    pub const fn folder(self) -> &'static str {
        match self {
            Self::ResourcePack => "resourcepacks",
            Self::ShaderPack => "shaderpacks",
            Self::DataPack => "datapacks",
            Self::World => "saves",
        }
    }

    /// Stable slug for IPC/UI.
    #[must_use]
    pub const fn slug(self) -> &'static str {
        match self {
            Self::ResourcePack => "resourcepack",
            Self::ShaderPack => "shaderpack",
            Self::DataPack => "datapack",
            Self::World => "world",
        }
    }

    /// Parses a slug from the UI/API.
    pub fn parse(s: &str) -> Option<Self> {
        match s {
            "resourcepack" | "resourcepacks" => Some(Self::ResourcePack),
            "shaderpack" | "shaderpacks" => Some(Self::ShaderPack),
            "datapack" | "datapacks" => Some(Self::DataPack),
            "world" | "worlds" | "saves" => Some(Self::World),
            _ => None,
        }
    }
}

/// One resource entry on disk.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ResourceEntry {
    /// Kind.
    pub kind: ResourceKind,
    /// File or directory name (without path).
    pub name: String,
    /// Whether the entry is enabled (not `.disabled`).
    pub enabled: bool,
    /// Absolute path currently on disk.
    pub path: PathBuf,
}

/// Lists resources of `kind` for an instance by scanning the game directory.
///
/// # Errors
///
/// [`CoreError::Io`] when the directory cannot be read.
pub fn list_resources(
    paths: &Paths,
    instance_id: i64,
    kind: ResourceKind,
) -> Result<Vec<ResourceEntry>, CoreError> {
    let dir = paths.game_dir(instance_id).join(kind.folder());
    if !dir.is_dir() {
        return Ok(Vec::new());
    }
    let mut out = Vec::new();
    for entry in fs::read_dir(&dir).map_err(|source| CoreError::Io {
        action: "list resource folder",
        path: dir.clone(),
        source,
    })? {
        let entry = entry.map_err(|source| CoreError::Io {
            action: "read resource entry",
            path: dir.clone(),
            source,
        })?;
        let path = entry.path();
        let name = entry.file_name().to_string_lossy().into_owned();
        if name.starts_with('.') {
            continue;
        }
        let enabled = !name.ends_with(".disabled");
        let display = name.strip_suffix(".disabled").unwrap_or(&name).to_owned();
        out.push(ResourceEntry {
            kind,
            name: display,
            enabled,
            path,
        });
    }
    out.sort_by(|a, b| a.name.cmp(&b.name));
    Ok(out)
}

/// Enables or disables a resource by renaming with `.disabled` suffix.
///
/// # Errors
///
/// [`CoreError::Io`] / not found when the entry is missing.
pub fn set_resource_enabled(
    paths: &Paths,
    instance_id: i64,
    kind: ResourceKind,
    name: &str,
    enabled: bool,
) -> Result<(), CoreError> {
    let dir = paths.game_dir(instance_id).join(kind.folder());
    let base = name.strip_suffix(".disabled").unwrap_or(name);
    let enabled_path = dir.join(base);
    let disabled_path = dir.join(format!("{base}.disabled"));
    if enabled {
        if disabled_path.is_file() || disabled_path.is_dir() {
            fs::rename(&disabled_path, &enabled_path).map_err(|source| CoreError::Io {
                action: "enable resource",
                path: disabled_path,
                source,
            })?;
        } else if !enabled_path.exists() {
            return Err(CoreError::Io {
                action: "enable resource",
                path: enabled_path,
                source: std::io::Error::new(std::io::ErrorKind::NotFound, "resource not found"),
            });
        }
    } else if enabled_path.is_file() || enabled_path.is_dir() {
        fs::rename(&enabled_path, &disabled_path).map_err(|source| CoreError::Io {
            action: "disable resource",
            path: enabled_path,
            source,
        })?;
    } else if !disabled_path.exists() {
        return Err(CoreError::Io {
            action: "disable resource",
            path: disabled_path,
            source: std::io::Error::new(std::io::ErrorKind::NotFound, "resource not found"),
        });
    }
    Ok(())
}

/// Batch enable/disable by name list.
///
/// # Errors
///
/// First failure aborts (previous renames may have applied).
pub fn set_resources_enabled_batch(
    paths: &Paths,
    instance_id: i64,
    kind: ResourceKind,
    names: &[String],
    enabled: bool,
) -> Result<usize, CoreError> {
    let mut n = 0;
    for name in names {
        set_resource_enabled(paths, instance_id, kind, name, enabled)?;
        n += 1;
    }
    Ok(n)
}

/// Ensures standard LNC-10 folders exist under the instance game dir.
///
/// # Errors
///
/// [`CoreError::Io`] on mkdir failure.
pub fn ensure_resource_dirs(paths: &Paths, instance_id: i64) -> Result<(), CoreError> {
    for kind in [
        ResourceKind::ResourcePack,
        ResourceKind::ShaderPack,
        ResourceKind::DataPack,
        ResourceKind::World,
    ] {
        let dir = paths.game_dir(instance_id).join(kind.folder());
        fs::create_dir_all(&dir).map_err(|source| CoreError::Io {
            action: "create resource folder",
            path: dir,
            source,
        })?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    #[test]
    fn list_and_toggle_resource_pack() {
        let tmp = TempDir::new().unwrap();
        let paths = Paths::new(tmp.path());
        let id = 7_i64;
        ensure_resource_dirs(&paths, id).unwrap();
        let pack_dir = paths.game_dir(id).join("resourcepacks");
        fs::write(pack_dir.join("pretty.zip"), b"zip").unwrap();
        let listed = list_resources(&paths, id, ResourceKind::ResourcePack).unwrap();
        assert_eq!(listed.len(), 1);
        assert!(listed[0].enabled);
        assert_eq!(listed[0].name, "pretty.zip");

        set_resource_enabled(&paths, id, ResourceKind::ResourcePack, "pretty.zip", false).unwrap();
        assert!(pack_dir.join("pretty.zip.disabled").is_file());
        let listed = list_resources(&paths, id, ResourceKind::ResourcePack).unwrap();
        assert!(!listed[0].enabled);

        set_resource_enabled(&paths, id, ResourceKind::ResourcePack, "pretty.zip", true).unwrap();
        assert!(pack_dir.join("pretty.zip").is_file());
    }

    #[test]
    fn batch_disable_worlds() {
        let tmp = TempDir::new().unwrap();
        let paths = Paths::new(tmp.path());
        ensure_resource_dirs(&paths, 1).unwrap();
        let saves = paths.game_dir(1).join("saves");
        fs::create_dir_all(saves.join("A")).unwrap();
        fs::create_dir_all(saves.join("B")).unwrap();
        let n = set_resources_enabled_batch(
            &paths,
            1,
            ResourceKind::World,
            &["A".into(), "B".into()],
            false,
        )
        .unwrap();
        assert_eq!(n, 2);
        assert!(saves.join("A.disabled").is_dir());
        assert!(saves.join("B.disabled").is_dir());
    }
}
