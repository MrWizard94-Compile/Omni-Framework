//! Importer tests over synthetic fixtures written to temp dirs, plus an
//! opt-in probe against the director's real CurseForge instance.
//!
//! The real-instance probe is `#[ignore]`d by design: it depends on a machine
//! -local path (ARCHITECTURE §8 calls these private fixtures). Run it with:
//! `cargo test -p omni-core --test import -- --ignored --nocapture`

use std::io::Write as _;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use serde_json::json;

use omni_core::clock::FixedClock;
use omni_core::import::{
    Confidence, CurseForgeImporter, ImportFormat, Importer, MrpackImporter, OfficialImporter,
    PrismImporter, detect_all, extract_mrpack_overrides, pending_downloads_from_settings,
    scan_mods_dir,
};
use omni_core::install::{
    BlobError, BlobSource, Paths, ensure_pending_downloads, extract_imported_mrpack_overrides,
    sha1_hex,
};
use omni_core::store::Ecosystem;
use omni_core::Db;
use std::collections::BTreeMap;
use std::sync::Mutex;

fn fresh_db() -> Db {
    Db::open_in_memory(Arc::new(FixedClock::new(1_800_000_000))).expect("db")
}

fn write(path: &Path, contents: &str) {
    std::fs::create_dir_all(path.parent().expect("parent")).expect("mkdir");
    std::fs::write(path, contents).expect("write");
}

/// Builds a minimal but valid jar (a ZIP holding one manifest entry).
fn fake_jar(manifest_name: &str) -> Vec<u8> {
    let mut buffer = std::io::Cursor::new(Vec::new());
    {
        let mut zip = zip::ZipWriter::new(&mut buffer);
        zip.start_file(manifest_name, zip::write::SimpleFileOptions::default())
            .expect("start entry");
        zip.write_all(b"{}").expect("write entry");
        zip.finish().expect("finish");
    }
    buffer.into_inner()
}

// ---------------------------------------------------------------- CurseForge

fn curseforge_fixture(root: &Path) {
    write(
        &root.join("minecraftinstance.json"),
        &json!({
            "name": "ATM10 Fixture",
            "gameVersion": "1.21.1",
            "allocatedMemory": 8192,
            "baseModLoader": { "name": "neoforge-21.1.221" },
            "installedAddons": [
                {
                    "addonID": 470_193,
                    "name": "Connectivity",
                    "isEnabled": true,
                    "installedFile": {
                        "id": 7_367_221,
                        "fileName": "connectivity-1.21.1-7.6.jar",
                        "fileNameOnDisk": "connectivity-1.21.1-7.6.jar",
                        "packageFingerprint": 1_642_034_907_u64,
                        "hashes": [
                            { "type": 1, "value": "6ADEBE84F745DEC8FA33BC3982383BC1CC24D49E" },
                            { "type": 2, "value": "f2d38f850688eefa338b807801df2b4b" }
                        ]
                    }
                },
                {
                    "addonID": 999,
                    "name": "Broken Addon",
                    "isEnabled": false
                }
            ]
        })
        .to_string(),
    );
}

#[test]
fn curseforge_detects_enumerates_and_imports() {
    let dir = tempfile::tempdir().expect("tempdir");
    let root = dir.path().join("ATM10");
    curseforge_fixture(&root);

    assert_eq!(CurseForgeImporter.detect(&root), Some(Confidence::Certain));
    // The parent instances-root is a weaker but real signal.
    assert_eq!(CurseForgeImporter.detect(dir.path()), Some(Confidence::Possible));
    assert_eq!(detect_all(&root)[0].0, ImportFormat::CurseForge);

    let candidates = CurseForgeImporter.enumerate(dir.path()).expect("enumerate root");
    assert_eq!(candidates.len(), 1);
    assert_eq!(candidates[0].name, "ATM10 Fixture");

    let db = fresh_db();
    let report = CurseForgeImporter.import(&db, &candidates[0]).expect("import");
    assert_eq!(report.mods_imported, 1);
    assert_eq!(report.skipped.len(), 1, "the addon without installedFile is reported");
    assert!(report.skipped[0].item.contains("Broken"));

    let instance = db
        .with_store(|store| store.instance(report.instance_id))
        .expect("query")
        .expect("instance");
    assert_eq!(instance.game_version, "1.21.1");
    assert_eq!(instance.settings["memoryMib"], 8192);
    assert_eq!(instance.settings["importedLoader"]["kind"], "neoforge");
    assert_eq!(instance.settings["importedLoader"]["version"], "21.1.221");
    assert_eq!(instance.settings["importedFrom"]["format"], "curseforge");

    // Stack seeded with the game component.
    let stack = db
        .with_store(|store| store.component_stack(report.instance_id))
        .expect("stack");
    assert_eq!(stack.len(), 1);
    assert_eq!(stack[0].uid, "net.minecraft");

    // Mod entity carries platform identity + normalized (lowercased) sha1.
    let mods = db
        .with_store(|store| store.mods_for_instance(report.instance_id))
        .expect("mods");
    assert_eq!(mods.len(), 1);
    assert_eq!(mods[0].file_name, "connectivity-1.21.1-7.6.jar");
    assert_eq!(mods[0].sha1.as_deref(), Some("6adebe84f745dec8fa33bc3982383bc1cc24d49e"));
    assert_eq!(mods[0].murmur2.as_deref(), Some("1642034907"));
    assert_eq!(mods[0].platform.as_deref(), Some("curseforge"));
    assert_eq!(mods[0].platform_project_id.as_deref(), Some("470193"));
    assert_eq!(mods[0].ecosystem, Ecosystem::Neoforge);
}

// ------------------------------------------------------------- Prism/MultiMC

#[test]
fn prism_imports_components_and_scans_mods() {
    let dir = tempfile::tempdir().expect("tempdir");
    let root = dir.path().join("Fabric Instance");
    write(
        &root.join("mmc-pack.json"),
        &json!({
            "formatVersion": 1,
            "components": [
                { "uid": "net.minecraft", "version": "1.21.1" },
                { "uid": "net.fabricmc.fabric-loader", "version": "0.16.9" },
                { "uid": "org.example.unknown", "version": "1.0" }
            ]
        })
        .to_string(),
    );
    write(&root.join("instance.cfg"), "InstanceType=OneSix\nname=My Prism Pack\nMaxMemAlloc=6144\n");
    std::fs::create_dir_all(root.join("minecraft/mods")).expect("mkdir");
    std::fs::write(root.join("minecraft/mods/sodium.jar"), fake_jar("fabric.mod.json"))
        .expect("jar");
    std::fs::write(
        root.join("minecraft/mods/oldmod.jar.disabled"),
        fake_jar("META-INF/mods.toml"),
    )
    .expect("jar");

    assert_eq!(PrismImporter.detect(&root), Some(Confidence::Certain));
    let candidates = PrismImporter.enumerate(&root).expect("enumerate");
    assert_eq!(candidates[0].name, "My Prism Pack");

    let db = fresh_db();
    let report = PrismImporter.import(&db, &candidates[0]).expect("import");
    assert_eq!(report.mods_imported, 2);
    assert!(
        report.skipped.iter().any(|s| s.item == "org.example.unknown"),
        "unmapped components are reported: {:?}",
        report.skipped
    );

    let instance = db
        .with_store(|store| store.instance(report.instance_id))
        .expect("query")
        .expect("instance");
    assert_eq!(instance.settings["importedLoader"]["kind"], "fabric");
    assert_eq!(instance.settings["memoryMib"], 6144);

    let mods = db
        .with_store(|store| store.mods_for_instance(report.instance_id))
        .expect("mods");
    let sodium = mods.iter().find(|m| m.file_name == "sodium.jar").expect("sodium");
    assert!(sodium.enabled);
    assert_eq!(sodium.ecosystem, Ecosystem::Fabric, "ecosystem sniffed from jar manifest");
    assert!(sodium.sha1.is_some() && sodium.sha512.is_some() && sodium.murmur2.is_some());
    let disabled = mods.iter().find(|m| m.file_name.ends_with(".disabled")).expect("disabled");
    assert!(!disabled.enabled);
    assert_eq!(disabled.ecosystem, Ecosystem::Forge);
}

// ------------------------------------------------------------------- mrpack

fn write_mrpack(path: &Path, index: &serde_json::Value) {
    let file = std::fs::File::create(path).expect("create mrpack");
    let mut zip = zip::ZipWriter::new(file);
    zip.start_file("modrinth.index.json", zip::write::SimpleFileOptions::default())
        .expect("start");
    zip.write_all(index.to_string().as_bytes()).expect("write");
    zip.finish().expect("finish");
}

#[test]
fn mrpack_imports_files_and_reports_non_mods() {
    let dir = tempfile::tempdir().expect("tempdir");
    let pack = dir.path().join("Cool Pack.mrpack");
    write_mrpack(
        &pack,
        &json!({
            "formatVersion": 1,
            "name": "Cool Pack",
            "versionId": "1.0.0",
            "dependencies": { "minecraft": "1.21.1", "fabric-loader": "0.16.9" },
            "files": [
                { "path": "mods/sodium.jar",
                  "hashes": { "sha1": "AA11", "sha512": "BB22" },
                  "downloads": ["https://cdn.modrinth.com/data/AA/versions/1/sodium.jar"] },
                { "path": "mods/serverside.jar",
                  "hashes": { "sha1": "CC33" },
                  "env": { "client": "unsupported", "server": "required" } },
                { "path": "config/sodium.json", "hashes": { "sha1": "DD44" } }
            ]
        }),
    );

    assert_eq!(MrpackImporter.detect(&pack), Some(Confidence::Certain));
    let candidates = MrpackImporter.enumerate(&pack).expect("enumerate");
    assert_eq!(candidates[0].name, "Cool Pack");

    let db = fresh_db();
    let report = MrpackImporter.import(&db, &candidates[0]).expect("import");
    assert_eq!(report.mods_imported, 1, "only the client-supported mod file");
    // serverside skipped for env; config skipped (no downloads URL, non-mod).
    assert!(report.skipped.iter().any(|s| s.item.contains("serverside")));
    assert!(report.skipped.iter().any(|s| s.item.contains("config/"))
        || report.notes.iter().any(|n| n.contains("queued")));

    let mods = db
        .with_store(|store| store.mods_for_instance(report.instance_id))
        .expect("mods");
    assert_eq!(mods[0].file_name, "sodium.jar");
    assert_eq!(mods[0].sha1.as_deref(), Some("aa11"));
    assert_eq!(mods[0].platform.as_deref(), Some("modrinth"));
    assert_eq!(mods[0].ecosystem, Ecosystem::Fabric);

    let settings = db
        .with_store(|store| Ok(store.instance(report.instance_id)?.unwrap().settings))
        .expect("settings");
    let pending = pending_downloads_from_settings(&settings).expect("pending");
    assert_eq!(pending.len(), 1);
    assert_eq!(pending[0].path, "mods/sodium.jar");
}

/// Map-backed [`BlobSource`] driving the real download helper (not a reimplementation).
struct ScriptedBlobs {
    map: BTreeMap<String, Vec<u8>>,
    fetches: Mutex<Vec<String>>,
}

impl BlobSource for ScriptedBlobs {
    fn fetch(&self, url: &str) -> Result<Vec<u8>, BlobError> {
        self.fetches.lock().expect("lock").push(url.to_owned());
        self.map
            .get(url)
            .cloned()
            .ok_or_else(|| BlobError { url: url.to_owned(), message: "not scripted".into() })
    }
}

/// Acceptance: real import queues CDN files; real `ensure_pending_downloads` hash-verifies
/// them under the instance game dir via the shipped install helper.
#[test]
fn mrpack_pending_downloads_are_fetched_into_game_dir_by_shipped_helper() {
    use omni_core::import::store_pending_downloads;

    let dir = tempfile::tempdir().expect("tempdir");
    let data = dir.path().join("data");
    let url = "https://cdn.modrinth.com/data/AA/versions/1/sodium.jar";
    let body = b"sodium-mod-bytes".to_vec();
    let sha1 = sha1_hex(&body);

    let pack_with_override = dir.path().join("cdn-overrides.mrpack");
    {
        let file = std::fs::File::create(&pack_with_override).unwrap();
        let mut zip = zip::ZipWriter::new(file);
        zip.start_file("modrinth.index.json", zip::write::SimpleFileOptions::default())
            .unwrap();
        zip.write_all(
            json!({
                "formatVersion": 1,
                "name": "CDN Pack",
                "dependencies": { "minecraft": "1.21.1", "fabric-loader": "0.16.9" },
                "files": [{
                    "path": "mods/sodium.jar",
                    "hashes": { "sha1": sha1 },
                    "downloads": [url],
                    "fileSize": body.len()
                }]
            })
            .to_string()
            .as_bytes(),
        )
        .unwrap();
        zip.start_file("overrides/config/demo.txt", zip::write::SimpleFileOptions::default())
            .unwrap();
        zip.write_all(b"from-override").unwrap();
        zip.finish().unwrap();
    }

    let db = fresh_db();
    let candidates = MrpackImporter.enumerate(&pack_with_override).expect("enumerate");
    let report = MrpackImporter.import(&db, &candidates[0]).expect("import");
    let settings = db
        .with_store(|store| Ok(store.instance(report.instance_id)?.unwrap().settings))
        .expect("settings");
    let pending = pending_downloads_from_settings(&settings).expect("pending");
    assert_eq!(pending.len(), 1);
    assert_eq!(pending[0].url, url);
    assert_eq!(pending[0].sha1.as_deref(), Some(sha1.as_str()));

    let paths = Paths::new(&data);
    let game = paths.game_dir(report.instance_id);
    let extracted = extract_mrpack_overrides(&pack_with_override, &game).expect("overrides");
    assert_eq!(extracted, 1);
    assert_eq!(
        std::fs::read_to_string(game.join("config/demo.txt")).expect("override file"),
        "from-override"
    );

    let blobs = ScriptedBlobs {
        map: BTreeMap::from([(url.to_owned(), body.clone())]),
        fetches: Mutex::new(Vec::new()),
    };
    let (done, remaining) =
        ensure_pending_downloads(&paths, &db, &blobs, report.instance_id, 25).expect("ensure");
    assert_eq!(done, 1);
    assert_eq!(remaining, 0);
    assert_eq!(blobs.fetches.lock().unwrap().as_slice(), &[url.to_owned()]);

    let on_disk = std::fs::read(game.join("mods/sodium.jar")).expect("mod jar written");
    assert_eq!(on_disk, body);
    assert_eq!(sha1_hex(&on_disk), sha1);

    // Re-queue the same pending entry: hash match must not re-fetch (repair path).
    store_pending_downloads(&db, report.instance_id, &pending).expect("requeue");
    let (done2, rem2) =
        ensure_pending_downloads(&paths, &db, &blobs, report.instance_id, 25).expect("re-ensure");
    assert_eq!(done2, 1, "existing hash-matching jar counts as completed");
    assert_eq!(rem2, 0);
    assert_eq!(blobs.fetches.lock().unwrap().len(), 1, "hash hit must not re-fetch");
}

/// Production import path calls [`extract_imported_mrpack_overrides`] (settings → pack → game/).
#[test]
fn extract_imported_mrpack_overrides_reads_import_provenance() {
    let dir = tempfile::tempdir().expect("tempdir");
    let data = dir.path().join("data");
    let pack = dir.path().join("with-overrides.mrpack");
    {
        let file = std::fs::File::create(&pack).unwrap();
        let mut zip = zip::ZipWriter::new(file);
        zip.start_file("modrinth.index.json", zip::write::SimpleFileOptions::default())
            .unwrap();
        zip.write_all(
            json!({
                "formatVersion": 1,
                "name": "O",
                "dependencies": { "minecraft": "1.21.1" },
                "files": []
            })
            .to_string()
            .as_bytes(),
        )
        .unwrap();
        zip.start_file("overrides/config/from_import.txt", zip::write::SimpleFileOptions::default())
            .unwrap();
        zip.write_all(b"provenance-path").unwrap();
        zip.finish().unwrap();
    }

    let db = fresh_db();
    let candidates = MrpackImporter.enumerate(&pack).expect("enumerate");
    let report = MrpackImporter.import(&db, &candidates[0]).expect("import");
    let paths = Paths::new(&data);
    let n = extract_imported_mrpack_overrides(&paths, &db, report.instance_id).expect("extract");
    assert_eq!(n, 1);
    let text = std::fs::read_to_string(
        paths.game_dir(report.instance_id).join("config/from_import.txt"),
    )
    .expect("override written under instance game dir");
    assert_eq!(text, "provenance-path");

    // Non-mrpack provenance: no-op.
    let id = db
        .with_store(|store| {
            store.create_instance(&omni_core::store::NewInstance {
                name: "vanilla".into(),
                icon: None,
                kind: omni_core::store::InstanceKind::Client,
                game_version: "1.21.1".into(),
            })
        })
        .unwrap()
        .id;
    assert_eq!(extract_imported_mrpack_overrides(&paths, &db, id).unwrap(), 0);
}

// --------------------------------------------------------- official launcher

#[test]
fn official_launcher_imports_pinned_profiles_and_rejects_dynamic() {
    let dir = tempfile::tempdir().expect("tempdir");
    let root = dir.path().join(".minecraft");
    write(
        &root.join("launcher_profiles.json"),
        &json!({
            "profiles": {
                "pinned": { "name": "Vanilla 1.21.1", "lastVersionId": "1.21.1" },
                "modded": { "name": "Fabric", "lastVersionId": "fabric-loader-0.16.9-1.21.1" },
                "dynamic": { "name": "Latest", "lastVersionId": "latest-release" }
            }
        })
        .to_string(),
    );
    std::fs::create_dir_all(root.join("mods")).expect("mkdir");
    std::fs::write(root.join("mods/lithium.jar"), fake_jar("fabric.mod.json")).expect("jar");

    assert_eq!(OfficialImporter.detect(&root), Some(Confidence::Certain));
    let candidates = OfficialImporter.enumerate(&root).expect("enumerate");
    assert_eq!(candidates.len(), 3, "one candidate per profile");

    let db = fresh_db();
    let modded = candidates.iter().find(|c| c.name == "Fabric").expect("modded");
    let report = OfficialImporter.import(&db, modded).expect("import modded");
    let instance = db
        .with_store(|store| store.instance(report.instance_id))
        .expect("query")
        .expect("instance");
    assert_eq!(instance.game_version, "1.21.1", "MC version split out of the version id");
    assert_eq!(instance.settings["importedLoader"]["kind"], "fabric");
    assert_eq!(instance.settings["importedLoader"]["version"], "0.16.9");
    assert_eq!(report.mods_imported, 1, "shared .minecraft mods scanned read-only");

    let dynamic = candidates.iter().find(|c| c.name == "Latest").expect("dynamic");
    let err = OfficialImporter.import(&db, dynamic).expect_err("dynamic must be refused");
    assert!(err.to_string().contains("dynamic version"), "actionable message: {err}");
}

/// NeoForge version ids carry no Minecraft version (`neoforge-21.1.221`), so
/// the vanilla version comes from the installed version JSON's `inheritsFrom`.
/// Without it, importing must REFUSE rather than store a bogus `game_version`
/// that could never resolve against the meta service.
#[test]
fn official_launcher_resolves_neoforge_via_inherits_from() {
    let dir = tempfile::tempdir().expect("tempdir");
    let root = dir.path().join(".minecraft");
    write(
        &root.join("launcher_profiles.json"),
        &json!({
            "profiles": {
                "neo": { "name": "NeoForge", "lastVersionId": "neoforge-21.1.221" },
                "forge": { "name": "Forge", "lastVersionId": "1.20.1-forge-47.3.0" }
            }
        })
        .to_string(),
    );
    let db = fresh_db();
    let candidates = OfficialImporter.enumerate(&root).expect("enumerate");
    let neo = candidates.iter().find(|c| c.name == "NeoForge").expect("neo");

    // No version JSON on disk yet: refuse with an actionable message.
    let err = OfficialImporter.import(&db, neo).expect_err("must refuse without inheritsFrom");
    let message = err.to_string();
    assert!(message.contains("neoforge-21.1.221"), "names the profile: {message}");
    assert!(message.contains("inheritsFrom"), "explains the cause: {message}");

    // With the version JSON present, inheritsFrom is authoritative.
    write(
        &root.join("versions/neoforge-21.1.221/neoforge-21.1.221.json"),
        &json!({ "id": "neoforge-21.1.221", "inheritsFrom": "1.21.1" }).to_string(),
    );
    let report = OfficialImporter.import(&db, neo).expect("import with inheritsFrom");
    let instance = db
        .with_store(|store| store.instance(report.instance_id))
        .expect("query")
        .expect("instance");
    assert_eq!(instance.game_version, "1.21.1", "vanilla version from inheritsFrom");
    assert_eq!(instance.settings["importedLoader"]["kind"], "neoforge");
    assert_eq!(instance.settings["importedLoader"]["version"], "21.1.221");

    // Forge ids embed the Minecraft version, so shape parsing suffices offline.
    let forge = candidates.iter().find(|c| c.name == "Forge").expect("forge");
    let report = OfficialImporter.import(&db, forge).expect("import forge");
    let instance = db
        .with_store(|store| store.instance(report.instance_id))
        .expect("query")
        .expect("instance");
    assert_eq!(instance.game_version, "1.20.1");
    assert_eq!(instance.settings["importedLoader"]["kind"], "forge");
    assert_eq!(instance.settings["importedLoader"]["version"], "47.3.0");
}

// -------------------------------------------------------------- shared bits

#[test]
fn detectors_do_not_claim_foreign_paths() {
    let dir = tempfile::tempdir().expect("tempdir");
    let empty = dir.path().join("empty");
    std::fs::create_dir_all(&empty).expect("mkdir");
    assert!(detect_all(&empty).is_empty());
    assert!(CurseForgeImporter.detect(&empty).is_none());
    assert!(PrismImporter.detect(&empty).is_none());
    assert!(MrpackImporter.detect(&empty).is_none());
    assert!(OfficialImporter.detect(&empty).is_none());
}

#[test]
fn scanner_hashes_and_orders_deterministically() {
    let dir = tempfile::tempdir().expect("tempdir");
    let mods = dir.path().join("mods");
    std::fs::create_dir_all(&mods).expect("mkdir");
    std::fs::write(mods.join("b.jar"), fake_jar("omni.mod.json")).expect("jar");
    std::fs::write(mods.join("a.jar"), fake_jar("META-INF/neoforge.mods.toml")).expect("jar");
    std::fs::write(mods.join("notes.txt"), b"ignored").expect("txt");

    let scanned = scan_mods_dir(&mods).expect("scan");
    assert_eq!(scanned.len(), 2, "non-jar files ignored");
    assert_eq!(scanned[0].file_name, "a.jar", "sorted order");
    assert_eq!(scanned[0].ecosystem, Ecosystem::Neoforge);
    assert_eq!(scanned[1].ecosystem, Ecosystem::Omni);
    assert_eq!(scanned[0].sha1.len(), 40);
    assert_eq!(scanned[0].sha512.len(), 128);
    assert!(scanned[0].murmur2.parse::<u32>().is_ok());
}

// ------------------------------------------- real-instance probe (opt-in)

/// The director's real ATM10 instance (permission granted 2026-07-19).
fn atm10_path() -> PathBuf {
    PathBuf::from(
        r"C:\Users\Bulkl\curseforge\minecraft\Instances\All the Mods 10 To the Sky   ATM10SKY",
    )
}

#[test]
#[ignore = "reads a machine-local private fixture; run with --ignored"]
fn real_atm10_instance_imports() {
    let root = atm10_path();
    assert!(root.is_dir(), "fixture not present: {}", root.display());
    assert_eq!(CurseForgeImporter.detect(&root), Some(Confidence::Certain));

    let candidates = CurseForgeImporter.enumerate(&root).expect("enumerate");
    assert_eq!(candidates.len(), 1);

    let db = fresh_db();
    let report = CurseForgeImporter.import(&db, &candidates[0]).expect("import");
    let instance = db
        .with_store(|store| store.instance(report.instance_id))
        .expect("query")
        .expect("instance");
    println!(
        "imported {:?}: mc {}, loader {} {}, {} mods, {} skipped",
        instance.name,
        instance.game_version,
        instance.settings["importedLoader"]["kind"],
        instance.settings["importedLoader"]["version"],
        report.mods_imported,
        report.skipped.len()
    );
    assert_eq!(instance.game_version, "1.21.1");
    assert_eq!(instance.settings["importedLoader"]["kind"], "neoforge");
    assert!(report.mods_imported > 300, "ATM10 carries 300+ mods");

    let mods = db
        .with_store(|store| store.mods_for_instance(report.instance_id))
        .expect("mods");
    assert!(mods.iter().all(|m| m.platform.as_deref() == Some("curseforge")));
    assert!(
        mods.iter().filter(|m| m.sha1.is_some()).count() > 300,
        "sha1 recovered for the corpus"
    );
    // Fingerprints must be re-derivable from the real jars on disk.
    let scanned = scan_mods_dir(&root.join("mods")).expect("scan real mods");
    println!("scanned {} jars from disk", scanned.len());
    let by_name: std::collections::BTreeMap<_, _> =
        scanned.iter().map(|s| (s.file_name.as_str(), s)).collect();
    let mut checked = 0_usize;
    for entity in &mods {
        if let (Some(scan), Some(expected)) =
            (by_name.get(entity.file_name.as_str()), entity.murmur2.as_deref())
        {
            assert_eq!(
                scan.murmur2, expected,
                "murmur2 mismatch for {} (our implementation vs CurseForge's record)",
                entity.file_name
            );
            checked += 1;
        }
    }
    println!("verified {checked} murmur2 fingerprints against CurseForge's own records");
    assert!(checked > 100, "expected to cross-check many real fingerprints");
}
