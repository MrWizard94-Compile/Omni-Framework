//! The Tauri command surface: one thin wrapper per [`AppService`] method.
//!
//! Every command in this file follows the same three-line shape — take the
//! managed service, hand the call to the blocking pool, map the join failure.
//! If a command here ever grows a fourth line of *decision*, that logic belongs
//! in `omni-core::app` instead (ARCHITECTURE §1). The [`dispatch!`] macro makes
//! that rule mechanical rather than aspirational.

use std::sync::Arc;

use omni_core::app::{
    AccountDto, AppError, AppService, ConsoleTail, DetectedFormat, DeviceCodeDto,
    ImportCandidateDto, ImportReportDto, InstanceDetail, InstanceSummary, JobDto, VersionOption,
};
use omni_core::store::InstanceKind;
use tauri::State;

/// Runs a synchronous core call on Tauri's blocking pool.
///
/// A panic inside the pool would otherwise surface to the UI as a silent
/// hang; here it becomes a typed `internal` error the UI can render.
async fn blocking<T, F>(service: Arc<AppService>, body: F) -> Result<T, AppError>
where
    T: Send + 'static,
    F: FnOnce(&AppService) -> Result<T, AppError> + Send + 'static,
{
    tauri::async_runtime::spawn_blocking(move || body(&service))
        .await
        .map_err(|err| AppError::new("internal", format!("command task failed: {err}")))?
}

// The service is managed as `Arc<AppService>`, so every command clones a
// refcount (never a second database) before handing work to the pool.

// ---------------------------------------------------------------- instances

/// Every instance for the grid.
#[tauri::command]
pub async fn list_instances(
    service: State<'_, Arc<AppService>>,
) -> Result<Vec<InstanceSummary>, AppError> {
    blocking(Arc::clone(&service), AppService::list_instances).await
}

/// One instance with stack, mods, settings.
#[tauri::command]
pub async fn instance_detail(
    service: State<'_, Arc<AppService>>,
    instance_id: i64,
) -> Result<InstanceDetail, AppError> {
    blocking(Arc::clone(&service), move |s| s.instance_detail(instance_id)).await
}

/// Creates an instance and seeds its stack.
#[tauri::command]
pub async fn create_instance(
    service: State<'_, Arc<AppService>>,
    name: String,
    game_version: String,
    kind: InstanceKind,
) -> Result<InstanceSummary, AppError> {
    blocking(Arc::clone(&service), move |s| s.create_instance(&name, &game_version, kind)).await
}

/// Renames an instance.
#[tauri::command]
pub async fn rename_instance(
    service: State<'_, Arc<AppService>>,
    instance_id: i64,
    name: String,
) -> Result<(), AppError> {
    blocking(Arc::clone(&service), move |s| s.rename_instance(instance_id, &name)).await
}

/// Replaces an instance's settings document.
#[tauri::command]
pub async fn update_instance_settings(
    service: State<'_, Arc<AppService>>,
    instance_id: i64,
    settings: serde_json::Value,
) -> Result<(), AppError> {
    blocking(Arc::clone(&service), move |s| s.update_instance_settings(instance_id, &settings))
        .await
}

/// Deletes an instance row (files on disk are left alone).
#[tauri::command]
pub async fn delete_instance(
    service: State<'_, Arc<AppService>>,
    instance_id: i64,
) -> Result<bool, AppError> {
    blocking(Arc::clone(&service), move |s| s.delete_instance(instance_id)).await
}

/// Enables or disables one mod.
#[tauri::command]
pub async fn set_mod_enabled(
    service: State<'_, Arc<AppService>>,
    mod_id: i64,
    enabled: bool,
) -> Result<(), AppError> {
    blocking(Arc::clone(&service), move |s| s.set_mod_enabled(mod_id, enabled)).await
}

/// Removes one mod entity.
#[tauri::command]
pub async fn delete_mod(
    service: State<'_, Arc<AppService>>,
    mod_id: i64,
) -> Result<bool, AppError> {
    blocking(Arc::clone(&service), move |s| s.delete_mod(mod_id)).await
}

// ----------------------------------------------------------------- versions

/// Selectable game versions from the meta service.
#[tauri::command]
pub async fn available_versions(
    service: State<'_, Arc<AppService>>,
    releases_only: bool,
) -> Result<Vec<VersionOption>, AppError> {
    blocking(Arc::clone(&service), move |s| s.available_versions(releases_only)).await
}

// --------------------------------------------------------------------- jobs

/// Every job for the job center.
#[tauri::command]
pub async fn list_jobs(service: State<'_, Arc<AppService>>) -> Result<Vec<JobDto>, AppError> {
    blocking(Arc::clone(&service), AppService::list_jobs).await
}

/// Starts an install job; returns its id immediately.
#[tauri::command]
pub async fn start_install(
    service: State<'_, Arc<AppService>>,
    instance_id: i64,
) -> Result<i64, AppError> {
    blocking(Arc::clone(&service), move |s| s.start_install(instance_id)).await
}

/// Resumes a paused or failed job.
#[tauri::command]
pub async fn resume_job(
    service: State<'_, Arc<AppService>>,
    job_id: i64,
) -> Result<(), AppError> {
    blocking(Arc::clone(&service), move |s| s.resume_job(job_id)).await
}

/// Requests a cooperative pause; `false` means no runner here owned it.
#[tauri::command]
pub async fn cancel_job(
    service: State<'_, Arc<AppService>>,
    job_id: i64,
) -> Result<bool, AppError> {
    blocking(Arc::clone(&service), move |s| Ok(s.cancel_job(job_id))).await
}

/// Clears finished jobs from the history; returns how many were removed.
#[tauri::command]
pub async fn clear_finished_jobs(
    service: State<'_, Arc<AppService>>,
) -> Result<usize, AppError> {
    blocking(Arc::clone(&service), AppService::clear_finished_jobs).await
}

// ----------------------------------------------------------------- accounts

/// Every signed-in account.
#[tauri::command]
pub async fn list_accounts(
    service: State<'_, Arc<AppService>>,
) -> Result<Vec<AccountDto>, AppError> {
    blocking(Arc::clone(&service), AppService::list_accounts).await
}

/// Starts a device-code sign-in and returns the prompt to display.
#[tauri::command]
pub async fn start_device_login(
    service: State<'_, Arc<AppService>>,
) -> Result<DeviceCodeDto, AppError> {
    blocking(Arc::clone(&service), AppService::start_device_login).await
}

/// Signs an account out.
#[tauri::command]
pub async fn sign_out(
    service: State<'_, Arc<AppService>>,
    uuid: String,
) -> Result<(), AppError> {
    blocking(Arc::clone(&service), move |s| s.sign_out(&uuid)).await
}

// ----------------------------------------------------------- launch/console

/// Records explicit server-EULA acceptance and writes `eula.txt`.
#[tauri::command]
pub async fn accept_server_eula(
    service: State<'_, Arc<AppService>>,
    instance_id: i64,
) -> Result<(), AppError> {
    blocking(Arc::clone(&service), move |s| s.accept_server_eula(instance_id)).await
}

/// Launches an instance; returns the session id.
#[tauri::command]
pub async fn launch_instance(
    service: State<'_, Arc<AppService>>,
    instance_id: i64,
    account_uuid: String,
) -> Result<i64, AppError> {
    blocking(Arc::clone(&service), move |s| s.launch_instance(instance_id, &account_uuid)).await
}

/// Stops a supervised process.
#[tauri::command]
pub async fn stop_process(
    service: State<'_, Arc<AppService>>,
    session_id: i64,
) -> Result<bool, AppError> {
    blocking(Arc::clone(&service), move |s| Ok(s.stop_process(session_id))).await
}

/// The tail of a session's captured log.
#[tauri::command]
pub async fn console_tail(
    service: State<'_, Arc<AppService>>,
    session_id: i64,
    max_lines: usize,
) -> Result<ConsoleTail, AppError> {
    blocking(Arc::clone(&service), move |s| s.console_tail(session_id, max_lines)).await
}

// ------------------------------------------------------------------- import

/// Which import formats a path looks like.
#[tauri::command]
pub async fn detect_import(
    service: State<'_, Arc<AppService>>,
    path: String,
) -> Result<Vec<DetectedFormat>, AppError> {
    blocking(Arc::clone(&service), move |s| Ok(s.detect_import(&path))).await
}

/// Importable candidates at a path for one format.
#[tauri::command]
pub async fn enumerate_import(
    service: State<'_, Arc<AppService>>,
    path: String,
    format: String,
) -> Result<Vec<ImportCandidateDto>, AppError> {
    blocking(Arc::clone(&service), move |s| s.enumerate_import(&path, &format)).await
}

/// Imports one candidate.
#[tauri::command]
pub async fn run_import(
    service: State<'_, Arc<AppService>>,
    candidate: ImportCandidateDto,
) -> Result<ImportReportDto, AppError> {
    blocking(Arc::clone(&service), move |s| s.run_import(&candidate)).await
}

// ----------------------------------------------------------------- settings

/// A global setting value.
#[tauri::command]
pub async fn get_setting(
    service: State<'_, Arc<AppService>>,
    key: String,
) -> Result<Option<serde_json::Value>, AppError> {
    blocking(Arc::clone(&service), move |s| s.get_setting(&key)).await
}

/// Creates or replaces a global setting value.
#[tauri::command]
pub async fn set_setting(
    service: State<'_, Arc<AppService>>,
    key: String,
    value: serde_json::Value,
) -> Result<(), AppError> {
    blocking(Arc::clone(&service), move |s| s.set_setting(&key, &value)).await
}

/// The complete invoke handler — the registry the UI's `invoke()` names map to.
pub fn handler() -> impl Fn(tauri::ipc::Invoke) -> bool + Send + Sync + 'static {
    tauri::generate_handler![
        list_instances,
        instance_detail,
        create_instance,
        rename_instance,
        update_instance_settings,
        delete_instance,
        set_mod_enabled,
        delete_mod,
        available_versions,
        list_jobs,
        start_install,
        resume_job,
        cancel_job,
        clear_finished_jobs,
        list_accounts,
        start_device_login,
        sign_out,
        accept_server_eula,
        launch_instance,
        stop_process,
        console_tail,
        detect_import,
        enumerate_import,
        run_import,
        get_setting,
        set_setting,
    ]
}
