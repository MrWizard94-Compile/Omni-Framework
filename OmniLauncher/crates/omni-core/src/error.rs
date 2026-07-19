//! Typed core errors (SOUL §16).

use std::fmt;
use std::path::PathBuf;

use crate::store::JobState;

/// Everything the core can fail with.
#[derive(Debug)]
pub enum CoreError {
    /// Database operation failed.
    Db {
        /// What was being done (e.g. `"insert instance"`).
        context: &'static str,
        /// Underlying driver error.
        source: rusqlite::Error,
    },
    /// A JSON column contained (or was asked to store) invalid JSON.
    Json {
        /// Which column/context.
        context: &'static str,
        /// Underlying serde error.
        source: serde_json::Error,
    },
    /// Filesystem failure (database path, backup target).
    Io {
        /// What was being done.
        action: &'static str,
        /// The path involved.
        path: PathBuf,
        /// OS-level cause.
        source: std::io::Error,
    },
    /// A row that must exist does not.
    NotFound {
        /// Entity kind (e.g. `"job"`).
        what: &'static str,
        /// Row id.
        id: i64,
    },
    /// A job state transition the state machine forbids.
    IllegalTransition {
        /// The job id.
        job_id: i64,
        /// Current state.
        from: JobState,
        /// Requested state.
        to: JobState,
    },
    /// A CHECK-constrained text column held a value this build does not know.
    /// (Forward-compat guard: a newer schema written by a newer build.)
    UnknownEnum {
        /// Column/context.
        context: &'static str,
        /// The unrecognized value.
        value: String,
    },
}

impl CoreError {
    /// Shorthand for database errors.
    pub fn db(context: &'static str) -> impl FnOnce(rusqlite::Error) -> Self {
        move |source| Self::Db { context, source }
    }

    /// Shorthand for JSON errors.
    pub fn json(context: &'static str) -> impl FnOnce(serde_json::Error) -> Self {
        move |source| Self::Json { context, source }
    }
}

impl fmt::Display for CoreError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Db { context, source } => write!(f, "database: {context}: {source}"),
            Self::Json { context, source } => write!(f, "json: {context}: {source}"),
            Self::Io { action, path, source } => {
                write!(f, "{action}: {}: {source}", path.display())
            }
            Self::NotFound { what, id } => write!(f, "{what} {id} not found"),
            Self::IllegalTransition { job_id, from, to } => {
                write!(f, "job {job_id}: illegal state transition {from} -> {to}")
            }
            Self::UnknownEnum { context, value } => {
                write!(f, "{context}: unrecognized value {value:?} (database newer than this build?)")
            }
        }
    }
}

impl std::error::Error for CoreError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Db { source, .. } => Some(source),
            Self::Json { source, .. } => Some(source),
            Self::Io { source, .. } => Some(source),
            _ => None,
        }
    }
}
