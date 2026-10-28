//! # omni-platform — content platform clients (LNC-9, ARCHITECTURE §6)
//!
//! Typed CurseForge Core API + Modrinth Labrinth API clients with a shared
//! [`ResourceQuery`] surface for search / version lookup / official download
//! routing. Honours:
//!
//! - official endpoints only (author monetization; COMPLIANCE §5)
//! - API keys via environment / constructor (never hard-coded)
//! - basic rate limiting between requests
//! - CurseForge distribution opt-out: opted-out projects return
//!   [`PlatformError::DistributionBlocked`] rather than a circumvention URL
//!
//! Transport is synchronous (`ureq`) to match `omni-core` / `omni-meta-client`.

#![warn(missing_docs)]

mod curseforge;
mod error;
mod modrinth;
mod query;
mod rate;
mod transport;

pub use curseforge::{blocked_distribution, official_app_link, CurseForgeClient};
pub use error::{PlatformError, PlatformResult};
pub use modrinth::ModrinthClient;
pub use query::{HashAlgorithm, Platform, ResourceQuery, SearchHit, VersionFile};
pub use rate::RateLimiter;
pub use transport::{HttpTransport, UreqTransport};

// Re-export for callers that type-erase transports.

/// User-Agent identifying OmniLauncher to platform APIs.
pub const USER_AGENT: &str = concat!("OmniLauncher/", env!("CARGO_PKG_VERSION"), " (wpaistudio.net)");
