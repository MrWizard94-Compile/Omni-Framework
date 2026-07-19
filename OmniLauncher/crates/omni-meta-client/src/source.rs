//! Transport abstraction: where tree files come from.

use std::fmt;
use std::io::Read as _;
use std::path::PathBuf;
use std::time::Duration;

/// A transport failure, independent of which source produced it.
#[derive(Debug)]
pub struct SourceError {
    /// Tree-relative path that was requested.
    pub path: String,
    /// Human-readable cause.
    pub message: String,
}

impl fmt::Display for SourceError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}: {}", self.path, self.message)
    }
}

impl std::error::Error for SourceError {}

/// Validates a tree-relative path before any source touches it: non-empty
/// `/`-separated segments, no traversal (`.`/`..`), no backslashes, drive
/// colons, or URL schemes. Index files are hash-verified but still treated as
/// untrusted input (SOUL §9) — both shipped sources enforce this.
///
/// # Errors
///
/// [`SourceError`] naming the offending path.
pub fn safe_rel_path(rel: &str) -> Result<(), SourceError> {
    let reject = |message: &str| {
        Err(SourceError { path: rel.to_owned(), message: message.to_owned() })
    };
    if rel.is_empty() {
        return reject("empty path");
    }
    for segment in rel.split('/') {
        if segment.is_empty() {
            return reject("empty path segment (leading/double slash)");
        }
        if segment == "." || segment == ".." {
            return reject("path traversal segment");
        }
        if segment.contains('\\') || segment.contains(':') {
            return reject("forbidden character in path segment");
        }
    }
    Ok(())
}

/// A place tree files can be fetched from. Implementations must be safe to
/// call from multiple threads (`&self`).
pub trait MetaSource {
    /// Fetches the file at a tree-relative path (e.g. `net.minecraft/index.json`).
    ///
    /// # Errors
    ///
    /// [`SourceError`] on unreachable/absent/oversized content or unsafe paths.
    fn fetch(&self, rel_path: &str) -> Result<Vec<u8>, SourceError>;

    /// Human-readable identity for diagnostics (base URL or directory).
    fn describe(&self) -> String;
}

/// CDN transport (ADR-0012). The base URL points at the `/v1` root and is a
/// build-config value exactly like (prism-launcher)'s `Launcher_META_URL`.
pub struct HttpSource {
    agent: ureq::Agent,
    base_url: String,
}

impl HttpSource {
    /// Creates a source rooted at `base_url` (with or without trailing slash).
    pub fn new(base_url: &str) -> Self {
        let agent = ureq::AgentBuilder::new()
            .timeout(Duration::from_secs(30))
            .user_agent(concat!(
                "omni-meta-client/",
                env!("CARGO_PKG_VERSION"),
                " (wpaistudio.net)"
            ))
            .build();
        Self { agent, base_url: base_url.trim_end_matches('/').to_owned() }
    }
}

/// Hard ceiling on any meta file. The largest real payloads are tens of
/// kilobytes; 64 MiB means a misconfigured URL can never balloon memory.
const MAX_FILE_BYTES: u64 = 64 * 1024 * 1024;

impl MetaSource for HttpSource {
    fn fetch(&self, rel_path: &str) -> Result<Vec<u8>, SourceError> {
        safe_rel_path(rel_path)?;
        let url = format!("{}/{rel_path}", self.base_url);
        let response = self.agent.get(&url).call().map_err(|err| SourceError {
            path: rel_path.to_owned(),
            message: err.to_string(),
        })?;
        let mut bytes = Vec::new();
        response
            .into_reader()
            .take(MAX_FILE_BYTES)
            .read_to_end(&mut bytes)
            .map_err(|err| SourceError { path: rel_path.to_owned(), message: err.to_string() })?;
        Ok(bytes)
    }

    fn describe(&self) -> String {
        self.base_url.clone()
    }
}

/// Local-directory transport: tests, tooling, and air-gapped trees. The root
/// points at a `v1` directory (the same shape the CDN serves).
pub struct DirSource {
    root: PathBuf,
}

impl DirSource {
    /// Creates a source over a local `v1` directory.
    pub fn new(root: impl Into<PathBuf>) -> Self {
        Self { root: root.into() }
    }
}

impl MetaSource for DirSource {
    fn fetch(&self, rel_path: &str) -> Result<Vec<u8>, SourceError> {
        safe_rel_path(rel_path)?;
        let mut path = self.root.clone();
        for segment in rel_path.split('/') {
            path.push(segment);
        }
        std::fs::read(&path)
            .map_err(|err| SourceError { path: rel_path.to_owned(), message: err.to_string() })
    }

    fn describe(&self) -> String {
        self.root.display().to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn safe_paths_pass() {
        for path in ["index.json", "net.minecraft/index.json", "net.minecraft/1.21.1.json"] {
            safe_rel_path(path).expect(path);
        }
    }

    #[test]
    fn hostile_paths_fail() {
        for path in [
            "",
            "/etc/passwd",
            "../secrets.json",
            "a/../b.json",
            "a//b.json",
            "a\\b.json",
            "C:/windows/system32",
            "https://evil.example/x.json",
        ] {
            assert!(safe_rel_path(path).is_err(), "should reject {path:?}");
        }
    }
}
