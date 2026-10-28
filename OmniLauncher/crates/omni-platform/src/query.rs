//! Shared [`ResourceQuery`] abstraction over both platforms (prism `ResourceAPI` lesson).

use crate::error::PlatformResult;

/// Which content platform a hit came from.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Platform {
    /// labrinth / modrinth.com
    Modrinth,
    /// CurseForge Core API
    CurseForge,
}

impl Platform {
    /// Stable slug for settings / DB.
    #[must_use]
    pub const fn slug(self) -> &'static str {
        match self {
            Self::Modrinth => "modrinth",
            Self::CurseForge => "curseforge",
        }
    }
}

/// One project hit from search.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchHit {
    /// Platform.
    pub platform: Platform,
    /// Platform-native project id.
    pub project_id: String,
    /// URL slug when available.
    pub slug: String,
    /// Display title.
    pub title: String,
    /// Short description.
    pub description: String,
    /// Author / team name when known.
    pub author: String,
    /// Download count when known.
    pub downloads: u64,
}

/// One downloadable file/version.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VersionFile {
    /// Platform.
    pub platform: Platform,
    /// Platform project id.
    pub project_id: String,
    /// Platform file / version id.
    pub file_id: String,
    /// File name on disk.
    pub file_name: String,
    /// Official download URL (empty when distribution is blocked).
    pub download_url: String,
    /// Lowercase hex sha1 when known.
    pub sha1: Option<String>,
    /// Lowercase hex sha512 when known.
    pub sha512: Option<String>,
    /// File size in bytes when known.
    pub size: Option<u64>,
    /// Game versions this file targets.
    pub game_versions: Vec<String>,
    /// Loaders (fabric, neoforge, …).
    pub loaders: Vec<String>,
}

/// One ResourceAPI-style query surface over a platform.
pub trait ResourceQuery: Send + Sync {
    /// Which platform this client talks to.
    fn platform(&self) -> Platform;

    /// Search projects for `query`, optionally filtered to `game_version`.
    ///
    /// # Errors
    ///
    /// Transport / parse failures.
    fn search(&self, query: &str, game_version: Option<&str>, limit: u32) -> PlatformResult<Vec<SearchHit>>;

    /// Resolve a file by content hash (sha1 for Modrinth; murmur2 for CurseForge).
    ///
    /// # Errors
    ///
    /// Transport / parse / not-found as transport message.
    fn version_by_hash(&self, hash: &str, algorithm: HashAlgorithm) -> PlatformResult<Option<VersionFile>>;
}

/// Hash algorithms used for fingerprint lookup.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HashAlgorithm {
    /// SHA-1 (Modrinth primary).
    Sha1,
    /// SHA-512 (Modrinth).
    Sha512,
    /// CurseForge murmur2 fingerprint (decimal string).
    Murmur2,
}
