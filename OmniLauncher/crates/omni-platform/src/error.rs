//! Platform client errors.

use std::fmt;

/// Result alias for platform operations.
pub type PlatformResult<T> = Result<T, PlatformError>;

/// Failures talking to CurseForge / Modrinth.
#[derive(Debug)]
pub enum PlatformError {
    /// HTTP / transport failure.
    Transport {
        /// What was attempted.
        action: &'static str,
        /// URL involved.
        url: String,
        /// Human-readable cause.
        message: String,
    },
    /// Response body was not the expected JSON shape.
    Parse {
        /// Context label.
        context: &'static str,
        /// Underlying serde error.
        source: serde_json::Error,
    },
    /// CurseForge project opted out of third-party distribution.
    DistributionBlocked {
        /// Platform project id.
        project_id: String,
        /// Official app deep-link when known.
        open_in_app: Option<String>,
    },
    /// Caller asked for a capability that needs a key and none was configured.
    MissingApiKey {
        /// Which platform.
        platform: &'static str,
    },
    /// Rate limit exhausted after retries.
    RateLimited {
        /// Platform name.
        platform: &'static str,
    },
}

impl fmt::Display for PlatformError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Transport { action, url, message } => {
                write!(f, "{action}: {url}: {message}")
            }
            Self::Parse { context, source } => write!(f, "parse {context}: {source}"),
            Self::DistributionBlocked { project_id, open_in_app } => {
                write!(f, "project {project_id} blocks third-party download")?;
                if let Some(link) = open_in_app {
                    write!(f, " — open in official app: {link}")?;
                }
                Ok(())
            }
            Self::MissingApiKey { platform } => {
                write!(f, "{platform} API key is required (set via client constructor or env)")
            }
            Self::RateLimited { platform } => write!(f, "{platform} rate limit exceeded"),
        }
    }
}

impl std::error::Error for PlatformError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Parse { source, .. } => Some(source),
            _ => None,
        }
    }
}
