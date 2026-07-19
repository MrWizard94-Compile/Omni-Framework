//! Accounts (vault refs only), Java runtimes, process sessions, settings.

use rusqlite::{OptionalExtension as _, Row, params};

use crate::error::CoreError;

use super::{Account, AccountKind, JavaRuntime, JavaSource, ProcessSession, Store};

fn map_account(row: &Row<'_>) -> rusqlite::Result<Account> {
    Ok(Account {
        id: row.get("id")?,
        uuid: row.get("uuid")?,
        username: row.get("username")?,
        kind: row.get("kind")?,
        vault_ref: row.get("vault_ref")?,
        entitlement_verified_at: row.get("entitlement_verified_at")?,
        entitlement_window_expires_at: row.get("entitlement_window_expires_at")?,
    })
}

fn map_java(row: &Row<'_>) -> rusqlite::Result<JavaRuntime> {
    Ok(JavaRuntime {
        id: row.get("id")?,
        path: row.get("path")?,
        vendor: row.get("vendor")?,
        version: row.get("version")?,
        arch: row.get("arch")?,
        source: row.get("source")?,
        valid_at: row.get("valid_at")?,
    })
}

fn map_process(row: &Row<'_>) -> rusqlite::Result<ProcessSession> {
    Ok(ProcessSession {
        id: row.get("id")?,
        instance_id: row.get("instance_id")?,
        pid: row.get("pid")?,
        started_at: row.get("started_at")?,
        ended_at: row.get("ended_at")?,
        exit_code: row.get("exit_code")?,
        session: row.get("session_json")?,
    })
}

const SELECT_ACCOUNT: &str = "SELECT id, uuid, username, kind, vault_ref, \
     entitlement_verified_at, entitlement_window_expires_at FROM accounts";
const SELECT_PROCESS: &str = "SELECT id, instance_id, pid, started_at, ended_at, \
     exit_code, session_json FROM processes";

impl Store<'_> {
    /// Inserts an account or refreshes `username`/`vault_ref` for an existing
    /// UUID (re-login semantics). Entitlement columns are untouched here —
    /// they change only through [`Store::set_entitlement_window`].
    ///
    /// # Errors
    ///
    /// [`CoreError::Db`] on driver failure.
    pub fn upsert_account(
        &mut self,
        uuid: &str,
        username: &str,
        kind: AccountKind,
        vault_ref: &str,
    ) -> Result<Account, CoreError> {
        self.conn
            .execute(
                "INSERT INTO accounts (uuid, username, kind, vault_ref) \
                 VALUES (?1, ?2, ?3, ?4) \
                 ON CONFLICT (uuid) DO UPDATE SET \
                 username = excluded.username, vault_ref = excluded.vault_ref",
                params![uuid, username, kind, vault_ref],
            )
            .map_err(CoreError::db("upsert account"))?;
        self.conn
            .query_row(&format!("{SELECT_ACCOUNT} WHERE uuid = ?1"), params![uuid], map_account)
            .map_err(CoreError::db("reselect account"))
    }

    /// Every account, ordered by username then id.
    ///
    /// # Errors
    ///
    /// [`CoreError::Db`] on driver failure.
    pub fn accounts(&mut self) -> Result<Vec<Account>, CoreError> {
        let mut statement = self
            .conn
            .prepare(&format!("{SELECT_ACCOUNT} ORDER BY username COLLATE NOCASE, id"))
            .map_err(CoreError::db("prepare account list"))?;
        let rows = statement
            .query_map([], map_account)
            .map_err(CoreError::db("list accounts"))?
            .collect::<Result<Vec<_>, _>>()
            .map_err(CoreError::db("read account rows"))?;
        Ok(rows)
    }

    /// Fetches an account by profile UUID.
    ///
    /// # Errors
    ///
    /// [`CoreError::Db`] on driver failure.
    pub fn account_by_uuid(&mut self, uuid: &str) -> Result<Option<Account>, CoreError> {
        self.conn
            .query_row(&format!("{SELECT_ACCOUNT} WHERE uuid = ?1"), params![uuid], map_account)
            .optional()
            .map_err(CoreError::db("select account"))
    }

    /// Stamps a successful entitlement verification and the CPL-2/ADR-0011
    /// offline window it opens. The window is anchored to the verification
    /// time by the caller (the auth module owns the 14-day policy constant).
    ///
    /// # Errors
    ///
    /// [`CoreError::NotFound`] for a missing account; [`CoreError::Db`] on
    /// driver failure.
    pub fn set_entitlement_window(
        &mut self,
        account_id: i64,
        verified_at: i64,
        expires_at: i64,
    ) -> Result<(), CoreError> {
        let changed = self
            .conn
            .execute(
                "UPDATE accounts SET entitlement_verified_at = ?2, \
                 entitlement_window_expires_at = ?3 WHERE id = ?1",
                params![account_id, verified_at, expires_at],
            )
            .map_err(CoreError::db("set entitlement window"))?;
        if changed == 0 {
            return Err(CoreError::NotFound { what: "account", id: account_id });
        }
        Ok(())
    }

    /// Removes an account row (vault cleanup is the auth module's duty).
    /// Returns whether a row was removed.
    ///
    /// # Errors
    ///
    /// [`CoreError::Db`] on driver failure.
    pub fn delete_account(&mut self, id: i64) -> Result<bool, CoreError> {
        let changed = self
            .conn
            .execute("DELETE FROM accounts WHERE id = ?1", params![id])
            .map_err(CoreError::db("delete account"))?;
        Ok(changed > 0)
    }

    /// Inserts a Java runtime or refreshes its metadata for an existing path.
    ///
    /// # Errors
    ///
    /// [`CoreError::Db`] on driver failure.
    pub fn upsert_java_runtime(
        &mut self,
        path: &str,
        vendor: Option<&str>,
        version: &str,
        arch: &str,
        source: JavaSource,
        valid_at: Option<i64>,
    ) -> Result<JavaRuntime, CoreError> {
        self.conn
            .execute(
                "INSERT INTO java_runtimes (path, vendor, version, arch, source, valid_at) \
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6) \
                 ON CONFLICT (path) DO UPDATE SET vendor = excluded.vendor, \
                 version = excluded.version, arch = excluded.arch, \
                 source = excluded.source, valid_at = excluded.valid_at",
                params![path, vendor, version, arch, source, valid_at],
            )
            .map_err(CoreError::db("upsert java runtime"))?;
        self.conn
            .query_row(
                "SELECT id, path, vendor, version, arch, source, valid_at \
                 FROM java_runtimes WHERE path = ?1",
                params![path],
                map_java,
            )
            .map_err(CoreError::db("reselect java runtime"))
    }

    /// Every known Java runtime, ordered by version descending then path.
    ///
    /// # Errors
    ///
    /// [`CoreError::Db`] on driver failure.
    pub fn java_runtimes(&mut self) -> Result<Vec<JavaRuntime>, CoreError> {
        let mut statement = self
            .conn
            .prepare(
                "SELECT id, path, vendor, version, arch, source, valid_at \
                 FROM java_runtimes ORDER BY version DESC, path",
            )
            .map_err(CoreError::db("prepare java list"))?;
        let rows = statement
            .query_map([], map_java)
            .map_err(CoreError::db("list java runtimes"))?
            .collect::<Result<Vec<_>, _>>()
            .map_err(CoreError::db("read java rows"))?;
        Ok(rows)
    }

    /// Removes a Java runtime row. Returns whether a row was removed.
    ///
    /// # Errors
    ///
    /// [`CoreError::Db`] on driver failure.
    pub fn delete_java_runtime(&mut self, id: i64) -> Result<bool, CoreError> {
        let changed = self
            .conn
            .execute("DELETE FROM java_runtimes WHERE id = ?1", params![id])
            .map_err(CoreError::db("delete java runtime"))?;
        Ok(changed > 0)
    }

    /// Records a game-process launch.
    ///
    /// # Errors
    ///
    /// [`CoreError::Db`] on driver failure (including unknown instance).
    pub fn record_process_start(
        &mut self,
        instance_id: i64,
        pid: Option<i64>,
        session: &serde_json::Value,
    ) -> Result<ProcessSession, CoreError> {
        let now = self.clock.now_unix();
        self.conn
            .execute(
                "INSERT INTO processes (instance_id, pid, started_at, session_json) \
                 VALUES (?1, ?2, ?3, ?4)",
                params![instance_id, pid, now, session],
            )
            .map_err(CoreError::db("insert process"))?;
        let id = self.conn.last_insert_rowid();
        self.conn
            .query_row(&format!("{SELECT_PROCESS} WHERE id = ?1"), params![id], map_process)
            .map_err(CoreError::db("reselect process"))
    }

    /// Records a game-process exit.
    ///
    /// # Errors
    ///
    /// [`CoreError::NotFound`] for a missing session; [`CoreError::Db`] on
    /// driver failure.
    pub fn record_process_exit(&mut self, id: i64, exit_code: i64) -> Result<(), CoreError> {
        let now = self.clock.now_unix();
        let changed = self
            .conn
            .execute(
                "UPDATE processes SET ended_at = ?2, exit_code = ?3 WHERE id = ?1",
                params![id, now, exit_code],
            )
            .map_err(CoreError::db("record process exit"))?;
        if changed == 0 {
            return Err(CoreError::NotFound { what: "process", id });
        }
        Ok(())
    }

    /// One process session by id (open or closed) — the console surface's
    /// lookup.
    ///
    /// # Errors
    ///
    /// [`CoreError::Db`] on driver failure.
    pub fn process_session(&mut self, id: i64) -> Result<Option<ProcessSession>, CoreError> {
        self.conn
            .query_row(&format!("{SELECT_PROCESS} WHERE id = ?1"), params![id], map_process)
            .optional()
            .map_err(CoreError::db("select process session"))
    }

    /// Sessions with no recorded exit (running, or orphaned by a crash).
    ///
    /// # Errors
    ///
    /// [`CoreError::Db`] on driver failure.
    pub fn open_processes(&mut self) -> Result<Vec<ProcessSession>, CoreError> {
        let mut statement = self
            .conn
            .prepare(&format!("{SELECT_PROCESS} WHERE ended_at IS NULL ORDER BY id"))
            .map_err(CoreError::db("prepare open process list"))?;
        let rows = statement
            .query_map([], map_process)
            .map_err(CoreError::db("list open processes"))?
            .collect::<Result<Vec<_>, _>>()
            .map_err(CoreError::db("read process rows"))?;
        Ok(rows)
    }

    /// A setting value, when present.
    ///
    /// # Errors
    ///
    /// [`CoreError::Db`] on driver failure.
    pub fn setting(&mut self, key: &str) -> Result<Option<serde_json::Value>, CoreError> {
        self.conn
            .query_row("SELECT value_json FROM settings WHERE key = ?1", params![key], |row| {
                row.get(0)
            })
            .optional()
            .map_err(CoreError::db("select setting"))
    }

    /// Creates or replaces a setting value.
    ///
    /// # Errors
    ///
    /// [`CoreError::Db`] on driver failure.
    pub fn set_setting(&mut self, key: &str, value: &serde_json::Value) -> Result<(), CoreError> {
        self.conn
            .execute(
                "INSERT INTO settings (key, value_json) VALUES (?1, ?2) \
                 ON CONFLICT (key) DO UPDATE SET value_json = excluded.value_json",
                params![key, value],
            )
            .map_err(CoreError::db("set setting"))?;
        Ok(())
    }
}
