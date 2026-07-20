//! Entity types and the text-enum bridge to CHECK-constrained columns.
//!
//! Every enum here mirrors a `CHECK (col IN (...))` constraint in
//! `db/migrations/0001_schema_v1.sql` — the database enforces the vocabulary,
//! the enum makes it typed. An unrecognized value (database written by a newer
//! build) surfaces as [`CoreError::UnknownEnum`], never a silent default.

use rusqlite::types::{FromSql, FromSqlError, FromSqlResult, ToSqlOutput, ValueRef};
use serde::{Deserialize, Serialize};

use crate::error::CoreError;

/// Defines a copyable enum with exact database text forms plus rusqlite
/// `FromSql`/`ToSql` and `Display` implementations.
macro_rules! text_enum {
    (
        $(#[$meta:meta])* $name:ident ($ctx:literal) {
            $($(#[$vmeta:meta])* $variant:ident => $text:literal),+ $(,)?
        }
    ) => {
        $(#[$meta])*
        #[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
        #[serde(rename_all = "kebab-case")]
        pub enum $name {
            $($(#[$vmeta])* $variant),+
        }

        impl $name {
            /// The exact database text form.
            pub fn as_str(self) -> &'static str {
                match self { $(Self::$variant => $text),+ }
            }

            /// Parses the database text form.
            ///
            /// # Errors
            ///
            /// [`CoreError::UnknownEnum`] for values this build does not know.
            pub fn parse(value: &str) -> Result<Self, CoreError> {
                match value {
                    $($text => Ok(Self::$variant),)+
                    other => Err(CoreError::UnknownEnum {
                        context: $ctx,
                        value: other.to_owned(),
                    }),
                }
            }
        }

        impl std::fmt::Display for $name {
            fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
                f.write_str(self.as_str())
            }
        }

        impl FromSql for $name {
            fn column_result(value: ValueRef<'_>) -> FromSqlResult<Self> {
                let text = <String as FromSql>::column_result(value)?;
                Self::parse(&text).map_err(|err| FromSqlError::Other(Box::new(err)))
            }
        }

        impl rusqlite::types::ToSql for $name {
            fn to_sql(&self) -> rusqlite::Result<ToSqlOutput<'_>> {
                Ok(ToSqlOutput::from(self.as_str()))
            }
        }
    };
}

text_enum! {
    /// What an instance runs as (D8: servers are first-class).
    InstanceKind ("instances.kind") {
        /// A playable client instance.
        Client => "client",
        /// A dedicated-server instance.
        Server => "server",
    }
}

text_enum! {
    /// Where a stack component's definition comes from.
    ComponentSource ("components.source") {
        /// Resolved through the Omni meta service (LNC-3).
        Meta => "meta",
        /// User-supplied custom component (`custom_json` holds the payload).
        Custom => "custom",
    }
}

text_enum! {
    /// Which loader ecosystem a mod file belongs to.
    Ecosystem ("mods.ecosystem") {
        /// Native Omni mod (`omni.mod.json`).
        Omni => "omni",
        /// Fabric/Quilt-family mod.
        Fabric => "fabric",
        /// NeoForge mod.
        Neoforge => "neoforge",
        /// Legacy Forge mod.
        Forge => "forge",
        /// Not yet identified.
        Unknown => "unknown",
    }
}

text_enum! {
    /// Native-port pipeline status of a mod (ADR-0003).
    PortStatus ("mods.port_status") {
        /// Written natively for OmniLoader.
        Native => "native",
        /// Runs through a compat layer.
        Compat => "compat",
        /// A native Omni port exists.
        Ported => "ported",
        /// Author declined porting — permanent (ADR-0003).
        Excluded => "excluded",
        /// Not yet evaluated.
        Unknown => "unknown",
    }
}

text_enum! {
    /// Account family. Exactly one exists by design (COMPLIANCE §2); the enum
    /// keeps the column typed and the vocabulary auditable.
    AccountKind ("accounts.kind") {
        /// Microsoft account via the official OAuth chain.
        Microsoft => "microsoft",
    }
}

text_enum! {
    /// How a Java runtime entered the database (LNC-6).
    JavaSource ("java_runtimes.source") {
        /// Found by scanning the machine.
        Detected => "detected",
        /// Installed from Mojang's runtime manifests.
        MojangManifest => "mojang-manifest",
    }
}

text_enum! {
    /// Job lifecycle state (LNC-5). Legal transitions are enforced by
    /// [`JobState::can_transition_to`]: `pending → running`,
    /// `running → paused | failed | done`, `paused | failed → running`
    /// (resume/retry), and `pending | paused → failed` for work that breaks
    /// before or between runs. `done` is terminal.
    JobState ("jobs.state") {
        /// Created, not yet claimed by a runner.
        Pending => "pending",
        /// A runner is executing steps.
        Running => "running",
        /// Stopped at a resume point (user pause or crash recovery).
        Paused => "paused",
        /// Stopped with a persisted error; retryable.
        Failed => "failed",
        /// Terminal success.
        Done => "done",
    }
}

impl JobState {
    /// The job state machine: `pending → running`; `running → paused | failed
    /// | done`; `paused → running` (resume); `failed → running` (retry);
    /// `done` is terminal.
    pub fn can_transition_to(self, to: Self) -> bool {
        matches!(
            (self, to),
            (Self::Pending | Self::Paused | Self::Failed, Self::Running)
                | (Self::Running, Self::Paused | Self::Failed | Self::Done)
                // A job can also fail *before or between* runs: its instance was
                // deleted, its component stack will not resolve, the meta service
                // is unreachable. Leaving that unrepresentable was a real bug — a
                // job whose setup failed sat at `pending` forever with no error,
                // which reads to the user as "about to start" rather than "broken"
                // (found on the first live install, 2026-07-20).
                | (Self::Pending | Self::Paused, Self::Failed)
        )
    }
}

/// A launcher instance (client or server) — schema `instances`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Instance {
    /// Row id.
    pub id: i64,
    /// Display name.
    pub name: String,
    /// Icon reference (UI asset key or path).
    pub icon: Option<String>,
    /// Client or server (D8).
    pub kind: InstanceKind,
    /// Minecraft version this instance targets.
    pub game_version: String,
    /// Creation time (Unix seconds).
    pub created_at: i64,
    /// Last launch time (Unix seconds).
    pub last_played_at: Option<i64>,
    /// Instance settings document (memory, JVM args, quick-play, …).
    pub settings: serde_json::Value,
    /// Free-form user notes.
    pub notes: String,
}

/// Input for creating an instance.
#[derive(Debug, Clone)]
pub struct NewInstance {
    /// Display name.
    pub name: String,
    /// Icon reference.
    pub icon: Option<String>,
    /// Client or server.
    pub kind: InstanceKind,
    /// Minecraft version.
    pub game_version: String,
}

/// One entry of an instance's component stack — schema `components`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StackEntry {
    /// Stack position (0-based, resolution order).
    pub position: i64,
    /// Component uid (meta-service vocabulary, e.g. `net.minecraft`).
    pub uid: String,
    /// Selected version.
    pub version: String,
    /// Pinned entries are excluded from bulk updates.
    pub pinned: bool,
    /// Meta-resolved or custom.
    pub source: ComponentSource,
    /// Full payload for custom components.
    pub custom: Option<serde_json::Value>,
}

/// Input for [`crate::store::Store::set_component_stack`] — position is
/// assigned from slice order.
#[derive(Debug, Clone)]
pub struct StackEntryInput {
    /// Component uid.
    pub uid: String,
    /// Selected version.
    pub version: String,
    /// Pin against bulk updates.
    pub pinned: bool,
    /// Meta-resolved or custom.
    pub source: ComponentSource,
    /// Full payload for custom components.
    pub custom: Option<serde_json::Value>,
}

/// A mod file inside an instance, as an entity — schema `mods`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Mod {
    /// Row id.
    pub id: i64,
    /// Owning instance.
    pub instance_id: i64,
    /// File name inside the instance's mods folder (unique per instance).
    pub file_name: String,
    /// Lowercase-hex sha1 (platform hash recovery).
    pub sha1: Option<String>,
    /// Lowercase-hex sha512 (Modrinth dialect).
    pub sha512: Option<String>,
    /// CurseForge murmur2 fingerprint, decimal string.
    pub murmur2: Option<String>,
    /// Human-readable name when known.
    pub display_name: Option<String>,
    /// Mod version when known.
    pub version: Option<String>,
    /// Loader ecosystem.
    pub ecosystem: Ecosystem,
    /// Enabled = participates in launches.
    pub enabled: bool,
    /// Platform slug (`curseforge` / `modrinth`) when identified.
    pub platform: Option<String>,
    /// Platform project id.
    pub platform_project_id: Option<String>,
    /// Platform file id.
    pub platform_file_id: Option<String>,
    /// Native-port pipeline status (ADR-0003).
    pub port_status: PortStatus,
}

/// Input for [`crate::store::Store::upsert_mod`].
#[derive(Debug, Clone)]
pub struct NewMod {
    /// Owning instance.
    pub instance_id: i64,
    /// File name inside the mods folder.
    pub file_name: String,
    /// Lowercase-hex sha1.
    pub sha1: Option<String>,
    /// Lowercase-hex sha512.
    pub sha512: Option<String>,
    /// CurseForge murmur2 fingerprint.
    pub murmur2: Option<String>,
    /// Human-readable name.
    pub display_name: Option<String>,
    /// Mod version.
    pub version: Option<String>,
    /// Loader ecosystem.
    pub ecosystem: Ecosystem,
    /// Participates in launches.
    pub enabled: bool,
    /// Platform slug.
    pub platform: Option<String>,
    /// Platform project id.
    pub platform_project_id: Option<String>,
    /// Platform file id.
    pub platform_file_id: Option<String>,
    /// Port pipeline status.
    pub port_status: PortStatus,
}

/// A signed-in account — schema `accounts`. Tokens are NEVER here (SOUL §36);
/// `vault_ref` names the OS-credential-vault entry that holds them.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Account {
    /// Row id.
    pub id: i64,
    /// Minecraft profile UUID (canonical, undashed lowercase).
    pub uuid: String,
    /// Profile name at last verification.
    pub username: String,
    /// Account family.
    pub kind: AccountKind,
    /// OS credential vault entry name.
    pub vault_ref: String,
    /// Last successful entitlement verification (Unix seconds).
    pub entitlement_verified_at: Option<i64>,
    /// CPL-2/ADR-0011 offline window expiry (Unix seconds).
    pub entitlement_window_expires_at: Option<i64>,
}

impl Account {
    /// Whether the CPL-2 entitlement window covers `now` — the launch gate:
    /// no unexpired window, no launch (COMPLIANCE §1).
    pub fn entitlement_valid(&self, now: i64) -> bool {
        self.entitlement_window_expires_at.is_some_and(|expires| now < expires)
    }
}

/// A usable Java installation — schema `java_runtimes`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct JavaRuntime {
    /// Row id.
    pub id: i64,
    /// Absolute path to the java executable.
    pub path: String,
    /// Vendor string when known.
    pub vendor: Option<String>,
    /// Full version string (e.g. `21.0.3`).
    pub version: String,
    /// Architecture (e.g. `x86_64`, `aarch64`).
    pub arch: String,
    /// Detected on machine vs installed from Mojang manifests.
    pub source: JavaSource,
    /// Last successful validation run (Unix seconds).
    pub valid_at: Option<i64>,
}

/// One game-process session — schema `processes`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProcessSession {
    /// Row id.
    pub id: i64,
    /// Instance that was launched.
    pub instance_id: i64,
    /// OS process id while known.
    pub pid: Option<i64>,
    /// Launch time (Unix seconds).
    pub started_at: i64,
    /// Exit time (Unix seconds).
    pub ended_at: Option<i64>,
    /// Process exit code.
    pub exit_code: Option<i64>,
    /// Session document (log paths, account used, launch args digest, …).
    pub session: serde_json::Value,
}

/// A resumable job — schema `jobs` (LNC-5).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Job {
    /// Row id.
    pub id: i64,
    /// Job kind (e.g. `"install"`, `"repair"`).
    pub kind: String,
    /// Related instance, when any.
    pub instance_id: Option<i64>,
    /// Lifecycle state.
    pub state: JobState,
    /// Kind-specific progress document (what the UI renders).
    pub progress: serde_json::Value,
    /// Exact resume point; `None` once done or never started.
    pub resume: Option<serde_json::Value>,
    /// Persisted failure detail for failed jobs.
    pub error: Option<serde_json::Value>,
    /// Creation time (Unix seconds).
    pub created_at: i64,
    /// Last persistence time (Unix seconds).
    pub updated_at: i64,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn enum_round_trips() {
        for state in
            [JobState::Pending, JobState::Running, JobState::Paused, JobState::Failed, JobState::Done]
        {
            assert_eq!(JobState::parse(state.as_str()).expect("round trip"), state);
        }
        assert!(JobState::parse("exploded").is_err());
        assert_eq!(JavaSource::MojangManifest.as_str(), "mojang-manifest");
    }

    #[test]
    fn job_state_machine() {
        use JobState::{Done, Failed, Paused, Pending, Running};
        let legal = [
            (Pending, Running),
            (Running, Paused),
            (Running, Failed),
            (Running, Done),
            (Paused, Running),
            (Failed, Running),
            // Setup can fail before the runner ever starts (unresolvable stack,
            // unreachable meta). Without these the failure has nowhere to go and
            // the job sits at `pending` looking like it is about to run.
            (Pending, Failed),
            (Paused, Failed),
        ];
        for (from, to) in legal {
            assert!(from.can_transition_to(to), "{from} -> {to} must be legal");
        }
        let illegal = [
            (Pending, Done),
            (Pending, Paused),
            (Done, Running),
            (Paused, Done),
            (Failed, Done),
            (Running, Pending),
            (Done, Failed),
        ];
        for (from, to) in illegal {
            assert!(!from.can_transition_to(to), "{from} -> {to} must be illegal");
        }
    }

    #[test]
    fn entitlement_window_gate() {
        let mut account = Account {
            id: 1,
            uuid: "u".into(),
            username: "n".into(),
            kind: AccountKind::Microsoft,
            vault_ref: "omni/msa/u".into(),
            entitlement_verified_at: Some(1000),
            entitlement_window_expires_at: Some(2000),
        };
        assert!(account.entitlement_valid(1999));
        assert!(!account.entitlement_valid(2000));
        account.entitlement_window_expires_at = None;
        assert!(!account.entitlement_valid(0));
    }
}
