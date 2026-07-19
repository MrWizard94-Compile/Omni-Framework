//! Hash-verified downloads into a content-addressed blob cache — the shared,
//! immutable store behind every install (LNC-5; the (hmcl) cross-instance
//! cache lesson). Keyed by **sha1** because that is Mojang's integrity dialect
//! for every artifact we fetch (COMPLIANCE §1: official CDNs only).

use std::io::Read as _;
use std::path::PathBuf;
use std::time::Duration;

use sha1::{Digest as _, Sha1};

use crate::error::CoreError;

/// Where bytes come from. Production: [`UreqBlobSource`]; tests: an in-memory
/// map. This trait is the SVC-2 provider-failover seam for Phase 5.
pub trait BlobSource: Send + Sync {
    /// Fetches the full body at `url`.
    ///
    /// # Errors
    ///
    /// [`BlobError`] on any transport failure; the cache wraps it with
    /// download context.
    fn fetch(&self, url: &str) -> Result<Vec<u8>, BlobError>;
}

/// A transport failure fetching one URL.
#[derive(Debug)]
pub struct BlobError {
    /// The URL that failed.
    pub url: String,
    /// Human-readable cause.
    pub message: String,
}

impl std::fmt::Display for BlobError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}: {}", self.url, self.message)
    }
}

impl std::error::Error for BlobError {}

/// Production source over ureq/rustls.
pub struct UreqBlobSource {
    agent: ureq::Agent,
}

/// Hard per-file ceiling; the largest legitimate artifact (client jar) is
/// tens of MB.
const MAX_BLOB_BYTES: u64 = 1024 * 1024 * 1024;

impl UreqBlobSource {
    /// Creates the production source.
    pub fn new() -> Self {
        Self {
            agent: ureq::AgentBuilder::new()
                .timeout(Duration::from_mins(10))
                .user_agent(concat!("OmniLauncher/", env!("CARGO_PKG_VERSION")))
                .build(),
        }
    }
}

impl Default for UreqBlobSource {
    fn default() -> Self {
        Self::new()
    }
}

impl BlobSource for UreqBlobSource {
    fn fetch(&self, url: &str) -> Result<Vec<u8>, BlobError> {
        let response = self
            .agent
            .get(url)
            .call()
            .map_err(|err| BlobError { url: url.to_owned(), message: err.to_string() })?;
        let mut bytes = Vec::new();
        response
            .into_reader()
            .take(MAX_BLOB_BYTES)
            .read_to_end(&mut bytes)
            .map_err(|err| BlobError { url: url.to_owned(), message: err.to_string() })?;
        Ok(bytes)
    }
}

/// Lowercase-hex sha1 of bytes (Mojang's artifact-integrity dialect).
pub fn sha1_hex(bytes: &[u8]) -> String {
    let digest = Sha1::digest(bytes);
    let mut out = String::with_capacity(40);
    for byte in digest {
        use std::fmt::Write as _;
        write!(out, "{byte:02x}").expect("writing to String cannot fail");
    }
    out
}

/// The content-addressed cache: `<root>/<sha1[0..2]>/<sha1>`. Objects are
/// immutable once verified; corrupted entries self-heal by refetch (the same
/// contract as the meta client's object cache).
pub struct ContentCache {
    root: PathBuf,
}

impl ContentCache {
    /// Creates the cache rooted at `root` (created on demand).
    pub fn new(root: impl Into<PathBuf>) -> Self {
        Self { root: root.into() }
    }

    /// The final path of an object (existence not implied).
    pub fn object_path(&self, sha1: &str) -> PathBuf {
        let prefix = sha1.get(0..2).unwrap_or("xx");
        self.root.join(prefix).join(sha1)
    }

    /// Ensures the object for (`url`, `sha1`) is present and verified, and
    /// returns its path. Cache hits are re-hashed (cheap, and it makes the
    /// cache self-healing); misses are fetched, verified, and stored via
    /// write-then-rename so torn writes can never surface under a final name.
    ///
    /// # Errors
    ///
    /// [`CoreError::Io`] in all failure cases, with `action` distinguishing
    /// them: `"download artifact"` for transport failures, `"verify artifact"`
    /// for sha1 mismatches (both record the URL), and filesystem actions for
    /// local trouble.
    pub fn ensure(
        &self,
        source: &dyn BlobSource,
        url: &str,
        sha1: &str,
    ) -> Result<PathBuf, CoreError> {
        let path = self.object_path(sha1);
        if let Ok(bytes) = std::fs::read(&path) {
            if sha1_hex(&bytes) == sha1 {
                return Ok(path);
            }
            let _ = std::fs::remove_file(&path);
        }
        let bytes = source.fetch(url).map_err(|err| CoreError::Io {
            action: "download artifact",
            path: PathBuf::from(url),
            source: std::io::Error::other(err.message),
        })?;
        let actual = sha1_hex(&bytes);
        if actual != sha1 {
            return Err(CoreError::Io {
                action: "verify artifact",
                path: PathBuf::from(url),
                source: std::io::Error::other(format!(
                    "sha1 mismatch: expected {sha1}, downloaded bytes hash to {actual}"
                )),
            });
        }
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).map_err(|e| CoreError::Io {
                action: "create cache directory",
                path: parent.to_path_buf(),
                source: e,
            })?;
        }
        let staging = path.with_extension("part");
        std::fs::write(&staging, &bytes).map_err(|e| CoreError::Io {
            action: "write cache object",
            path: staging.clone(),
            source: e,
        })?;
        if std::fs::rename(&staging, &path).is_err() {
            // Lost a race to an identical (verified) writer; harmless.
            let _ = std::fs::remove_file(&staging);
        }
        Ok(path)
    }

    /// Whether a verified object already exists (used for progress math).
    pub fn has(&self, sha1: &str) -> bool {
        std::fs::read(self.object_path(sha1)).is_ok_and(|bytes| sha1_hex(&bytes) == sha1)
    }
}
