//! The typed event stream foundation (LNC-1): core state changes are pushed
//! through an [`EventSink`] so the Tauri shell (brick 7) can forward them to
//! the UI without the UI ever polling or owning truth.

use crate::store::JobState;

/// Events the core emits. Grows with later bricks (auth, installs, processes);
/// additive growth only — consumers must ignore variants they do not know.
#[derive(Debug, Clone, PartialEq, Eq)]
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
