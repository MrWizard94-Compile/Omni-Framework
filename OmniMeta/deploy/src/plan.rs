//! Turning a generated tree into an upload plan — the part worth testing.
//!
//! Publishing is where a metadata service is most easily broken, and always in the
//! same two ways: uploading files in an order that leaves clients reading a torn
//! tree, and setting cache headers that make a mistake permanent. Both are
//! decided here, in pure code with no network, so both are testable.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use omni_meta_format::sha256_hex;

use crate::error::DeployError;

/// How long the mutable roots may be cached. Short enough that a publish is
/// visible quickly, long enough to absorb a burst of launches.
pub const ROOT_MAX_AGE_SECS: u32 = 300;

/// Cache policy for hash-addressed payloads: they are immutable by construction,
/// so a client that has one never needs to ask again (a full year is the
/// conventional "forever" for HTTP caches).
pub const IMMUTABLE_CACHE_CONTROL: &str = "public, max-age=31536000, immutable";

/// One file to publish.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PlannedUpload {
    /// Key in the bucket, e.g. `v1/net.minecraft/1.21.1.json`.
    pub key: String,
    /// Local file to read.
    pub source: PathBuf,
    /// `Content-Type` header.
    pub content_type: String,
    /// `Cache-Control` header.
    pub cache_control: String,
    /// Lowercase-hex sha256 of the file's bytes (payload signing + verification).
    pub sha256: String,
    /// Size in bytes.
    pub size: u64,
    /// Whether this file is one of the mutable roots.
    pub is_root: bool,
}

/// The full publish plan, ordered.
#[derive(Debug, Clone)]
pub struct DeployPlan {
    /// Uploads in the order they must happen.
    pub uploads: Vec<PlannedUpload>,
}

impl DeployPlan {
    /// Total bytes to transfer.
    pub fn total_bytes(&self) -> u64 {
        self.uploads.iter().map(|upload| upload.size).sum()
    }

    /// How many of the uploads are mutable roots.
    pub fn root_count(&self) -> usize {
        self.uploads.iter().filter(|upload| upload.is_root).count()
    }
}

/// Builds the plan for the `v1` tree under `tree_root`.
///
/// **Ordering is the load-bearing decision.** Payloads and component indexes go
/// first; the root index goes last. The tree is a hash chain — the root names
/// component indexes by sha256, which name payloads by sha256 — so a client that
/// reads a root only ever asks for files whose hashes that root recorded. Publish
/// the root first and there is a window where it points at objects that do not
/// exist yet; publish it last and the worst case is a client briefly seeing the
/// *previous* complete tree. One of those is a broken launcher, the other is a
/// slightly stale one.
///
/// # Errors
///
/// [`DeployError::Io`] when the tree cannot be read; [`DeployError::Usage`] when
/// there is no `v1/index.json` (the directory is not a generated tree).
pub fn plan(tree_root: &Path, key_prefix: &str) -> Result<DeployPlan, DeployError> {
    let v1_dir = tree_root.join("v1");
    let root_index = v1_dir.join("index.json");
    if !root_index.is_file() {
        return Err(DeployError::Usage(format!(
            "{} is not a generated tree: no v1/index.json. Run `omni-meta-gen generate` first.",
            tree_root.display()
        )));
    }

    // BTreeMap keeps the walk deterministic, so two runs over the same tree
    // produce byte-identical plans (SOUL §21).
    let mut files: BTreeMap<String, PathBuf> = BTreeMap::new();
    collect(&v1_dir, &v1_dir, &mut files)?;

    let prefix = normalize_prefix(key_prefix);
    let mut payloads = Vec::new();
    let mut roots = Vec::new();

    for (relative, source) in files {
        let bytes = std::fs::read(&source).map_err(|err| DeployError::Io {
            action: "read tree file",
            path: source.clone(),
            source: err,
        })?;
        // The two mutable roots: the tree root index, and each component's version
        // index. Everything else is named by a hash and therefore immutable.
        let is_root = relative == "index.json" || relative.ends_with("/index.json");
        let upload = PlannedUpload {
            key: format!("{prefix}v1/{relative}"),
            source,
            content_type: "application/json".to_owned(),
            cache_control: if is_root {
                format!("public, max-age={ROOT_MAX_AGE_SECS}, must-revalidate")
            } else {
                IMMUTABLE_CACHE_CONTROL.to_owned()
            },
            sha256: sha256_hex(&bytes),
            size: bytes.len() as u64,
            is_root,
        };
        if is_root {
            roots.push(upload);
        } else {
            payloads.push(upload);
        }
    }

    // Payloads first, then component indexes, then the tree root — strictly
    // deepest-first so a reader can never follow a pointer into thin air.
    roots.sort_by(|a, b| {
        let depth = |upload: &PlannedUpload| upload.key.matches('/').count();
        depth(b).cmp(&depth(a)).then_with(|| a.key.cmp(&b.key))
    });
    payloads.extend(roots);
    Ok(DeployPlan { uploads: payloads })
}

fn collect(
    base: &Path,
    dir: &Path,
    out: &mut BTreeMap<String, PathBuf>,
) -> Result<(), DeployError> {
    let entries = std::fs::read_dir(dir).map_err(|err| DeployError::Io {
        action: "list tree directory",
        path: dir.to_path_buf(),
        source: err,
    })?;
    for entry in entries {
        let entry = entry.map_err(|err| DeployError::Io {
            action: "list tree directory",
            path: dir.to_path_buf(),
            source: err,
        })?;
        let path = entry.path();
        if path.is_dir() {
            collect(base, &path, out)?;
        } else {
            let relative = path
                .strip_prefix(base)
                .map_err(|_| DeployError::Usage("tree walk escaped its root".to_owned()))?
                .to_string_lossy()
                .replace('\\', "/");
            out.insert(relative, path);
        }
    }
    Ok(())
}

fn normalize_prefix(prefix: &str) -> String {
    let trimmed = prefix.trim_matches('/');
    if trimmed.is_empty() {
        String::new()
    } else {
        format!("{trimmed}/")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn write(path: &Path, contents: &str) {
        std::fs::create_dir_all(path.parent().expect("parent")).expect("mkdir");
        std::fs::write(path, contents).expect("write");
    }

    fn sample_tree() -> tempfile::TempDir {
        let dir = tempfile::tempdir().expect("tempdir");
        let v1 = dir.path().join("v1");
        write(&v1.join("index.json"), r#"{"formatVersion":1,"components":[]}"#);
        write(&v1.join("net.minecraft/index.json"), r#"{"uid":"net.minecraft","versions":[]}"#);
        write(&v1.join("net.minecraft/1.21.1.json"), r#"{"uid":"net.minecraft"}"#);
        write(&v1.join("org.lwjgl3/index.json"), r#"{"uid":"org.lwjgl3","versions":[]}"#);
        write(&v1.join("org.lwjgl3/3.3.3.json"), r#"{"uid":"org.lwjgl3"}"#);
        dir
    }

    #[test]
    fn payloads_upload_before_the_indexes_that_name_them() {
        let dir = sample_tree();
        let plan = plan(dir.path(), "").expect("plan");
        let keys: Vec<&str> = plan.uploads.iter().map(|u| u.key.as_str()).collect();

        let payload = keys.iter().position(|k| *k == "v1/net.minecraft/1.21.1.json").expect("payload");
        let component = keys.iter().position(|k| *k == "v1/net.minecraft/index.json").expect("component");
        let root = keys.iter().position(|k| *k == "v1/index.json").expect("root");

        assert!(payload < component, "a component index must not outrun its payloads: {keys:?}");
        assert!(component < root, "the tree root must be published last: {keys:?}");
        assert_eq!(root, keys.len() - 1, "the root is the very last write");
    }

    #[test]
    fn roots_revalidate_and_payloads_are_immutable() {
        let dir = sample_tree();
        let plan = plan(dir.path(), "").expect("plan");
        assert_eq!(plan.root_count(), 3, "tree root plus two component indexes");

        for upload in &plan.uploads {
            if upload.is_root {
                assert!(upload.cache_control.contains("must-revalidate"), "{}", upload.key);
                assert!(upload.cache_control.contains("max-age=300"), "{}", upload.key);
            } else {
                // Hash-addressed content: a client that has it never needs to re-ask.
                assert_eq!(upload.cache_control, IMMUTABLE_CACHE_CONTROL, "{}", upload.key);
            }
            assert_eq!(upload.content_type, "application/json");
            assert_eq!(upload.sha256.len(), 64);
        }
    }

    #[test]
    fn planning_is_deterministic() {
        let dir = sample_tree();
        let first = plan(dir.path(), "").expect("plan");
        let second = plan(dir.path(), "").expect("plan");
        assert_eq!(first.uploads, second.uploads, "same tree must plan identically");
    }

    #[test]
    fn prefixes_are_normalized() {
        let dir = sample_tree();
        for prefix in ["meta", "/meta", "meta/", "/meta/"] {
            let plan = plan(dir.path(), prefix).expect("plan");
            assert!(
                plan.uploads.iter().all(|u| u.key.starts_with("meta/v1/")),
                "prefix {prefix:?} produced {:?}",
                plan.uploads[0].key
            );
        }
        let plan = plan(dir.path(), "").expect("plan");
        assert!(plan.uploads.iter().all(|u| u.key.starts_with("v1/")));
    }

    #[test]
    fn a_directory_without_a_tree_is_a_usage_error() {
        let dir = tempfile::tempdir().expect("tempdir");
        let err = plan(dir.path(), "").expect_err("must refuse");
        assert!(matches!(err, DeployError::Usage(_)), "got: {err:?}");
        assert!(err.to_string().contains("omni-meta-gen generate"), "actionable: {err}");
    }

    #[test]
    fn plan_reports_its_size() {
        let dir = sample_tree();
        let plan = plan(dir.path(), "").expect("plan");
        assert_eq!(plan.uploads.len(), 5);
        assert!(plan.total_bytes() > 0);
    }
}
