//! The UI wire contract: every type crossing the Tauri boundary.
//!
//! These are **projections, never truth** (LNC-1: "the UI holds no truth").
//! The store owns state; a DTO is a snapshot the UI renders and then forgets.
//! When something changes, the core emits a [`crate::CoreEvent`] and the UI
//! refetches — it never patches a DTO locally and assumes the core agrees.
//!
//! Field naming is `camelCase` on the wire (JavaScript convention) while the
//! Rust side stays `snake_case`; the mapping is declared per struct so the
//! TypeScript definitions in `apps/launcher-ui/src/api/types.ts` are a direct
//! transcription. Every field here is documented in
//! `OmniLauncher/COMMANDS.md`, which is the single human-readable contract.

use serde::{Deserialize, Serialize};

use crate::store::{
    Account, Ecosystem, Instance, InstanceKind, Job, JobState, Mod, PortStatus, StackEntry,
};

/// One row of the instance grid.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InstanceSummary {
    /// Row id — the handle for every other instance command.
    pub id: i64,
    /// Display name.
    pub name: String,
    /// Icon reference, when set.
    pub icon: Option<String>,
    /// `client` or `server` (D8).
    pub kind: InstanceKind,
    /// Minecraft version the stack targets.
    pub game_version: String,
    /// Creation time (Unix seconds, UTC).
    pub created_at: i64,
    /// Last launch time (Unix seconds, UTC), when ever launched.
    pub last_played_at: Option<i64>,
    /// Mod-entity count (badge on the grid card).
    pub mod_count: usize,
    /// Imported loader record (`{kind, version}`) when the instance came from
    /// a foreign format and OmniLoader has not yet claimed it.
    pub loader: Option<LoaderRef>,
}

/// A loader identity recorded on an instance.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LoaderRef {
    /// `fabric` / `quilt` / `neoforge` / `forge`.
    pub kind: String,
    /// Loader version as recorded by the source format.
    pub version: String,
}

/// The instance detail surface: stack, mods, settings, notes.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InstanceDetail {
    /// The same summary the grid shows.
    pub summary: InstanceSummary,
    /// Free-form user notes.
    pub notes: String,
    /// Instance settings document (memory, JVM args, EULA stamp, import
    /// provenance …) — rendered as a form, persisted verbatim.
    pub settings: serde_json::Value,
    /// The component stack in resolution order.
    pub stack: Vec<StackEntryDto>,
    /// Mods as entities, ordered by file name.
    pub mods: Vec<ModDto>,
    /// Whether the server EULA has been explicitly accepted (server kind only).
    pub server_eula_accepted: bool,
}

/// One component-stack row.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StackEntryDto {
    /// Position in resolution order (0-based).
    pub position: i64,
    /// Component uid, e.g. `net.minecraft`.
    pub uid: String,
    /// Selected version.
    pub version: String,
    /// Pinned entries are excluded from bulk updates.
    pub pinned: bool,
}

impl From<StackEntry> for StackEntryDto {
    fn from(entry: StackEntry) -> Self {
        Self {
            position: entry.position,
            uid: entry.uid,
            version: entry.version,
            pinned: entry.pinned,
        }
    }
}

/// One mod entity, with the badges the detail view renders.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModDto {
    /// Row id — the handle for enable/disable/delete.
    pub id: i64,
    /// File name inside the instance's mods folder.
    pub file_name: String,
    /// Human name when the source format supplied one.
    pub display_name: Option<String>,
    /// Mod version when known.
    pub version: Option<String>,
    /// Loader ecosystem (badge).
    pub ecosystem: Ecosystem,
    /// Whether the mod participates in launches.
    pub enabled: bool,
    /// `curseforge` / `modrinth` when identified.
    pub platform: Option<String>,
    /// Native-port pipeline status (badge; ADR-0003).
    pub port_status: PortStatus,
}

impl From<Mod> for ModDto {
    fn from(value: Mod) -> Self {
        Self {
            id: value.id,
            file_name: value.file_name,
            display_name: value.display_name,
            version: value.version,
            ecosystem: value.ecosystem,
            enabled: value.enabled,
            platform: value.platform,
            port_status: value.port_status,
        }
    }
}

/// One account in the account manager.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountDto {
    /// Row id.
    pub id: i64,
    /// Profile UUID (the handle for launch/sign-out).
    pub uuid: String,
    /// Profile name at last verification.
    pub username: String,
    /// Whether the CPL-2 entitlement window currently permits launching.
    pub entitlement_valid: bool,
    /// When offline play expires (Unix seconds), when a window exists.
    pub window_expires_at: Option<i64>,
}

/// The device-code panel's contents (COMPLIANCE §2: consent happens in
/// Microsoft's surfaces — the launcher only displays this).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceCodeDto {
    /// Where the user goes.
    pub verification_uri: String,
    /// The code they enter there.
    pub user_code: String,
    /// Seconds until the prompt expires.
    pub expires_in_secs: u64,
}

/// One row of the job center.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct JobDto {
    /// Row id.
    pub id: i64,
    /// Job kind, e.g. `install`.
    pub kind: String,
    /// Related instance, when any.
    pub instance_id: Option<i64>,
    /// Lifecycle state.
    pub state: JobState,
    /// Kind-specific progress document (`{phase, completed, total}` for
    /// installs).
    pub progress: serde_json::Value,
    /// Persisted failure detail for failed jobs.
    pub error: Option<serde_json::Value>,
    /// Whether a runner thread currently owns this job (cancellable).
    pub running: bool,
    /// Creation time (Unix seconds).
    pub created_at: i64,
    /// Last persistence time (Unix seconds).
    pub updated_at: i64,
}

/// A selectable game version in the instance-creation form.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VersionOption {
    /// Version id, e.g. `1.21.1`.
    pub version: String,
    /// `release` / `snapshot` / `old_beta` / `old_alpha`.
    pub release_type: String,
    /// Whether the meta policy recommends it.
    pub recommended: bool,
    /// Upstream release time (RFC 3339).
    pub release_time: String,
}

/// A format detected at an import path.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DetectedFormat {
    /// Format slug (`curseforge`, `prism-multimc`, `mrpack`,
    /// `official-launcher`).
    pub format: String,
    /// `certain` when the defining manifest parsed, else `possible`.
    pub confidence: String,
}

/// One importable thing found at a path.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportCandidateDto {
    /// Format slug (round-trips back into `run_import`).
    pub format: String,
    /// Display name from the source format.
    pub name: String,
    /// Path the importer will read.
    pub root: String,
    /// Format-internal key (official-launcher profile id), when needed.
    pub key: Option<String>,
}

/// The outcome of an import, rendered as the wizard's final panel.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportReportDto {
    /// The created instance.
    pub instance_id: i64,
    /// Mods mapped into entities.
    pub mods_imported: usize,
    /// What did not map, and why.
    pub skipped: Vec<SkippedDto>,
    /// Human-relevant notes.
    pub notes: Vec<String>,
}

/// One unmapped item with its reason.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SkippedDto {
    /// What was skipped.
    pub item: String,
    /// Why.
    pub reason: String,
}

/// A tail of a process's captured log (the server console surface).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ConsoleTail {
    /// The log file that was read.
    pub path: String,
    /// The last N lines, oldest first.
    pub lines: Vec<String>,
    /// Whether the process is still recorded as running.
    pub running: bool,
}

/// Builds a summary from a store row plus its mod count.
pub(crate) fn summary_of(instance: &Instance, mod_count: usize) -> InstanceSummary {
    let loader = instance
        .settings
        .get(crate::import::IMPORTED_LOADER_KEY)
        .and_then(|value| {
            Some(LoaderRef {
                kind: value.get("kind")?.as_str()?.to_owned(),
                version: value.get("version")?.as_str()?.to_owned(),
            })
        });
    InstanceSummary {
        id: instance.id,
        name: instance.name.clone(),
        icon: instance.icon.clone(),
        kind: instance.kind,
        game_version: instance.game_version.clone(),
        created_at: instance.created_at,
        last_played_at: instance.last_played_at,
        mod_count,
        loader,
    }
}

/// Builds an account DTO, evaluating the entitlement window against `now`.
pub(crate) fn account_dto(account: &Account, now: i64) -> AccountDto {
    AccountDto {
        id: account.id,
        uuid: account.uuid.clone(),
        username: account.username.clone(),
        entitlement_valid: account.entitlement_valid(now),
        window_expires_at: account.entitlement_window_expires_at,
    }
}

/// Builds a job DTO, marking whether a runner thread currently owns it.
pub(crate) fn job_dto(job: Job, running: bool) -> JobDto {
    JobDto {
        id: job.id,
        kind: job.kind,
        instance_id: job.instance_id,
        state: job.state,
        progress: job.progress,
        error: job.error,
        running,
        created_at: job.created_at,
        updated_at: job.updated_at,
    }
}
