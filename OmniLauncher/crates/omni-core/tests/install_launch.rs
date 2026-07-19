//! Install/launch pipeline tests: the real meta client over the committed
//! golden tree, a scripted blob source, and temp-dir data roots — no network,
//! no real game files (ARCHITECTURE §8).
#![expect(
    clippy::redundant_closure_for_method_calls,
    reason = "with_store takes a closure over &mut Store<'_>; clippy's suggested bare \
              method paths fail HRTB inference (`implementation of FnOnce is not \
              general enough`), so these closures are load-bearing"
)]

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use serde_json::json;

use omni_core::auth::{LaunchAuth, LiveSession};
use omni_core::clock::FixedClock;
use omni_core::install::{
    BlobError, BlobSource, InstallExecutor, InstallSpec, Paths, ResolvedStack, resolve_stack,
    sha1_hex,
};
use omni_core::jobs::{CancelToken, RunOutcome, run};
use omni_core::launch::{
    LaunchInputs, accept_server_eula, build_client_command, build_server_command,
    spawn_recorded, wait_and_record,
};
use omni_core::platform::Platform;
use omni_core::store::{ComponentSource, InstanceKind, JobState, NewInstance};
use omni_core::{CoreError, Db, NullSink};
use omni_meta_client::{DirSource, MetaClient};

fn golden_v1() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../OmniMeta/generator/tests/golden/v1")
        .canonicalize()
        .expect("golden tree exists")
}

/// Blob source over an in-memory url → bytes map, counting fetches.
#[derive(Default)]
struct MapBlobSource {
    map: BTreeMap<String, Vec<u8>>,
    fetches: Mutex<Vec<String>>,
}

impl MapBlobSource {
    fn insert(&mut self, url: &str, bytes: Vec<u8>) -> String {
        let sha1 = sha1_hex(&bytes);
        self.map.insert(url.to_owned(), bytes);
        sha1
    }

    fn fetch_count(&self) -> usize {
        self.fetches.lock().expect("fetches").len()
    }
}

impl BlobSource for MapBlobSource {
    fn fetch(&self, url: &str) -> Result<Vec<u8>, BlobError> {
        self.fetches.lock().expect("fetches").push(url.to_owned());
        self.map
            .get(url)
            .cloned()
            .ok_or_else(|| BlobError { url: url.to_owned(), message: "not scripted".into() })
    }
}

/// A synthetic resolved stack whose URLs all live in the blob map. Shapes
/// mirror the golden tree's dialect; content bytes are tiny stand-ins.
fn synthetic_world() -> (ResolvedStack, MapBlobSource) {
    let mut blobs = MapBlobSource::default();
    let lib_sha = blobs.insert("https://libs.test/brigadier.jar", b"brigadier-bytes".to_vec());
    let natives_sha =
        blobs.insert("https://libs.test/lwjgl-natives.jar", b"natives-bytes".to_vec());
    let client_sha = blobs.insert("https://game.test/client.jar", b"client-jar-bytes".to_vec());
    let server_sha = blobs.insert("https://game.test/server.jar", b"server-jar-bytes".to_vec());

    let asset_a = b"asset-object-a".to_vec();
    let sound_hash = sha1_hex(&asset_a);
    let asset_b = b"asset-object-b".to_vec();
    let lang_hash = sha1_hex(&asset_b);
    let index_doc = serde_json::to_vec(&json!({
        "objects": {
            "minecraft/sounds/a.ogg": { "hash": sound_hash, "size": asset_a.len() },
            "minecraft/lang/b.json":  { "hash": lang_hash, "size": asset_b.len() },
        }
    }))
    .expect("index doc");
    let index_sha = blobs.insert("https://meta.test/assets/17.json", index_doc);
    blobs.insert(
        &format!(
            "https://resources.download.minecraft.net/{}/{}",
            &sound_hash[0..2],
            sound_hash
        ),
        asset_a,
    );
    blobs.insert(
        &format!(
            "https://resources.download.minecraft.net/{}/{}",
            &lang_hash[0..2],
            lang_hash
        ),
        asset_b,
    );

    let java_file = b"#!java-binary".to_vec();
    let java_file_sha = blobs.insert("https://java.test/bin/java.exe", java_file);
    let platform_key = omni_core::install::java_platform_key(&Platform::current());
    let manifest_doc = serde_json::to_vec(&json!({
        "files": {
            "bin/java.exe": {
                "type": "file", "executable": true,
                "downloads": { "raw": {
                    "url": "https://java.test/bin/java.exe",
                    "sha1": java_file_sha, "size": 13,
                } },
            },
            "legal": { "type": "directory" },
        }
    }))
    .expect("manifest doc");
    let manifest_sha = blobs.insert("https://java.test/manifest.json", manifest_doc);

    let game = serde_json::from_value(json!({
        "uid": "net.minecraft", "version": "1.21.1",
        "releaseTime": "2024-08-08T12:24:45+00:00", "type": "release",
        "mainClass": "net.minecraft.client.main.Main",
        "arguments": {
            "game": [
                "--username", "${auth_player_name}", "--gameDir", "${game_directory}",
                { "rules": [ { "action": "allow",
                               "features": { "is_demo_user": true } } ],
                  "value": "--demo" },
            ],
            "jvm": [
                "-Djava.library.path=${natives_directory}",
                "-cp", "${classpath}",
            ],
        },
        "libraries": [
            { "coord": "com.mojang:brigadier:1.2.9", "url": "https://libs.test/brigadier.jar",
              "sha1": lib_sha, "size": 15 },
            { "coord": "org.lwjgl:lwjgl:3.3.3:natives-windows",
              "url": "https://libs.test/lwjgl-natives.jar", "sha1": natives_sha, "size": 13,
              "rules": [ { "action": "allow", "os": { "name": "windows" } } ] },
            { "coord": "org.lwjgl:lwjgl:3.3.3:natives-macos",
              "url": "https://libs.test/absent.jar", "sha1": "0".repeat(40), "size": 1,
              "rules": [ { "action": "allow", "os": { "name": "osx" } } ] },
        ],
        "assetIndex": { "id": "17", "url": "https://meta.test/assets/17.json",
                        "sha1": index_sha, "size": 1, "totalSize": 2 },
        "javaVersion": { "component": "java-runtime-delta", "majorVersion": 21 },
        "data": {
            "downloads": {
                "client": { "url": "https://game.test/client.jar", "sha1": client_sha, "size": 16 },
                "server": { "url": "https://game.test/server.jar", "sha1": server_sha, "size": 16 },
            },
        },
    }))
    .expect("game payload");

    let java = serde_json::from_value(json!({
        "uid": "net.minecraft.java", "version": "21.0.3",
        "releaseTime": "2024-04-16T12:00:00+00:00", "type": "release",
        "data": { "platforms": { platform_key: { "java-runtime-delta": {
            "manifest": { "url": "https://java.test/manifest.json",
                          "sha1": manifest_sha, "size": 1 },
            "released": "2024-04-16T12:00:00+00:00",
        } } } },
    }))
    .expect("java payload");

    (ResolvedStack { payloads: vec![game], java_payloads: vec![java] }, blobs)
}

fn fresh_env() -> (Db, Paths, tempfile::TempDir) {
    let clock = Arc::new(FixedClock::new(1_800_000_000));
    let db = Db::open_in_memory(clock).expect("db");
    let root = tempfile::tempdir().expect("tempdir");
    let paths = Paths::new(root.path());
    (db, paths, root)
}

fn make_instance(db: &Db, kind: InstanceKind) -> i64 {
    db.with_store(|store| {
        store.create_instance(&NewInstance {
            name: "Test".into(),
            icon: None,
            kind,
            game_version: "1.21.1".into(),
        })
    })
    .expect("instance")
    .id
}

fn run_install(db: &Db, paths: &Paths, source: &MapBlobSource, spec: InstallSpec) -> RunOutcome {
    let job = db.with_store(|store| store.create_job("install", Some(spec.instance_id)))
        .expect("job");
    let mut executor = InstallExecutor::new(db, paths, source, spec);
    run(db, job.id, &mut executor, &NullSink, &CancelToken::new()).expect("run")
}

#[test]
fn resolve_stack_walks_the_requires_closure_on_the_golden_tree() {
    let cache = tempfile::tempdir().expect("tempdir");
    let meta = MetaClient::new(DirSource::new(golden_v1()), cache.path()).expect("meta");
    let stack = [omni_core::store::StackEntry {
        position: 0,
        uid: "net.minecraft".into(),
        version: "1.21.1".into(),
        pinned: false,
        source: ComponentSource::Meta,
        custom: None,
    }];
    let resolved = resolve_stack(&meta, &stack).expect("resolves");
    let uids: Vec<&str> = resolved.payloads.iter().map(|p| p.uid.as_str()).collect();
    assert!(uids.contains(&"net.minecraft"));
    assert!(uids.contains(&"org.lwjgl3"), "requires closure must pull the LWJGL split: {uids:?}");
    assert!(!resolved.java_payloads.is_empty());
}

#[test]
fn client_install_completes_and_places_everything() {
    let (db, paths, _root) = fresh_env();
    let (stack, blobs) = synthetic_world();
    let id = make_instance(&db, InstanceKind::Client);

    let outcome =
        run_install(&db, &paths, &blobs, InstallSpec { instance_id: id, server: false, stack });
    assert_eq!(outcome, RunOutcome::Completed);

    // Objects present + verified layout.
    assert!(paths.asset_indexes_dir().join("17.json").exists());
    let asset_hash = sha1_hex(b"asset-object-a");
    assert!(
        paths.asset_objects_root().join(&asset_hash[0..2]).join(&asset_hash).exists(),
        "asset object in official layout"
    );
    assert!(paths.java_install_dir("java-runtime-delta", "21.0.3").join("bin/java.exe").exists());
    assert!(paths.game_dir(id).exists());
    // Java runtime registered (LNC-6).
    let runtimes = db.with_store(|store| store.java_runtimes()).expect("runtimes");
    assert_eq!(runtimes.len(), 1);
    assert_eq!(runtimes[0].version, "21.0.3");
    // The osx-only natives library was rule-filtered out: nothing fetched it.
    assert!(
        !blobs.fetches.lock().expect("log").iter().any(|url| url.contains("absent.jar")),
        "platform-filtered library must not download"
    );
}

/// Wraps a blob source and trips a cancel token once `limit` fetches have
/// happened — a genuine mid-install interrupt: the in-flight step still
/// completes and persists its resume point, then the runner pauses at the
/// step boundary (exactly what a user-pause or a crash-then-recover looks
/// like to the executor).
struct CancelAfter<'a> {
    inner: &'a MapBlobSource,
    token: CancelToken,
    limit: usize,
    seen: Mutex<usize>,
}

impl BlobSource for CancelAfter<'_> {
    fn fetch(&self, url: &str) -> Result<Vec<u8>, BlobError> {
        let bytes = self.inner.fetch(url)?;
        let mut seen = self.seen.lock().expect("seen");
        *seen += 1;
        if *seen >= self.limit {
            self.token.cancel();
        }
        Ok(bytes)
    }
}

/// Baseline: how many fetches a clean, uninterrupted install performs.
fn clean_install_fetch_count() -> usize {
    let (db, paths, _root) = fresh_env();
    let (stack, blobs) = synthetic_world();
    let id = make_instance(&db, InstanceKind::Client);
    let outcome =
        run_install(&db, &paths, &blobs, InstallSpec { instance_id: id, server: false, stack });
    assert_eq!(outcome, RunOutcome::Completed);
    blobs.fetch_count()
}

#[test]
fn interrupted_install_resumes_without_refetching() {
    let baseline = clean_install_fetch_count();
    assert!(baseline > 3, "the fixture must need several fetches to be worth interrupting");

    let (db, paths, _root) = fresh_env();
    let (stack, blobs) = synthetic_world();
    let id = make_instance(&db, InstanceKind::Client);
    let job = db.with_store(|store| store.create_job("install", Some(id))).expect("job");

    // Phase 1: interrupt partway through, after real work has been persisted.
    let token = CancelToken::new();
    let interrupting =
        CancelAfter { inner: &blobs, token: token.clone(), limit: 3, seen: Mutex::new(0) };
    let mut executor = InstallExecutor::new(
        &db,
        &paths,
        &interrupting,
        InstallSpec { instance_id: id, server: false, stack },
    );
    let outcome = run(&db, job.id, &mut executor, &NullSink, &token).expect("run");
    assert_eq!(outcome, RunOutcome::Paused);

    let paused = db.with_store(|store| store.job(job.id)).expect("query").expect("job");
    assert_eq!(paused.state, JobState::Paused);
    let resume = paused.resume.expect("a paused install carries its resume point");
    let phase = resume["phase"].as_str().expect("phase recorded");
    assert_ne!(phase, "game-files", "the interrupt must land past the first phase: {resume}");
    let fetched_before = blobs.fetch_count();
    assert!(fetched_before >= 3, "real downloads happened before the interrupt");
    assert!(fetched_before < baseline, "the install is genuinely incomplete");

    // Phase 2: a fresh executor (new-process semantics) resumes to completion.
    let (stack2, _) = synthetic_world();
    let mut executor = InstallExecutor::new(
        &db,
        &paths,
        &blobs,
        InstallSpec { instance_id: id, server: false, stack: stack2 },
    );
    let outcome =
        run(&db, job.id, &mut executor, &NullSink, &CancelToken::new()).expect("resume");
    assert_eq!(outcome, RunOutcome::Completed);
    assert_eq!(
        blobs.fetch_count(),
        baseline,
        "interrupt + resume must fetch each artifact exactly once — no refetching"
    );

    // A third full run (repair pass): everything verifies locally, zero fetches.
    let job2 = db.with_store(|store| store.create_job("install", Some(id))).expect("job2");
    let (stack3, _) = synthetic_world();
    let mut executor = InstallExecutor::new(
        &db,
        &paths,
        &blobs,
        InstallSpec { instance_id: id, server: false, stack: stack3 },
    );
    let outcome =
        run(&db, job2.id, &mut executor, &NullSink, &CancelToken::new()).expect("repair");
    assert_eq!(outcome, RunOutcome::Completed);
    assert_eq!(blobs.fetch_count(), baseline, "repair pass must be all cache hits");
}

#[test]
fn corrupted_object_self_heals_on_repair() {
    let (db, paths, _root) = fresh_env();
    let (stack, blobs) = synthetic_world();
    let id = make_instance(&db, InstanceKind::Client);
    let outcome =
        run_install(&db, &paths, &blobs, InstallSpec { instance_id: id, server: false, stack });
    assert_eq!(outcome, RunOutcome::Completed);

    // Corrupt the client jar object.
    let client_sha = sha1_hex(b"client-jar-bytes");
    let object = paths.objects_root().join(&client_sha[0..2]).join(&client_sha);
    std::fs::write(&object, b"corrupted").expect("corrupt");
    let before = blobs.fetch_count();

    let (stack2, _) = synthetic_world();
    let outcome = run_install(
        &db,
        &paths,
        &blobs,
        InstallSpec { instance_id: id, server: false, stack: stack2 },
    );
    assert_eq!(outcome, RunOutcome::Completed);
    assert_eq!(blobs.fetch_count(), before + 1, "exactly the corrupted object refetches");
    let bytes = std::fs::read(&object).expect("read");
    assert_eq!(sha1_hex(&bytes), client_sha);
}

fn online_auth(name: &str, uuid: &str) -> LaunchAuth {
    LaunchAuth::Online(LiveSession {
        account: omni_core::store::Account {
            id: 1,
            uuid: uuid.into(),
            username: name.into(),
            kind: omni_core::store::AccountKind::Microsoft,
            vault_ref: format!("omni/msa/{uuid}"),
            entitlement_verified_at: Some(1_800_000_000),
            entitlement_window_expires_at: Some(1_801_209_600),
        },
        profile: omni_core::auth::McProfile { uuid: uuid.into(), name: name.into() },
        mc_access_token: "TOKEN".into(),
    })
}

#[test]
fn client_command_assembles_args_classpath_and_features() {
    let (db, paths, _root) = fresh_env();
    let (stack, _) = synthetic_world();
    let id = make_instance(&db, InstanceKind::Client);
    let instance =
        db.with_store(|store| store.instance(id)).expect("query").expect("instance");
    let auth = online_auth("MrWizard94", "069a79f4");
    let platform = Platform { os_name: "windows", arch: "x86_64" };

    let inputs = LaunchInputs {
        instance: &instance,
        stack: &stack,
        auth: &auth,
        java_exe: PathBuf::from("C:/java/bin/java.exe"),
        memory_mib: 4096,
        features: BTreeMap::new(),
        extra_jvm_args: vec!["-XX:+UseG1GC".into()],
    };
    let command = build_client_command(&paths, &inputs, &platform).expect("command");

    assert_eq!(command.program, PathBuf::from("C:/java/bin/java.exe"));
    assert_eq!(command.args[0], "-Xmx4096M");
    assert!(command.args.contains(&"-XX:+UseG1GC".to_owned()));
    assert!(command.args.contains(&"net.minecraft.client.main.Main".to_owned()));
    assert!(command.args.contains(&"MrWizard94".to_owned()), "username substituted");
    assert!(!command.args.contains(&"--demo".to_owned()), "demo feature off by default");
    // Classpath: brigadier + windows natives + client jar; not the osx natives.
    let cp_index = command.args.iter().position(|a| a == "-cp").expect("-cp present");
    let classpath = &command.args[cp_index + 1];
    let client_sha = sha1_hex(b"client-jar-bytes");
    assert!(classpath.contains(&client_sha));
    assert!(classpath.contains(&sha1_hex(b"brigadier-bytes")));
    assert!(classpath.contains(&sha1_hex(b"natives-bytes")), "modern natives ride the classpath");
    assert!(!classpath.contains(&"0".repeat(40)), "osx natives filtered on windows");

    // Demo feature flips the conditional argument on.
    let mut features = BTreeMap::new();
    features.insert("is_demo_user".to_owned(), true);
    let inputs = LaunchInputs { features, ..inputs };
    let command = build_client_command(&paths, &inputs, &platform).expect("command");
    assert!(command.args.contains(&"--demo".to_owned()));
}

#[test]
fn server_launch_is_gated_on_explicit_eula_acceptance() {
    let (db, paths, _root) = fresh_env();
    let (stack, _) = synthetic_world();
    let id = make_instance(&db, InstanceKind::Server);
    let instance =
        db.with_store(|store| store.instance(id)).expect("query").expect("instance");
    let auth = online_auth("MrWizard94", "069a79f4");
    let inputs = LaunchInputs {
        instance: &instance,
        stack: &stack,
        auth: &auth,
        java_exe: PathBuf::from("java"),
        memory_mib: 2048,
        features: BTreeMap::new(),
        extra_jvm_args: Vec::new(),
    };

    let err = build_server_command(&db, &paths, &inputs).expect_err("must refuse");
    assert!(
        matches!(err, CoreError::NotFound { what: "server EULA acceptance", .. }),
        "got: {err:?}"
    );

    accept_server_eula(&db, &paths, id).expect("explicit acceptance");
    let eula = std::fs::read_to_string(paths.game_dir(id).join("eula.txt")).expect("eula.txt");
    assert!(eula.contains("eula=true"));

    let command = build_server_command(&db, &paths, &inputs).expect("command");
    assert!(command.args.contains(&"-jar".to_owned()));
    assert!(command.args.contains(&"nogui".to_owned()));
    assert_eq!(command.cwd, paths.game_dir(id));
}

#[cfg(windows)]
#[test]
fn spawn_records_session_and_exit_code() {
    let (db, paths, _root) = fresh_env();
    let id = make_instance(&db, InstanceKind::Client);
    let command = omni_core::launch::LaunchCommand {
        program: PathBuf::from("cmd"),
        args: vec!["/C".into(), "echo omni && exit 7".into()],
        cwd: paths.game_dir(id),
    };
    let (session, child) = spawn_recorded(&db, &paths, id, &command).expect("spawn");
    assert!(session.pid.is_some());
    let code = wait_and_record(&db, &session, child).expect("wait");
    assert_eq!(code, 7);
    let recorded = db
        .with_store(|store| store.open_processes())
        .expect("open list");
    assert!(recorded.is_empty(), "session must be closed after exit");
    let instance =
        db.with_store(|store| store.instance(id)).expect("query").expect("instance");
    assert!(instance.last_played_at.is_some());
}
