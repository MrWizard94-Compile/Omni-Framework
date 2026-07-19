//! # omni-core — `OmniLauncher`'s headless core (foundations)
//!
//! Implements the foundation layer of `OmniLauncher/ARCHITECTURE.md`: **all**
//! launcher truth lives here, UI-free and headless-testable (LNC-1); the Tauri
//! shell is a thin command/event adapter on top.
//!
//! This brick (Phase 4 brick 3) ships:
//!
//! - [`db`] — the single SQLite database (LNC-2, D5): bundled SQLite, WAL mode,
//!   foreign keys enforced, versioned embedded migrations (sqlx-style pattern
//!   per ADR-0015: numbered SQL files + a tracking table), online backup.
//! - [`store`] — typed repositories over schema v1: instances as component
//!   stacks, mods as entities, accounts (vault refs only — never tokens,
//!   SOUL §36), java runtimes, process sessions, settings, jobs.
//! - [`jobs`] — the store-backed resumable job runner (LNC-5): every
//!   install/repair/update is a job whose progress and resume point are
//!   persisted transactionally after every step, so a crash at any point
//!   resumes to completion (the modrinth-app install-recovery lesson, made a
//!   tested property).
//! - [`events`] — the typed event stream foundation (LNC-1): state changes and
//!   progress emit [`events::CoreEvent`]s through an [`events::EventSink`];
//!   the UI holds no truth.
//! - [`auth`] (Phase 4 brick 4) — the COMPLIANCE §2 Microsoft chain with the
//!   XSTS error taxonomy, OS-vault token storage, the ADR-0011 entitlement
//!   window, and the CPL-2 launch gate.
//! - [`install`] / [`launch`] / [`platform`] (brick 5) — meta-resolved,
//!   hash-verified, resumable installs and the gated launch chain.
//! - [`import`] (brick 6) — the four migration surfaces (LNC-7, D7), read-only
//!   parsers that map foreign instances into the canonical store.
//!
//! Threading model: synchronous API over one mutex-guarded connection; callers
//! (the Tauri shell, job worker threads) invoke from whatever thread suits
//! them. WAL keeps readers cheap; the coarse lock is deliberate simplicity for
//! the MVP and is documented in ADR-0015.

pub mod app;
pub mod auth;
pub mod clock;
pub mod db;
pub mod error;
pub mod events;
pub mod import;
pub mod install;
pub mod jobs;
pub mod launch;
pub mod platform;
pub mod store;

pub use clock::{Clock, SystemClock};
pub use db::Db;
pub use error::CoreError;
pub use events::{CoreEvent, EventSink, NullSink};
