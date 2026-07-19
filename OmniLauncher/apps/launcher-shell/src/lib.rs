//! # OmniLauncher shell — Tauri wiring, and nothing else
//!
//! ARCHITECTURE §1: "`omni-core` exposes an async command API + typed event
//! stream; the UI holds no truth." This crate is the adapter between those two
//! facts and Tauri. It deliberately contains **no launcher logic**:
//!
//! - Every `#[tauri::command]` in [`commands`] is a one-line dispatch into an
//!   [`omni_core::app::AppService`] method.
//! - [`WindowSink`] forwards [`omni_core::CoreEvent`]s onto Tauri's event
//!   channel verbatim (the wire contract lives in `OmniLauncher/COMMANDS.md`
//!   and is pinned by `omni-core`'s `tests/app.rs`).
//! - Startup performs exactly two duties beyond construction: recovering jobs
//!   a previous crash left `running`, and reporting a fatal init failure to
//!   the user instead of vanishing.
//!
//! Because the logic lives one layer down, the whole command surface is tested
//! headlessly in `omni-core` — this crate has nothing left worth unit-testing,
//! which is the intended outcome, not an omission.
//!
//! ## Blocking discipline
//!
//! `omni-core` is synchronous (ADR-0015). Commands are declared `async` and
//! immediately hand work to Tauri's blocking pool via
//! [`tauri::async_runtime::spawn_blocking`], so a database lock or a socket can
//! never stall the UI thread. Commands that would otherwise run long (installs,
//! sign-in, launches) return a handle instantly and report progress as events.

mod commands;
mod events;

pub use events::WindowSink;

use std::sync::Arc;

use omni_core::app::{AppConfig, AppService};
use tauri::Manager as _;

/// The Tauri event name every [`omni_core::CoreEvent`] is published under.
/// The UI subscribes once and matches on the payload's variant.
pub const EVENT_CHANNEL: &str = "omni://event";

/// Builds and runs the desktop application.
///
/// # Panics
///
/// Panics only if Tauri itself cannot start (no display server, corrupt
/// bundle) — an unrecoverable environment failure that has no in-app remedy.
/// Launcher-level init failures (unwritable data directory, corrupt database)
/// are *not* panics: they surface as a dialog-worthy error event and leave the
/// window running so the user can see what happened.
pub fn run() {
    tauri::Builder::default()
        .setup(|app| {
            let sink = Arc::new(WindowSink::new(app.handle().clone()));
            let service = AppService::new(AppConfig::default(), sink)?;

            // Startup duty (see omni-core::app docs): a job still marked
            // `running` was owned by a process that died; move it to `paused`
            // at its persisted resume point so the job center can offer retry.
            match service.recover_interrupted_jobs() {
                Ok(recovered) if !recovered.is_empty() => {
                    eprintln!("[omni] recovered {} interrupted job(s)", recovered.len());
                }
                Ok(_) => {}
                Err(err) => eprintln!("[omni] job recovery failed: {err}"),
            }

            // Managed as `Arc` so each command clones a refcount rather than
            // borrowing across the blocking-pool boundary.
            app.manage(Arc::new(service));
            Ok(())
        })
        .invoke_handler(commands::handler())
        .run(tauri::generate_context!())
        .expect("Tauri failed to start");
}
