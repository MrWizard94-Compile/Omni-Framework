//! Typed client errors (SOUL §16).

use std::fmt;
use std::path::PathBuf;

use omni_meta_format::FormatError;

use crate::source::SourceError;

/// Everything that can fail while resolving meta data.
#[derive(Debug)]
pub enum MetaError {
    /// The source failed and no cached fallback was available.
    Offline {
        /// The underlying source failure.
        source: SourceError,
    },
    /// The source failed for a non-root file (payloads have no fallback other
    /// than the object cache, which also missed).
    Source(SourceError),
    /// Fetched bytes do not match the sha256 the tree declares for them.
    HashMismatch {
        /// Tree-relative path of the offending file.
        path: String,
        /// Hash declared by the referencing index.
        expected: String,
        /// Hash of the actual bytes.
        actual: String,
    },
    /// The tree content is malformed (bad JSON, wrong `formatVersion`, …).
    Format(FormatError),
    /// A parsed document's identity disagrees with the index that referenced it.
    Identity {
        /// Tree-relative path of the document.
        path: String,
        /// What disagreed.
        detail: String,
    },
    /// The root index has no such component.
    UnknownComponent {
        /// The requested uid.
        uid: String,
    },
    /// The component index has no such version.
    UnknownVersion {
        /// The component uid.
        uid: String,
        /// The requested version.
        version: String,
    },
    /// Local cache I/O failed.
    Io {
        /// What the operation was doing.
        action: &'static str,
        /// The path involved.
        path: PathBuf,
        /// OS-level cause.
        source: std::io::Error,
    },
}

impl fmt::Display for MetaError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Offline { source } => {
                write!(f, "meta service unreachable and no cached root available: {source}")
            }
            Self::Source(source) => write!(f, "meta source failure: {source}"),
            Self::HashMismatch { path, expected, actual } => write!(
                f,
                "{path}: sha256 mismatch (tree declares {expected}, bytes are {actual})"
            ),
            Self::Format(err) => write!(f, "{err}"),
            Self::Identity { path, detail } => write!(f, "{path}: {detail}"),
            Self::UnknownComponent { uid } => write!(f, "unknown component {uid:?}"),
            Self::UnknownVersion { uid, version } => {
                write!(f, "component {uid} has no version {version:?}")
            }
            Self::Io { action, path, source } => {
                write!(f, "{action}: {}: {source}", path.display())
            }
        }
    }
}

impl std::error::Error for MetaError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Format(err) => Some(err),
            Self::Io { source, .. } => Some(source),
            _ => None,
        }
    }
}

impl From<FormatError> for MetaError {
    fn from(err: FormatError) -> Self {
        Self::Format(err)
    }
}
