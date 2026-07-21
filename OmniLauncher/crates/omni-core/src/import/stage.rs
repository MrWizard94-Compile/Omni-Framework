//! Materialize imported mod jars into the instance `game/mods/` directory.
//!
//! Import historically only wrote SQLite mod rows. Launch always points OmniLoader
//! at `paths.game_dir(id)/mods`, so without this step a "successful" import still
//! boots an empty pack. This module copies (or hard-links) jars from the foreign
//! source into the private instance game dir (MUST NOT #4: never share the
//! foreign mods folder as the live path).

use std::fs;
use std::path::{Path, PathBuf};

use crate::db::Db;
use crate::error::CoreError;
use crate::install::Paths;
use crate::store::Mod;

use super::{IMPORT_SOURCE_KEY, IMPORTED_LOADER_KEY};

/// Outcome of a materialize pass — counts only.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct StageReport {
    /// Jars present under the instance mods dir after this pass.
    pub staged: usize,
    /// Enabled mods in the store that still have no matching jar.
    pub missing: usize,
    /// Source directories that were scanned.
    pub sources_scanned: usize,
}

/// Candidate source `mods/` folders for a foreign instance root + import format.
pub fn source_mods_dirs(format: &str, source_root: &Path) -> Vec<PathBuf> {
    let mut dirs = Vec::new();
    let push = |dirs: &mut Vec<PathBuf>, path: PathBuf| {
        if path.is_dir() && !dirs.iter().any(|existing| existing == &path) {
            dirs.push(path);
        }
    };
    // Order matters slightly for scan notes; all existing dirs are used.
    if matches!(format, "mrpack") {
        push(&mut dirs, source_root.join("overrides").join("mods"));
    }
    if matches!(format, "prism-multimc" | "prism" | "multimc") {
        push(&mut dirs, source_root.join("minecraft").join("mods"));
        push(&mut dirs, source_root.join(".minecraft").join("mods"));
    }
    // Common layouts (CF, official, expanded packs, fallbacks).
    push(&mut dirs, source_root.join("mods"));
    push(&mut dirs, source_root.join("minecraft").join("mods"));
    push(&mut dirs, source_root.join(".minecraft").join("mods"));
    dirs
}

fn is_mod_jar_name(name: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    // Launcher convention uses a compound suffix `.jar.disabled` (not a single
    // extension), so Path::extension alone is the wrong tool.
    lower.ends_with(".jar.disabled")
        || Path::new(&lower)
            .extension()
            .is_some_and(|ext| ext.eq_ignore_ascii_case("jar"))
}

/// Copies or hard-links every `*.jar` / `*.jar.disabled` from `source_mods` into
/// `dest_mods`, preserving the disabled suffix. Existing destinations are left
/// alone (idempotent re-import / re-launch).
///
/// # Errors
///
/// [`CoreError::Io`] when directories or files cannot be read or written.
pub fn copy_mod_jars(source_mods: &Path, dest_mods: &Path) -> Result<usize, CoreError> {
    if !source_mods.is_dir() {
        return Ok(0);
    }
    fs::create_dir_all(dest_mods).map_err(|source| CoreError::Io {
        action: "create instance mods dir",
        path: dest_mods.to_path_buf(),
        source,
    })?;
    let mut staged = 0_usize;
    let entries = fs::read_dir(source_mods).map_err(|source| CoreError::Io {
        action: "list source mods dir",
        path: source_mods.to_path_buf(),
        source,
    })?;
    for entry in entries {
        let entry = entry.map_err(|source| CoreError::Io {
            action: "read source mods entry",
            path: source_mods.to_path_buf(),
            source,
        })?;
        let path = entry.path();
        let name = entry.file_name();
        let name_str = name.to_string_lossy();
        if !is_mod_jar_name(&name_str) || !path.is_file() {
            continue;
        }
        let dest = dest_mods.join(&name);
        if dest.exists() {
            staged += 1;
            continue;
        }
        // Prefer hard-link (cheap, same volume); fall back to copy.
        if fs::hard_link(&path, &dest).is_err() {
            fs::copy(&path, &dest).map_err(|source| CoreError::Io {
                action: "copy mod jar into instance",
                path: path.clone(),
                source,
            })?;
        }
        staged += 1;
    }
    Ok(staged)
}

/// Applies store `enabled` flags to on-disk names (`.jar` ↔ `.jar.disabled`).
///
/// # Errors
///
/// [`CoreError::Io`] when a rename fails.
pub fn sync_enabled_flags(dest_mods: &Path, mods: &[Mod]) -> Result<(), CoreError> {
    if !dest_mods.is_dir() {
        return Ok(());
    }
    for mod_row in mods {
        let enabled_name = strip_disabled_suffix(&mod_row.file_name);
        let disabled_name = format!("{enabled_name}.disabled");
        let enabled_path = dest_mods.join(&enabled_name);
        let disabled_path = dest_mods.join(&disabled_name);
        let raw_path = dest_mods.join(&mod_row.file_name);
        if mod_row.enabled {
            if disabled_path.is_file() && !enabled_path.exists() {
                fs::rename(&disabled_path, &enabled_path).map_err(|source| CoreError::Io {
                    action: "enable mod jar",
                    path: disabled_path,
                    source,
                })?;
            }
            continue;
        }
        let from = if enabled_path.is_file() {
            Some(enabled_path)
        } else if raw_path.is_file()
            && raw_path.extension().is_none_or(|e| e != "disabled")
        {
            Some(raw_path)
        } else {
            None
        };
        if let Some(from) = from.filter(|_| !disabled_path.exists()) {
            fs::rename(&from, &disabled_path).map_err(|source| CoreError::Io {
                action: "disable mod jar",
                path: from,
                source,
            })?;
        }
    }
    Ok(())
}

fn strip_disabled_suffix(name: &str) -> String {
    name.strip_suffix(".disabled")
        .map_or_else(|| name.to_owned(), str::to_owned)
}

/// Full materialize for one instance: resolve import provenance, copy jars,
/// apply enablement. Safe to call repeatedly (idempotent).
///
/// # Errors
///
/// [`CoreError::NotFound`] for a missing instance; [`CoreError::Io`] on filesystem
/// failures; store errors when reading settings or writing `importedLoader`.
pub fn materialize_instance_mods(
    paths: &Paths,
    db: &Db,
    instance_id: i64,
) -> Result<StageReport, CoreError> {
    let (settings, mods) = db.with_store(|store| {
        let instance = store
            .instance(instance_id)?
            .ok_or(CoreError::NotFound { what: "instance", id: instance_id })?;
        let mods = store.mods_for_instance(instance_id)?;
        Ok((instance.settings, mods))
    })?;

    let dest_mods = paths.game_dir(instance_id).join("mods");
    fs::create_dir_all(&dest_mods).map_err(|source| CoreError::Io {
        action: "create instance mods dir",
        path: dest_mods.clone(),
        source,
    })?;

    let mut report = StageReport::default();
    if let Some(imported) = settings.get(IMPORT_SOURCE_KEY) {
        let format = imported.get("format").and_then(|v| v.as_str()).unwrap_or("");
        if let Some(source_root) = imported
            .get("source")
            .and_then(|v| v.as_str())
            .map(PathBuf::from)
        {
            for dir in source_mods_dirs(format, &source_root) {
                report.sources_scanned += 1;
                report.staged += copy_mod_jars(&dir, &dest_mods)?;
            }
        }
    }

    sync_enabled_flags(&dest_mods, &mods)?;

    let on_disk: std::collections::BTreeSet<String> = fs::read_dir(&dest_mods)
        .map_err(|source| CoreError::Io {
            action: "list instance mods after stage",
            path: dest_mods.clone(),
            source,
        })?
        .filter_map(Result::ok)
        .filter_map(|entry| {
            let name = entry.file_name().to_string_lossy().into_owned();
            is_mod_jar_name(&name).then_some(name)
        })
        .collect();
    report.staged = on_disk.len();
    for mod_row in &mods {
        if !mod_row.enabled {
            continue;
        }
        let base = strip_disabled_suffix(&mod_row.file_name);
        let present = on_disk.contains(&base)
            || on_disk.contains(&mod_row.file_name)
            || on_disk.contains(&format!("{base}.disabled"));
        if !present {
            report.missing += 1;
        }
    }

    // Manual drops into a vanilla instance would otherwise launch vanilla and
    // ignore mods/. Prefer fabric (D10) when jars exist and no loader is set.
    if report.staged > 0 {
        let has_loader = settings
            .get(IMPORTED_LOADER_KEY)
            .and_then(|v| v.get("kind"))
            .and_then(|v| v.as_str())
            .is_some_and(|k| !k.eq_ignore_ascii_case("vanilla"));
        if !has_loader {
            let mut new_settings = settings.clone();
            new_settings[IMPORTED_LOADER_KEY] = serde_json::json!({
                "kind": "fabric",
                "version": "hosted",
                "note": "auto-set because game/mods contains jars",
            });
            db.with_store(|store| store.update_instance_settings(instance_id, &new_settings))?;
        }
    }

    Ok(report)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    #[test]
    fn copy_mod_jars_is_idempotent_and_preserves_disabled() {
        let dir = TempDir::new().expect("tmp");
        let src = dir.path().join("src_mods");
        let dst = dir.path().join("dst_mods");
        fs::create_dir_all(&src).unwrap();
        fs::write(src.join("a.jar"), b"aaa").unwrap();
        fs::write(src.join("b.jar.disabled"), b"bbb").unwrap();
        assert_eq!(copy_mod_jars(&src, &dst).unwrap(), 2);
        assert_eq!(copy_mod_jars(&src, &dst).unwrap(), 2);
        assert!(dst.join("a.jar").is_file());
        assert!(dst.join("b.jar.disabled").is_file());
    }

    #[test]
    fn source_mods_dirs_prism_prefers_minecraft() {
        let dir = TempDir::new().expect("tmp");
        let root = dir.path();
        fs::create_dir_all(root.join("minecraft").join("mods")).unwrap();
        let found = source_mods_dirs("prism-multimc", root);
        assert!(found.iter().any(|p| p.ends_with(Path::new("minecraft").join("mods"))));
    }
}
