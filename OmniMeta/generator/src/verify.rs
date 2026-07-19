//! Tree audit: the publish gate before any CDN upload (and the second half of
//! `generate`, which refuses to hand over a tree it cannot verify).
//!
//! Checks, per level: parseability, hash-chain integrity (root → component
//! indexes → payloads), identity cross-checks (uid/version/releaseTime/type
//! between index rows and payloads), URL safety (relative, no traversal), and
//! `requires` resolvability under the v1 range dialect (absent = any, present
//! = exact version).

use std::collections::{BTreeMap, BTreeSet};
use std::fmt;
use std::path::{Path, PathBuf};

use omni_meta_format::{ComponentIndex, ComponentPayload, IndexRoot, sha256_hex};

use crate::error::GenError;

/// One verification finding. Any finding fails the gate.
#[derive(Debug, Clone)]
pub struct Issue {
    /// Tree-relative file the finding is about.
    pub path: String,
    /// What is wrong.
    pub message: String,
}

impl fmt::Display for Issue {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}: {}", self.path, self.message)
    }
}

/// Resolves a tree-relative URL to a filesystem path, rejecting anything that
/// could escape the tree (absolute paths, `..`, backslashes, drive colons).
fn resolve(v1_dir: &Path, rel: &str) -> Option<PathBuf> {
    if rel.is_empty() {
        return None;
    }
    let mut path = v1_dir.to_path_buf();
    for segment in rel.split('/') {
        if segment.is_empty()
            || segment == "."
            || segment == ".."
            || segment.contains('\\')
            || segment.contains(':')
        {
            return None;
        }
        path.push(segment);
    }
    Some(path)
}

fn read_checked(
    v1_dir: &Path,
    rel: &str,
    expected_sha256: &str,
    issues: &mut Vec<Issue>,
) -> Option<Vec<u8>> {
    let Some(path) = resolve(v1_dir, rel) else {
        issues.push(Issue { path: rel.to_owned(), message: "unsafe url".to_owned() });
        return None;
    };
    let bytes = match std::fs::read(&path) {
        Ok(bytes) => bytes,
        Err(err) => {
            issues.push(Issue { path: rel.to_owned(), message: format!("unreadable: {err}") });
            return None;
        }
    };
    let actual = sha256_hex(&bytes);
    if actual != expected_sha256 {
        issues.push(Issue {
            path: rel.to_owned(),
            message: format!("sha256 mismatch: index declares {expected_sha256}, file is {actual}"),
        });
        return None;
    }
    Some(bytes)
}

/// Audits one component: index hash + parse, identity cross-checks against
/// every payload, duplicate-version detection. Feeds the version universe and
/// payload list for the requires pass.
fn verify_component(
    v1_dir: &Path,
    component: &omni_meta_format::ComponentRef,
    universe: &mut BTreeMap<String, BTreeSet<String>>,
    payloads: &mut Vec<(String, ComponentPayload)>,
    issues: &mut Vec<Issue>,
) {
    let Some(index_bytes) =
        read_checked(v1_dir, &component.versions_url, &component.sha256, issues)
    else {
        return;
    };
    let index = match ComponentIndex::parse(&index_bytes) {
        Ok(index) => index,
        Err(err) => {
            issues.push(Issue { path: component.versions_url.clone(), message: err.to_string() });
            return;
        }
    };
    if index.uid != component.uid {
        issues.push(Issue {
            path: component.versions_url.clone(),
            message: format!(
                "component index declares uid {:?}, root expects {:?}",
                index.uid, component.uid
            ),
        });
    }
    let mut seen_versions = BTreeSet::new();
    for summary in &index.versions {
        if !seen_versions.insert(summary.version.clone()) {
            issues.push(Issue {
                path: component.versions_url.clone(),
                message: format!("duplicate version {:?}", summary.version),
            });
            continue;
        }
        let Some(payload_bytes) = read_checked(v1_dir, &summary.url, &summary.sha256, issues)
        else {
            continue;
        };
        let payload = match ComponentPayload::parse(&payload_bytes) {
            Ok(payload) => payload,
            Err(err) => {
                issues.push(Issue { path: summary.url.clone(), message: err.to_string() });
                continue;
            }
        };
        for (field, index_value, payload_value) in [
            ("uid", component.uid.as_str(), payload.uid.as_str()),
            ("version", summary.version.as_str(), payload.version.as_str()),
            ("releaseTime", summary.release_time.as_str(), payload.release_time.as_str()),
            ("type", summary.release_type.as_str(), payload.release_type.as_str()),
        ] {
            if index_value != payload_value {
                issues.push(Issue {
                    path: summary.url.clone(),
                    message: format!(
                        "{field} mismatch: index says {index_value:?}, \
                         payload says {payload_value:?}"
                    ),
                });
            }
        }
        universe.entry(component.uid.clone()).or_default().insert(summary.version.clone());
        payloads.push((summary.url.clone(), payload));
    }
}

/// Verifies the `/v1` tree under `tree_root` (the directory passed to
/// `generate --out`). Returns every finding; an empty list is a pass.
///
/// # Errors
///
/// [`GenError::Io`] only when the root index itself cannot be read — every
/// deeper problem is reported as an [`Issue`] instead so one broken file
/// cannot mask the rest of the audit.
pub fn verify(tree_root: &Path) -> Result<Vec<Issue>, GenError> {
    let v1_dir = tree_root.join("v1");
    let mut issues = Vec::new();

    let root_path = v1_dir.join("index.json");
    let root_bytes =
        std::fs::read(&root_path).map_err(|e| GenError::io("read root index", &root_path, e))?;
    let root = match IndexRoot::parse(&root_bytes) {
        Ok(root) => root,
        Err(err) => {
            issues.push(Issue { path: "index.json".to_owned(), message: err.to_string() });
            return Ok(issues);
        }
    };

    // Pass 1: structure + hashes + identity; collect the version universe.
    let mut universe: BTreeMap<String, BTreeSet<String>> = BTreeMap::new();
    let mut payloads: Vec<(String, ComponentPayload)> = Vec::new();
    let mut seen_uids = BTreeSet::new();
    for component in &root.components {
        if !seen_uids.insert(component.uid.clone()) {
            issues.push(Issue {
                path: "index.json".to_owned(),
                message: format!("duplicate component uid {:?}", component.uid),
            });
            continue;
        }
        verify_component(&v1_dir, component, &mut universe, &mut payloads, &mut issues);
    }

    // Pass 2: requires edges against the collected universe.
    for (url, payload) in &payloads {
        for requirement in &payload.requires {
            match universe.get(&requirement.uid) {
                None => issues.push(Issue {
                    path: url.clone(),
                    message: format!(
                        "requires unknown component {:?}",
                        requirement.uid
                    ),
                }),
                Some(versions) => {
                    if let Some(range) = &requirement.range
                        && !versions.contains(range)
                    {
                        issues.push(Issue {
                            path: url.clone(),
                            message: format!(
                                "requires {} {range:?}, which is not in the tree \
                                 (v1 range dialect is exact-or-any)",
                                requirement.uid
                            ),
                        });
                    }
                }
            }
        }
    }

    Ok(issues)
}
