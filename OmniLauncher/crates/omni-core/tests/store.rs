//! Store integration tests: migrations, CRUD round-trips, constraint and
//! cascade behavior, backup.
#![expect(
    clippy::redundant_closure_for_method_calls,
    reason = "with_store takes a closure over &mut Store<'_>; clippy's suggested bare \
              method paths fail HRTB inference (`implementation of FnOnce is not \
              general enough`), so these closures are load-bearing"
)]

use std::sync::Arc;

use omni_core::clock::FixedClock;
use omni_core::store::{
    AccountKind, ComponentSource, Ecosystem, InstanceKind, JavaSource, NewInstance, NewMod,
    PortStatus, StackEntryInput,
};
use omni_core::{CoreError, Db};

fn fresh_db() -> (Db, Arc<FixedClock>) {
    let clock = Arc::new(FixedClock::new(1_800_000_000));
    let db = Db::open_in_memory(clock.clone()).expect("open in-memory db");
    (db, clock)
}

fn sample_instance(db: &Db) -> i64 {
    db.with_store(|store| {
        store.create_instance(&NewInstance {
            name: "Test Instance".into(),
            icon: None,
            kind: InstanceKind::Client,
            game_version: "1.21.1".into(),
        })
    })
    .expect("create instance")
    .id
}

#[test]
fn migrations_apply_and_reopen_is_idempotent() {
    let dir = tempfile::tempdir().expect("tempdir");
    let path = dir.path().join("state.db");
    let clock = Arc::new(FixedClock::new(1_800_000_000));
    {
        let db = Db::open(&path, clock.clone()).expect("first open");
        assert_eq!(db.schema_version().expect("version"), 1);
    }
    let db = Db::open(&path, clock).expect("reopen");
    assert_eq!(db.schema_version().expect("version"), 1);
}

#[test]
fn backup_produces_a_working_database() {
    let dir = tempfile::tempdir().expect("tempdir");
    let (db, clock) = fresh_db();
    let id = sample_instance(&db);

    let backup_path = dir.path().join("backup.db");
    db.backup_to(&backup_path).expect("backup");

    let restored = Db::open(&backup_path, clock).expect("open backup");
    let instance = restored
        .with_store(|store| store.instance(id))
        .expect("query backup")
        .expect("instance present in backup");
    assert_eq!(instance.name, "Test Instance");
}

#[test]
fn instance_crud_roundtrip() {
    let (db, clock) = fresh_db();
    let id = sample_instance(&db);

    db.with_store(|store| {
        let fetched = store.instance(id)?.expect("exists");
        assert_eq!(fetched.kind, InstanceKind::Client);
        assert_eq!(fetched.created_at, 1_800_000_000);
        assert_eq!(fetched.settings, serde_json::json!({}));
        assert!(fetched.last_played_at.is_none());

        store.rename_instance(id, "Renamed")?;
        store.update_instance_settings(id, &serde_json::json!({"memoryMib": 4096}))?;
        Ok(())
    })
    .expect("mutations");

    clock.advance(60);
    db.with_store(|store| store.touch_last_played(id)).expect("touch");

    let fetched = db.with_store(|store| store.instance(id)).expect("query").expect("exists");
    assert_eq!(fetched.name, "Renamed");
    assert_eq!(fetched.settings["memoryMib"], 4096);
    assert_eq!(fetched.last_played_at, Some(1_800_000_060));

    assert!(db.with_store(|store| store.delete_instance(id)).expect("delete"));
    assert!(db.with_store(|store| store.instance(id)).expect("query").is_none());
}

#[test]
fn component_stack_replaces_transactionally() {
    let (db, _clock) = fresh_db();
    let id = sample_instance(&db);

    let stack = vec![
        StackEntryInput {
            uid: "net.minecraft".into(),
            version: "1.21.1".into(),
            pinned: false,
            source: ComponentSource::Meta,
            custom: None,
        },
        StackEntryInput {
            uid: "org.omniframework.omniloader".into(),
            version: "0.1.0".into(),
            pinned: true,
            source: ComponentSource::Meta,
            custom: None,
        },
    ];
    db.with_store(|store| store.set_component_stack(id, &stack)).expect("set stack");

    let read = db.with_store(|store| store.component_stack(id)).expect("read stack");
    assert_eq!(read.len(), 2);
    assert_eq!(read[0].uid, "net.minecraft");
    assert_eq!(read[0].position, 0);
    assert_eq!(read[1].uid, "org.omniframework.omniloader");
    assert!(read[1].pinned);

    // A duplicate uid violates UNIQUE(instance_id, uid): the whole replace
    // rolls back and the previous stack survives.
    let broken = vec![
        StackEntryInput {
            uid: "dup".into(),
            version: "1".into(),
            pinned: false,
            source: ComponentSource::Meta,
            custom: None,
        },
        StackEntryInput {
            uid: "dup".into(),
            version: "2".into(),
            pinned: false,
            source: ComponentSource::Meta,
            custom: None,
        },
    ];
    let err = db.with_store(|store| store.set_component_stack(id, &broken));
    assert!(matches!(err, Err(CoreError::Db { .. })), "got: {err:?}");
    let survived = db.with_store(|store| store.component_stack(id)).expect("read stack");
    assert_eq!(survived.len(), 2, "previous stack must survive the failed replace");

    // Unknown instance is a typed NotFound.
    let err = db.with_store(|store| store.set_component_stack(9999, &[]));
    assert!(matches!(err, Err(CoreError::NotFound { what: "instance", .. })), "got: {err:?}");
}

#[test]
fn mods_upsert_cascade_and_hash_lookup() {
    let (db, _clock) = fresh_db();
    let id = sample_instance(&db);

    let new_mod = NewMod {
        instance_id: id,
        file_name: "sodium.jar".into(),
        sha1: Some("aa".repeat(20)),
        sha512: None,
        murmur2: Some("123456".into()),
        display_name: Some("Sodium".into()),
        version: Some("0.6.0".into()),
        ecosystem: Ecosystem::Fabric,
        enabled: true,
        platform: Some("modrinth".into()),
        platform_project_id: Some("AANobbMI".into()),
        platform_file_id: None,
        port_status: PortStatus::Compat,
    };
    let stored = db.with_store(|store| store.upsert_mod(&new_mod)).expect("insert");
    assert_eq!(stored.ecosystem, Ecosystem::Fabric);

    // Same (instance, file) upserts in place.
    let updated = db
        .with_store(|store| {
            store.upsert_mod(&NewMod { version: Some("0.6.1".into()), ..new_mod.clone() })
        })
        .expect("upsert");
    assert_eq!(updated.id, stored.id);
    assert_eq!(updated.version.as_deref(), Some("0.6.1"));

    let found = db.with_store(|store| store.mods_by_sha1(&"aa".repeat(20))).expect("lookup");
    assert_eq!(found.len(), 1);

    db.with_store(|store| store.set_mod_enabled(stored.id, false)).expect("disable");
    db.with_store(|store| store.set_mod_port_status(stored.id, PortStatus::Ported))
        .expect("port status");
    let fetched =
        db.with_store(|store| store.mod_by_id(stored.id)).expect("query").expect("exists");
    assert!(!fetched.enabled);
    assert_eq!(fetched.port_status, PortStatus::Ported);

    // FK: unknown instance is rejected by the database.
    let err = db.with_store(|store| {
        store.upsert_mod(&NewMod { instance_id: 9999, ..new_mod.clone() })
    });
    assert!(matches!(err, Err(CoreError::Db { .. })), "got: {err:?}");

    // Cascade: deleting the instance removes its mods.
    db.with_store(|store| store.delete_instance(id)).expect("delete instance");
    let orphans = db.with_store(|store| store.mods_by_sha1(&"aa".repeat(20))).expect("lookup");
    assert!(orphans.is_empty(), "mods must cascade with their instance");
}

#[test]
fn accounts_entitlement_window_and_refresh() {
    let (db, _clock) = fresh_db();
    let account = db
        .with_store(|store| {
            store.upsert_account("uuid-1", "Steve", AccountKind::Microsoft, "omni/msa/uuid-1")
        })
        .expect("insert account");
    assert!(account.entitlement_verified_at.is_none());
    assert!(!account.entitlement_valid(1_800_000_000));

    db.with_store(|store| {
        store.set_entitlement_window(account.id, 1_800_000_000, 1_800_000_000 + 14 * 86_400)
    })
    .expect("stamp window");

    // Re-login refreshes username/vault_ref but never the entitlement columns.
    let refreshed = db
        .with_store(|store| {
            store.upsert_account("uuid-1", "SteveRenamed", AccountKind::Microsoft, "omni/msa/uuid-1")
        })
        .expect("refresh");
    assert_eq!(refreshed.id, account.id);
    assert_eq!(refreshed.username, "SteveRenamed");
    assert_eq!(refreshed.entitlement_verified_at, Some(1_800_000_000));
    assert!(refreshed.entitlement_valid(1_800_000_000 + 13 * 86_400));
    assert!(!refreshed.entitlement_valid(1_800_000_000 + 15 * 86_400));

    let listed = db.with_store(|store| store.accounts()).expect("list");
    assert_eq!(listed.len(), 1);
}

#[test]
fn java_runtimes_and_processes_and_settings() {
    let (db, clock) = fresh_db();
    let id = sample_instance(&db);

    let runtime = db
        .with_store(|store| {
            store.upsert_java_runtime(
                "C:/java/21/bin/java.exe",
                Some("Temurin"),
                "21.0.10",
                "x86_64",
                JavaSource::Detected,
                Some(1_800_000_000),
            )
        })
        .expect("insert runtime");
    let again = db
        .with_store(|store| {
            store.upsert_java_runtime(
                "C:/java/21/bin/java.exe",
                Some("Temurin"),
                "21.0.11",
                "x86_64",
                JavaSource::MojangManifest,
                None,
            )
        })
        .expect("upsert runtime");
    assert_eq!(again.id, runtime.id);
    assert_eq!(again.version, "21.0.11");
    assert_eq!(again.source, JavaSource::MojangManifest);

    let session = db
        .with_store(|store| {
            store.record_process_start(id, Some(4321), &serde_json::json!({"account": "uuid-1"}))
        })
        .expect("process start");
    assert_eq!(
        db.with_store(|store| store.open_processes()).expect("open list").len(),
        1
    );
    clock.advance(300);
    db.with_store(|store| store.record_process_exit(session.id, 0)).expect("exit");
    assert!(db.with_store(|store| store.open_processes()).expect("open list").is_empty());

    db.with_store(|store| store.set_setting("theme", &serde_json::json!("dark")))
        .expect("set setting");
    db.with_store(|store| store.set_setting("theme", &serde_json::json!("light")))
        .expect("overwrite setting");
    let value = db.with_store(|store| store.setting("theme")).expect("get setting");
    assert_eq!(value, Some(serde_json::json!("light")));
    assert_eq!(db.with_store(|store| store.setting("missing")).expect("get"), None);
}
