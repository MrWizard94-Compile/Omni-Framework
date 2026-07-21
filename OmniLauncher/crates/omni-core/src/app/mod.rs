//! # The application service — the launcher's command API (LNC-1)
//!
//! `OmniLauncher/ARCHITECTURE.md` §1 assigns the command API and the typed
//! event stream to `omni-core`, explicitly **not** to the shell: "the UI holds
//! no truth". This module is that API. The Tauri shell
//! (`apps/launcher-shell`) contains no logic — every `#[tauri::command]` is a
//! one-line dispatch into an [`AppService`] method, which is why the entire
//! surface is testable headlessly (`tests/app.rs`) with no windowing, no
//! `WebView`, and no network.
//!
//! ## Threading and async
//!
//! The core is synchronous by decision (ADR-0015: one mutex-guarded WAL
//! connection, no async contagion). The shell's commands are `async` and
//! dispatch through Tauri's blocking pool, so the UI thread never waits on a
//! database lock or a socket. Long-running work (installs, sign-in polling,
//! process supervision) is **not** performed inside a command at all: the
//! command registers work and returns a handle immediately, and progress
//! reaches the UI as [`CoreEvent`]s. That is the whole reason the event
//! stream exists.
//!
//! ## Registries
//!
//! Two pieces of live, non-persistent state exist only while the app runs:
//!
//! - **jobs**: `job_id → CancelToken` for runner threads currently owning a
//!   job. Absence means "not running here" — the persisted `jobs.state` is
//!   still the truth, and a job left `running` by a crash is recovered to
//!   `paused` at startup ([`AppService::recover_interrupted_jobs`]).
//! - **processes**: `session_id → Child` for launched games/servers, so the
//!   console surface can stop them. Absence means "not supervised by this
//!   process"; the `processes` row remains the audit record either way.
//!
//! Both are deliberately *not* persisted: they describe this OS process's
//! ownership, and inventing durable ownership would create exactly the
//! stale-lock class of bug the crash-recovery path exists to avoid.

mod commands;
mod dto;

pub use dto::{
    AccountDto, ConsoleTail, DetectedFormat, DeviceCodeDto, ExportReportDto, ImportCandidateDto,
    ImportReportDto, InstanceDetail, InstanceSummary, JobDto, LoaderRef, ModDto, SkippedDto,
    StackEntryDto, VersionOption,
};

use std::collections::BTreeMap;
use std::fmt;
use std::path::PathBuf;
use std::process::Child;
use std::sync::{Arc, Mutex};

use omni_meta_client::{HttpSource, MetaClient, MetaSource};

use crate::auth::{AuthError, AuthHttp, KeyringVault, TokenVault, UreqAuthHttp};
use crate::clock::{Clock, SystemClock};
use crate::db::Db;
use crate::error::CoreError;
use crate::events::EventSink;
use crate::install::{BlobSource, Paths, UreqBlobSource};
use crate::jobs::CancelToken;

/// The meta client with its source erased, so the service needs no generic
/// parameter (see the blanket `MetaSource for Arc<T>` impl in
/// `omni-meta-client`).
pub type SharedMeta = Arc<MetaClient<Arc<dyn MetaSource>>>;

/// Everything the launcher needs to know before it can start.
#[derive(Debug, Clone)]
pub struct AppConfig {
    /// Root of all launcher data (database, caches, instances, runtimes).
    pub data_root: PathBuf,
    /// Base URL of the Omni meta service `/v1` tree. Build-config, exactly
    /// like (prism-launcher)'s `Launcher_META_URL` — see ADR-0012/ADR-0016.
    pub meta_url: String,
    /// Azure application (client) id for the MSA chain (ADR-0016).
    pub client_id: String,
    /// Service namespace for OS credential-vault entries.
    pub vault_service: String,
    /// The staged OmniLoader distribution (its `loader.json` + `lib/`), used to
    /// boot loader-managed instances through OmniLoader rather than vanilla
    /// (B2). `None` means no loader runtime is available, so a loader-managed
    /// instance refuses to launch with a message saying so rather than
    /// silently running vanilla. Build-config, like `meta_url`.
    pub loader_dist_dir: Option<PathBuf>,
}

/// The meta service's production URL (ADR-0016; overridable at build time).
pub const DEFAULT_META_URL: &str = "https://omnimeta.wpaistudio.net/v1";

/// The launcher's data root for the current user:
/// `%APPDATA%\OmniLauncher` on Windows, `$XDG_DATA_HOME`/`~/.local/share`
/// elsewhere. Falls back to `./omni-data` when the environment says nothing.
pub fn default_data_root() -> PathBuf {
    let from_env = |key: &str| std::env::var_os(key).map(PathBuf::from).filter(|p| !p.as_os_str().is_empty());
    if cfg!(windows) {
        if let Some(appdata) = from_env("APPDATA") {
            return appdata.join("OmniLauncher");
        }
    } else {
        if let Some(data_home) = from_env("XDG_DATA_HOME") {
            return data_home.join("OmniLauncher");
        }
        if let Some(home) = from_env("HOME") {
            return home.join(".local/share/OmniLauncher");
        }
    }
    PathBuf::from("omni-data")
}

impl Default for AppConfig {
    fn default() -> Self {
        Self {
            data_root: default_data_root(),
            meta_url: DEFAULT_META_URL.to_owned(),
            client_id: crate::auth::DEV_CLIENT_ID.to_owned(),
            vault_service: "OmniLauncher".to_owned(),
            // Overridable by env so a dev can point the launcher at the staged
            // loader distribution (OmniLoader/loader-bootstrap/build/loader-dist)
            // without a code change — the same shape as data_root reading env.
            // Absent by default: production supplies a bundled path explicitly.
            loader_dist_dir: std::env::var_os("OMNI_LOADER_DIST").map(PathBuf::from),
        }
    }
}

/// A command failure, shaped for the UI: a machine-readable `kind` for
/// branching plus a human, actionable `message` (LDR-10's quality bar applied
/// to the launcher half of the story).
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AppError {
    /// Stable discriminant: `store`, `auth`, `entitlement`, `network`,
    /// `notFound`, `usage`.
    pub kind: String,
    /// Human-readable, actionable text — safe to render verbatim.
    pub message: String,
}

impl AppError {
    /// Builds an error with an explicit kind.
    pub fn new(kind: &str, message: impl Into<String>) -> Self {
        Self { kind: kind.to_owned(), message: message.into() }
    }

    /// A usage/validation failure (bad argument, unsupported request).
    pub fn usage(message: impl Into<String>) -> Self {
        Self::new("usage", message)
    }
}

impl fmt::Display for AppError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}: {}", self.kind, self.message)
    }
}

impl std::error::Error for AppError {}

impl From<CoreError> for AppError {
    fn from(err: CoreError) -> Self {
        let kind = match &err {
            CoreError::NotFound { .. } => "notFound",
            CoreError::Io { .. } => "io",
            _ => "store",
        };
        Self::new(kind, err.to_string())
    }
}

impl From<AuthError> for AppError {
    fn from(err: AuthError) -> Self {
        let kind = match &err {
            AuthError::Http { .. } => "network",
            AuthError::EntitlementWindowExpired | AuthError::NotEntitled => "entitlement",
            _ => "auth",
        };
        Self::new(kind, err.to_string())
    }
}

/// The launcher's application service: one per running app.
pub struct AppService {
    pub(crate) db: Arc<Db>,
    pub(crate) paths: Paths,
    pub(crate) config: AppConfig,
    pub(crate) clock: Arc<dyn Clock>,
    pub(crate) sink: Arc<dyn EventSink>,
    pub(crate) meta: SharedMeta,
    pub(crate) vault: Arc<dyn TokenVault>,
    pub(crate) http: Arc<dyn AuthHttp>,
    pub(crate) blobs: Arc<dyn BlobSource>,
    /// Jobs owned by a runner thread in this process. `Arc` because runner
    /// threads deregister themselves as they exit.
    pub(crate) running_jobs: JobRegistry,
    /// Processes supervised by this process. `Arc` for the same reason.
    pub(crate) processes: ProcessRegistry,
}

/// Live ownership of running jobs (process-local; see the module docs).
pub(crate) type JobRegistry = Arc<Mutex<BTreeMap<i64, CancelToken>>>;
/// Live ownership of launched processes (process-local).
pub(crate) type ProcessRegistry = Arc<Mutex<BTreeMap<i64, Child>>>;

impl fmt::Debug for AppService {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("AppService")
            .field("data_root", &self.config.data_root)
            .field("meta_url", &self.config.meta_url)
            .finish_non_exhaustive()
    }
}

impl AppService {
    /// Production wiring: real database, HTTP meta source, OS credential
    /// vault, network blob source, system clock.
    ///
    /// # Errors
    ///
    /// [`AppError`] when the data directories or database cannot be opened.
    pub fn new(config: AppConfig, sink: Arc<dyn EventSink>) -> Result<Self, AppError> {
        let clock: Arc<dyn Clock> = Arc::new(SystemClock);
        let db = Arc::new(Db::open(config.data_root.join("launcher.db"), clock.clone())?);
        let paths = Paths::new(&config.data_root);
        let source: Arc<dyn MetaSource> = Arc::new(HttpSource::new(&config.meta_url));
        let meta = Arc::new(
            MetaClient::new(source, config.data_root.join("meta-cache"))
                .map_err(|err| AppError::new("network", err.to_string()))?,
        );
        Ok(Self {
            db,
            paths,
            vault: Arc::new(KeyringVault::new(config.vault_service.clone())),
            http: Arc::new(UreqAuthHttp::new()),
            blobs: Arc::new(UreqBlobSource::new()),
            meta,
            clock,
            sink,
            config,
            running_jobs: Arc::new(Mutex::new(BTreeMap::new())),
            processes: Arc::new(Mutex::new(BTreeMap::new())),
        })
    }

    /// Full dependency injection — the constructor tests use to run the whole
    /// command surface against fixtures (ARCHITECTURE §8: never live secrets,
    /// never live endpoints).
    #[expect(
        clippy::too_many_arguments,
        reason = "an injection constructor names every seam on purpose; grouping them \
                  into a struct would only move the same list one level out"
    )]
    pub fn with_parts(
        config: AppConfig,
        db: Arc<Db>,
        clock: Arc<dyn Clock>,
        sink: Arc<dyn EventSink>,
        meta: SharedMeta,
        vault: Arc<dyn TokenVault>,
        http: Arc<dyn AuthHttp>,
        blobs: Arc<dyn BlobSource>,
    ) -> Self {
        let paths = Paths::new(&config.data_root);
        Self {
            db,
            paths,
            config,
            clock,
            sink,
            meta,
            vault,
            http,
            blobs,
            running_jobs: Arc::new(Mutex::new(BTreeMap::new())),
            processes: Arc::new(Mutex::new(BTreeMap::new())),
        }
    }

    /// The filesystem layout this service uses.
    pub fn paths(&self) -> &Paths {
        &self.paths
    }

    /// The configuration this service started with.
    pub fn config(&self) -> &AppConfig {
        &self.config
    }

    /// The shared database handle (the shell's shutdown hook uses it).
    pub fn db(&self) -> &Arc<Db> {
        &self.db
    }

    pub(crate) fn emit(&self, event: crate::CoreEvent) {
        self.sink.emit(event);
    }

    pub(crate) fn now(&self) -> i64 {
        self.clock.now_unix()
    }
}
