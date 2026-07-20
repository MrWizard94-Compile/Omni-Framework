//! The launch chain (LNC-5, D8): argument assembly from meta payloads,
//! classpath construction over the content-addressed cache, gated process
//! spawn with session recording, and the server variant with its explicit
//! EULA-acceptance step.
//!
//! Version-scope note (SOUL §4, TGT-1): the modern (1.13+) `arguments` shape
//! is fully supported; pre-1.13 `legacyArguments` are assembled by whitespace
//! split + substitution, which matches upstream templates (they contain no
//! quoted tokens). Extract-era natives (pre-modern LWJGL) are out of MVP
//! scope: for the 1.21.1 target, natives-classified jars belong on the
//! classpath exactly as the official launcher treats them, and LWJGL
//! self-extracts. Older versions become data + a small extract pass when the
//! census demands them (TGT-2).

use std::collections::BTreeMap;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};

use omni_meta_format::{Argument, ArgumentValue};

use crate::auth::LaunchAuth;
use crate::db::Db;
use crate::error::CoreError;
use crate::install::{ContentCache, Paths, ResolvedStack};
use crate::platform::{Platform, rules_allow};
use crate::store::{Instance, ProcessSession};

/// A fully assembled command, ready to spawn (kept separate from
/// `std::process::Command` so tests can assert on it).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LaunchCommand {
    /// Program (the java executable).
    pub program: PathBuf,
    /// Arguments in order.
    pub args: Vec<String>,
    /// Working directory (the instance game dir).
    pub cwd: PathBuf,
}

/// Inputs for building a launch command.
pub struct LaunchInputs<'a> {
    /// The instance row.
    pub instance: &'a Instance,
    /// The resolved stack used at install time.
    pub stack: &'a ResolvedStack,
    /// The launch authorization from [`crate::auth::AuthService::launch_gate`].
    pub auth: &'a LaunchAuth,
    /// The java executable to run.
    pub java_exe: PathBuf,
    /// Maximum heap, MiB.
    pub memory_mib: u64,
    /// Feature flags for conditional arguments (demo, resolution, quick-play).
    pub features: BTreeMap<String, bool>,
    /// Extra JVM arguments from instance settings.
    pub extra_jvm_args: Vec<String>,
}

/// The lowest heap the game can actually start with (MiB). Below this the JVM
/// either refuses the value outright or dies reserving the object heap, so no
/// value under it is ever worth emitting.
const MIN_HEAP_MIB: u64 = 512;

/// The heap size to actually write into `-Xmx`, never below the floor.
///
/// The single place a heap argument is emitted, and therefore the one place
/// that must guarantee the JVM is never handed something it will reject. The
/// caller's value has normally already been through the settings policy, but
/// this floor holds regardless of how the inputs were built — a `0` here would
/// otherwise become `-Xmx0M`, which is exactly the argument that crashed a real
/// launch on 2026-07-20 with "Invalid maximum heap size".
fn heap_mib(requested: u64) -> u64 {
    requested.max(MIN_HEAP_MIB)
}

fn substitute(template: &str, values: &BTreeMap<&str, String>) -> String {
    let mut out = template.to_owned();
    for (key, value) in values {
        out = out.replace(&format!("${{{key}}}"), value);
    }
    // Unknown placeholders stay verbatim (visible in logs, never silently
    // swallowed) — matches corpus launcher behavior.
    out
}

fn push_argument(
    out: &mut Vec<String>,
    argument: &Argument,
    platform: &Platform,
    features: &BTreeMap<String, bool>,
    values: &BTreeMap<&str, String>,
) {
    match argument {
        Argument::Plain(token) => out.push(substitute(token, values)),
        Argument::Conditional { rules, value } => {
            if rules_allow(rules, platform, features) {
                match value {
                    ArgumentValue::One(token) => out.push(substitute(token, values)),
                    ArgumentValue::Many(tokens) => {
                        out.extend(tokens.iter().map(|token| substitute(token, values)));
                    }
                }
            }
        }
    }
}

/// The auth identity a command is built with.
struct AuthValues {
    name: String,
    uuid: String,
    token: String,
}

fn auth_values(auth: &LaunchAuth) -> AuthValues {
    match auth {
        LaunchAuth::Online(session) => AuthValues {
            name: session.profile.name.clone(),
            uuid: session.profile.uuid.clone(),
            token: session.mc_access_token.clone(),
        },
        // Offline-within-window (CPL-2): a verified owner without network.
        // The cached identity launches; the token is a non-secret placeholder
        // (online services are unreachable by definition).
        LaunchAuth::OfflineWithinWindow { account, profile_uuid, .. } => AuthValues {
            name: account.username.clone(),
            uuid: profile_uuid.clone(),
            token: "offline-entitlement-window".to_owned(),
        },
    }
}

/// Builds the client launch command from the instance's resolved stack.
///
/// # Errors
///
/// [`CoreError::NotFound`] when the stack has no `net.minecraft` payload or
/// it lacks a `mainClass`.
pub fn build_client_command(
    paths: &Paths,
    inputs: &LaunchInputs<'_>,
    platform: &Platform,
) -> Result<LaunchCommand, CoreError> {
    let game = inputs.stack.game()?;
    let main_class = game
        .main_class
        .clone()
        .ok_or(CoreError::NotFound { what: "mainClass", id: inputs.instance.id })?;
    let cache = ContentCache::new(paths.objects_root());

    // Classpath: every rule-allowed library of every stack payload, plus the
    // game jar — all pointing into the immutable content cache (hmcl-grade
    // cross-instance sharing; the game dir stays per-instance).
    let separator = if cfg!(windows) { ";" } else { ":" };
    let mut classpath_entries: Vec<String> = Vec::new();
    for payload in &inputs.stack.payloads {
        for library in &payload.libraries {
            if rules_allow(&library.rules, platform, &inputs.features) {
                classpath_entries
                    .push(cache.object_path(&library.sha1).to_string_lossy().into_owned());
            }
        }
    }
    let game_jar_sha1 = game
        .data
        .get("downloads")
        .and_then(|downloads| downloads["client"]["sha1"].as_str())
        .ok_or(CoreError::NotFound { what: "client jar download", id: inputs.instance.id })?;
    classpath_entries.push(cache.object_path(game_jar_sha1).to_string_lossy().into_owned());
    let classpath = classpath_entries.join(separator);

    let auth = auth_values(inputs.auth);
    let game_dir = paths.game_dir(inputs.instance.id);
    let assets_root = paths.assets_root();
    let natives_dir = paths.natives_dir(inputs.instance.id);
    let mut values: BTreeMap<&str, String> = BTreeMap::new();
    values.insert("auth_player_name", auth.name);
    values.insert("auth_uuid", auth.uuid);
    values.insert("auth_access_token", auth.token);
    values.insert("auth_xuid", String::new());
    values.insert("clientid", crate::auth::DEV_CLIENT_ID.to_owned());
    values.insert("user_type", "msa".to_owned());
    values.insert("version_name", game.version.clone());
    values.insert("version_type", game.release_type.clone());
    values.insert("game_directory", game_dir.to_string_lossy().into_owned());
    values.insert("assets_root", assets_root.to_string_lossy().into_owned());
    values.insert(
        "assets_index_name",
        game.asset_index.as_ref().map(|index| index.id.clone()).unwrap_or_default(),
    );
    values.insert("natives_directory", natives_dir.to_string_lossy().into_owned());
    values.insert("launcher_name", "OmniLauncher".to_owned());
    values.insert("launcher_version", env!("CARGO_PKG_VERSION").to_owned());
    values.insert("classpath", classpath);

    let mut args: Vec<String> = vec![format!("-Xmx{}M", heap_mib(inputs.memory_mib))];
    args.extend(inputs.extra_jvm_args.iter().cloned());
    match (&game.arguments, &game.legacy_arguments) {
        (Some(arguments), _) => {
            for argument in &arguments.jvm {
                push_argument(&mut args, argument, platform, &inputs.features, &values);
            }
            args.push(main_class);
            for argument in &arguments.game {
                push_argument(&mut args, argument, platform, &inputs.features, &values);
            }
        }
        (None, Some(legacy)) => {
            // Pre-1.13 template: no JVM args upstream; supply the classpath.
            args.push("-cp".to_owned());
            args.push(values["classpath"].clone());
            args.push(main_class);
            args.extend(legacy.split_whitespace().map(|token| substitute(token, &values)));
        }
        (None, None) => {
            return Err(CoreError::NotFound { what: "launch arguments", id: inputs.instance.id });
        }
    }

    Ok(LaunchCommand { program: inputs.java_exe.clone(), args, cwd: game_dir })
}

/// The server EULA settings key recorded on explicit acceptance (D8).
pub const EULA_SETTINGS_KEY: &str = "serverEulaAcceptedAt";

/// Records the user's **explicit** acceptance of the Minecraft server EULA
/// for one instance and writes `eula.txt`. This function is only ever called
/// from a direct user action (the UI's acceptance dialog) — never implicitly.
///
/// # Errors
///
/// [`CoreError::Io`]/[`CoreError::Db`] on persistence failures.
pub fn accept_server_eula(db: &Db, paths: &Paths, instance_id: i64) -> Result<(), CoreError> {
    let game_dir = paths.game_dir(instance_id);
    std::fs::create_dir_all(&game_dir).map_err(|e| CoreError::Io {
        action: "create game dir",
        path: game_dir.clone(),
        source: e,
    })?;
    let eula_path = game_dir.join("eula.txt");
    std::fs::write(
        &eula_path,
        "# Accepted through OmniLauncher by explicit user action (see instance settings).\neula=true\n",
    )
    .map_err(|e| CoreError::Io { action: "write eula.txt", path: eula_path, source: e })?;
    db.with_store(|store| {
        let mut instance = store
            .instance(instance_id)?
            .ok_or(CoreError::NotFound { what: "instance", id: instance_id })?;
        if !instance.settings.is_object() {
            instance.settings = serde_json::json!({});
        }
        instance.settings[EULA_SETTINGS_KEY] = serde_json::json!(store.clock_now());
        store.update_instance_settings(instance_id, &instance.settings)
    })
}

/// Builds the server launch command. Refuses (typed) unless
/// [`accept_server_eula`] has recorded acceptance for this instance.
///
/// # Errors
///
/// [`CoreError::NotFound`] with `what = "server EULA acceptance"` when the
/// acceptance record is absent; otherwise as in the client path.
pub fn build_server_command(
    db: &Db,
    paths: &Paths,
    inputs: &LaunchInputs<'_>,
) -> Result<LaunchCommand, CoreError> {
    let accepted = db.with_store(|store| {
        Ok(store
            .instance(inputs.instance.id)?
            .is_some_and(|instance| instance.settings[EULA_SETTINGS_KEY].is_number()))
    })?;
    if !accepted {
        return Err(CoreError::NotFound {
            what: "server EULA acceptance",
            id: inputs.instance.id,
        });
    }
    let game = inputs.stack.game()?;
    let cache = ContentCache::new(paths.objects_root());
    let server_sha1 = game
        .data
        .get("downloads")
        .and_then(|downloads| downloads["server"]["sha1"].as_str())
        .ok_or(CoreError::NotFound { what: "server jar download", id: inputs.instance.id })?;
    let mut args = vec![format!("-Xmx{}M", heap_mib(inputs.memory_mib))];
    args.extend(inputs.extra_jvm_args.iter().cloned());
    args.push("-jar".to_owned());
    args.push(cache.object_path(server_sha1).to_string_lossy().into_owned());
    args.push("nogui".to_owned());
    Ok(LaunchCommand {
        program: inputs.java_exe.clone(),
        args,
        cwd: paths.game_dir(inputs.instance.id),
    })
}

/// Spawns a launch command with stdout/stderr captured to the instance's log
/// dir, records the process session row, and returns both.
///
/// # Errors
///
/// [`CoreError::Io`] on spawn/log failures; [`CoreError::Db`] on recording.
pub fn spawn_recorded(
    db: &Db,
    paths: &Paths,
    instance_id: i64,
    command: &LaunchCommand,
) -> Result<(ProcessSession, Child), CoreError> {
    let logs_dir = paths.logs_dir(instance_id);
    std::fs::create_dir_all(&logs_dir).map_err(|e| CoreError::Io {
        action: "create log dir",
        path: logs_dir.clone(),
        source: e,
    })?;
    let log_path = logs_dir.join(format!("session-{}.log", db.clock().now_unix()));
    let log_file = std::fs::File::create(&log_path).map_err(|e| CoreError::Io {
        action: "create session log",
        path: log_path.clone(),
        source: e,
    })?;
    let log_err = log_file.try_clone().map_err(|e| CoreError::Io {
        action: "clone session log handle",
        path: log_path.clone(),
        source: e,
    })?;
    std::fs::create_dir_all(&command.cwd).map_err(|e| CoreError::Io {
        action: "create game dir",
        path: command.cwd.clone(),
        source: e,
    })?;
    let child = Command::new(&command.program)
        .args(&command.args)
        .current_dir(&command.cwd)
        .stdin(Stdio::null())
        .stdout(Stdio::from(log_file))
        .stderr(Stdio::from(log_err))
        .spawn()
        .map_err(|e| CoreError::Io {
            action: "spawn game process",
            path: command.program.clone(),
            source: e,
        })?;
    let session = db.with_store(|store| {
        store.record_process_start(
            instance_id,
            Some(i64::from(child.id())),
            &serde_json::json!({ "log": log_path.to_string_lossy(), "program": command.program.to_string_lossy() }),
        )
    })?;
    Ok((session, child))
}

/// Waits for a spawned process, records its exit, stamps `last_played_at`,
/// and returns the exit code (`-1` when unavailable).
///
/// # Errors
///
/// [`CoreError::Io`] when waiting fails; [`CoreError::Db`] on recording.
pub fn wait_and_record(
    db: &Db,
    session: &ProcessSession,
    mut child: Child,
) -> Result<i64, CoreError> {
    let status = child.wait().map_err(|e| CoreError::Io {
        action: "wait for game process",
        path: PathBuf::from("child"),
        source: e,
    })?;
    let code = i64::from(status.code().unwrap_or(-1));
    db.with_store(|store| {
        store.record_process_exit(session.id, code)?;
        store.touch_last_played(session.instance_id)
    })?;
    Ok(code)
}

#[cfg(test)]
mod heap_tests {
    use super::{heap_mib, MIN_HEAP_MIB};

    #[test]
    fn zero_is_floored_never_emitted_as_xmx0m() {
        // The exact 2026-07-20 crash: a stored 0 reached the arg builder and
        // became `-Xmx0M`, which the JVM rejects as "Invalid maximum heap
        // size". The floor is the last line of defence, independent of the
        // settings policy upstream.
        assert_eq!(heap_mib(0), MIN_HEAP_MIB);
        assert_eq!(format!("-Xmx{}M", heap_mib(0)), format!("-Xmx{MIN_HEAP_MIB}M"));
    }

    #[test]
    fn sub_floor_values_are_raised_to_the_floor() {
        assert_eq!(heap_mib(1), MIN_HEAP_MIB);
        assert_eq!(heap_mib(MIN_HEAP_MIB - 1), MIN_HEAP_MIB);
    }

    #[test]
    fn viable_values_pass_through_untouched() {
        // A real modpack heap (10 GB) must not be clamped down.
        assert_eq!(heap_mib(MIN_HEAP_MIB), MIN_HEAP_MIB);
        assert_eq!(heap_mib(4096), 4096);
        assert_eq!(heap_mib(10240), 10240);
    }
}
