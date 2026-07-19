//! The typed event stream foundation (LNC-1): core state changes are pushed
//! through an [`EventSink`] so the Tauri shell (brick 7) can forward them to
//! the UI without the UI ever polling or owning truth.

use crate::store::JobState;

/// Events the core emits — **also the launcher's UI wire contract**: the
/// Tauri shell serializes these verbatim onto its event channel, so the
/// serde representation here is what the web UI receives (documented in
/// `OmniLauncher/COMMANDS.md`). Additive growth only; consumers must ignore
/// variants they do not know.
///
/// Serialization is externally tagged (`{"JobState": {...}}`), matching the
/// contract document and the TypeScript union in the UI.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[non_exhaustive]
pub enum CoreEvent {
    /// A job changed state (created, started, paused, failed, done).
    JobState {
        /// Job row id.
        job_id: i64,
        /// Job kind (e.g. `"install"`).
        kind: String,
        /// The new state.
        state: JobState,
    },
    /// A running job reported progress.
    JobProgress {
        /// Job row id.
        job_id: i64,
        /// Job-kind-specific progress document (persisted verbatim in
        /// `jobs.progress_json`).
        progress: serde_json::Value,
    },
    /// A game/server process was launched and recorded.
    ProcessStarted {
        /// The instance that was launched.
        instance_id: i64,
        /// `processes` row id (the console/stop handle).
        session_id: i64,
        /// OS process id, when known.
        pid: Option<i64>,
    },
    /// A recorded process ended.
    ProcessExited {
        /// The instance that was running.
        instance_id: i64,
        /// `processes` row id.
        session_id: i64,
        /// Exit code (`-1` when unavailable, e.g. killed by signal).
        exit_code: i64,
    },
    /// An instance's persisted state changed (created, renamed, settings,
    /// mods, stack, deleted) — the UI refetches rather than mirroring truth.
    InstanceChanged {
        /// The affected instance; `None` means "the list changed".
        instance_id: Option<i64>,
    },
    /// The account set changed (login completed, sign-out, window re-stamped).
    AccountsChanged,
    /// A background sign-in finished (success or typed failure), so the UI can
    /// close its device-code panel without polling.
    LoginFinished {
        /// Profile UUID on success.
        uuid: Option<String>,
        /// Human-actionable error text on failure.
        error: Option<String>,
    },
}

/// Receives core events. Implementations must be cheap and non-blocking —
/// emission happens on whatever thread produced the change; heavy consumers
/// queue internally.
pub trait EventSink: Send + Sync {
    /// Delivers one event. Must not panic; must not block.
    fn emit(&self, event: CoreEvent);
}

/// Discards every event (headless tools, tests that don't assert on events).
#[derive(Debug, Clone, Copy, Default)]
pub struct NullSink;

impl EventSink for NullSink {
    fn emit(&self, _event: CoreEvent) {}
}

/// Collects events into a vector — test helper.
#[derive(Debug, Default)]
pub struct CollectingSink(std::sync::Mutex<Vec<CoreEvent>>);

impl CollectingSink {
    /// Creates an empty collector.
    pub fn new() -> Self {
        Self::default()
    }

    /// Drains everything collected so far.
    pub fn drain(&self) -> Vec<CoreEvent> {
        std::mem::take(&mut self.0.lock().unwrap_or_else(std::sync::PoisonError::into_inner))
    }
}

impl EventSink for CollectingSink {
    fn emit(&self, event: CoreEvent) {
        self.0.lock().unwrap_or_else(std::sync::PoisonError::into_inner).push(event);
    }
}
