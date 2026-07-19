//! The event bridge: `omni-core` → Tauri → the web UI.

use omni_core::events::{CoreEvent, EventSink};
use tauri::{AppHandle, Emitter as _};

/// Forwards core events onto Tauri's event channel verbatim.
///
/// The payload is the serialized [`CoreEvent`] — the shell adds no envelope,
/// renames nothing, and drops nothing, so the wire contract pinned by
/// `omni-core`'s `event_wire_shapes_are_stable` test *is* what the UI sees.
///
/// Emission never fails loudly: an event that cannot reach a closing window is
/// not a launcher error, and [`EventSink::emit`] must not panic (it runs on
/// job and process threads). Failures are logged and dropped.
pub struct WindowSink {
    app: AppHandle,
}

impl WindowSink {
    /// Wraps an app handle as a sink.
    pub fn new(app: AppHandle) -> Self {
        Self { app }
    }
}

impl EventSink for WindowSink {
    fn emit(&self, event: CoreEvent) {
        if let Err(err) = self.app.emit(crate::EVENT_CHANNEL, &event) {
            eprintln!("[omni] dropping event (window gone?): {err}");
        }
    }
}
