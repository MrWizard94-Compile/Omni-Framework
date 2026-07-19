//! Instances and their component stacks.

use rusqlite::{OptionalExtension as _, Row, params};

use crate::error::CoreError;

use super::{Instance, NewInstance, StackEntry, StackEntryInput, Store};

fn map_instance(row: &Row<'_>) -> rusqlite::Result<Instance> {
    Ok(Instance {
        id: row.get("id")?,
        name: row.get("name")?,
        icon: row.get("icon")?,
        kind: row.get("kind")?,
        game_version: row.get("game_version")?,
        created_at: row.get("created_at")?,
        last_played_at: row.get("last_played_at")?,
        settings: row.get("settings_json")?,
        notes: row.get("notes")?,
    })
}

const SELECT_INSTANCE: &str = "SELECT id, name, icon, kind, game_version, created_at, \
     last_played_at, settings_json, notes FROM instances";

impl Store<'_> {
    /// Creates an instance with empty settings and returns the stored row.
    ///
    /// # Errors
    ///
    /// [`CoreError::Db`] on constraint or driver failure.
    pub fn create_instance(&mut self, new: &NewInstance) -> Result<Instance, CoreError> {
        let now = self.clock.now_unix();
        self.conn
            .execute(
                "INSERT INTO instances (name, icon, kind, game_version, created_at) \
                 VALUES (?1, ?2, ?3, ?4, ?5)",
                params![new.name, new.icon, new.kind, new.game_version, now],
            )
            .map_err(CoreError::db("insert instance"))?;
        let id = self.conn.last_insert_rowid();
        self.instance(id)?.ok_or(CoreError::NotFound { what: "instance", id })
    }

    /// Fetches one instance.
    ///
    /// # Errors
    ///
    /// [`CoreError::Db`] on driver failure.
    pub fn instance(&mut self, id: i64) -> Result<Option<Instance>, CoreError> {
        self.conn
            .query_row(&format!("{SELECT_INSTANCE} WHERE id = ?1"), params![id], map_instance)
            .optional()
            .map_err(CoreError::db("select instance"))
    }

    /// Every instance, ordered by name (case-insensitive), then id.
    ///
    /// # Errors
    ///
    /// [`CoreError::Db`] on driver failure.
    pub fn instances(&mut self) -> Result<Vec<Instance>, CoreError> {
        let mut statement = self
            .conn
            .prepare(&format!("{SELECT_INSTANCE} ORDER BY name COLLATE NOCASE, id"))
            .map_err(CoreError::db("prepare instance list"))?;
        let rows = statement
            .query_map([], map_instance)
            .map_err(CoreError::db("list instances"))?
            .collect::<Result<Vec<_>, _>>()
            .map_err(CoreError::db("read instance rows"))?;
        Ok(rows)
    }

    /// Renames an instance.
    ///
    /// # Errors
    ///
    /// [`CoreError::NotFound`] when the row does not exist; [`CoreError::Db`]
    /// on driver failure.
    pub fn rename_instance(&mut self, id: i64, name: &str) -> Result<(), CoreError> {
        let changed = self
            .conn
            .execute("UPDATE instances SET name = ?2 WHERE id = ?1", params![id, name])
            .map_err(CoreError::db("rename instance"))?;
        if changed == 0 {
            return Err(CoreError::NotFound { what: "instance", id });
        }
        Ok(())
    }

    /// Replaces an instance's settings document.
    ///
    /// # Errors
    ///
    /// [`CoreError::NotFound`] when the row does not exist; [`CoreError::Db`]
    /// on driver failure.
    pub fn update_instance_settings(
        &mut self,
        id: i64,
        settings: &serde_json::Value,
    ) -> Result<(), CoreError> {
        let changed = self
            .conn
            .execute(
                "UPDATE instances SET settings_json = ?2 WHERE id = ?1",
                params![id, settings],
            )
            .map_err(CoreError::db("update instance settings"))?;
        if changed == 0 {
            return Err(CoreError::NotFound { what: "instance", id });
        }
        Ok(())
    }

    /// Stamps `last_played_at` with the current time.
    ///
    /// # Errors
    ///
    /// [`CoreError::NotFound`] when the row does not exist; [`CoreError::Db`]
    /// on driver failure.
    pub fn touch_last_played(&mut self, id: i64) -> Result<(), CoreError> {
        let now = self.clock.now_unix();
        let changed = self
            .conn
            .execute("UPDATE instances SET last_played_at = ?2 WHERE id = ?1", params![id, now])
            .map_err(CoreError::db("touch last played"))?;
        if changed == 0 {
            return Err(CoreError::NotFound { what: "instance", id });
        }
        Ok(())
    }

    /// Deletes an instance; components, mods, and process rows cascade.
    /// Returns whether a row was removed.
    ///
    /// # Errors
    ///
    /// [`CoreError::Db`] on driver failure.
    pub fn delete_instance(&mut self, id: i64) -> Result<bool, CoreError> {
        let changed = self
            .conn
            .execute("DELETE FROM instances WHERE id = ?1", params![id])
            .map_err(CoreError::db("delete instance"))?;
        Ok(changed > 0)
    }

    /// Transactionally replaces an instance's component stack; positions are
    /// assigned from slice order.
    ///
    /// # Errors
    ///
    /// [`CoreError::NotFound`] for a missing instance; [`CoreError::Db`] on
    /// constraint (duplicate uid) or driver failure — the previous stack
    /// survives any failure.
    pub fn set_component_stack(
        &mut self,
        instance_id: i64,
        entries: &[StackEntryInput],
    ) -> Result<(), CoreError> {
        let tx = self.conn.transaction().map_err(CoreError::db("begin stack transaction"))?;
        let exists: Option<i64> = tx
            .query_row("SELECT id FROM instances WHERE id = ?1", params![instance_id], |row| {
                row.get(0)
            })
            .optional()
            .map_err(CoreError::db("check instance"))?;
        if exists.is_none() {
            return Err(CoreError::NotFound { what: "instance", id: instance_id });
        }
        tx.execute("DELETE FROM components WHERE instance_id = ?1", params![instance_id])
            .map_err(CoreError::db("clear component stack"))?;
        for (position, entry) in (0_i64..).zip(entries.iter()) {
            tx.execute(
                "INSERT INTO components \
                 (instance_id, position, uid, version, pinned, source, custom_json) \
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
                params![
                    instance_id,
                    position,
                    entry.uid,
                    entry.version,
                    entry.pinned,
                    entry.source,
                    entry.custom,
                ],
            )
            .map_err(CoreError::db("insert component"))?;
        }
        tx.commit().map_err(CoreError::db("commit stack transaction"))
    }

    /// The instance's component stack in position order.
    ///
    /// # Errors
    ///
    /// [`CoreError::Db`] on driver failure.
    pub fn component_stack(&mut self, instance_id: i64) -> Result<Vec<StackEntry>, CoreError> {
        let mut statement = self
            .conn
            .prepare(
                "SELECT position, uid, version, pinned, source, custom_json \
                 FROM components WHERE instance_id = ?1 ORDER BY position",
            )
            .map_err(CoreError::db("prepare stack query"))?;
        let rows = statement
            .query_map(params![instance_id], |row| {
                Ok(StackEntry {
                    position: row.get("position")?,
                    uid: row.get("uid")?,
                    version: row.get("version")?,
                    pinned: row.get("pinned")?,
                    source: row.get("source")?,
                    custom: row.get("custom_json")?,
                })
            })
            .map_err(CoreError::db("query stack"))?
            .collect::<Result<Vec<_>, _>>()
            .map_err(CoreError::db("read stack rows"))?;
        Ok(rows)
    }
}
