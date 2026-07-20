//! # omni-meta-deploy — publishing the meta tree (SVC-1, ADR-0012/ADR-0016)
//!
//! Takes a tree produced by `omni-meta-gen generate` and publishes it to the
//! Cloudflare R2 bucket behind `omnimeta.wpaistudio.net`.
//!
//! Three decisions define this tool, and each exists to prevent a specific way
//! that publishing a metadata service goes wrong:
//!
//! 1. **Deepest-first upload order** ([`plan`]). The tree is a hash chain: the
//!    root names component indexes by sha256, which name payloads by sha256. A
//!    client that has read a root only ever requests files that root vouched
//!    for, so payloads must exist before the index that names them. Publishing
//!    the root last means the worst case during a publish is a client seeing the
//!    previous *complete* tree, never a broken one.
//! 2. **Per-object cache policy** ([`plan`]). Hash-addressed payloads are
//!    immutable and get a year; the mutable roots get five minutes and
//!    `must-revalidate`. Getting this backwards is the mistake you cannot undo —
//!    a payload cached wrong is invisible, a root cached forever pins every
//!    client to a stale tree for as long as the TTL.
//! 3. **Credentials from the environment only** ([`r2::Credentials`]). Never a
//!    file, never an argument (SOUL §36).
//!
//! The planning half is pure and fully tested; the network half is thin by
//! design, because it is the half that cannot be tested without real credentials.

pub mod error;
pub mod plan;
pub mod r2;
pub mod sigv4;

pub use error::DeployError;
pub use plan::{DeployPlan, PlannedUpload};
pub use r2::{Credentials, R2Client, Target};
