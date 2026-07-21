//! Every command the UI can issue, grouped by surface (ARCHITECTURE §7).
//!
//! Contract rules that hold for **all** of them, so the shell and the UI never
//! have to reason about exceptions:
//!
//! 1. **Commands return quickly.** Anything long-running (install, sign-in,
//!    game process) registers work and returns a handle; progress arrives as
//!    [`CoreEvent`]s.
//! 2. **Commands never partially mutate.** Multi-row changes go through store
//!    transactions, so a failure leaves the previous state intact.
//! 3. **Every mutation emits an event.** The UI refetches on the event rather
//!    than patching its local copy — the only way "the UI holds no truth" can
//!    actually hold.
//! 4. **Errors are typed** ([`AppError`]) with a machine `kind` and a human
//!    `message` that is safe to render verbatim.
#![expect(
    clippy::redundant_closure_for_method_calls,
    reason = "`Db::with_store` takes a closure over `&mut Store<'_>`; clippy's suggested               bare method paths fail HRTB inference (`implementation of FnOnce is not               general enough`), verified 2026-07-19. The closures are load-bearing."
)]

use std::path::{Path, PathBuf};
use std::sync::Arc;

use crate::app::dto::{account_dto, job_dto, summary_of};
use crate::app::{
    AccountDto, AppError, AppService, ConsoleTail, DetectedFormat, DeviceCodeDto, ExportReportDto,
    ImportCandidateDto, ImportReportDto, InstanceDetail, InstanceSummary, JobDto, ModDto,
    SkippedDto, VersionOption,
};
use crate::auth::{AuthService, LaunchAuth};
use crate::events::CoreEvent;
use crate::export::{ExportFormat, ExportInput, export_instance};
use crate::import::{
    Candidate, Confidence, CurseForgeImporter, ImportFormat, Importer, MrpackImporter,
    OfficialImporter, PrismImporter, detect_all,
};
use crate::install::{
    InstallExecutor, InstallSpec, ResolvedStack, java_platform_key, resolve_stack,
    select_java_offer,
};
use crate::jobs::{CancelToken, run};
use crate::launch::{
    LaunchInputs, LoaderRuntime, accept_server_eula, build_client_command, build_loader_command,
    build_server_command, spawn_recorded, wait_and_record,
};
use crate::platform::Platform;
use crate::store::{ComponentSource, InstanceKind, JobState, NewInstance, StackEntryInput};

/// Default heap for a new instance when settings say nothing (MiB).
const DEFAULT_MEMORY_MIB: u64 = 4096;

/// Resolves the heap size to launch with from an instance's settings.
///
/// The setting is user intent, and "no intent" has two spellings that must
/// behave identically: the key being absent, and the key being present as `0`
/// — the latter is how imported packs carry "no per-instance override" (see
/// the importer). Both yield the default. A stored `0` reaching the JVM as
/// `-Xmx0M` is what crashed the first imported pack on 2026-07-20; this maps it
/// back to the default so instances imported before that fix still launch,
/// rather than needing a manual settings edit.
///
/// A positive value is respected as given, including large ones — a 10 GB
/// modpack heap is a legitimate choice, so this is deliberately not capped
/// here. The hard floor below which no JVM will start lives at the argument
/// builder (`build_client_command`), the one place that must never emit an
/// impossible heap regardless of how the value arrived.
fn resolve_memory_mib(settings: &serde_json::Value) -> u64 {
    settings["memoryMib"].as_u64().filter(|&mib| mib > 0).unwrap_or(DEFAULT_MEMORY_MIB)
}

/// Console tail cap, so a runaway log can never balloon an IPC message.
const MAX_CONSOLE_LINES: usize = 2000;

/// The mod loader an instance is managed by, if it is not vanilla.
///
/// An imported pack records its loader under `importedLoader.kind` (`neoforge`, `fabric`,
/// `forge`). That is what decides whether a launch boots through OmniLoader or runs the game
/// directly. A `vanilla` kind, or none at all, is a direct launch.
fn loader_managed_kind(settings: &serde_json::Value) -> Option<String> {
    settings["importedLoader"]["kind"]
        .as_str()
        .filter(|kind| !kind.eq_ignore_ascii_case("vanilla"))
        .map(str::to_owned)
}

/// Resolves the OmniLoader runtime for an instance from the staged distribution.
///
/// Three outcomes, each distinct on purpose:
/// - `Ok(None)` — the instance is vanilla; launch directly.
/// - `Ok(Some(_))` — loader-managed, and a usable distribution was found.
/// - `Err(_)` — loader-managed but the runtime is missing or malformed. This is a refusal, not a
///   fallback: silently running a modded pack as vanilla is the exact "where did my mods go"
///   failure this whole path exists to end. The message names the loader and what to set.
fn resolve_loader_runtime(
    settings: &serde_json::Value,
    dist_dir: Option<&Path>,
) -> Result<Option<LoaderRuntime>, AppError> {
    let Some(kind) = loader_managed_kind(settings) else {
        return Ok(None);
    };
    let dist = dist_dir.ok_or_else(|| {
        AppError::usage(format!(
            "this instance uses the {kind} mod loader, but no OmniLoader runtime is configured. \
             Set OMNI_LOADER_DIST to the staged loader distribution \
             (OmniLoader/loader-bootstrap/build/loader-dist)."
        ))
    })?;

    let manifest_path = dist.join("loader.json");
    let manifest_text = std::fs::read_to_string(&manifest_path).map_err(|err| {
        AppError::new(
            "usage",
            format!(
                "the OmniLoader runtime at {} has no readable loader.json ({err}); \
                 build it with `gradlew :loader-bootstrap:loaderDist`.",
                dist.display()
            ),
        )
    })?;
    let manifest: serde_json::Value = serde_json::from_str(&manifest_text)
        .map_err(|err| AppError::new("usage", format!("loader.json is not valid JSON: {err}")))?;
    let main_class = manifest["mainClass"]
        .as_str()
        .filter(|name| !name.is_empty())
        .ok_or_else(|| AppError::new("usage", "loader.json has no 'mainClass'".to_owned()))?
        .to_owned();

    let lib_dir = dist.join("lib");
    let mut classpath: Vec<PathBuf> = Vec::new();
    let entries = std::fs::read_dir(&lib_dir).map_err(|err| {
        AppError::new(
            "usage",
            format!("the OmniLoader runtime has no readable lib/ at {} ({err})", lib_dir.display()),
        )
    })?;
    for entry in entries {
        let path = entry.map_err(|err| AppError::new("usage", err.to_string()))?.path();
        if path.extension().and_then(|ext| ext.to_str()) == Some("jar") {
            classpath.push(path);
        }
    }
    if classpath.is_empty() {
        return Err(AppError::new(
            "usage",
            format!("the OmniLoader runtime at {} has no jars in lib/", dist.display()),
        ));
    }
    // Deterministic order so the launch command is stable between runs.
    classpath.sort();
    Ok(Some(LoaderRuntime { main_class, classpath }))
}

// ===========================================================================
// Instances
// ===========================================================================

impl AppService {
    /// Every instance, newest-played first then by name — the grid's order.
    ///
    /// # Errors
    ///
    /// [`AppError`] on store failure.
    pub fn list_instances(&self) -> Result<Vec<InstanceSummary>, AppError> {
        let summaries = self.db.with_store(|store| {
            let instances = store.instances()?;
            let mut out = Vec::with_capacity(instances.len());
            for instance in instances {
                let mod_count = store.mods_for_instance(instance.id)?.len();
                out.push(summary_of(&instance, mod_count));
            }
            Ok(out)
        })?;
        let mut summaries = summaries;
        summaries.sort_by(|a, b| {
            b.last_played_at
                .cmp(&a.last_played_at)
                .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
        });
        Ok(summaries)
    }

    /// One instance with its stack, mods, settings, and EULA state.
    ///
    /// # Errors
    ///
    /// [`AppError`] with kind `notFound` when the instance does not exist.
    pub fn instance_detail(&self, instance_id: i64) -> Result<InstanceDetail, AppError> {
        let detail = self.db.with_store(|store| {
            let instance = store.instance(instance_id)?.ok_or(crate::CoreError::NotFound {
                what: "instance",
                id: instance_id,
            })?;
            let mods: Vec<ModDto> =
                store.mods_for_instance(instance_id)?.into_iter().map(ModDto::from).collect();
            let stack = store
                .component_stack(instance_id)?
                .into_iter()
                .map(Into::into)
                .collect();
            let server_eula_accepted =
                instance.settings[crate::launch::EULA_SETTINGS_KEY].is_number();
            Ok(InstanceDetail {
                summary: summary_of(&instance, mods.len()),
                notes: instance.notes.clone(),
                settings: instance.settings.clone(),
                stack,
                mods,
                server_eula_accepted,
            })
        })?;
        Ok(detail)
    }

    /// Creates an instance and seeds its component stack with the game
    /// version. The install job is a separate, explicit step
    /// ([`AppService::start_install`]) so the UI can show it in the job center.
    ///
    /// # Errors
    ///
    /// [`AppError`] with kind `usage` for an empty name, or on store failure.
    pub fn create_instance(
        &self,
        name: &str,
        game_version: &str,
        kind: InstanceKind,
    ) -> Result<InstanceSummary, AppError> {
        if name.trim().is_empty() {
            return Err(AppError::usage("an instance needs a name"));
        }
        let summary = self.db.with_store(|store| {
            let instance = store.create_instance(&NewInstance {
                name: name.trim().to_owned(),
                icon: None,
                kind,
                game_version: game_version.to_owned(),
            })?;
            store.set_component_stack(
                instance.id,
                &[StackEntryInput {
                    uid: "net.minecraft".to_owned(),
                    version: game_version.to_owned(),
                    pinned: false,
                    source: ComponentSource::Meta,
                    custom: None,
                }],
            )?;
            Ok(summary_of(&instance, 0))
        })?;
        self.emit(CoreEvent::InstanceChanged { instance_id: Some(summary.id) });
        Ok(summary)
    }

    /// Renames an instance.
    ///
    /// # Errors
    ///
    /// [`AppError`] `usage` for an empty name, `notFound` for a missing row.
    pub fn rename_instance(&self, instance_id: i64, name: &str) -> Result<(), AppError> {
        if name.trim().is_empty() {
            return Err(AppError::usage("an instance needs a name"));
        }
        self.db.with_store(|store| store.rename_instance(instance_id, name.trim()))?;
        self.emit(CoreEvent::InstanceChanged { instance_id: Some(instance_id) });
        Ok(())
    }

    /// Replaces an instance's settings document.
    ///
    /// # Errors
    ///
    /// [`AppError`] `usage` when the document is not a JSON object,
    /// `notFound` for a missing row.
    pub fn update_instance_settings(
        &self,
        instance_id: i64,
        settings: &serde_json::Value,
    ) -> Result<(), AppError> {
        if !settings.is_object() {
            return Err(AppError::usage("settings must be a JSON object"));
        }
        self.db.with_store(|store| store.update_instance_settings(instance_id, settings))?;
        self.emit(CoreEvent::InstanceChanged { instance_id: Some(instance_id) });
        Ok(())
    }

    /// Deletes an instance row (mods, stack, and process history cascade).
    /// Files on disk are left alone — deleting user worlds is never a side
    /// effect of a database operation.
    ///
    /// # Errors
    ///
    /// [`AppError`] on store failure.
    pub fn delete_instance(&self, instance_id: i64) -> Result<bool, AppError> {
        let removed = self.db.with_store(|store| store.delete_instance(instance_id))?;
        if removed {
            self.emit(CoreEvent::InstanceChanged { instance_id: None });
        }
        Ok(removed)
    }

    /// Enables or disables one mod entity.
    ///
    /// # Errors
    ///
    /// [`AppError`] `notFound` when the mod does not exist.
    pub fn set_mod_enabled(&self, mod_id: i64, enabled: bool) -> Result<(), AppError> {
        let instance_id = self.db.with_store(|store| {
            store.set_mod_enabled(mod_id, enabled)?;
            Ok(store.mod_by_id(mod_id)?.map(|m| m.instance_id))
        })?;
        self.emit(CoreEvent::InstanceChanged { instance_id });
        Ok(())
    }

    /// Removes one mod entity from an instance.
    ///
    /// # Errors
    ///
    /// [`AppError`] on store failure.
    pub fn delete_mod(&self, mod_id: i64) -> Result<bool, AppError> {
        let (removed, instance_id) = self.db.with_store(|store| {
            let instance_id = store.mod_by_id(mod_id)?.map(|m| m.instance_id);
            Ok((store.delete_mod(mod_id)?, instance_id))
        })?;
        if removed {
            self.emit(CoreEvent::InstanceChanged { instance_id });
        }
        Ok(removed)
    }
}

// ===========================================================================
// Versions (meta)
// ===========================================================================

impl AppService {
    /// Selectable game versions from the meta service, newest first.
    /// `releases_only` hides snapshots and the historical channels.
    ///
    /// # Errors
    ///
    /// [`AppError`] with kind `network` when the meta service is unreachable
    /// and nothing is cached.
    pub fn available_versions(
        &self,
        releases_only: bool,
    ) -> Result<Vec<VersionOption>, AppError> {
        let (root, _freshness) =
            self.meta.root().map_err(|err| AppError::new("network", err.to_string()))?;
        let index = self
            .meta
            .component_index(&root, "net.minecraft")
            .map_err(|err| AppError::new("network", err.to_string()))?;
        Ok(index
            .versions
            .iter()
            .filter(|summary| !releases_only || summary.release_type == "release")
            .map(|summary| VersionOption {
                version: summary.version.clone(),
                release_type: summary.release_type.clone(),
                recommended: summary.recommended,
                release_time: summary.release_time.clone(),
            })
            .collect())
    }

    /// Resolves an instance's stack (and the java catalog) through the meta
    /// client — shared by install and launch.
    fn resolve(&self, instance_id: i64) -> Result<ResolvedStack, AppError> {
        let stack = self.db.with_store(|store| store.component_stack(instance_id))?;
        if stack.is_empty() {
            return Err(AppError::usage(
                "this instance has no component stack; re-create it or re-run the import",
            ));
        }
        Ok(resolve_stack(self.meta.as_ref(), &stack)?)
    }
}

// ===========================================================================
// Jobs
// ===========================================================================

impl AppService {
    /// Every job, newest first — the job center's list.
    ///
    /// # Errors
    ///
    /// [`AppError`] on store failure.
    pub fn list_jobs(&self) -> Result<Vec<JobDto>, AppError> {
        let mut jobs = Vec::new();
        for state in [
            JobState::Running,
            JobState::Pending,
            JobState::Paused,
            JobState::Failed,
            JobState::Done,
        ] {
            jobs.extend(self.db.with_store(|store| store.jobs_in_state(state))?);
        }
        jobs.sort_by_key(|job| std::cmp::Reverse(job.id));
        let running = self.running_jobs.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        Ok(jobs
            .into_iter()
            .map(|job| {
                let is_running = running.contains_key(&job.id);
                job_dto(job, is_running)
            })
            .collect())
    }

    /// Creates an install job for an instance and starts running it in the
    /// background. Returns the job id immediately.
    ///
    /// # Errors
    ///
    /// [`AppError`] `notFound` for a missing instance, or on store failure.
    ///
    /// # Panics
    ///
    /// If the OS refuses to create a thread — an environment failure with no
    /// in-app remedy (the alternative would be silently doing nothing).
    pub fn start_install(&self, instance_id: i64) -> Result<i64, AppError> {
        let kind = self
            .db
            .with_store(|store| store.instance(instance_id))?
            .ok_or_else(|| AppError::new("notFound", format!("instance {instance_id} not found")))?
            .kind;
        let job = self.db.with_store(|store| store.create_job("install", Some(instance_id)))?;
        self.spawn_job(job.id, instance_id, kind == InstanceKind::Server);
        Ok(job.id)
    }

    /// Resumes a paused or failed job (the job center's retry button).
    ///
    /// # Errors
    ///
    /// [`AppError`] `usage` when the job is not resumable or is already
    /// running here, `notFound` when it does not exist.
    ///
    /// # Panics
    ///
    /// If the OS refuses to create a thread — an environment failure with no
    /// in-app remedy (the alternative would be silently doing nothing).
    pub fn resume_job(&self, job_id: i64) -> Result<(), AppError> {
        let job = self
            .db
            .with_store(|store| store.job(job_id))?
            .ok_or_else(|| AppError::new("notFound", format!("job {job_id} not found")))?;
        if self
            .running_jobs
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .contains_key(&job_id)
        {
            return Err(AppError::usage("that job is already running"));
        }
        if !matches!(job.state, JobState::Pending | JobState::Paused | JobState::Failed) {
            return Err(AppError::usage(format!(
                "a {} job cannot be resumed",
                job.state
            )));
        }
        let instance_id = job
            .instance_id
            .ok_or_else(|| AppError::usage("that job is not attached to an instance"))?;
        let kind = self
            .db
            .with_store(|store| store.instance(instance_id))?
            .ok_or_else(|| AppError::new("notFound", format!("instance {instance_id} not found")))?
            .kind;
        self.spawn_job(job.id, instance_id, kind == InstanceKind::Server);
        Ok(())
    }

    /// Requests a cooperative pause of a running job. Returns whether a
    /// runner in this process owned it.
    pub fn cancel_job(&self, job_id: i64) -> bool {
        let running = self.running_jobs.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if let Some(token) = running.get(&job_id) {
            token.cancel();
            true
        } else {
            false
        }
    }

    /// Clears finished jobs (`done` and `failed`) from the history, returning
    /// how many were removed.
    ///
    /// Work in progress is never touched — `pending`, `paused`, and `running`
    /// jobs survive, because those are things the user has not finished with.
    /// Clearing a failed job is not lossy: its downloaded files live in the
    /// content-addressed cache, so a fresh install resumes from exactly the same
    /// point.
    ///
    /// # Errors
    ///
    /// [`AppError`] on store failure.
    pub fn clear_finished_jobs(&self) -> Result<usize, AppError> {
        let removed = self.db.with_store(|store| store.delete_finished_jobs())?;
        if removed > 0 {
            self.emit(CoreEvent::JobsChanged);
        }
        Ok(removed)
    }

    /// Startup duty: any job still marked `running` was owned by a process
    /// that died, so it becomes `paused` at its persisted resume point.
    ///
    /// # Errors
    ///
    /// [`AppError`] on store failure.
    pub fn recover_interrupted_jobs(&self) -> Result<Vec<i64>, AppError> {
        let ids = self.db.with_store(|store| store.recover_interrupted_jobs())?;
        Ok(ids)
    }

    /// Spawns the runner thread for an install job: resolve the stack, run to
    /// completion/pause/failure, then deregister.
    fn spawn_job(&self, job_id: i64, instance_id: i64, server: bool) {
        let token = CancelToken::new();
        self.running_jobs
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .insert(job_id, token.clone());

        let db = Arc::clone(&self.db);
        let paths = self.paths.clone();
        let blobs = Arc::clone(&self.blobs);
        let sink = Arc::clone(&self.sink);
        let meta = Arc::clone(&self.meta);
        // The registry entry is removed by the thread itself, however it ends.
        let service_jobs = Arc::clone(&self.running_jobs);
        std::thread::Builder::new()
            .name(format!("omni-install-{job_id}"))
            .spawn(move || {
                let outcome = (|| {
                    let stack = db.with_store(|store| store.component_stack(instance_id))?;
                    let resolved = resolve_stack(meta.as_ref(), &stack)?;
                    let mut executor = InstallExecutor::new(
                        &db,
                        &paths,
                        blobs.as_ref(),
                        InstallSpec { instance_id, server, stack: resolved },
                    );
                    run(&db, job_id, &mut executor, sink.as_ref(), &token)
                })();
                if let Err(err) = outcome {
                    // A failure anywhere in the thread — resolving the stack,
                    // constructing the executor, or inside the runner — must land
                    // on the job row. Anything else leaves the job center showing
                    // a state the job is not actually in.
                    let detail = serde_json::json!({
                        "message": err.to_string(),
                        "retryable": true,
                    });
                    let _ = db.with_store(|store| {
                        if let Some(job) = store.job(job_id)?
                            && job.state.can_transition_to(JobState::Failed)
                        {
                            store.fail_job(job_id, &detail)?;
                        }
                        Ok(())
                    });
                    sink.emit(CoreEvent::JobState {
                        job_id,
                        kind: "install".to_owned(),
                        state: JobState::Failed,
                    });
                }
                service_jobs
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner)
                    .remove(&job_id);
                sink.emit(CoreEvent::InstanceChanged { instance_id: Some(instance_id) });
            })
            .expect("spawning a named thread cannot fail on supported platforms");
    }
}

// ===========================================================================
// Accounts (LNC-4 surfaces)
// ===========================================================================

impl AppService {
    /// Every signed-in account with its entitlement state.
    ///
    /// # Errors
    ///
    /// [`AppError`] on store failure.
    pub fn list_accounts(&self) -> Result<Vec<AccountDto>, AppError> {
        let now = self.now();
        let accounts = self.db.with_store(|store| store.accounts())?;
        Ok(accounts.iter().map(|account| account_dto(account, now)).collect())
    }

    /// Starts a device-code sign-in: returns the prompt to display
    /// immediately and completes the chain on a background thread, emitting
    /// [`CoreEvent::LoginFinished`] and [`CoreEvent::AccountsChanged`].
    ///
    /// The launcher never sees a password — the user consents in Microsoft's
    /// own surfaces (COMPLIANCE §2).
    ///
    /// # Errors
    ///
    /// [`AppError`] `network` when the device-code request itself fails.
    ///
    /// # Panics
    ///
    /// If the OS refuses to create a thread — an environment failure with no
    /// in-app remedy (the alternative would be silently doing nothing).
    pub fn start_device_login(&self) -> Result<DeviceCodeDto, AppError> {
        let auth = AuthService::new(self.http.as_ref(), self.vault.as_ref(), &self.config.client_id);
        let prompt = crate::auth::device_code_begin(self.http.as_ref(), &self.config.client_id)?;
        let dto = DeviceCodeDto {
            verification_uri: prompt.verification_uri.clone(),
            user_code: prompt.user_code.clone(),
            expires_in_secs: prompt.expires_in.as_secs(),
        };
        drop(auth);

        let db = Arc::clone(&self.db);
        let sink = Arc::clone(&self.sink);
        let http = Arc::clone(&self.http);
        let vault = Arc::clone(&self.vault);
        let client_id = self.config.client_id.clone();
        std::thread::Builder::new()
            .name("omni-device-login".to_owned())
            .spawn(move || {
                let auth = AuthService::new(http.as_ref(), vault.as_ref(), client_id.clone());
                let outcome = crate::auth::device_code_poll(
                    http.as_ref(),
                    &client_id,
                    &prompt,
                    &|delay| std::thread::sleep(delay),
                )
                .and_then(|tokens| auth.complete_login(&db, &tokens));
                match outcome {
                    Ok(session) => {
                        sink.emit(CoreEvent::LoginFinished {
                            uuid: Some(session.profile.uuid),
                            error: None,
                        });
                    }
                    Err(err) => {
                        sink.emit(CoreEvent::LoginFinished {
                            uuid: None,
                            error: Some(err.to_string()),
                        });
                    }
                }
                sink.emit(CoreEvent::AccountsChanged);
            })
            .expect("spawning a named thread cannot fail on supported platforms");
        Ok(dto)
    }

    /// Signs an account out: vault entry deleted, account row removed.
    ///
    /// # Errors
    ///
    /// [`AppError`] on vault or store failure.
    pub fn sign_out(&self, uuid: &str) -> Result<(), AppError> {
        let auth = AuthService::new(self.http.as_ref(), self.vault.as_ref(), &self.config.client_id);
        auth.sign_out(&self.db, uuid)?;
        self.emit(CoreEvent::AccountsChanged);
        Ok(())
    }
}

// ===========================================================================
// Launch & console (LNC-5, D8)
// ===========================================================================

impl AppService {
    /// Records explicit acceptance of the Minecraft server EULA for an
    /// instance and writes `eula.txt`. Only ever called from a direct user
    /// action in the UI's acceptance dialog.
    ///
    /// # Errors
    ///
    /// [`AppError`] `notFound` for a missing instance, or on I/O failure.
    pub fn accept_server_eula(&self, instance_id: i64) -> Result<(), AppError> {
        accept_server_eula(&self.db, &self.paths, instance_id)?;
        self.emit(CoreEvent::InstanceChanged { instance_id: Some(instance_id) });
        Ok(())
    }

    /// Launches an instance and supervises the process.
    /// Returns the `processes` row id (the console/stop handle).
    ///
    /// **Client:** CPL-2 gate (online verification or offline window).
    /// **Server:** no MSA — EULA acceptance only (D8 / COMPLIANCE §1: ownership
    /// gates *play*, not spawning an official server jar).
    ///
    /// # Errors
    ///
    /// [`AppError`] `entitlement`/`auth` when the client gate refuses, `notFound`
    /// for missing rows, `usage` when the server EULA has not been accepted or
    /// Java is missing.
    ///
    /// # Panics
    ///
    /// If the OS refuses to create a thread — an environment failure with no
    /// in-app remedy (the alternative would be silently doing nothing).
    pub fn launch_instance(
        &self,
        instance_id: i64,
        account_uuid: &str,
    ) -> Result<i64, AppError> {
        // Ensure imported / dropped jars are in game/mods before we build the
        // command — also refreshes enablement and may auto-set importedLoader
        // when jars exist on a previously vanilla instance.
        let _stage =
            crate::import::materialize_instance_mods(&self.paths, &self.db, instance_id)?;
        // Re-read after materialize (settings may have gained importedLoader).
        let instance = self
            .db
            .with_store(|store| store.instance(instance_id))?
            .ok_or_else(|| AppError::new("notFound", format!("instance {instance_id} not found")))?;
        let auth = self.authorize_launch(&instance, account_uuid)?;
        let command = self.build_launch_command(&instance, &auth)?;
        self.spawn_and_supervise(instance_id, &command)
    }

    /// Client → CPL-2; server → dedicated (no account).
    fn authorize_launch(
        &self,
        instance: &crate::store::Instance,
        account_uuid: &str,
    ) -> Result<LaunchAuth, AppError> {
        match instance.kind {
            InstanceKind::Server => Ok(LaunchAuth::DedicatedServer),
            InstanceKind::Client => {
                if account_uuid.is_empty() {
                    return Err(AppError::new(
                        "auth",
                        "sign in with a Microsoft account that owns Minecraft before launching",
                    ));
                }
                let auth_service = AuthService::new(
                    self.http.as_ref(),
                    self.vault.as_ref(),
                    &self.config.client_id,
                );
                Ok(auth_service.launch_gate(&self.db, account_uuid)?)
            }
        }
    }

    /// Resolves stack/Java and builds the process command for this instance kind.
    fn build_launch_command(
        &self,
        instance: &crate::store::Instance,
        auth: &LaunchAuth,
    ) -> Result<crate::launch::LaunchCommand, AppError> {
        let stack = self.resolve(instance.id)?;
        let game = stack.game()?;
        let component = game
            .java_version
            .as_ref()
            .map_or("java-runtime-delta", |jv| jv.component.as_str());
        let platform = Platform::current();
        let java_refs: Vec<&omni_meta_format::ComponentPayload> =
            stack.java_payloads.iter().collect();
        let (java_version, _, _) =
            select_java_offer(&java_refs, component, java_platform_key(&platform))?;
        let java_exe = self.paths.java_exe(component, &java_version);
        if !java_exe.exists() {
            return Err(AppError::usage(
                "the Java runtime for this instance is not installed yet — run the \
                 install job first",
            ));
        }
        let memory_mib = resolve_memory_mib(&instance.settings);
        let extra_jvm_args: Vec<String> = instance.settings["jvmArgs"]
            .as_array()
            .map(|args| {
                args.iter().filter_map(|arg| arg.as_str().map(str::to_owned)).collect()
            })
            .unwrap_or_default();
        let inputs = LaunchInputs {
            instance,
            stack: &stack,
            auth,
            java_exe,
            memory_mib,
            features: std::collections::BTreeMap::new(),
            extra_jvm_args,
        };
        match instance.kind {
            InstanceKind::Client => {
                match resolve_loader_runtime(
                    &instance.settings,
                    self.config.loader_dist_dir.as_deref(),
                )? {
                    Some(loader) => {
                        Ok(build_loader_command(&self.paths, &inputs, &platform, &loader)?)
                    }
                    None => Ok(build_client_command(&self.paths, &inputs, &platform)?),
                }
            }
            InstanceKind::Server => build_server_command(&self.db, &self.paths, &inputs).map_err(
                |err| match err {
                    crate::CoreError::NotFound { what: "server EULA acceptance", .. } => {
                        AppError::usage(
                            "accept the Minecraft server EULA for this instance before \
                             starting it",
                        )
                    }
                    other => AppError::from(other),
                },
            ),
        }
    }

    /// Spawns the process, records the session, and waits off-thread.
    fn spawn_and_supervise(
        &self,
        instance_id: i64,
        command: &crate::launch::LaunchCommand,
    ) -> Result<i64, AppError> {
        let (session, child) = spawn_recorded(&self.db, &self.paths, instance_id, command)?;
        self.emit(CoreEvent::ProcessStarted {
            instance_id,
            session_id: session.id,
            pid: session.pid,
        });
        let db = Arc::clone(&self.db);
        let sink = Arc::clone(&self.sink);
        let processes = Arc::clone(&self.processes);
        let session_id = session.id;
        std::thread::Builder::new()
            .name(format!("omni-process-{session_id}"))
            .spawn(move || {
                let child = processes
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner)
                    .remove(&session_id);
                let exit_code = match child {
                    Some(child) => wait_and_record(&db, &session, child).unwrap_or(-1),
                    None => -1,
                };
                sink.emit(CoreEvent::ProcessExited { instance_id, session_id, exit_code });
                sink.emit(CoreEvent::InstanceChanged { instance_id: Some(instance_id) });
            })
            .expect("spawning a named thread cannot fail on supported platforms");
        self.processes
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .insert(session_id, child);
        Ok(session_id)
    }

    /// Stops a supervised process (the console's stop button). Returns
    /// whether a process in this app owned it.
    pub fn stop_process(&self, session_id: i64) -> bool {
        let child = self
            .processes
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .remove(&session_id);
        match child {
            Some(mut child) => {
                let killed = child.kill().is_ok();
                // Reap so the OS handle is released; the supervisor thread has
                // already given up ownership, so record the exit here.
                let code = child.wait().ok().and_then(|status| status.code()).unwrap_or(-1);
                let _ = self
                    .db
                    .with_store(|store| store.record_process_exit(session_id, i64::from(code)));
                killed
            }
            None => false,
        }
    }

    /// The last lines of a session's captured log (the server console and the
    /// client crash view). `max_lines` is capped at 2000.
    ///
    /// # Errors
    ///
    /// [`AppError`] `notFound` when the session or its log is unknown.
    pub fn console_tail(&self, session_id: i64, max_lines: usize) -> Result<ConsoleTail, AppError> {
        let session = self
            .db
            .with_store(|store| store.process_session(session_id))?
            .ok_or_else(|| {
                AppError::new("notFound", format!("session {session_id} not found"))
            })?;
        let running = session.ended_at.is_none();
        let log_path = session.session["log"]
            .as_str()
            .map(PathBuf::from)
            .ok_or_else(|| AppError::new("notFound", "this session recorded no log file"))?;
        let text = std::fs::read_to_string(&log_path).unwrap_or_default();
        let take = max_lines.min(MAX_CONSOLE_LINES);
        let all: Vec<&str> = text.lines().collect();
        let start = all.len().saturating_sub(take);
        Ok(ConsoleTail {
            path: log_path.to_string_lossy().into_owned(),
            lines: all[start..].iter().map(|line| (*line).to_owned()).collect(),
            running,
        })
    }

}

// ===========================================================================
// Import wizard (LNC-7)
// ===========================================================================

fn importer_for(format: &str) -> Option<Box<dyn Importer>> {
    match format {
        "curseforge" => Some(Box::new(CurseForgeImporter)),
        "prism-multimc" => Some(Box::new(PrismImporter)),
        "mrpack" => Some(Box::new(MrpackImporter)),
        "official-launcher" => Some(Box::new(OfficialImporter)),
        _ => None,
    }
}

fn format_slug(format: ImportFormat) -> String {
    format.to_string()
}

impl AppService {
    /// Which import formats a path looks like, best confidence first.
    pub fn detect_import(&self, path: &str) -> Vec<DetectedFormat> {
        detect_all(Path::new(path))
            .into_iter()
            .map(|(format, confidence)| DetectedFormat {
                format: format_slug(format),
                confidence: match confidence {
                    Confidence::Certain => "certain".to_owned(),
                    Confidence::Possible => "possible".to_owned(),
                },
            })
            .collect()
    }

    /// Importable candidates at a path for one format.
    ///
    /// # Errors
    ///
    /// [`AppError`] `usage` for an unknown format slug, or on read failure.
    pub fn enumerate_import(
        &self,
        path: &str,
        format: &str,
    ) -> Result<Vec<ImportCandidateDto>, AppError> {
        let importer = importer_for(format)
            .ok_or_else(|| AppError::usage(format!("unknown import format {format:?}")))?;
        let candidates = importer.enumerate(Path::new(path))?;
        Ok(candidates
            .into_iter()
            .map(|candidate| ImportCandidateDto {
                format: format_slug(candidate.format),
                name: candidate.name,
                root: candidate.root.to_string_lossy().into_owned(),
                key: candidate.key,
            })
            .collect())
    }

    /// Imports one candidate, returning the report the wizard renders.
    ///
    /// # Errors
    ///
    /// [`AppError`] `usage` for an unknown format, or the importer's typed
    /// failure (e.g. a dynamic official-launcher profile).
    pub fn run_import(&self, candidate: &ImportCandidateDto) -> Result<ImportReportDto, AppError> {
        let importer = importer_for(&candidate.format).ok_or_else(|| {
            AppError::usage(format!("unknown import format {:?}", candidate.format))
        })?;
        let native = Candidate {
            format: match candidate.format.as_str() {
                "curseforge" => ImportFormat::CurseForge,
                "prism-multimc" => ImportFormat::PrismMultiMc,
                "mrpack" => ImportFormat::Mrpack,
                _ => ImportFormat::OfficialLauncher,
            },
            name: candidate.name.clone(),
            root: PathBuf::from(&candidate.root),
            key: candidate.key.clone(),
        };
        let report = importer.import(&self.db, &native)?;
        // Copy jars from the foreign pack into this instance's private mods/
        // so OmniLoader has something to discover on launch (must not leave
        // mods as DB-only inventory).
        let stage = crate::import::materialize_instance_mods(
            &self.paths,
            &self.db,
            report.instance_id,
        )?;
        let mut notes = report.notes;
        notes.push(format!(
            "staged {} mod jar(s) into the instance game/mods/ ({} source folder(s) scanned; {} enabled row(s) still missing a jar)",
            stage.staged, stage.sources_scanned, stage.missing
        ));
        self.emit(CoreEvent::InstanceChanged { instance_id: Some(report.instance_id) });
        Ok(ImportReportDto {
            instance_id: report.instance_id,
            mods_imported: report.mods_imported,
            skipped: report
                .skipped
                .into_iter()
                .map(|skipped| SkippedDto { item: skipped.item, reason: skipped.reason })
                .collect(),
            notes,
        })
    }

    /// Exports an instance to a foreign or Omni pack format (Phase 5 / ADR-0014).
    ///
    /// `format` is one of `mrpack`, `curseforge`, `omni`. `dest` is a file path
    /// for mrpack/omni, or a directory for curseforge. When `include_mod_files`
    /// is true, jars under the instance's `mods/` folder are packed as overrides.
    ///
    /// # Errors
    ///
    /// [`AppError`] `usage` for an unknown format; `notFound` for a missing
    /// instance; `io` / `store` on write failures.
    pub fn export_instance(
        &self,
        instance_id: i64,
        format: &str,
        dest: &str,
        include_mod_files: bool,
    ) -> Result<ExportReportDto, AppError> {
        let format = ExportFormat::parse(format).map_err(|err| AppError::usage(err.to_string()))?;
        let dest_path = PathBuf::from(dest);
        let (instance, stack, mods) = self.db.with_store(|store| {
            let instance = store
                .instance(instance_id)?
                .ok_or(crate::CoreError::NotFound { what: "instance", id: instance_id })?;
            let stack = store.component_stack(instance_id)?;
            let mods = store.mods_for_instance(instance_id)?;
            Ok((instance, stack, mods))
        })?;
        let mods_dir = if include_mod_files {
            Some(self.paths().instance_dir(instance_id).join("mods"))
        } else {
            None
        };
        let report = export_instance(
            format,
            &ExportInput {
                instance: &instance,
                stack: &stack,
                mods: &mods,
                mods_dir: mods_dir.as_deref(),
                dest: &dest_path,
            },
        )?;
        Ok(ExportReportDto::from(report))
    }
}

// ===========================================================================
// Settings
// ===========================================================================

impl AppService {
    /// A global setting value, when set.
    ///
    /// # Errors
    ///
    /// [`AppError`] on store failure.
    pub fn get_setting(&self, key: &str) -> Result<Option<serde_json::Value>, AppError> {
        Ok(self.db.with_store(|store| store.setting(key))?)
    }

    /// Creates or replaces a global setting value.
    ///
    /// # Errors
    ///
    /// [`AppError`] on store failure.
    pub fn set_setting(&self, key: &str, value: &serde_json::Value) -> Result<(), AppError> {
        self.db.with_store(|store| store.set_setting(key, value))?;
        Ok(())
    }
}

#[cfg(test)]
mod memory_tests {
    use super::{resolve_memory_mib, DEFAULT_MEMORY_MIB};

    #[test]
    fn absent_setting_uses_the_default() {
        assert_eq!(resolve_memory_mib(&serde_json::json!({})), DEFAULT_MEMORY_MIB);
    }

    #[test]
    fn zero_sentinel_uses_the_default() {
        // The reported bug: an imported pack stored memoryMib: 0, which must
        // resolve to the default rather than reaching the JVM as a zero heap.
        // This is what lets instance 1 — already in the DB with 0 — launch
        // without a manual settings edit.
        assert_eq!(resolve_memory_mib(&serde_json::json!({ "memoryMib": 0 })), DEFAULT_MEMORY_MIB);
    }

    #[test]
    fn wrong_type_uses_the_default() {
        assert_eq!(
            resolve_memory_mib(&serde_json::json!({ "memoryMib": "lots" })),
            DEFAULT_MEMORY_MIB
        );
    }

    #[test]
    fn a_real_choice_is_respected_including_large_heaps() {
        // A 10 GB modpack allocation is legitimate and must survive unchanged.
        assert_eq!(resolve_memory_mib(&serde_json::json!({ "memoryMib": 10240 })), 10240);
        assert_eq!(resolve_memory_mib(&serde_json::json!({ "memoryMib": 2048 })), 2048);
    }
}

#[cfg(test)]
mod loader_routing_tests {
    use super::{loader_managed_kind, resolve_loader_runtime};

    #[test]
    fn vanilla_settings_are_not_loader_managed() {
        assert_eq!(loader_managed_kind(&serde_json::json!({})), None);
        assert_eq!(
            loader_managed_kind(&serde_json::json!({ "importedLoader": { "kind": "vanilla" } })),
            None,
            "an explicit vanilla loader is still a direct launch",
        );
    }

    #[test]
    fn a_mod_loader_is_detected_by_kind() {
        assert_eq!(
            loader_managed_kind(&serde_json::json!({ "importedLoader": { "kind": "neoforge" } })),
            Some("neoforge".to_owned()),
        );
        assert_eq!(
            loader_managed_kind(&serde_json::json!({ "importedLoader": { "kind": "fabric" } })),
            Some("fabric".to_owned()),
        );
    }

    #[test]
    fn vanilla_instance_resolves_to_no_runtime() {
        // Ok(None) — launch directly, no loader dist consulted even if one exists.
        let resolved = resolve_loader_runtime(&serde_json::json!({}), None).expect("ok");
        assert!(resolved.is_none());
    }

    #[test]
    fn modded_instance_without_a_runtime_refuses() {
        // The core promise: a modded pack with no loader configured errors clearly rather than
        // silently launching vanilla and eating the mods.
        let settings = serde_json::json!({ "importedLoader": { "kind": "neoforge" } });
        let err = resolve_loader_runtime(&settings, None).expect_err("must refuse");
        assert_eq!(err.kind, "usage");
        assert!(err.message.contains("neoforge"), "{}", err.message);
        assert!(err.message.contains("OMNI_LOADER_DIST"), "{}", err.message);
    }

    #[test]
    fn a_staged_distribution_resolves_to_a_runtime() {
        let dir = tempfile::tempdir().expect("tempdir");
        let dist = dir.path();
        std::fs::write(
            dist.join("loader.json"),
            "{\"mainClass\":\"org.omniframework.loader.bootstrap.OmniLoaderBootstrap\",\
             \"version\":\"0.1.0\"}",
        )
        .expect("write manifest");
        let lib = dist.join("lib");
        std::fs::create_dir_all(&lib).expect("lib dir");
        std::fs::write(lib.join("loader-bootstrap-0.1.0.jar"), b"jar").expect("jar a");
        std::fs::write(lib.join("loader-core-0.1.0.jar"), b"jar").expect("jar b");
        // A non-jar in lib/ must be ignored, not put on the classpath.
        std::fs::write(lib.join("README.txt"), b"not a jar").expect("txt");

        let settings = serde_json::json!({ "importedLoader": { "kind": "neoforge" } });
        let runtime = resolve_loader_runtime(&settings, Some(dist))
            .expect("ok")
            .expect("a runtime");

        assert_eq!(
            runtime.main_class,
            "org.omniframework.loader.bootstrap.OmniLoaderBootstrap",
        );
        assert_eq!(runtime.classpath.len(), 2, "only the two jars, not the README");
        // Sorted for a stable command between runs.
        assert!(runtime.classpath[0].ends_with("loader-bootstrap-0.1.0.jar"));
        assert!(runtime.classpath[1].ends_with("loader-core-0.1.0.jar"));
    }

    #[test]
    fn a_broken_distribution_refuses_with_detail() {
        // Manifest present but lib/ empty: a malformed runtime must not resolve to a launch that
        // fails cryptically at the JVM. Name the fault instead.
        let dir = tempfile::tempdir().expect("tempdir");
        let dist = dir.path();
        std::fs::write(dist.join("loader.json"), "{\"mainClass\":\"X\",\"version\":\"0\"}")
            .expect("manifest");
        std::fs::create_dir_all(dist.join("lib")).expect("empty lib");

        let settings = serde_json::json!({ "importedLoader": { "kind": "fabric" } });
        let err = resolve_loader_runtime(&settings, Some(dist)).expect_err("must refuse");
        assert_eq!(err.kind, "usage");
        assert!(err.message.contains("no jars"), "{}", err.message);
    }

    #[test]
    fn a_missing_manifest_refuses() {
        let dir = tempfile::tempdir().expect("tempdir");
        let settings = serde_json::json!({ "importedLoader": { "kind": "neoforge" } });
        let err = resolve_loader_runtime(&settings, Some(dir.path())).expect_err("must refuse");
        assert_eq!(err.kind, "usage");
        assert!(err.message.contains("loader.json"), "{}", err.message);
    }
}
