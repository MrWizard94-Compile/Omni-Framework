//! Typed generator errors (SOUL §16: every failure names its exact context).

use std::fmt;
use std::path::PathBuf;

use omni_meta_format::FormatError;

/// Everything that can fail across the snapshot/generate/verify pipeline.
#[derive(Debug)]
pub enum GenError {
    /// Filesystem operation failed.
    Io {
        /// What the operation was doing (e.g. `"write payload"`).
        action: &'static str,
        /// The path involved.
        path: PathBuf,
        /// OS-level cause.
        source: std::io::Error,
    },
    /// HTTP fetch failed (transport error or non-success status).
    Http {
        /// The URL that failed.
        url: String,
        /// Human-readable cause.
        message: String,
    },
    /// A JSON document did not match its expected shape.
    Json {
        /// Which document (path or URL).
        origin: String,
        /// Underlying serde error (includes line/column).
        source: serde_json::Error,
    },
    /// Format-level violation (invalid uid/version, unsupported formatVersion, …).
    Format(FormatError),
    /// Content integrity violation: a fetched or emitted artifact does not
    /// match its declared hash, or two inputs conflict irreconcilably.
    Integrity {
        /// What failed, with expected/actual detail.
        detail: String,
    },
    /// Invalid CLI usage or configuration input.
    Usage(String),
}

impl GenError {
    /// Shorthand for I/O errors.
    pub fn io(action: &'static str, path: impl Into<PathBuf>, source: std::io::Error) -> Self {
        Self::Io { action, path: path.into(), source }
    }
}

impl fmt::Display for GenError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Io { action, path, source } => {
                write!(f, "{action}: {}: {source}", path.display())
            }
            Self::Http { url, message } => write!(f, "fetch {url}: {message}"),
            Self::Json { origin, source } => write!(f, "parse {origin}: {source}"),
            Self::Format(err) => write!(f, "{err}"),
            Self::Integrity { detail } => write!(f, "integrity: {detail}"),
            Self::Usage(message) => write!(f, "usage: {message}"),
        }
    }
}

impl std::error::Error for GenError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Io { source, .. } => Some(source),
            Self::Json { source, .. } => Some(source),
            Self::Format(err) => Some(err),
            _ => None,
        }
    }
}

impl From<FormatError> for GenError {
    fn from(err: FormatError) -> Self {
        Self::Format(err)
    }
}
