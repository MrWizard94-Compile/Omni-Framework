//! Hash-verified download of instance-owned content (mrpack CDN files, etc.).
//!
//! Separate from Mojang game/assets/java phases: these land in the instance
//! `game/` tree under relative paths from the pack index, not the shared
//! content-addressed object store alone (though blobs are still cached by sha1
//! when a sha1 is present).

use std::path::{Path, PathBuf};

use sha2::{Digest as _, Sha512};

use crate::db::Db;
use crate::error::CoreError;
use crate::import::{
    PendingDownload, extract_mrpack_overrides, pending_downloads_from_settings,
    store_pending_downloads, IMPORT_SOURCE_KEY,
};
use crate::install::blob::{BlobSource, ContentCache, sha1_hex};
use crate::install::Paths;

/// Ensures every pending download for `instance_id` is present under the
/// instance game directory. Already-present, hash-matching files are skipped.
///
/// Returns `(completed_this_call, remaining)`.
///
/// # Errors
///
/// Transport, hash-mismatch, or filesystem failures as [`CoreError::Io`].
pub fn ensure_pending_downloads(
    paths: &Paths,
    db: &Db,
    source: &dyn BlobSource,
    instance_id: i64,
    max_files: usize,
) -> Result<(usize, usize), CoreError> {
    let settings = db.with_store(|store| {
        store
            .instance(instance_id)?
            .map(|i| i.settings)
            .ok_or(CoreError::NotFound { what: "instance", id: instance_id })
    })?;
    let pending = pending_downloads_from_settings(&settings)?;
    if pending.is_empty() {
        return Ok((0, 0));
    }

    let game_dir = paths.game_dir(instance_id);
    std::fs::create_dir_all(&game_dir).map_err(|source| CoreError::Io {
        action: "create instance game dir for mod downloads",
        path: game_dir.clone(),
        source,
    })?;

    // Prefer the shared object cache when sha1 is known (cross-instance reuse).
    let cache = ContentCache::new(paths.objects_root());
    let mut done = 0_usize;
    let mut still: Vec<PendingDownload> = Vec::new();
    let mut queue = pending;

    while let Some(item) = queue.first().cloned() {
        if done >= max_files {
            still.extend(queue);
            break;
        }
        queue.remove(0);
        let dest = safe_game_path(&game_dir, &item.path)?;
        if file_satisfies(&dest, &item)? {
            done += 1;
            continue;
        }
        match fetch_one(&cache, source, &item, &dest) {
            Ok(()) => done += 1,
            Err(err) => {
                // Keep the item queued so a resume can retry; surface the error.
                still.push(item);
                still.extend(queue);
                store_pending_downloads(db, instance_id, &still)?;
                return Err(err);
            }
        }
    }
    store_pending_downloads(db, instance_id, &still)?;
    Ok((done, still.len()))
}

fn fetch_one(
    cache: &ContentCache,
    source: &dyn BlobSource,
    item: &PendingDownload,
    dest: &Path,
) -> Result<(), CoreError> {
    let urls: Vec<&str> = std::iter::once(item.url.as_str())
        .chain(item.mirrors.iter().map(String::as_str))
        .collect();
    let mut last_err: Option<CoreError> = None;
    for url in urls {
        let result = if let Some(sha1) = item.sha1.as_deref() {
            cache.ensure(source, url, sha1).and_then(|object| {
                if let Some(parent) = dest.parent() {
                    std::fs::create_dir_all(parent).map_err(|e| CoreError::Io {
                        action: "create dest parent for pack file",
                        path: parent.to_path_buf(),
                        source: e,
                    })?;
                }
                std::fs::copy(&object, dest).map_err(|e| CoreError::Io {
                    action: "place pack file from cache",
                    path: dest.to_path_buf(),
                    source: e,
                })?;
                Ok(())
            })
        } else {
            // No sha1: fetch, optionally verify sha512, write.
            source.fetch(url).map_err(|err| CoreError::Io {
                action: "download pack file",
                path: PathBuf::from(url),
                source: std::io::Error::other(err.message),
            }).and_then(|bytes| {
                if let Some(expected) = item.sha512.as_deref() {
                    let actual = sha512_hex(&bytes);
                    if actual != expected {
                        return Err(CoreError::Io {
                            action: "verify pack file sha512",
                            path: PathBuf::from(url),
                            source: std::io::Error::other(format!(
                                "sha512 mismatch: expected {expected}, got {actual}"
                            )),
                        });
                    }
                }
                if let Some(parent) = dest.parent() {
                    std::fs::create_dir_all(parent).map_err(|e| CoreError::Io {
                        action: "create dest parent for pack file",
                        path: parent.to_path_buf(),
                        source: e,
                    })?;
                }
                std::fs::write(dest, &bytes).map_err(|e| CoreError::Io {
                    action: "write pack file",
                    path: dest.to_path_buf(),
                    source: e,
                })?;
                Ok(())
            })
        };
        match result {
            Ok(()) => return Ok(()),
            Err(err) => last_err = Some(err),
        }
    }
    Err(last_err.unwrap_or_else(|| CoreError::Io {
        action: "download pack file",
        path: PathBuf::from(&item.url),
        source: std::io::Error::other("no download URLs"),
    }))
}

fn file_satisfies(path: &Path, item: &PendingDownload) -> Result<bool, CoreError> {
    if !path.is_file() {
        return Ok(false);
    }
    let bytes = std::fs::read(path).map_err(|e| CoreError::Io {
        action: "read existing pack file",
        path: path.to_path_buf(),
        source: e,
    })?;
    if let Some(sha1) = item.sha1.as_deref() {
        return Ok(sha1_hex(&bytes) == sha1);
    }
    if let Some(sha512) = item.sha512.as_deref() {
        return Ok(sha512_hex(&bytes) == sha512);
    }
    if let Some(size) = item.size {
        return Ok(u64::try_from(bytes.len()).unwrap_or(u64::MAX) == size);
    }
    // No integrity data: treat existing file as good (idempotent re-run).
    Ok(true)
}

fn sha512_hex(bytes: &[u8]) -> String {
    let digest = Sha512::digest(bytes);
    let mut out = String::with_capacity(128);
    for byte in digest {
        use std::fmt::Write as _;
        write!(&mut out, "{byte:02x}").expect("String write");
    }
    out
}

fn safe_game_path(game_dir: &Path, relative: &str) -> Result<PathBuf, CoreError> {
    let mut dest = game_dir.to_path_buf();
    for component in relative.replace('\\', "/").split('/') {
        if component.is_empty() || component == "." {
            continue;
        }
        if component == ".." {
            return Err(CoreError::Io {
                action: "reject path escape in pack file",
                path: game_dir.join(relative),
                source: std::io::Error::new(
                    std::io::ErrorKind::InvalidInput,
                    "pack file path escapes game directory",
                ),
            });
        }
        dest.push(component);
    }
    Ok(dest)
}

/// After import: extract mrpack overrides when the source is an `.mrpack`.
///
/// # Errors
///
/// Filesystem / zip errors as [`CoreError`].
pub fn extract_imported_mrpack_overrides(
    paths: &Paths,
    db: &Db,
    instance_id: i64,
) -> Result<usize, CoreError> {
    let settings = db.with_store(|store| {
        store
            .instance(instance_id)?
            .map(|i| i.settings)
            .ok_or(CoreError::NotFound { what: "instance", id: instance_id })
    })?;
    let Some(imported) = settings.get(IMPORT_SOURCE_KEY) else {
        return Ok(0);
    };
    let format = imported.get("format").and_then(|v| v.as_str()).unwrap_or("");
    if format != "mrpack" {
        return Ok(0);
    }
    let Some(source) = imported.get("source").and_then(|v| v.as_str()).map(PathBuf::from) else {
        return Ok(0);
    };
    if !source.is_file() {
        return Ok(0);
    }
    let game = paths.game_dir(instance_id);
    extract_mrpack_overrides(&source, &game)
}
