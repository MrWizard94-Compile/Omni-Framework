//! # omni-meta-gen — the `OmniMeta` static-tree generator
//!
//! Implements SVC-1 (see `OmniMeta/README.md`, ADR-0010, ADR-0012): normalizes
//! every upstream the launcher/loader needs into the format-v1 tree served from
//! the CDN. Three stages, exposed as library functions (the binary in `main.rs`
//! is a thin CLI over them, which is also how the integration tests drive the
//! pipeline):
//!
//! 1. **snapshot** ([`snapshot::fetch`]) — pulls upstreams (Mojang version
//!    manifest v2, per-version client.json, java-runtime `all.json`) into a
//!    pinned local snapshot directory with sha256 provenance. The only stage
//!    that touches the network.
//! 2. **generate** ([`normalize::normalize`] + [`emit::emit`]) — pure function
//!    from a snapshot (plus optional Omni component definitions and a
//!    recommended-version policy) to the `/v1` tree. Deterministic: identical
//!    inputs produce byte-identical output (SOUL §21) — enforced by the golden
//!    and double-run tests.
//! 3. **verify** ([`verify::verify`]) — structural + hash-chain audit of an
//!    emitted tree; the publish gate before any CDN upload.
//!
//! Compliance envelope (COMPLIANCE.md §1): the tree carries **metadata only** —
//! URLs into Mojang's official CDNs and Omni's own artifact hosting. No game
//! files are copied, cached, or re-hosted by this tool.

pub mod emit;
pub mod error;
pub mod mojang;
pub mod normalize;
pub mod snapshot;
pub mod verify;

pub use error::GenError;
