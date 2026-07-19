//! Mods as entities (LNC-2; atlauncher lesson).

use rusqlite::{OptionalExtension as _, Row, params};

use crate::error::CoreError;

use super::{Mod, NewMod, PortStatus, Store};

fn map_mod(row: &Row<'_>) -> rusqlite::Result<Mod> {
    Ok(Mod {
        id: row.get("id")?,
        instance_id: row.get("instance_id")?,
        file_name: row.get("file_name")?,
        sha1: row.get("sha1")?,
        sha512: row.get("sha512")?,
        murmur2: row.get("murmur2")?,
        display_name: row.get("display_name")?,
        version: row.get("version")?,
        ecosystem: row.get("ecosystem")?,
        enabled: row.get("enabled")?,
        platform: row.get("platform")?,
        platform_project_id: row.get("platform_project_id")?,
        platform_file_id: row.get("platform_file_id")?,
        port_status: row.get("port_status")?,
    })
}

const SELECT_MOD: &str = "SELECT id, instance_id, file_name, sha1, sha512, murmur2, \
     display_name, version, ecosystem, enabled, platform, platform_project_id, \
     platform_file_id, port_status FROM mods";

impl Store<'_> {
    /// Inserts a mod entity, or updates every mutable field when
    /// `(instance_id, file_name)` already exists (folder-rescan semantics).
    ///
    /// # Errors
    ///
    /// [`CoreError::Db`] on constraint (unknown instance) or driver failure.
    pub fn upsert_mod(&mut self, new: &NewMod) -> Result<Mod, CoreError> {
        self.conn
            .execute(
                "INSERT INTO mods (instance_id, file_name, sha1, sha512, murmur2, \
                 display_name, version, ecosystem, enabled, platform, \
                 platform_project_id, platform_file_id, port_status) \
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13) \
                 ON CONFLICT (instance_id, file_name) DO UPDATE SET \
                 sha1 = excluded.sha1, sha512 = excluded.sha512, \
                 murmur2 = excluded.murmur2, display_name = excluded.display_name, \
                 version = excluded.version, ecosystem = excluded.ecosystem, \
                 enabled = excluded.enabled, platform = excluded.platform, \
                 platform_project_id = excluded.platform_project_id, \
                 platform_file_id = excluded.platform_file_id, \
                 port_status = excluded.port_status",
                params![
                    new.instance_id,
                    new.file_name,
                    new.sha1,
                    new.sha512,
                    new.murmur2,
                    new.display_name,
                    new.version,
                    new.ecosystem,
                    new.enabled,
                    new.platform,
                    new.platform_project_id,
                    new.platform_file_id,
                    new.port_status,
                ],
            )
            .map_err(CoreError::db("upsert mod"))?;
        self.conn
            .query_row(
                &format!("{SELECT_MOD} WHERE instance_id = ?1 AND file_name = ?2"),
                params![new.instance_id, new.file_name],
                map_mod,
            )
            .map_err(CoreError::db("reselect mod"))
    }

    /// Every mod of an instance, ordered by file name.
    ///
    /// # Errors
    ///
    /// [`CoreError::Db`] on driver failure.
    pub fn mods_for_instance(&mut self, instance_id: i64) -> Result<Vec<Mod>, CoreError> {
        let mut statement = self
            .conn
            .prepare(&format!("{SELECT_MOD} WHERE instance_id = ?1 ORDER BY file_name"))
            .map_err(CoreError::db("prepare mod list"))?;
        let rows = statement
            .query_map(params![instance_id], map_mod)
            .map_err(CoreError::db("list mods"))?
            .collect::<Result<Vec<_>, _>>()
            .map_err(CoreError::db("read mod rows"))?;
        Ok(rows)
    }

    /// Finds mods by sha1 across all instances (hash recovery, census reuse).
    ///
    /// # Errors
    ///
    /// [`CoreError::Db`] on driver failure.
    pub fn mods_by_sha1(&mut self, sha1: &str) -> Result<Vec<Mod>, CoreError> {
        let mut statement = self
            .conn
            .prepare(&format!("{SELECT_MOD} WHERE sha1 = ?1 ORDER BY id"))
            .map_err(CoreError::db("prepare sha1 lookup"))?;
        let rows = statement
            .query_map(params![sha1], map_mod)
            .map_err(CoreError::db("lookup mods by sha1"))?
            .collect::<Result<Vec<_>, _>>()
            .map_err(CoreError::db("read mod rows"))?;
        Ok(rows)
    }

    /// Enables/disables a mod (LNC-10 toggling).
    ///
    /// # Errors
    ///
    /// [`CoreError::NotFound`] for a missing row; [`CoreError::Db`] on driver
    /// failure.
    pub fn set_mod_enabled(&mut self, id: i64, enabled: bool) -> Result<(), CoreError> {
        let changed = self
            .conn
            .execute("UPDATE mods SET enabled = ?2 WHERE id = ?1", params![id, enabled])
            .map_err(CoreError::db("set mod enabled"))?;
        if changed == 0 {
            return Err(CoreError::NotFound { what: "mod", id });
        }
        Ok(())
    }

    /// Updates a mod's port-pipeline status (ADR-0003 tracking).
    ///
    /// # Errors
    ///
    /// [`CoreError::NotFound`] for a missing row; [`CoreError::Db`] on driver
    /// failure.
    pub fn set_mod_port_status(&mut self, id: i64, status: PortStatus) -> Result<(), CoreError> {
        let changed = self
            .conn
            .execute("UPDATE mods SET port_status = ?2 WHERE id = ?1", params![id, status])
            .map_err(CoreError::db("set mod port status"))?;
        if changed == 0 {
            return Err(CoreError::NotFound { what: "mod", id });
        }
        Ok(())
    }

    /// Removes a mod entity. Returns whether a row was removed.
    ///
    /// # Errors
    ///
    /// [`CoreError::Db`] on driver failure.
    pub fn delete_mod(&mut self, id: i64) -> Result<bool, CoreError> {
        let changed = self
            .conn
            .execute("DELETE FROM mods WHERE id = ?1", params![id])
            .map_err(CoreError::db("delete mod"))?;
        Ok(changed > 0)
    }

    /// Fetches one mod.
    ///
    /// # Errors
    ///
    /// [`CoreError::Db`] on driver failure.
    pub fn mod_by_id(&mut self, id: i64) -> Result<Option<Mod>, CoreError> {
        self.conn
            .query_row(&format!("{SELECT_MOD} WHERE id = ?1"), params![id], map_mod)
            .optional()
            .map_err(CoreError::db("select mod"))
    }
}
