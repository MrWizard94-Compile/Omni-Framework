//! The one launcher database (LNC-2): open, migrate, backup, access.

mod migrations;

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard};

use rusqlite::Connection;

use crate::clock::Clock;
use crate::error::CoreError;
use crate::store::Store;

pub use migrations::MIGRATIONS;

/// Handle to the launcher database: one mutex-guarded connection in WAL mode.
///
/// Coarse locking is a deliberate MVP simplicity (ADR-0015): every store
/// operation is short, WAL keeps the file readable by external tools, and the
/// job runner persists in small transactions. Revisit only with profiling
/// evidence (SOUL §8).
pub struct Db {
    conn: Mutex<Connection>,
    clock: Arc<dyn Clock>,
    path: PathBuf,
}

impl std::fmt::Debug for Db {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Db").field("path", &self.path).finish_non_exhaustive()
    }
}

fn configure(conn: &Connection) -> Result<(), CoreError> {
    // WAL for concurrent readers + crash durability; NORMAL sync is the
    // documented WAL pairing (safe against app crash; power-loss rollback is
    // bounded by the WAL). Foreign keys are off by default in SQLite — on, always.
    conn.pragma_update(None, "journal_mode", "wal").map_err(CoreError::db("set WAL mode"))?;
    conn.pragma_update(None, "synchronous", "normal")
        .map_err(CoreError::db("set synchronous"))?;
    conn.pragma_update(None, "foreign_keys", "on")
        .map_err(CoreError::db("enable foreign keys"))?;
    Ok(())
}

impl Db {
    /// Opens (creating if absent) the database at `path`, applies pragmas, and
    /// runs every pending migration.
    ///
    /// # Errors
    ///
    /// [`CoreError::Io`] when the parent directory cannot be created;
    /// [`CoreError::Db`] on open/pragma/migration failures.
    pub fn open(path: impl Into<PathBuf>, clock: Arc<dyn Clock>) -> Result<Self, CoreError> {
        let path = path.into();
        if let Some(parent) = path.parent()
            && !parent.as_os_str().is_empty()
        {
            std::fs::create_dir_all(parent).map_err(|source| CoreError::Io {
                action: "create database directory",
                path: parent.to_path_buf(),
                source,
            })?;
        }
        let conn = Connection::open(&path).map_err(CoreError::db("open database"))?;
        configure(&conn)?;
        migrations::apply_all(&conn, clock.as_ref())?;
        Ok(Self { conn: Mutex::new(conn), clock, path })
    }

    /// Opens an in-memory database (tests, ephemeral tools). Same pragmas and
    /// migrations as [`Db::open`].
    ///
    /// # Errors
    ///
    /// [`CoreError::Db`] on open/pragma/migration failures.
    pub fn open_in_memory(clock: Arc<dyn Clock>) -> Result<Self, CoreError> {
        let conn = Connection::open_in_memory().map_err(CoreError::db("open database"))?;
        configure(&conn)?;
        migrations::apply_all(&conn, clock.as_ref())?;
        Ok(Self { conn: Mutex::new(conn), clock, path: PathBuf::from(":memory:") })
    }

    /// The database file path (`:memory:` for in-memory handles).
    pub fn path(&self) -> &Path {
        &self.path
    }

    /// Runs `body` against the typed store. The connection lock is held for
    /// the duration — keep bodies short (single logical operation).
    ///
    /// # Errors
    ///
    /// Whatever `body` returns.
    pub fn with_store<T>(
        &self,
        body: impl FnOnce(&mut Store<'_>) -> Result<T, CoreError>,
    ) -> Result<T, CoreError> {
        let mut guard: MutexGuard<'_, Connection> =
            self.conn.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let mut store = Store::new(&mut guard, self.clock.as_ref());
        body(&mut store)
    }

    /// Applied schema version (highest migration number; `0` = empty).
    ///
    /// # Errors
    ///
    /// [`CoreError::Db`] on query failure.
    pub fn schema_version(&self) -> Result<i64, CoreError> {
        let guard = self.conn.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        migrations::current_version(&guard)
    }

    /// Online backup of the live database to `target` (LNC-2 backup duty).
    /// Safe while the database is in use; produces a consistent snapshot.
    ///
    /// # Errors
    ///
    /// [`CoreError::Db`] on backup failure.
    pub fn backup_to(&self, target: &Path) -> Result<(), CoreError> {
        let guard = self.conn.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let mut dest =
            Connection::open(target).map_err(CoreError::db("open backup target"))?;
        let backup = rusqlite::backup::Backup::new(&guard, &mut dest)
            .map_err(CoreError::db("start backup"))?;
        backup
            .run_to_completion(64, std::time::Duration::from_millis(5), None)
            .map_err(CoreError::db("run backup"))?;
        Ok(())
    }
}
