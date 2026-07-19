//! Tree → files: canonical serialization, hash chaining, index construction.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use omni_meta_format::{
    ComponentIndex, ComponentRef, IndexRoot, VersionSummary, component_index_path,
    payload_path, root_index_path, sanitize_file_stem, sha256_hex, to_canonical_json,
    validate_uid, FORMAT_VERSION,
};

use crate::error::GenError;
use crate::normalize::Tree;

/// Inputs for [`emit`].
#[derive(Debug, Clone)]
pub struct EmitOptions {
    /// Directory that will contain the `v1/` tree.
    pub out_root: PathBuf,
    /// Replace an existing `v1/` tree instead of refusing to touch it.
    pub force: bool,
}

/// What [`emit`] wrote.
#[derive(Debug, Clone, Copy)]
pub struct EmitSummary {
    /// Number of components.
    pub components: usize,
    /// Number of version payloads.
    pub versions: usize,
    /// Total files written (payloads + indexes + root).
    pub files: usize,
}

fn write_tree_file(v1_dir: &Path, rel: &str, bytes: &[u8]) -> Result<(), GenError> {
    let path = v1_dir.join(rel.replace('/', std::path::MAIN_SEPARATOR_STR));
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)
            .map_err(|e| GenError::io("create tree directory", parent, e))?;
    }
    std::fs::write(&path, bytes).map_err(|e| GenError::io("write tree file", &path, e))
}

/// Writes the `/v1` tree for a normalized [`Tree`].
///
/// Canonical ordering (all enforced here, verified by the golden tests):
/// components sorted by uid; versions sorted newest `releaseTime` first with
/// ties broken by version string ascending; every file in canonical JSON.
///
/// # Errors
///
/// [`GenError::Usage`] when `out_root/v1` exists and `force` is off;
/// [`GenError::Integrity`] on sanitized-file-stem collisions or payloads whose
/// uid/version disagree with their map position; [`GenError::Io`] on
/// filesystem failures.
pub fn emit(tree: &Tree, options: &EmitOptions) -> Result<EmitSummary, GenError> {
    let v1_dir = options.out_root.join("v1");
    if v1_dir.exists() {
        if !options.force {
            return Err(GenError::Usage(format!(
                "output tree {} already exists; pass --force to replace it",
                v1_dir.display()
            )));
        }
        std::fs::remove_dir_all(&v1_dir)
            .map_err(|e| GenError::io("remove previous tree", &v1_dir, e))?;
    }

    let mut component_refs = Vec::new();
    let mut versions_total = 0_usize;
    let mut files = 0_usize;

    for (uid, versions) in &tree.components {
        validate_uid(uid)?;
        let recommended = tree.recommended.get(uid);
        let mut stems: BTreeMap<String, &str> = BTreeMap::new();
        let mut summaries = Vec::new();
        for (version, payload) in versions {
            if payload.uid != *uid || payload.version != *version {
                return Err(GenError::Integrity {
                    detail: format!(
                        "payload declares {}/{} but is stored as {uid}/{version}",
                        payload.uid, payload.version
                    ),
                });
            }
            let stem = sanitize_file_stem(version)?;
            if let Some(previous) = stems.insert(stem.clone(), version) {
                return Err(GenError::Integrity {
                    detail: format!(
                        "component {uid}: versions {previous:?} and {version:?} both sanitize \
                         to file stem {stem:?}"
                    ),
                });
            }
            let bytes = to_canonical_json(payload)?;
            let rel = payload_path(uid, version)?;
            write_tree_file(&v1_dir, &rel, &bytes)?;
            files += 1;
            versions_total += 1;
            summaries.push(VersionSummary {
                version: version.clone(),
                release_time: payload.release_time.clone(),
                release_type: payload.release_type.clone(),
                recommended: recommended.is_some_and(|set| set.contains(version)),
                url: rel,
                sha256: sha256_hex(&bytes),
            });
        }
        // Newest first; ties broken by version string ascending. Release times
        // are upstream RFC 3339 with a uniform offset, so string order is
        // chronological order.
        summaries.sort_by(|a, b| {
            b.release_time.cmp(&a.release_time).then_with(|| a.version.cmp(&b.version))
        });
        let index = ComponentIndex { uid: uid.clone(), versions: summaries };
        let bytes = to_canonical_json(&index)?;
        let rel = component_index_path(uid);
        write_tree_file(&v1_dir, &rel, &bytes)?;
        files += 1;
        component_refs.push(ComponentRef {
            uid: uid.clone(),
            versions_url: rel,
            sha256: sha256_hex(&bytes),
        });
    }

    let root = IndexRoot { format_version: FORMAT_VERSION, components: component_refs };
    let bytes = to_canonical_json(&root)?;
    write_tree_file(&v1_dir, &root_index_path(), &bytes)?;
    files += 1;

    Ok(EmitSummary { components: tree.components.len(), versions: versions_total, files })
}
