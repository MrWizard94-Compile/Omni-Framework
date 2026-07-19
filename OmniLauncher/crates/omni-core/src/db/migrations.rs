//! Versioned, embedded, forward-only migrations (ADR-0015).
//!
//! The sqlx-style *pattern* without the sqlx machinery: numbered SQL files
//! compiled into the binary, applied in order inside transactions, tracked in
//! `schema_migrations`. Migrations are append-only history — published files
//! are never edited (same discipline as `docs/DECISIONS.md`).

use rusqlite::Connection;

use crate::clock::Clock;
use crate::error::CoreError;

/// One embedded migration.
#[derive(Debug, Clone, Copy)]
pub struct Migration {
    /// Monotonic version number (the file's numeric prefix).
    pub version: i64,
    /// Human-readable name (the file stem).
    pub name: &'static str,
    /// The SQL to execute.
    pub sql: &'static str,
}

/// Every migration, in apply order. Append only.
pub const MIGRATIONS: &[Migration] = &[Migration {
    version: 1,
    name: "schema_v1",
    sql: include_str!("migrations/0001_schema_v1.sql"),
}];

fn ensure_tracking_table(conn: &Connection) -> Result<(), CoreError> {
    conn.execute_batch(
        "CREATE TABLE IF NOT EXISTS schema_migrations (
             version    INTEGER PRIMARY KEY,
             name       TEXT    NOT NULL,
             applied_at INTEGER NOT NULL
         );",
    )
    .map_err(CoreError::db("create schema_migrations"))
}

/// Highest applied migration version (`0` when none).
///
/// # Errors
///
/// [`CoreError::Db`] on query failure.
pub fn current_version(conn: &Connection) -> Result<i64, CoreError> {
    ensure_tracking_table(conn)?;
    conn.query_row("SELECT COALESCE(MAX(version), 0) FROM schema_migrations", [], |row| {
        row.get(0)
    })
    .map_err(CoreError::db("read schema version"))
}

/// Applies every pending migration, each inside its own transaction with its
/// tracking row — a failed migration rolls back completely and leaves the
/// database at the previous version.
///
/// # Errors
///
/// [`CoreError::Db`] on any SQL failure (the failing migration is named in
/// the underlying error's context).
pub fn apply_all(conn: &Connection, clock: &dyn Clock) -> Result<(), CoreError> {
    let applied = current_version(conn)?;
    for migration in MIGRATIONS.iter().filter(|m| m.version > applied) {
        let tx = conn
            .unchecked_transaction()
            .map_err(CoreError::db("begin migration transaction"))?;
        tx.execute_batch(migration.sql).map_err(CoreError::db("apply migration"))?;
        tx.execute(
            "INSERT INTO schema_migrations (version, name, applied_at) VALUES (?1, ?2, ?3)",
            rusqlite::params![migration.version, migration.name, clock.now_unix()],
        )
        .map_err(CoreError::db("record migration"))?;
        tx.commit().map_err(CoreError::db("commit migration"))?;
    }
    Ok(())
}
