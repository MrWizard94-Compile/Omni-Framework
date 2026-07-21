//! Phase 5 export writers (ADR-0014): golden-shape tests for omni / curseforge /
//! mrpack. Asserts document properties, not merely "did not throw".

use std::io::{Read as _, Write as _};
use std::path::Path;
use std::sync::Arc;

use serde_json::json;
use zip::ZipArchive;

use omni_core::clock::FixedClock;
use omni_core::export::{ExportFormat, ExportInput, export_instance};
use omni_core::store::{
    ComponentSource, Ecosystem, InstanceKind, NewInstance, NewMod, PortStatus, StackEntryInput,
};
use omni_core::Db;

fn fresh_db() -> Db {
    Db::open_in_memory(Arc::new(FixedClock::new(1_800_000_000))).expect("db")
}

fn seed_instance(db: &Db) -> i64 {
    db.with_store(|store| {
        let instance = store.create_instance(&NewInstance {
            name: "Export Fixture".to_owned(),
            icon: None,
            kind: InstanceKind::Client,
            game_version: "1.21.1".to_owned(),
        })?;
        store.update_instance_settings(
            instance.id,
            &json!({
                "importedLoader": { "kind": "neoforge", "version": "21.1.221" },
                "packVersion": "1.0.0-test"
            }),
        )?;
        store.set_component_stack(
            instance.id,
            &[
                StackEntryInput {
                    uid: "net.minecraft".to_owned(),
                    version: "1.21.1".to_owned(),
                    pinned: true,
                    source: ComponentSource::Meta,
                    custom: None,
                },
                StackEntryInput {
                    uid: "net.neoforged".to_owned(),
                    version: "21.1.221".to_owned(),
                    pinned: false,
                    source: ComponentSource::Meta,
                    custom: None,
                },
            ],
        )?;
        store.upsert_mod(&NewMod {
            instance_id: instance.id,
            file_name: "connectivity-1.21.1-7.6.jar".to_owned(),
            sha1: Some("6adebe84f745dec8fa33bc3982383bc1cc24d49e".to_owned()),
            sha512: Some("aa".repeat(64)),
            murmur2: Some("1642034907".to_owned()),
            display_name: Some("Connectivity".to_owned()),
            version: Some("7.6".to_owned()),
            ecosystem: Ecosystem::Neoforge,
            enabled: true,
            platform: Some("curseforge".to_owned()),
            platform_project_id: Some("470193".to_owned()),
            platform_file_id: Some("7367221".to_owned()),
            port_status: PortStatus::Unknown,
        })?;
        store.upsert_mod(&NewMod {
            instance_id: instance.id,
            file_name: "local-only.jar".to_owned(),
            sha1: Some("bb".repeat(20)),
            sha512: Some("cc".repeat(64)),
            murmur2: None,
            display_name: Some("Local Only".to_owned()),
            version: Some("1.0".to_owned()),
            ecosystem: Ecosystem::Unknown,
            enabled: true,
            platform: None,
            platform_project_id: None,
            platform_file_id: None,
            port_status: PortStatus::Unknown,
        })?;
        // Disabled mod must not appear in CF required files / mrpack overrides.
        store.upsert_mod(&NewMod {
            instance_id: instance.id,
            file_name: "disabled.jar".to_owned(),
            sha1: None,
            sha512: None,
            murmur2: None,
            display_name: Some("Disabled".to_owned()),
            version: None,
            ecosystem: Ecosystem::Unknown,
            enabled: false,
            platform: Some("curseforge".to_owned()),
            platform_project_id: Some("1".to_owned()),
            platform_file_id: Some("2".to_owned()),
            port_status: PortStatus::Unknown,
        })?;
        Ok(instance.id)
    })
    .expect("seed")
}

fn load_export_input(
    db: &Db,
    id: i64,
    dest: &Path,
    mods_dir: Option<&Path>,
) -> (omni_core::store::Instance, Vec<omni_core::store::StackEntry>, Vec<omni_core::store::Mod>) {
    db.with_store(|store| {
        let instance = store.instance(id)?.expect("instance");
        let stack = store.component_stack(id)?;
        let mods = store.mods_for_instance(id)?;
        let _ = (dest, mods_dir);
        Ok((instance, stack, mods))
    })
    .expect("load")
}

fn fake_jar() -> Vec<u8> {
    let mut buffer = std::io::Cursor::new(Vec::new());
    {
        let mut zip = zip::ZipWriter::new(&mut buffer);
        zip.start_file("META-INF/MANIFEST.MF", zip::write::SimpleFileOptions::default())
            .expect("start");
        zip.write_all(b"Manifest-Version: 1.0\n").expect("body");
        zip.finish().expect("finish");
    }
    buffer.into_inner()
}

#[test]
fn export_format_parse_accepts_aliases() {
    assert_eq!(ExportFormat::parse("mrpack").unwrap(), ExportFormat::Mrpack);
    assert_eq!(ExportFormat::parse("modrinth").unwrap(), ExportFormat::Mrpack);
    assert_eq!(ExportFormat::parse("curseforge").unwrap(), ExportFormat::CurseForge);
    assert_eq!(ExportFormat::parse("omni").unwrap(), ExportFormat::Omni);
    assert!(ExportFormat::parse("zip").is_err());
}

#[test]
fn omni_pack_is_schema_zero_and_round_trips_fields() {
    let db = fresh_db();
    let id = seed_instance(&db);
    let dir = tempfile::tempdir().expect("tmp");
    let dest = dir.path().join("pack.omnipack.json");
    let (instance, stack, mods) = load_export_input(&db, id, &dest, None);
    let report = export_instance(
        ExportFormat::Omni,
        &ExportInput {
            instance: &instance,
            stack: &stack,
            mods: &mods,
            mods_dir: None,
            dest: &dest,
        },
    )
    .expect("export");
    assert_eq!(report.mods_exported, 3);
    let doc: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(&dest).expect("read")).expect("json");
    assert_eq!(doc["schema"], 0);
    assert_eq!(doc["name"], "Export Fixture");
    assert_eq!(doc["gameVersion"], "1.21.1");
    assert_eq!(doc["packVersion"], "1.0.0-test");
    assert_eq!(doc["stack"].as_array().expect("stack").len(), 2);
    assert_eq!(doc["mods"].as_array().expect("mods").len(), 3);
    assert!(
        doc["mods"]
            .as_array()
            .unwrap()
            .iter()
            .any(|m| m["fileName"] == "connectivity-1.21.1-7.6.jar"
                && m["platformProjectId"] == "470193")
    );
}

#[test]
fn curseforge_manifest_lists_only_enabled_cf_ids() {
    let db = fresh_db();
    let id = seed_instance(&db);
    let dir = tempfile::tempdir().expect("tmp");
    let dest = dir.path().join("cf-out");
    let (instance, stack, mods) = load_export_input(&db, id, &dest, None);
    let report = export_instance(
        ExportFormat::CurseForge,
        &ExportInput {
            instance: &instance,
            stack: &stack,
            mods: &mods,
            mods_dir: None,
            dest: &dest,
        },
    )
    .expect("export");
    let manifest: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(dest.join("manifest.json")).expect("manifest"),
    )
    .expect("json");
    assert_eq!(manifest["manifestType"], "minecraftModpack");
    assert_eq!(manifest["manifestVersion"], 1);
    assert_eq!(manifest["minecraft"]["version"], "1.21.1");
    let loaders = manifest["minecraft"]["modLoaders"].as_array().expect("loaders");
    assert_eq!(loaders[0]["id"], "neoforge-21.1.221");
    assert_eq!(loaders[0]["primary"], true);
    let files = manifest["files"].as_array().expect("files");
    // Enabled CF mod only — disabled.jar excluded, local-only skipped.
    assert_eq!(files.len(), 1);
    assert_eq!(files[0]["projectID"], 470_193);
    assert_eq!(files[0]["fileID"], 7_367_221);
    assert!(report.skipped.iter().any(|s| s.contains("local-only.jar")));
    assert!(!report.skipped.iter().any(|s| s.contains("disabled.jar")));
}

#[test]
fn curseforge_packs_local_jars_into_overrides_when_mods_dir_present() {
    let db = fresh_db();
    let id = seed_instance(&db);
    let dir = tempfile::tempdir().expect("tmp");
    let mods_dir = dir.path().join("mods");
    std::fs::create_dir_all(&mods_dir).expect("mods");
    std::fs::write(mods_dir.join("local-only.jar"), fake_jar()).expect("jar");
    let dest = dir.path().join("cf-out");
    let (instance, stack, mods) = load_export_input(&db, id, &dest, Some(&mods_dir));
    let report = export_instance(
        ExportFormat::CurseForge,
        &ExportInput {
            instance: &instance,
            stack: &stack,
            mods: &mods,
            mods_dir: Some(&mods_dir),
            dest: &dest,
        },
    )
    .expect("export");
    assert!(dest.join("overrides/mods/local-only.jar").is_file());
    assert!(report.notes.iter().any(|n| n.contains("1 override")));
}

#[test]
fn mrpack_contains_index_and_override_jars() {
    let db = fresh_db();
    let id = seed_instance(&db);
    let dir = tempfile::tempdir().expect("tmp");
    let mods_dir = dir.path().join("mods");
    std::fs::create_dir_all(&mods_dir).expect("mods");
    std::fs::write(mods_dir.join("connectivity-1.21.1-7.6.jar"), fake_jar()).expect("jar");
    std::fs::write(mods_dir.join("local-only.jar"), fake_jar()).expect("jar");
    let dest = dir.path().join("pack.mrpack");
    let (instance, stack, mods) = load_export_input(&db, id, &dest, Some(&mods_dir));
    let report = export_instance(
        ExportFormat::Mrpack,
        &ExportInput {
            instance: &instance,
            stack: &stack,
            mods: &mods,
            mods_dir: Some(&mods_dir),
            dest: &dest,
        },
    )
    .expect("export");
    assert_eq!(report.mods_exported, 2);
    assert!(dest.is_file());

    let file = std::fs::File::open(&dest).expect("open");
    let mut archive = ZipArchive::new(file).expect("zip");
    let mut index_entry = archive.by_name("modrinth.index.json").expect("index");
    let mut index_bytes = Vec::new();
    index_entry.read_to_end(&mut index_bytes).expect("read");
    let index: serde_json::Value = serde_json::from_slice(&index_bytes).expect("json");
    assert_eq!(index["formatVersion"], 1);
    assert_eq!(index["game"], "minecraft");
    assert_eq!(index["dependencies"]["minecraft"], "1.21.1");
    assert_eq!(index["dependencies"]["neoforge"], "21.1.221");
    assert_eq!(index["files"].as_array().unwrap().len(), 2);
    drop(index_entry);

    assert!(archive.by_name("overrides/mods/connectivity-1.21.1-7.6.jar").is_ok());
    assert!(archive.by_name("overrides/mods/local-only.jar").is_ok());
    // Disabled jar never packed.
    assert!(archive.by_name("overrides/mods/disabled.jar").is_err());
}

#[test]
fn mrpack_without_mods_dir_skips_jars_but_still_writes_valid_index() {
    let db = fresh_db();
    let id = seed_instance(&db);
    let dir = tempfile::tempdir().expect("tmp");
    let dest = dir.path().join("empty.mrpack");
    let (instance, stack, mods) = load_export_input(&db, id, &dest, None);
    let report = export_instance(
        ExportFormat::Mrpack,
        &ExportInput {
            instance: &instance,
            stack: &stack,
            mods: &mods,
            mods_dir: None,
            dest: &dest,
        },
    )
    .expect("export");
    assert_eq!(report.mods_exported, 0);
    assert!(!report.skipped.is_empty());
    let file = std::fs::File::open(&dest).expect("open");
    let mut archive = ZipArchive::new(file).expect("zip");
    assert!(archive.by_name("modrinth.index.json").is_ok());
}
