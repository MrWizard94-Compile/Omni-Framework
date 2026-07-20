//! Typed deploy errors (SOUL §16).

use std::fmt;
use std::path::PathBuf;

/// Everything publishing can fail with.
#[derive(Debug)]
pub enum DeployError {
    /// Filesystem failure reading the tree.
    Io {
        /// What was being done.
        action: &'static str,
        /// The path involved.
        path: PathBuf,
        /// OS-level cause.
        source: std::io::Error,
    },
    /// Invalid invocation or configuration.
    Usage(String),
    /// Credentials are absent from the environment.
    MissingCredentials {
        /// The variable that was not set.
        variable: &'static str,
    },
    /// The endpoint rejected a request.
    ///
    /// The body is included because R2's S3 errors are XML with a useful
    /// `<Code>`; the request that failed is named, but never its credentials.
    Endpoint {
        /// Object key involved.
        key: String,
        /// HTTP status.
        status: u16,
        /// Response body, truncated.
        body: String,
    },
    /// Transport failure.
    Transport {
        /// Object key involved.
        key: String,
        /// Human-readable cause.
        message: String,
    },
    /// A published object did not read back as the bytes that were sent.
    Verification {
        /// Object key involved.
        key: String,
        /// What was expected.
        expected: String,
        /// What was found.
        actual: String,
    },
}

impl fmt::Display for DeployError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Io { action, path, source } => {
                write!(f, "{action}: {}: {source}", path.display())
            }
            Self::Usage(message) => write!(f, "{message}"),
            Self::MissingCredentials { variable } => write!(
                f,
                "{variable} is not set. Export the R2 credentials in this shell only — \
                 the tool never reads them from a file or an argument, so they cannot \
                 end up in the repository or your shell history."
            ),
            Self::Endpoint { key, status, body } => {
                write!(f, "{key}: endpoint returned {status}: {body}")
            }
            Self::Transport { key, message } => write!(f, "{key}: {message}"),
            Self::Verification { key, expected, actual } => write!(
                f,
                "{key}: published object does not match what was sent \
                 (expected sha256 {expected}, read back {actual})"
            ),
        }
    }
}

impl std::error::Error for DeployError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Io { source, .. } => Some(source),
            _ => None,
        }
    }
}
