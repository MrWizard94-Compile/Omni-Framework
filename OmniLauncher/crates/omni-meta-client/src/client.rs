//! The client proper: hash-verified resolution with a content-addressed cache.

use std::path::{Path, PathBuf};

use omni_meta_format::{
    ComponentIndex, ComponentPayload, IndexRoot, VersionSummary, sha256_hex,
};

use crate::error::MetaError;
use crate::source::MetaSource;

/// Whether a result came from the live source or the offline fallback.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Freshness {
    /// Fetched from the source this call.
    Fresh,
    /// Served from the local cache because the source was unreachable —
    /// callers should surface degraded mode (SVC-2).
    CachedFallback,
}

/// Meta client over any [`MetaSource`], with a content-addressed local cache.
///
/// Cache layout under the directory given to [`MetaClient::new`]:
///
/// ```text
/// root/index.json               last good root index (mutable, short-lived)
/// objects/<sha[0..2]>/<sha256>  verified immutable files (indexes + payloads)
/// ```
pub struct MetaClient<S: MetaSource> {
    source: S,
    cache_dir: PathBuf,
}

fn read_io<'p>(
    action: &'static str,
    path: &'p Path,
) -> impl FnOnce(std::io::Error) -> MetaError + 'p {
    move |source| MetaError::Io { action, path: path.to_path_buf(), source }
}

impl<S: MetaSource> MetaClient<S> {
    /// Creates a client, preparing the cache directory.
    ///
    /// # Errors
    ///
    /// [`MetaError::Io`] when the cache directories cannot be created.
    pub fn new(source: S, cache_dir: impl Into<PathBuf>) -> Result<Self, MetaError> {
        let cache_dir = cache_dir.into();
        for sub in ["root", "objects"] {
            let dir = cache_dir.join(sub);
            std::fs::create_dir_all(&dir).map_err(read_io("create cache directory", &dir))?;
        }
        Ok(Self { source, cache_dir })
    }

    /// The source this client reads from (diagnostics).
    pub fn describe_source(&self) -> String {
        self.source.describe()
    }

    fn cached_root_path(&self) -> PathBuf {
        self.cache_dir.join("root").join("index.json")
    }

    fn object_path(&self, sha256: &str) -> PathBuf {
        let prefix = sha256.get(0..2).unwrap_or("xx");
        self.cache_dir.join("objects").join(prefix).join(sha256)
    }

    /// Fetches the root index: source-first, cache fallback (SVC-2).
    ///
    /// # Errors
    ///
    /// [`MetaError::Offline`] when both the source and the cached fallback are
    /// unavailable; [`MetaError::Format`] for malformed/unsupported documents.
    pub fn root(&self) -> Result<(IndexRoot, Freshness), MetaError> {
        match self.source.fetch("index.json") {
            Ok(bytes) => {
                let root = IndexRoot::parse(&bytes)?;
                // Only a root that parsed becomes the offline fallback.
                let path = self.cached_root_path();
                std::fs::write(&path, &bytes).map_err(read_io("write cached root", &path))?;
                Ok((root, Freshness::Fresh))
            }
            Err(source_error) => {
                let path = self.cached_root_path();
                match std::fs::read(&path) {
                    Ok(bytes) => Ok((IndexRoot::parse(&bytes)?, Freshness::CachedFallback)),
                    Err(_) => Err(MetaError::Offline { source: source_error }),
                }
            }
        }
    }

    /// Returns the verified bytes for a hash-addressed tree file: cache hit
    /// (re-verified, self-healing) or source fetch (verified, then cached).
    fn object(&self, rel_path: &str, sha256: &str) -> Result<Vec<u8>, MetaError> {
        let path = self.object_path(sha256);
        if let Ok(bytes) = std::fs::read(&path) {
            if sha256_hex(&bytes) == sha256 {
                return Ok(bytes);
            }
            // Corrupted cache object: remove and refetch (self-healing; a
            // failed remove is not fatal — the rewrite below replaces it).
            let _ = std::fs::remove_file(&path);
        }

        let bytes = self.source.fetch(rel_path).map_err(MetaError::Source)?;
        let actual = sha256_hex(&bytes);
        if actual != sha256 {
            return Err(MetaError::HashMismatch {
                path: rel_path.to_owned(),
                expected: sha256.to_owned(),
                actual,
            });
        }
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).map_err(read_io("create cache directory", parent))?;
        }
        // Write-then-rename so a crash can never leave a torn object under its
        // final name. A concurrent writer racing the rename produced identical
        // (hash-verified) bytes, so losing the race is harmless.
        let staging = path.with_extension("part");
        std::fs::write(&staging, &bytes).map_err(read_io("write cache object", &staging))?;
        if std::fs::rename(&staging, &path).is_err() {
            let _ = std::fs::remove_file(&staging);
        }
        Ok(bytes)
    }

    /// Fetches and verifies a component's version index.
    ///
    /// # Errors
    ///
    /// [`MetaError::UnknownComponent`] when the root has no such uid; transport,
    /// hash, format, and identity errors as their respective variants.
    pub fn component_index(
        &self,
        root: &IndexRoot,
        uid: &str,
    ) -> Result<ComponentIndex, MetaError> {
        let reference = root
            .components
            .iter()
            .find(|component| component.uid == uid)
            .ok_or_else(|| MetaError::UnknownComponent { uid: uid.to_owned() })?;
        let bytes = self.object(&reference.versions_url, &reference.sha256)?;
        let index = ComponentIndex::parse(&bytes)?;
        if index.uid != uid {
            return Err(MetaError::Identity {
                path: reference.versions_url.clone(),
                detail: format!("declares uid {:?}, root expects {uid:?}", index.uid),
            });
        }
        Ok(index)
    }

    /// Fetches and verifies one version payload from a component index.
    ///
    /// # Errors
    ///
    /// [`MetaError::UnknownVersion`] when the index has no such version;
    /// transport, hash, format, and identity errors as their respective
    /// variants.
    pub fn payload(
        &self,
        index: &ComponentIndex,
        version: &str,
    ) -> Result<ComponentPayload, MetaError> {
        let summary = index
            .versions
            .iter()
            .find(|summary| summary.version == version)
            .ok_or_else(|| MetaError::UnknownVersion {
                uid: index.uid.clone(),
                version: version.to_owned(),
            })?;
        let bytes = self.object(&summary.url, &summary.sha256)?;
        let payload = ComponentPayload::parse(&bytes)?;
        if payload.uid != index.uid || payload.version != summary.version {
            return Err(MetaError::Identity {
                path: summary.url.clone(),
                detail: format!(
                    "declares {}/{}, index expects {}/{}",
                    payload.uid, payload.version, index.uid, summary.version
                ),
            });
        }
        Ok(payload)
    }

    /// Convenience: root → component index → payload in one call.
    ///
    /// # Errors
    ///
    /// Any error from [`Self::root`], [`Self::component_index`], or
    /// [`Self::payload`].
    pub fn resolve(
        &self,
        uid: &str,
        version: &str,
    ) -> Result<(ComponentPayload, Freshness), MetaError> {
        let (root, freshness) = self.root()?;
        let index = self.component_index(&root, uid)?;
        Ok((self.payload(&index, version)?, freshness))
    }

    /// The versions a component index marks as recommended, in index order.
    pub fn recommended<'a>(&self, index: &'a ComponentIndex) -> Vec<&'a VersionSummary> {
        index.versions.iter().filter(|summary| summary.recommended).collect()
    }
}
