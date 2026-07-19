//! Typed repositories over schema v1 (LNC-2, D5).
//!
//! One [`Store`] value = one locked connection; obtain it through
//! [`crate::Db::with_store`] and keep each closure to a single logical
//! operation. Multi-row invariants (component stacks, job steps) are enforced
//! with transactions inside the respective methods.

mod accounts;
mod content;
mod instances;
mod jobs;
mod types;

pub use types::{
    Account, AccountKind, ComponentSource, Ecosystem, Instance, InstanceKind, JavaRuntime,
    JavaSource, Job, JobState, Mod, NewInstance, NewMod, PortStatus, ProcessSession,
    StackEntry, StackEntryInput,
};

use rusqlite::Connection;

use crate::clock::Clock;

/// The typed store facade. Repository methods live in `impl` blocks across
/// this module's files, grouped by entity family.
pub struct Store<'a> {
    pub(crate) conn: &'a mut Connection,
    pub(crate) clock: &'a dyn Clock,
}

impl<'a> Store<'a> {
    pub(crate) fn new(conn: &'a mut Connection, clock: &'a dyn Clock) -> Self {
        Self { conn, clock }
    }

    /// The store's current time (the injected clock — SOUL §21).
    pub fn clock_now(&self) -> i64 {
        self.clock.now_unix()
    }
}
