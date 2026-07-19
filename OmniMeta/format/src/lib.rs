//! # omni-meta-format — Omni meta index format **v1**
//!
//! The typed model of the statically generated, CDN-served metadata tree defined in
//! `OmniMeta/README.md` (SVC-1, D6). This crate is shared by the generator
//! (`omni-meta-gen`) and `OmniLauncher`'s `omni-meta-client` so both sides of the wire
//! agree on one dialect by construction (LNC-3).
//!
//! ## Tree layout (all paths relative to the `/v1/` root)
//!
//! ```text
//! index.json                  IndexRoot      — components: [{uid, versionsUrl, sha256}]
//! <uid>/index.json            ComponentIndex — versions: [{version, releaseTime, type,
//!                                              recommended, url, sha256}]
//! <uid>/<version>.json        ComponentPayload
//! ```
//!
//! ## Format guarantees (v1)
//!
//! - **Canonical serialization**: [`to_canonical_json`] emits pretty-printed JSON with
//!   2-space indent, struct fields in declaration order, all maps sorted
//!   (`serde_json`'s default `BTreeMap` backing), and a trailing newline. Identical
//!   values always produce identical bytes — the foundation of the deterministic
//!   generator (SOUL §21) and of the `sha256` links between tree levels.
//! - **Hash chaining**: the root index records the sha256 of every component index;
//!   every component index records the sha256 of every payload. Payloads are therefore
//!   immutable-cacheable; only the roots need a TTL (ADR-0012).
//! - **Lenient read, strict write**: readers ignore unknown JSON keys (additive
//!   evolution inside a `formatVersion`), writers only emit the fields defined here.
//!   Incompatible changes bump [`FORMAT_VERSION`].
//! - **`requires.range` dialect**: in v1 a requirement range is either **absent**
//!   (any version satisfies) or an **exact version string**. Richer range syntax is an
//!   incompatible extension and requires a `formatVersion` bump plus ADR.
//! - **File naming**: component uids must pass [`validate_uid`]. Version strings may
//!   contain characters that are hostile to URLs/filesystems (Mojang has shipped ids
//!   with spaces), so payload file names use [`sanitize_file_stem`] and the *explicit*
//!   `url` fields in the indexes are the only sanctioned way to locate a payload.

mod canonical;
mod error;
mod model;
mod names;

pub use canonical::{sha256_hex, to_canonical_json};
pub use error::FormatError;
pub use model::{
    Argument, ArgumentValue, Arguments, AssetIndex, ComponentIndex, ComponentPayload,
    ComponentRef, IndexRoot, JavaVersion, Library, OsSpec, Requirement, Rule, RuleAction,
    VersionSummary,
};
pub use names::{component_index_path, payload_path, root_index_path, sanitize_file_stem,
    validate_uid};

/// The index format generation this crate implements. Serialized as `formatVersion`
/// in the root index; readers must reject other values (see [`IndexRoot::parse`]).
pub const FORMAT_VERSION: u32 = 1;
