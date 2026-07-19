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
    AccountDto, AppError, AppService, ConsoleTail, DetectedFormat, DeviceCodeDto,
    ImportCandidateDto, ImportReportDto, InstanceDetail, InstanceSummary, JobDto, ModDto,
    SkippedDto, VersionOption,
};
use crate::auth::{AuthService, LaunchAuth};
use crate::events::CoreEvent;
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
    LaunchInputs, accept_server_eula, build_client_command, build_server_command,
    spawn_recorded, wait_and_record,
};
use crate::platform::Platform;
use crate::store::{ComponentSource, InstanceKind, JobState, NewInstance, StackEntryInput};

/// Default heap for a new instance when settings say nothing (MiB).
const DEFAULT_MEMORY_MIB: u64 = 4096;
/// Console tail cap, so a runaway log can never balloon an IPC message.
const MAX_CONSOLE_LINES: usize = 2000;

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
                    // Resolution/store failure before or around the runner:
                    // record it on the job so the job center can show it.
                    let detail = serde_json::json!({
                        "message": err.to_string(),
                        "retryable": true,
                    });
                    let _ = db.with_store(|store| {
                        let job = store.job(job_id)?;
                        if job.is_some_and(|job| job.state == JobState::Running) {
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

    /// Launches an instance for an account and supervises the process.
    /// Returns the `processes` row id (the console/stop handle).
    ///
    /// The CPL-2 gate runs first: online verification when reachable, else the
    /// vault-anchored offline window, else a typed refusal — there is no
    /// launch path without a verified entitlement (COMPLIANCE §1).
    ///
    /// # Errors
    ///
    /// [`AppError`] `entitlement` when the gate refuses, `network`/`auth` on
    /// sign-in trouble, `notFound` for missing rows, `usage` when the server
    /// EULA has not been accepted.
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
        let instance = self
            .db
            .with_store(|store| store.instance(instance_id))?
            .ok_or_else(|| AppError::new("notFound", format!("instance {instance_id} not found")))?;

        let auth_service =
            AuthService::new(self.http.as_ref(), self.vault.as_ref(), &self.config.client_id);
        let auth: LaunchAuth = auth_service.launch_gate(&self.db, account_uuid)?;

        let stack = self.resolve(instance_id)?;
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

        let memory_mib = instance.settings["memoryMib"].as_u64().unwrap_or(DEFAULT_MEMORY_MIB);
        let extra_jvm_args: Vec<String> = instance.settings["jvmArgs"]
            .as_array()
            .map(|args| {
                args.iter().filter_map(|arg| arg.as_str().map(str::to_owned)).collect()
            })
            .unwrap_or_default();
        let inputs = LaunchInputs {
            instance: &instance,
            stack: &stack,
            auth: &auth,
            java_exe,
            memory_mib,
            features: std::collections::BTreeMap::new(),
            extra_jvm_args,
        };
        let command = match instance.kind {
            InstanceKind::Client => build_client_command(&self.paths, &inputs, &platform)?,
            InstanceKind::Server => build_server_command(&self.db, &self.paths, &inputs)
                .map_err(|err| match err {
                    crate::CoreError::NotFound { what: "server EULA acceptance", .. } => {
                        AppError::usage(
                            "accept the Minecraft server EULA for this instance before \
                             starting it",
                        )
                    }
                    other => AppError::from(other),
                })?,
        };

        let (session, child) = spawn_recorded(&self.db, &self.paths, instance_id, &command)?;
        self.emit(CoreEvent::ProcessStarted {
            instance_id,
            session_id: session.id,
            pid: session.pid,
        });

        // Supervise: wait off-thread so the command returns immediately.
        let db = Arc::clone(&self.db);
        let sink = Arc::clone(&self.sink);
        let processes = Arc::clone(&self.processes);
        let session_id = session.id;
        std::thread::Builder::new()
            .name(format!("omni-process-{session_id}"))
            .spawn(move || {
                // Take ownership back from the registry to wait on it; if the
                // user pressed stop, the child is already gone from the map
                // and killed, and `wait_and_record` still records the exit.
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
        self.emit(CoreEvent::InstanceChanged { instance_id: Some(report.instance_id) });
        Ok(ImportReportDto {
            instance_id: report.instance_id,
            mods_imported: report.mods_imported,
            skipped: report
                .skipped
                .into_iter()
                .map(|skipped| SkippedDto { item: skipped.item, reason: skipped.reason })
                .collect(),
            notes: report.notes,
        })
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
