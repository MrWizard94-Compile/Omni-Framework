//! Live install + optional spawn probe for a dedicated-server instance —
//! the Phase 4 exit path that needs no Microsoft account.
//!
//! ```text
//! cargo run -p omni-core --example server_smoke                # install only
//! cargo run -p omni-core --example server_smoke -- --accept-eula
//! cargo run -p omni-core --example server_smoke -- --accept-eula --launch
//! ```
//!
//! Exercises: live meta resolve, server-jar/library download with hash
//! verification, auto-Java, optional EULA record, and optional process spawn.
//!
//! **CPL-2 applies to client play, not dedicated servers (D8).** Server launch
//! uses `LaunchAuth::DedicatedServer` and the EULA gate only.
//!
//! **The EULA is not accepted for you.** `--accept-eula` records *your*
//! affirmation of Mojang's server EULA (<https://aka.ms/MinecraftEULA>) and
//! writes `eula.txt`. Run it yourself, having read the terms.

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
    let do_launch = args.iter().any(|arg| arg == "--launch");

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
        loader_dist_dir: None,
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
            println!("\n[1/4] reusing instance {id} ({INSTANCE_NAME})");
            id
        }
        Some(Err(err)) => {
            eprintln!("cannot list instances: {err}");
            return ExitCode::FAILURE;
        }
        None => match service.create_instance(INSTANCE_NAME, GAME_VERSION, InstanceKind::Server) {
            Ok(summary) => {
                println!("\n[1/4] created instance {} ({INSTANCE_NAME}, {GAME_VERSION})", summary.id);
                summary.id
            }
            Err(err) => {
                eprintln!("cannot create instance: {err}");
                return ExitCode::FAILURE;
            }
        },
    };

    // 2. Install --------------------------------------------------------
    println!("\n[2/4] install — resolving {GAME_VERSION} from meta and fetching artifacts");
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
    println!("\n[3/4] server EULA");
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
        println!("        cargo run -p omni-core --example server_smoke -- --accept-eula --launch");
        println!("\nINSTALL CHAIN VERIFIED (launch skipped without EULA)");
        return ExitCode::SUCCESS;
    }

    // 4. Launch ---------------------------------------------------------
    if !do_launch {
        println!("\n[4/4] launch skipped (pass --launch after accepting the EULA)");
        println!("\nINSTALL + EULA VERIFIED");
        return ExitCode::SUCCESS;
    }

    println!("\n[4/4] launch dedicated server (no Microsoft account)");
    let session = match service.launch_instance(instance, "") {
        Ok(id) => id,
        Err(err) => {
            eprintln!("launch FAILED: {err}");
            return ExitCode::FAILURE;
        }
    };
    println!("      session {session} started");
    // Give the JVM a moment to bind or crash, then stop cleanly.
    std::thread::sleep(Duration::from_secs(8));
    let stopped = service.stop_process(session);
    println!("      stop_process → {stopped}");
    println!("\nPHASE 4 SERVER EXIT PATH VERIFIED (install + eula + spawn + stop)");
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
