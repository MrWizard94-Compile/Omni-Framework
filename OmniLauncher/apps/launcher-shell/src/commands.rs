//! The Tauri command surface: one thin wrapper per [`AppService`] method.
//!
//! Every command in this file follows the same three-line shape — take the
//! managed service, hand the call to the blocking pool, map the join failure.
//! If a command here ever grows a fourth line of *decision*, that logic belongs
//! in `omni-core::app` instead (ARCHITECTURE §1). The [`dispatch!`] macro makes
//! that rule mechanical rather than aspirational.

use std::sync::Arc;

use omni_core::app::{
    AccountDto, AppError, AppService, BootIssueDto, ConsoleTail, ContentHitDto, DetectedFormat,
    DeviceCodeDto, ExportReportDto, ImportCandidateDto, ImportReportDto, InstanceDetail,
    InstanceSummary, JavaRuntimeDto, JobDto, ResourceDto, ServerConfigDto, VersionOption,
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

/// Opens the Microsoft device-code sign-in page in the system browser.
///
/// # Why this command exists at all, and why it is the exception
///
/// Every other command here is a thin wrapper over an [`AppService`] method,
/// because all *launcher logic* lives in the portable core. This one is not:
/// opening the OS browser is platform integration, which belongs to the shell,
/// not to `omni-core` (a core that shelled out to a browser could not be tested
/// without one). It is the single sanctioned exception to the wrapper rule.
///
/// It exists because the window's capability grants the web layer no opener or
/// shell permission by design (`capabilities/default.json`), so a plain
/// `<a target="_blank">` is inert inside the webview — it opens a tab in a real
/// browser during dev and does nothing in the shipped app. That gap was
/// invisible to every headless UI test and to browser-based Vite dev; it
/// surfaced the first time a human clicked the link in the actual window
/// (2026-07-20).
///
/// # Why it takes the URL yet cannot open an arbitrary one
///
/// The URL comes from the core's device-code response, but the web layer hands
/// it back, so the shell must not trust it: a bug or compromise in the web
/// layer could otherwise turn this into "open any URL". [`is_sign_in_url`]
/// rejects anything that is not HTTPS to a known Microsoft sign-in host, so the
/// worst the web layer can do is open a Microsoft login page. The web layer
/// proposes; the shell disposes.
///
/// # Errors
///
/// [`AppError`] `usage` if the URL is not an allowed sign-in URL; `internal` if
/// the browser process cannot be spawned.
#[tauri::command]
pub async fn open_sign_in_page(url: String) -> Result<(), AppError> {
    if !is_sign_in_url(&url) {
        return Err(AppError::usage(format!(
            "refusing to open '{url}': not an HTTPS Microsoft sign-in URL"
        )));
    }
    open_in_browser(&url)
}

/// Hosts the sign-in page may live on. Kept explicit rather than "any
/// microsoft.com" so a look-alike subdomain cannot slip through.
const SIGN_IN_HOSTS: &[&str] = &[
    "microsoft.com",
    "www.microsoft.com",
    "login.microsoftonline.com",
    "login.live.com",
];

/// Whether `url` is an HTTPS URL to a known Microsoft sign-in host.
///
/// Pure and total so the security boundary is unit-testable without spawning
/// anything. Parsing is deliberately strict: scheme must be exactly `https`,
/// and the host must equal an allowlisted entry — not merely end with it, since
/// `microsoft.com.evil.test` ends with nothing useful but `evil-microsoft.com`
/// would pass a naive suffix check.
fn is_sign_in_url(url: &str) -> bool {
    let Some(rest) = url.strip_prefix("https://") else {
        return false;
    };
    // Host runs to the first '/', '?' or '#'. Reject empty and userinfo ('@'),
    // which is the classic way to disguise the real host.
    let host = rest.split(['/', '?', '#']).next().unwrap_or("");
    if host.is_empty() || host.contains('@') {
        return false;
    }
    // Strip a port if present; compare the bare host.
    let bare = host.split(':').next().unwrap_or("");
    SIGN_IN_HOSTS.contains(&bare)
}

/// Hands a validated URL to the OS default browser.
///
/// Windows uses `rundll32 url.dll,FileProtocolHandler`, which passes the URL as
/// a direct argument with no shell in between — so no `cmd` metacharacter can
/// be interpreted even though [`is_sign_in_url`] already rules them out. Other
/// platforms use their standard opener; the shell targets Windows today, and
/// these keep the code honest for the eventual port.
fn open_in_browser(url: &str) -> Result<(), AppError> {
    let mut command = browser_command(url);
    command
        .spawn()
        .map(|_| ())
        .map_err(|err| AppError::new("internal", format!("cannot open the browser: {err}")))
}

/// The platform command that opens a URL, without a shell.
fn browser_command(url: &str) -> std::process::Command {
    #[cfg(target_os = "windows")]
    {
        let mut command = std::process::Command::new("rundll32.exe");
        command.arg("url.dll,FileProtocolHandler").arg(url);
        command
    }
    #[cfg(target_os = "macos")]
    {
        let mut command = std::process::Command::new("open");
        command.arg(url);
        command
    }
    #[cfg(not(any(target_os = "windows", target_os = "macos")))]
    {
        let mut command = std::process::Command::new("xdg-open");
        command.arg(url);
        command
    }
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

/// Exports an instance to mrpack / curseforge / omni (Phase 5 / ADR-0014).
#[tauri::command]
pub async fn export_instance(
    service: State<'_, Arc<AppService>>,
    instance_id: i64,
    format: String,
    dest: String,
    include_mod_files: bool,
) -> Result<ExportReportDto, AppError> {
    blocking(Arc::clone(&service), move |s| {
        s.export_instance(instance_id, &format, &dest, include_mod_files)
    })
    .await
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

// ---------------------------------------------------------------- resources / content / server

/// Lists LNC-10 folder resources.
#[tauri::command]
pub async fn list_resources(
    service: State<'_, Arc<AppService>>,
    instance_id: i64,
    kind: String,
) -> Result<Vec<ResourceDto>, AppError> {
    blocking(Arc::clone(&service), move |s| s.list_resources(instance_id, &kind)).await
}

/// Enables or disables a resource pack / shader / datapack / world.
#[tauri::command]
pub async fn set_resource_enabled(
    service: State<'_, Arc<AppService>>,
    instance_id: i64,
    kind: String,
    name: String,
    enabled: bool,
) -> Result<(), AppError> {
    blocking(Arc::clone(&service), move |s| {
        s.set_resource_enabled(instance_id, &kind, &name, enabled)
    })
    .await
}

/// Content browse search.
#[tauri::command]
pub async fn search_content(
    service: State<'_, Arc<AppService>>,
    query: String,
    game_version: Option<String>,
    limit: u32,
) -> Result<Vec<ContentHitDto>, AppError> {
    blocking(Arc::clone(&service), move |s| {
        s.search_content(&query, game_version.as_deref(), limit)
    })
    .await
}

/// Install content jar into an instance.
#[tauri::command]
pub async fn install_content(
    service: State<'_, Arc<AppService>>,
    instance_id: i64,
    file: serde_json::Value,
) -> Result<String, AppError> {
    blocking(Arc::clone(&service), move |s| s.install_content(instance_id, &file)).await
}

/// Resolve a browse project hit to a file and install it into the instance.
#[tauri::command]
pub async fn install_browse_hit(
    service: State<'_, Arc<AppService>>,
    instance_id: i64,
    platform: String,
    project_id: String,
    game_version: Option<String>,
) -> Result<String, AppError> {
    blocking(Arc::clone(&service), move |s| {
        s.install_browse_hit(instance_id, &platform, &project_id, game_version.as_deref())
    })
    .await
}

/// Identify a local jar via platform fingerprints.
#[tauri::command]
pub async fn identify_jar(
    service: State<'_, Arc<AppService>>,
    path: String,
) -> Result<Option<serde_json::Value>, AppError> {
    blocking(Arc::clone(&service), move |s| s.identify_jar(&path)).await
}

/// Discover system JVMs.
#[tauri::command]
pub async fn discover_java_runtimes(
    service: State<'_, Arc<AppService>>,
    register: bool,
) -> Result<Vec<JavaRuntimeDto>, AppError> {
    blocking(Arc::clone(&service), move |s| s.discover_java_runtimes(register)).await
}

/// Server.properties projection.
#[tauri::command]
pub async fn get_server_config(
    service: State<'_, Arc<AppService>>,
    instance_id: i64,
) -> Result<ServerConfigDto, AppError> {
    blocking(Arc::clone(&service), move |s| s.get_server_config(instance_id)).await
}

/// Write server.properties.
#[tauri::command]
pub async fn set_server_config(
    service: State<'_, Arc<AppService>>,
    instance_id: i64,
    config: ServerConfigDto,
) -> Result<(), AppError> {
    blocking(Arc::clone(&service), move |s| s.set_server_config(instance_id, config)).await
}

/// Boot failure issue tree from OmniLoader dump.
#[tauri::command]
pub async fn boot_issues(
    service: State<'_, Arc<AppService>>,
    instance_id: i64,
) -> Result<Vec<BootIssueDto>, AppError> {
    blocking(Arc::clone(&service), move |s| s.boot_issues(instance_id)).await
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
        open_sign_in_page,
        accept_server_eula,
        launch_instance,
        stop_process,
        console_tail,
        detect_import,
        enumerate_import,
        run_import,
        export_instance,
        get_setting,
        set_setting,
        list_resources,
        set_resource_enabled,
        search_content,
        install_content,
        install_browse_hit,
        identify_jar,
        discover_java_runtimes,
        get_server_config,
        set_server_config,
        boot_issues,
    ]
}

#[cfg(test)]
mod tests {
    use super::is_sign_in_url;

    #[test]
    fn accepts_the_device_code_page() {
        // The one the device-code flow actually returns.
        assert!(is_sign_in_url("https://www.microsoft.com/link"));
        assert!(is_sign_in_url("https://microsoft.com/link"));
    }

    #[test]
    fn accepts_the_other_microsoft_sign_in_hosts() {
        assert!(is_sign_in_url("https://login.microsoftonline.com/common/oauth2/v2.0/authorize"));
        assert!(is_sign_in_url("https://login.live.com/oauth20_authorize.srf?client_id=x"));
    }

    #[test]
    fn rejects_plain_http() {
        // A downgrade to http would let the sign-in page be intercepted.
        assert!(!is_sign_in_url("http://www.microsoft.com/link"));
    }

    #[test]
    fn rejects_unknown_hosts() {
        assert!(!is_sign_in_url("https://example.com/link"));
        assert!(!is_sign_in_url("https://evil.test/steal"));
    }

    #[test]
    fn rejects_lookalike_hosts() {
        // The failure modes a naive check would wave through:
        // a suffix trick, a prefix trick, and a subdomain of the wrong domain.
        assert!(!is_sign_in_url("https://microsoft.com.evil.test/link"));
        assert!(!is_sign_in_url("https://evil-microsoft.com/link"));
        assert!(!is_sign_in_url("https://notmicrosoft.com/link"));
        assert!(!is_sign_in_url("https://microsoft.com.attacker.net/link"));
    }

    #[test]
    fn rejects_userinfo_disguise() {
        // The @ trick: the real host is evil.test, microsoft.com is only userinfo.
        assert!(!is_sign_in_url("https://www.microsoft.com@evil.test/link"));
        assert!(!is_sign_in_url("https://microsoft.com:pass@evil.test/"));
    }

    #[test]
    fn rejects_non_https_schemes() {
        // file: and custom schemes are the interesting attacks if a URL opener
        // ever trusted them.
        assert!(!is_sign_in_url("file:///c:/windows/system32/calc.exe"));
        assert!(!is_sign_in_url("javascript:alert(1)"));
        assert!(!is_sign_in_url("data:text/html,<script>alert(1)</script>"));
        assert!(!is_sign_in_url(""));
        assert!(!is_sign_in_url("microsoft.com/link"));
    }

    #[test]
    fn accepts_an_allowed_host_with_a_port() {
        assert!(is_sign_in_url("https://login.live.com:443/oauth20_authorize.srf"));
    }
}
