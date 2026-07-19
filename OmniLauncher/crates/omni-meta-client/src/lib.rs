//! # omni-meta-client — the launcher-side consumer of the Omni meta service
//!
//! Implements LNC-3 (`OmniLauncher` consumes **only** the Omni meta dialect) and
//! SVC-2 (degraded networks are an operating condition, not an error case) over
//! the format-v1 tree emitted by `omni-meta-gen`:
//!
//! - **Hash-chain verification end to end**: the root index names the sha256 of
//!   every component index, which names the sha256 of every payload. Every byte
//!   this crate returns has been verified against that chain; tampered or
//!   truncated files are typed errors, never silent acceptance (SOUL §9 —
//!   remote metadata is untrusted input).
//! - **Content-addressed cache**: verified component indexes and payloads are
//!   stored under `<cache>/objects/<sha256[0..2]>/<sha256>` — immutable, shared
//!   across instances, self-healing (a corrupted cache object is deleted and
//!   refetched). Only the mutable root index ever needs the network twice.
//! - **Offline fallback**: when the source is unreachable, the last good root
//!   index is served from the cache and the result is marked
//!   [`Freshness::CachedFallback`] so callers can surface degraded mode in UI.
//!
//! Transport is abstracted behind [`MetaSource`]: [`HttpSource`] for the CDN
//! (ADR-0012), [`DirSource`] for local trees (tests, tools, air-gapped use).
//! The API is synchronous by design — `omni-core` wraps calls in its async
//! runtime's blocking facility, keeping this crate runtime-agnostic.

mod client;
mod error;
mod source;

pub use client::{Freshness, MetaClient};
pub use error::MetaError;
pub use source::{DirSource, HttpSource, MetaSource, SourceError, safe_rel_path};
