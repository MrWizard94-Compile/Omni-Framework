//! Command/event contract tests for the application service.
//!
//! ARCHITECTURE §8 says of the UI layer: "command/event contract tests; no
//! logic to test in the shell by design." This file is that contract, exercised
//! headlessly — no window, no `WebView`, no network, no real credentials. If the
//! shell ever grows behavior that isn't covered here, the layering has been
//! violated.
#![expect(
    clippy::redundant_closure_for_method_calls,
    reason = "`Db::with_store` takes a closure over `&mut Store<'_>`; clippy's suggested               bare method paths fail HRTB inference (`implementation of FnOnce is not               general enough`). The closures are load-bearing."
)]

use std::sync::{Arc, Mutex};

use serde_json::json;

use omni_core::app::{AppConfig, AppService};
use omni_core::auth::{
    AuthError, AuthHttp, EntitlementRecord, HttpResponse, MemoryVault, TokenSet, TokenVault,
};
use omni_core::clock::FixedClock;
use omni_core::events::CollectingSink;
use omni_core::store::{Ecosystem, InstanceKind, JobState, NewMod, PortStatus};
use omni_core::{CoreEvent, Db};
use omni_meta_client::{DirSource, MetaClient, MetaSource};

const NOW: i64 = 1_800_000_000;

fn golden_v1() -> std::path::PathBuf {
    std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../OmniMeta/generator/tests/golden/v1")
        .canonicalize()
        .expect("golden tree exists")
}

/// An auth transport that fails every call — the offline default for tests
/// that never touch the account surfaces.
struct OfflineHttp;

impl AuthHttp for OfflineHttp {
    fn post_form(&self, url: &str, _form: &[(&str, &str)]) -> Result<HttpResponse, AuthError> {
        Err(AuthError::Http { url: url.to_owned(), message: "offline in tests".into() })
    }

    fn post_json(
        &self,
        url: &str,
        _body: &serde_json::Value,
        _bearer: Option<&str>,
    ) -> Result<HttpResponse, AuthError> {
        Err(AuthError::Http { url: url.to_owned(), message: "offline in tests".into() })
    }

    fn get_bearer(&self, url: &str, _bearer: &str) -> Result<HttpResponse, AuthError> {
        Err(AuthError::Http { url: url.to_owned(), message: "offline in tests".into() })
    }
}

/// A blob source that serves nothing — install jobs in these tests are only
/// driven far enough to prove registration and event flow.
struct EmptyBlobs;

impl omni_core::install::BlobSource for EmptyBlobs {
    fn fetch(&self, url: &str) -> Result<Vec<u8>, omni_core::install::BlobError> {
        Err(omni_core::install::BlobError {
            url: url.to_owned(),
            message: "no blobs in contract tests".into(),
        })
    }
}

struct Harness {
    service: AppService,
    sink: Arc<CollectingSink>,
    vault: Arc<MemoryVault>,
    _data: tempfile::TempDir,
    _cache: tempfile::TempDir,
}

fn harness() -> Harness {
    let data = tempfile::tempdir().expect("tempdir");
    let cache = tempfile::tempdir().expect("tempdir");
    let clock = Arc::new(FixedClock::new(NOW));
    let db = Arc::new(Db::open_in_memory(clock.clone()).expect("db"));
    let sink = Arc::new(CollectingSink::new());
    let source: Arc<dyn MetaSource> = Arc::new(DirSource::new(golden_v1()));
    let meta = Arc::new(MetaClient::new(source, cache.path()).expect("meta"));
    let config = AppConfig {
        data_root: data.path().to_path_buf(),
        meta_url: "dir://golden".to_owned(),
        client_id: omni_core::auth::DEV_CLIENT_ID.to_owned(),
        vault_service: "OmniLauncher-test".to_owned(),
    };
    let vault = Arc::new(MemoryVault::new());
    let service = AppService::with_parts(
        config,
        db,
        clock,
        sink.clone(),
        meta,
        vault.clone(),
        Arc::new(OfflineHttp),
        Arc::new(EmptyBlobs),
    );
    Harness { service, sink, vault, _data: data, _cache: cache }
}

/// Seeds a signed-in account: the store row plus the vault entry the launch
/// gate reads, with an entitlement window ending at `window_expires_at`.
fn seed_account(h: &Harness, uuid: &str, window_expires_at: i64) {
    h.service
        .db()
        .with_store(|store| {
            let account = store.upsert_account(
                uuid,
                "MrWizard94",
                omni_core::store::AccountKind::Microsoft,
                &format!("omni/msa/{uuid}"),
            )?;
            store.set_entitlement_window(account.id, window_expires_at - 86_400, window_expires_at)
        })
        .expect("seed account row");
    h.vault
        .store(
            &format!("omni/msa/{uuid}"),
            &TokenSet {
                msa_refresh_token: "refresh".into(),
                mc_access_token: "mc".into(),
                // Already expired, so the gate must go online (and, offline,
                // fall back to the window) rather than reuse a cached token.
                mc_token_expires_at: NOW - 1,
                entitlement: EntitlementRecord {
                    verified_at: window_expires_at - 86_400,
                    window_expires_at,
                    profile_uuid: uuid.to_owned(),
                },
            },
        )
        .expect("seed vault entry");
}

// ===========================================================================
// Instances
// ===========================================================================

#[test]
fn instance_lifecycle_emits_events_and_projects_dtos() {
    let h = harness();

    assert!(h.service.list_instances().expect("list").is_empty());

    let created = h
        .service
        .create_instance("My Pack", "1.21.1", InstanceKind::Client)
        .expect("create");
    assert_eq!(created.game_version, "1.21.1");
    assert_eq!(created.mod_count, 0);
    assert!(created.loader.is_none());
    assert_eq!(
        h.sink.drain(),
        vec![CoreEvent::InstanceChanged { instance_id: Some(created.id) }],
        "every mutation announces itself"
    );

    // Creation seeds the stack, so the detail surface is immediately useful.
    let detail = h.service.instance_detail(created.id).expect("detail");
    assert_eq!(detail.stack.len(), 1);
    assert_eq!(detail.stack[0].uid, "net.minecraft");
    assert_eq!(detail.stack[0].version, "1.21.1");
    assert!(!detail.server_eula_accepted);

    h.service.rename_instance(created.id, "  Renamed  ").expect("rename");
    h.service
        .update_instance_settings(created.id, &json!({ "memoryMib": 6144 }))
        .expect("settings");
    let detail = h.service.instance_detail(created.id).expect("detail");
    assert_eq!(detail.summary.name, "Renamed", "names are trimmed");
    assert_eq!(detail.settings["memoryMib"], 6144);

    assert!(h.service.delete_instance(created.id).expect("delete"));
    assert!(h.service.list_instances().expect("list").is_empty());
    assert!(
        h.sink.drain().contains(&CoreEvent::InstanceChanged { instance_id: None }),
        "a deletion invalidates the whole list"
    );
}

#[test]
fn instance_commands_validate_input() {
    let h = harness();
    let err = h
        .service
        .create_instance("   ", "1.21.1", InstanceKind::Client)
        .expect_err("blank name refused");
    assert_eq!(err.kind, "usage");

    let created =
        h.service.create_instance("Pack", "1.21.1", InstanceKind::Client).expect("create");
    let err = h.service.rename_instance(created.id, "").expect_err("blank rename refused");
    assert_eq!(err.kind, "usage");
    let err = h
        .service
        .update_instance_settings(created.id, &json!([1, 2, 3]))
        .expect_err("non-object settings refused");
    assert_eq!(err.kind, "usage");

    let err = h.service.instance_detail(9999).expect_err("missing instance");
    assert_eq!(err.kind, "notFound");
    assert!(err.message.contains("9999"), "the message names the row: {}", err.message);
}

#[test]
fn instance_list_orders_recently_played_first() {
    let h = harness();
    let a = h.service.create_instance("Alpha", "1.21.1", InstanceKind::Client).expect("a");
    let b = h.service.create_instance("Beta", "1.21.1", InstanceKind::Client).expect("b");
    h.service
        .db()
        .with_store(|store| store.touch_last_played(b.id))
        .expect("touch");

    let listed = h.service.list_instances().expect("list");
    assert_eq!(listed[0].id, b.id, "played instances float to the top");
    assert_eq!(listed[1].id, a.id);
}

#[test]
fn mod_toggles_project_and_announce() {
    let h = harness();
    let instance =
        h.service.create_instance("Pack", "1.21.1", InstanceKind::Client).expect("create");
    let stored = h
        .service
        .db()
        .with_store(|store| {
            store.upsert_mod(&NewMod {
                instance_id: instance.id,
                file_name: "sodium.jar".into(),
                sha1: None,
                sha512: None,
                murmur2: None,
                display_name: Some("Sodium".into()),
                version: Some("0.6.0".into()),
                ecosystem: Ecosystem::Fabric,
                enabled: true,
                platform: Some("modrinth".into()),
                platform_project_id: None,
                platform_file_id: None,
                port_status: PortStatus::Compat,
            })
        })
        .expect("mod");
    let _ = h.sink.drain();

    let detail = h.service.instance_detail(instance.id).expect("detail");
    assert_eq!(detail.summary.mod_count, 1);
    assert_eq!(detail.mods[0].display_name.as_deref(), Some("Sodium"));
    assert_eq!(detail.mods[0].port_status, PortStatus::Compat);
    assert!(detail.mods[0].enabled);

    h.service.set_mod_enabled(stored.id, false).expect("disable");
    let detail = h.service.instance_detail(instance.id).expect("detail");
    assert!(!detail.mods[0].enabled);
    assert_eq!(
        h.sink.drain(),
        vec![CoreEvent::InstanceChanged { instance_id: Some(instance.id) }]
    );

    assert!(h.service.delete_mod(stored.id).expect("delete"));
    assert_eq!(h.service.instance_detail(instance.id).expect("detail").mods.len(), 0);
}

// ===========================================================================
// Versions (meta)
// ===========================================================================

#[test]
fn available_versions_reads_the_meta_index() {
    let h = harness();
    let all = h.service.available_versions(false).expect("versions");
    assert!(all.iter().any(|v| v.version == "1.21.1"));
    assert!(
        all.iter().find(|v| v.version == "1.21.1").expect("1.21.1").recommended,
        "the golden policy recommends 1.21.1"
    );
    let releases = h.service.available_versions(true).expect("releases");
    assert!(releases.iter().all(|v| v.release_type == "release"));
}

// ===========================================================================
// Jobs
// ===========================================================================

#[test]
fn install_job_registers_runs_and_reports_failure_without_blocking() {
    let h = harness();
    let instance =
        h.service.create_instance("Pack", "1.21.1", InstanceKind::Client).expect("create");
    let job_id = h.service.start_install(instance.id).expect("start install");

    // The command returned immediately with a handle; the job exists.
    let jobs = h.service.list_jobs().expect("jobs");
    assert_eq!(jobs.len(), 1);
    assert_eq!(jobs[0].id, job_id);
    assert_eq!(jobs[0].instance_id, Some(instance.id));

    // The blob source serves nothing, so the runner must fail the job rather
    // than hang or panic — and the failure must be persisted for the UI.
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(30);
    let final_state = loop {
        let job = h
            .service
            .db()
            .with_store(|store| store.job(job_id))
            .expect("query")
            .expect("job");
        if matches!(job.state, JobState::Failed | JobState::Done) {
            break job;
        }
        assert!(std::time::Instant::now() < deadline, "job never settled");
        std::thread::sleep(std::time::Duration::from_millis(20));
    };
    assert_eq!(final_state.state, JobState::Failed);
    let error = final_state.error.expect("failure detail persisted");
    assert!(error["message"].is_string(), "the UI gets a message: {error}");

    // Once the thread is gone the job is no longer marked running.
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
    while h.service.list_jobs().expect("jobs")[0].running {
        assert!(std::time::Instant::now() < deadline, "registry entry never cleared");
        std::thread::sleep(std::time::Duration::from_millis(20));
    }

    // Events reached the sink, including the failure transition.
    let events = h.sink.drain();
    assert!(
        events.iter().any(|event| matches!(
            event,
            CoreEvent::JobState { state: JobState::Failed, .. }
        )),
        "the job center learns about failures from events: {events:?}"
    );
}

/// Regression: the first live install sat at `pending` forever with no error.
///
/// The install thread failed while resolving the component stack (the meta
/// service was unreachable), but `pending → failed` was not a legal transition,
/// so the failure was silently dropped and the job center showed a job that
/// looked like it was about to start. A job whose setup fails must reach
/// `failed` and carry a message, exactly like one that fails mid-run.
#[test]
fn a_job_that_fails_before_running_still_reports_failure() {
    // A meta source that serves nothing, so `resolve_stack` fails before the
    // runner is ever constructed — the live condition, reproduced.
    let data = tempfile::tempdir().expect("tempdir");
    let cache = tempfile::tempdir().expect("tempdir");
    let empty_tree = tempfile::tempdir().expect("tempdir");
    let clock = Arc::new(FixedClock::new(NOW));
    let db = Arc::new(Db::open_in_memory(clock.clone()).expect("db"));
    let sink = Arc::new(CollectingSink::new());
    let source: Arc<dyn MetaSource> = Arc::new(DirSource::new(empty_tree.path()));
    let meta = Arc::new(MetaClient::new(source, cache.path()).expect("meta"));
    let service = AppService::with_parts(
        AppConfig {
            data_root: data.path().to_path_buf(),
            meta_url: "dir://empty".to_owned(),
            client_id: omni_core::auth::DEV_CLIENT_ID.to_owned(),
            vault_service: "OmniLauncher-test".to_owned(),
        },
        db,
        clock,
        sink.clone(),
        meta,
        Arc::new(MemoryVault::new()),
        Arc::new(OfflineHttp),
        Arc::new(EmptyBlobs),
    );

    let instance = service
        .create_instance("Pack", "1.21.1", InstanceKind::Client)
        .expect("create");
    let job_id = service.start_install(instance.id).expect("start install");

    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(30);
    let settled = loop {
        let job = service
            .db()
            .with_store(|store| store.job(job_id))
            .expect("query")
            .expect("job");
        if job.state != JobState::Pending && job.state != JobState::Running {
            break job;
        }
        assert!(
            std::time::Instant::now() < deadline,
            "job never left pending — the failure was swallowed again"
        );
        std::thread::sleep(std::time::Duration::from_millis(20));
    };

    assert_eq!(settled.state, JobState::Failed, "setup failure must surface as failed");
    let error = settled.error.expect("a failed job must say why");
    let message = error["message"].as_str().expect("message text");
    assert!(
        message.contains("meta"),
        "the message must name the cause the user can act on: {message}"
    );
    // (Timestamps are not asserted here: the harness runs on a fixed clock, so
    // created_at and updated_at are equal by construction. The state and the
    // error message are what prove the row was written.)
}

#[test]
fn job_commands_validate_and_report_ownership() {
    let h = harness();
    let err = h.service.resume_job(4242).expect_err("missing job");
    assert_eq!(err.kind, "notFound");

    // Nothing is running, so cancelling is a truthful "no".
    assert!(!h.service.cancel_job(4242));

    let instance =
        h.service.create_instance("Pack", "1.21.1", InstanceKind::Client).expect("create");
    let job = h
        .service
        .db()
        .with_store(|store| store.create_job("install", Some(instance.id)))
        .expect("job");
    h.service
        .db()
        .with_store(|store| {
            store.transition_job(job.id, JobState::Running)?;
            store.complete_job(job.id, &json!({}))
        })
        .expect("complete");
    let err = h.service.resume_job(job.id).expect_err("done jobs are terminal");
    assert_eq!(err.kind, "usage");
    assert!(err.message.contains("done"), "explains why: {}", err.message);
}

/// Clearing history must remove what is finished and keep what is not. The
/// asymmetry matters: a `paused` job is work the user suspended, and losing it
/// to a "clear" button would be the kind of surprise that stops people pressing
/// buttons at all.
#[test]
fn clearing_history_removes_finished_jobs_and_keeps_live_ones() {
    let h = harness();
    let instance =
        h.service.create_instance("Pack", "1.21.1", InstanceKind::Client).expect("create");

    let make = |state: JobState| {
        let job = h
            .service
            .db()
            .with_store(|store| store.create_job("install", Some(instance.id)))
            .expect("job");
        h.service
            .db()
            .with_store(|store| {
                match state {
                    JobState::Pending => {}
                    JobState::Done => {
                        store.transition_job(job.id, JobState::Running)?;
                        store.complete_job(job.id, &json!({}))?;
                    }
                    JobState::Failed => {
                        store.transition_job(job.id, JobState::Running)?;
                        store.fail_job(job.id, &json!({ "message": "boom" }))?;
                    }
                    JobState::Paused => {
                        store.transition_job(job.id, JobState::Running)?;
                        store.transition_job(job.id, JobState::Paused)?;
                    }
                    JobState::Running => {
                        store.transition_job(job.id, JobState::Running)?;
                    }
                }
                Ok(())
            })
            .expect("set state");
        job.id
    };

    let done = make(JobState::Done);
    let failed = make(JobState::Failed);
    let paused = make(JobState::Paused);
    let pending = make(JobState::Pending);
    let running = make(JobState::Running);
    let _ = h.sink.drain();

    let removed = h.service.clear_finished_jobs().expect("clear");
    assert_eq!(removed, 2, "exactly the done and failed jobs");

    let surviving: Vec<i64> = h.service.list_jobs().expect("jobs").iter().map(|j| j.id).collect();
    assert!(!surviving.contains(&done), "finished work is history");
    assert!(!surviving.contains(&failed), "so is a failure the user moved past");
    assert!(surviving.contains(&paused), "suspended work must survive");
    assert!(surviving.contains(&pending), "queued work must survive");
    assert!(surviving.contains(&running), "running work must survive");

    assert!(
        h.sink.drain().contains(&CoreEvent::JobsChanged),
        "the list changed, so the UI is told to refetch"
    );

    // Clearing again removes nothing and, having changed nothing, says nothing.
    let _ = h.sink.drain();
    assert_eq!(h.service.clear_finished_jobs().expect("clear"), 0);
    assert!(
        !h.sink.drain().contains(&CoreEvent::JobsChanged),
        "a no-op must not announce a change"
    );
}

#[test]
fn crash_recovery_is_exposed_as_a_startup_command() {
    let h = harness();
    let instance =
        h.service.create_instance("Pack", "1.21.1", InstanceKind::Client).expect("create");
    let job = h
        .service
        .db()
        .with_store(|store| store.create_job("install", Some(instance.id)))
        .expect("job");
    h.service
        .db()
        .with_store(|store| {
            store.transition_job(job.id, JobState::Running)?;
            store.persist_job_step(job.id, &json!({ "phase": "assets", "done": 4 }), &json!({}))
        })
        .expect("simulate a crash mid-install");

    let recovered = h.service.recover_interrupted_jobs().expect("recover");
    assert_eq!(recovered, vec![job.id]);
    let jobs = h.service.list_jobs().expect("jobs");
    assert_eq!(jobs[0].state, JobState::Paused);
    assert!(!jobs[0].running, "a recovered job is owned by nobody");
}

// ===========================================================================
// Accounts
// ===========================================================================

#[test]
fn account_surface_reports_entitlement_state() {
    let h = harness();
    assert!(h.service.list_accounts().expect("accounts").is_empty());

    h.service
        .db()
        .with_store(|store| {
            let account = store.upsert_account(
                "uuid-1",
                "MrWizard94",
                omni_core::store::AccountKind::Microsoft,
                "omni/msa/uuid-1",
            )?;
            store.set_entitlement_window(account.id, NOW, NOW + 3600)
        })
        .expect("seed account");

    let accounts = h.service.list_accounts().expect("accounts");
    assert_eq!(accounts[0].username, "MrWizard94");
    assert!(accounts[0].entitlement_valid, "window is open at the fixed clock");
    assert_eq!(accounts[0].window_expires_at, Some(NOW + 3600));
}

#[test]
fn device_login_failure_is_typed_as_network_when_offline() {
    let h = harness();
    let err = h.service.start_device_login().expect_err("offline transport");
    assert_eq!(err.kind, "network", "the UI can distinguish 'no internet' from 'declined'");
}

// ===========================================================================
// Launch gating
// ===========================================================================

/// The CPL-2 gate has exactly three outcomes, and the UI branches on the
/// error `kind` to say the right thing. All three are pinned here.
#[test]
fn launch_gate_distinguishes_its_three_outcomes() {
    let h = harness();
    let instance =
        h.service.create_instance("Pack", "1.21.1", InstanceKind::Client).expect("create");
    let _ = h.sink.drain();

    // (1) Unknown account / no stored credentials -> "sign in again", NOT
    // "your offline window expired". The distinction drives which panel the
    // UI shows.
    let err = h.service.launch_instance(instance.id, "nobody").expect_err("no credentials");
    assert_eq!(err.kind, "auth", "got: {err:?}");
    assert!(err.message.contains("sign-in"), "actionable: {}", err.message);

    // (2) Known account, offline, window already expired -> entitlement stop.
    seed_account(&h, "expired-uuid", NOW - 86_400);
    let err = h
        .service
        .launch_instance(instance.id, "expired-uuid")
        .expect_err("expired window");
    assert_eq!(err.kind, "entitlement", "got: {err:?}");

    // (3) Known account, offline, window still open -> the gate PASSES, and
    // the next guard (no installed Java runtime) is what stops the launch.
    seed_account(&h, "valid-uuid", NOW + 7 * 86_400);
    let err = h.service.launch_instance(instance.id, "valid-uuid").expect_err("no runtime");
    assert_eq!(err.kind, "usage", "the gate let this through; got: {err:?}");
    assert!(
        err.message.contains("install job"),
        "tells the user what to do: {}",
        err.message
    );

    // No refused path may start a process.
    assert!(
        !h.sink
            .drain()
            .iter()
            .any(|event| matches!(event, CoreEvent::ProcessStarted { .. })),
        "a refused launch must never spawn anything"
    );
    assert!(
        h.service.db().with_store(|store| store.open_processes()).expect("procs").is_empty()
    );
}

#[test]
fn server_eula_acceptance_is_explicit_and_recorded() {
    let h = harness();
    let instance =
        h.service.create_instance("Server", "1.21.1", InstanceKind::Server).expect("create");
    let detail = h.service.instance_detail(instance.id).expect("detail");
    assert!(!detail.server_eula_accepted, "never accepted implicitly");

    h.service.accept_server_eula(instance.id).expect("accept");
    let detail = h.service.instance_detail(instance.id).expect("detail");
    assert!(detail.server_eula_accepted);
    let eula = std::fs::read_to_string(
        h.service.paths().game_dir(instance.id).join("eula.txt"),
    )
    .expect("eula.txt written");
    assert!(eula.contains("eula=true"));
}

#[test]
fn console_tail_reads_a_recorded_session() {
    let h = harness();
    let instance =
        h.service.create_instance("Pack", "1.21.1", InstanceKind::Client).expect("create");
    let log_path = h.service.paths().logs_dir(instance.id).join("session-test.log");
    std::fs::create_dir_all(log_path.parent().expect("parent")).expect("mkdir");
    std::fs::write(&log_path, "line one\nline two\nline three\n").expect("log");
    let session = h
        .service
        .db()
        .with_store(|store| {
            store.record_process_start(
                instance.id,
                Some(1234),
                &json!({ "log": log_path.to_string_lossy() }),
            )
        })
        .expect("session");

    let tail = h.service.console_tail(session.id, 2).expect("tail");
    assert_eq!(tail.lines, vec!["line two", "line three"], "tail returns the LAST n lines");
    assert!(tail.running, "an unexited session reads as running");

    h.service
        .db()
        .with_store(|store| store.record_process_exit(session.id, 0))
        .expect("exit");
    assert!(!h.service.console_tail(session.id, 10).expect("tail").running);

    let err = h.service.console_tail(9999, 10).expect_err("missing session");
    assert_eq!(err.kind, "notFound");
}

#[test]
fn stopping_an_unowned_process_is_a_truthful_no() {
    let h = harness();
    assert!(!h.service.stop_process(4242));
}

// ===========================================================================
// Import wizard
// ===========================================================================

#[test]
fn import_wizard_detects_enumerates_and_imports() {
    let h = harness();
    let source = tempfile::tempdir().expect("tempdir");
    let root = source.path().join("ATM-ish");
    std::fs::create_dir_all(&root).expect("mkdir");
    std::fs::write(
        root.join("minecraftinstance.json"),
        json!({
            "name": "Imported Pack",
            "gameVersion": "1.21.1",
            "baseModLoader": { "name": "neoforge-21.1.221" },
            "installedAddons": [ {
                "addonID": 1,
                "name": "Some Mod",
                "isEnabled": true,
                "installedFile": { "id": 2, "fileName": "some-mod.jar", "hashes": [] }
            } ]
        })
        .to_string(),
    )
    .expect("manifest");

    let detected = h.service.detect_import(&root.to_string_lossy());
    assert_eq!(detected[0].format, "curseforge");
    assert_eq!(detected[0].confidence, "certain");

    let candidates = h
        .service
        .enumerate_import(&root.to_string_lossy(), "curseforge")
        .expect("enumerate");
    assert_eq!(candidates[0].name, "Imported Pack");
    let _ = h.sink.drain();

    let report = h.service.run_import(&candidates[0]).expect("import");
    assert_eq!(report.mods_imported, 1);
    assert!(
        h.sink.drain().contains(&CoreEvent::InstanceChanged {
            instance_id: Some(report.instance_id)
        }),
        "the grid learns about imported instances"
    );

    // The imported loader is projected for the grid badge.
    let summary = h
        .service
        .list_instances()
        .expect("list")
        .into_iter()
        .find(|i| i.id == report.instance_id)
        .expect("imported instance");
    let loader = summary.loader.expect("loader recorded");
    assert_eq!(loader.kind, "neoforge");
    assert_eq!(loader.version, "21.1.221");
}

#[test]
fn import_commands_reject_unknown_formats() {
    let h = harness();
    let err = h.service.enumerate_import(".", "not-a-format").expect_err("unknown format");
    assert_eq!(err.kind, "usage");
    assert!(err.message.contains("not-a-format"));
}

// ===========================================================================
// Settings + wire contract
// ===========================================================================

#[test]
fn settings_round_trip() {
    let h = harness();
    assert_eq!(h.service.get_setting("theme").expect("get"), None);
    h.service.set_setting("theme", &json!("dark")).expect("set");
    assert_eq!(h.service.get_setting("theme").expect("get"), Some(json!("dark")));
    h.service.set_setting("theme", &json!("light")).expect("overwrite");
    assert_eq!(h.service.get_setting("theme").expect("get"), Some(json!("light")));
}

/// The event enum is the UI's wire contract (documented in COMMANDS.md), so
/// its serialized shape is pinned here: a change that breaks the TypeScript
/// union has to break this test first.
#[test]
fn event_wire_shapes_are_stable() {
    let cases = [
        (
            CoreEvent::JobState { job_id: 7, kind: "install".into(), state: JobState::Running },
            json!({ "JobState": { "job_id": 7, "kind": "install", "state": "running" } }),
        ),
        (
            CoreEvent::JobProgress { job_id: 7, progress: json!({ "phase": "assets" }) },
            json!({ "JobProgress": { "job_id": 7, "progress": { "phase": "assets" } } }),
        ),
        (
            CoreEvent::ProcessStarted { instance_id: 1, session_id: 2, pid: Some(30) },
            json!({ "ProcessStarted": { "instance_id": 1, "session_id": 2, "pid": 30 } }),
        ),
        (
            CoreEvent::ProcessExited { instance_id: 1, session_id: 2, exit_code: 0 },
            json!({ "ProcessExited": { "instance_id": 1, "session_id": 2, "exit_code": 0 } }),
        ),
        (
            CoreEvent::InstanceChanged { instance_id: Some(5) },
            json!({ "InstanceChanged": { "instance_id": 5 } }),
        ),
        (CoreEvent::JobsChanged, json!("JobsChanged")),
        (CoreEvent::AccountsChanged, json!("AccountsChanged")),
        (
            CoreEvent::LoginFinished { uuid: Some("u".into()), error: None },
            json!({ "LoginFinished": { "uuid": "u", "error": null } }),
        ),
    ];
    for (event, expected) in cases {
        let encoded = serde_json::to_value(&event).expect("serialize");
        assert_eq!(encoded, expected, "wire shape changed for {event:?}");
        let decoded: CoreEvent = serde_json::from_value(encoded).expect("round trip");
        assert_eq!(decoded, event);
    }
}

/// DTO field names are the other half of the contract: the TypeScript
/// definitions transcribe these exactly.
#[test]
fn dto_wire_shapes_are_camel_case() {
    let h = harness();
    let created =
        h.service.create_instance("Pack", "1.21.1", InstanceKind::Client).expect("create");
    let encoded = serde_json::to_value(&created).expect("serialize");
    for key in ["id", "name", "kind", "gameVersion", "createdAt", "lastPlayedAt", "modCount"] {
        assert!(encoded.get(key).is_some(), "summary is missing {key}: {encoded}");
    }
    assert_eq!(encoded["kind"], "client");

    let detail = h.service.instance_detail(created.id).expect("detail");
    let encoded = serde_json::to_value(&detail).expect("serialize");
    for key in ["summary", "notes", "settings", "stack", "mods", "serverEulaAccepted"] {
        assert!(encoded.get(key).is_some(), "detail is missing {key}: {encoded}");
    }

    let error = h.service.instance_detail(9999).expect_err("missing");
    let encoded = serde_json::to_value(&error).expect("serialize");
    assert_eq!(encoded["kind"], "notFound");
    assert!(encoded["message"].is_string());
}

/// Concurrency guard: the service is shared across Tauri's command threads,
/// so it must be usable from several at once without interior corruption.
#[test]
fn service_is_usable_from_many_threads() {
    let h = harness();
    let service = Arc::new(h.service);
    let errors: Arc<Mutex<Vec<String>>> = Arc::new(Mutex::new(Vec::new()));
    let mut handles = Vec::new();
    for index in 0..8 {
        let service = Arc::clone(&service);
        let errors = Arc::clone(&errors);
        handles.push(std::thread::spawn(move || {
            let outcome = service
                .create_instance(&format!("Pack {index}"), "1.21.1", InstanceKind::Client)
                .and_then(|summary| service.instance_detail(summary.id))
                .and_then(|detail| {
                    service.set_setting(&format!("k{index}"), &json!(index)).map(|()| detail)
                });
            if let Err(err) = outcome {
                errors.lock().expect("errors").push(err.to_string());
            }
        }));
    }
    for handle in handles {
        handle.join().expect("thread");
    }
    assert!(errors.lock().expect("errors").is_empty(), "{:?}", errors.lock().expect("errors"));
    assert_eq!(service.list_instances().expect("list").len(), 8);
}
