//! Typed errors for format parsing and validation.

use std::fmt;

/// Everything that can go wrong while reading or validating format-v1 data.
///
/// Every variant carries enough context to name the exact offending input
/// (SOUL §16: failures must be diagnosable without a debugger).
#[derive(Debug)]
pub enum FormatError {
    /// The JSON was syntactically or structurally invalid for the expected type.
    Json {
        /// What was being parsed (e.g. `"root index"`, `"component payload"`).
        what: &'static str,
        /// The underlying serde error (includes line/column).
        source: serde_json::Error,
    },
    /// A root index declared a `formatVersion` this crate does not implement.
    UnsupportedFormatVersion {
        /// The version found in the document.
        found: u32,
    },
    /// A component uid failed [`crate::validate_uid`].
    InvalidUid {
        /// The rejected uid.
        uid: String,
        /// Why it was rejected.
        reason: &'static str,
    },
    /// A version string produced an unusable file stem (empty after sanitization
    /// or the reserved name `index`).
    InvalidVersion {
        /// The rejected version string.
        version: String,
        /// Why it was rejected.
        reason: &'static str,
    },
}

impl fmt::Display for FormatError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Json { what, source } => {
                write!(f, "invalid JSON for {what}: {source}")
            }
            Self::UnsupportedFormatVersion { found } => write!(
                f,
                "unsupported formatVersion {found} (this build implements v{})",
                crate::FORMAT_VERSION
            ),
            Self::InvalidUid { uid, reason } => {
                write!(f, "invalid component uid {uid:?}: {reason}")
            }
            Self::InvalidVersion { version, reason } => {
                write!(f, "invalid version string {version:?}: {reason}")
            }
        }
    }
}

impl std::error::Error for FormatError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Json { source, .. } => Some(source),
            _ => None,
        }
    }
}
