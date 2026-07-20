//! Live install probe for a dedicated-server instance — the unblocked part of
//! the Phase 4 exit criterion.
//!
//! ```text
//! cargo run -p omni-core --example server_smoke                # install
//! cargo run -p omni-core --example server_smoke -- --accept-eula
//! ```
//!
//! Exercises the chain that needs no Microsoft account at all: meta resolve
//! against the live service, server-jar and library download with hash
//! verification, auto-Java provisioning, and (optionally) recording the
//! director's EULA acceptance.
//!
//! **It stops before launching.** `AppService::launch_instance` applies the
//! CPL-2 entitlement gate to every launch, servers included, and CPL-2's
//! wording is categorical: "no launch path exists without a verified
//! entitlement record". Reaching a spawn from here would mean either
//! fabricating a `LaunchAuth` or adding a public launch-without-auth method —
//! that is, deciding the CPL-2 scope question by implementing it. That
//! decision belongs to the director and to a superseding ADR, so this tool
//! does neither. Once the scope is settled the launch step lands here.
//!
//! **The EULA is not accepted for you.** `--accept-eula` records *your*
//! affirmation of Mojang's server EULA (<https://aka.ms/MinecraftEULA>) and
//! writes `eula.txt`. Run it yourself, having read the terms; nothing here
//! accepts on your behalf.

use std::process::ExitCode;
use std::sync::Arc;
use std::time::{Duration, Instant};

use omni_core::app::{AppConfig, AppService, DEFAULT_META_URL};
use omni_core::events::NullSink;
use omni_core::store::InstanceKind;

const GAME_VERSION: &str = "1.21.1";
const INSTANCE_NAME: &str = "server-smoke";

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    let accept_eula = args.iter().any(|arg| arg == "--accept-eula");

    let data_root = std::env::current_dir()
        .unwrap_or_else(|_| ".".into())
        .join("server-smoke-data");
    println!("data root : {}", data_root.display());
    println!("meta      : {DEFAULT_META_URL}");

    let config = AppConfig {
        data_root: data_root.clone(),
        meta_url: DEFAULT_META_URL.to_owned(),
        // Unused on this path: no account is signed in and none is needed.
        client_id: omni_core::auth::DEV_CLIENT_ID.to_owned(),
        vault_service: "OmniLauncher-server-smoke".to_owned(),
    };
    let service = match AppService::new(config, Arc::new(NullSink)) {
        Ok(service) => service,
        Err(err) => {
            eprintln!("cannot start app service: {err}");
            return ExitCode::FAILURE;
        }
    };

    // 1. Instance -------------------------------------------------------
    let instance = match existing_instance(&service).transpose() {
        Some(Ok(id)) => {
            println!("\n[1/3] reusing instance {id} ({INSTANCE_NAME})");
            id
        }
        Some(Err(err)) => {
            eprintln!("cannot list instances: {err}");
            return ExitCode::FAILURE;
        }
        None => match service.create_instance(INSTANCE_NAME, GAME_VERSION, InstanceKind::Server) {
            Ok(summary) => {
                println!("\n[1/3] created instance {} ({INSTANCE_NAME}, {GAME_VERSION})", summary.id);
                summary.id
            }
            Err(err) => {
                eprintln!("cannot create instance: {err}");
                return ExitCode::FAILURE;
            }
        },
    };

    // 2. Install --------------------------------------------------------
    println!("\n[2/3] install — resolving {GAME_VERSION} from meta and fetching artifacts");
    let started = Instant::now();
    let job_id = match service.start_install(instance) {
        Ok(id) => id,
        Err(err) => {
            eprintln!("cannot start install: {err}");
            return ExitCode::FAILURE;
        }
    };
    if let Err(err) = await_job(&service, job_id) {
        eprintln!("install FAILED: {err}");
        return ExitCode::FAILURE;
    }
    println!("      install complete in {:.1}s", started.elapsed().as_secs_f64());

    // 3. EULA -----------------------------------------------------------
    println!("\n[3/3] server EULA");
    if accept_eula {
        match service.accept_server_eula(instance) {
            Ok(()) => println!("      recorded your acceptance; eula.txt written"),
            Err(err) => {
                eprintln!("cannot record EULA acceptance: {err}");
                return ExitCode::FAILURE;
            }
        }
    } else {
        println!("      NOT accepted — this is yours to affirm, not mine.");
        println!("      Read https://aka.ms/MinecraftEULA, then re-run with:");
        println!("        cargo run -p omni-core --example server_smoke -- --accept-eula");
    }

    println!("\nINSTALL CHAIN VERIFIED — launch is gated on the CPL-2 scope decision");
    println!("  instance {instance} is installed and ready; see the module docs.");
    ExitCode::SUCCESS
}

/// Finds a previous smoke instance so re-runs exercise the warm-cache path.
fn existing_instance(service: &AppService) -> Result<Option<i64>, String> {
    let instances = service.list_instances().map_err(|err| err.to_string())?;
    Ok(instances.iter().find(|entry| entry.name == INSTANCE_NAME).map(|entry| entry.id))
}

/// Polls a job to completion, printing progress as it moves.
fn await_job(service: &AppService, job_id: i64) -> Result<(), String> {
    use omni_core::store::JobState;

    let mut last = String::new();
    loop {
        let jobs = service.list_jobs().map_err(|err| err.to_string())?;
        let job = jobs
            .into_iter()
            .find(|candidate| candidate.id == job_id)
            .ok_or_else(|| format!("job {job_id} vanished"))?;

        let phase = job.progress["phase"].as_str().unwrap_or("working").to_owned();
        let done = job.progress["completed"].as_u64().unwrap_or(0);
        let total = job.progress["total"].as_u64().unwrap_or(0);
        let line = if total > 0 {
            format!("      {phase}: {done}/{total}")
        } else {
            format!("      {phase}")
        };
        if line != last {
            println!("{line}");
            last = line;
        }

        match job.state {
            JobState::Done => return Ok(()),
            JobState::Failed => {
                let detail = job
                    .error
                    .map_or_else(|| "no detail recorded".to_owned(), |err| err.to_string());
                return Err(detail);
            }
            // Paused is a resume point, not an end state — but nothing here
            // pauses, so reaching it means the runner stopped unexpectedly and
            // waiting forever would be worse than saying so.
            JobState::Paused => {
                return Err("job paused unexpectedly — resume it from the app".to_owned());
            }
            JobState::Pending | JobState::Running => {
                std::thread::sleep(Duration::from_millis(500));
            }
        }
    }
}
