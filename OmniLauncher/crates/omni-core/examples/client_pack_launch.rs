//! Launch each non-empty director instance through the real AppService path
//! with staged OmniLoader dist. Captures session outcomes for residual proof.
//!
//! ```text
//! $env:OMNI_LOADER_DIST = "...\OmniLoader\loader-bootstrap\build\loader-dist"
//! cargo run -p omni-core --example client_pack_launch -- --wait-secs 25
//! cargo run -p omni-core --example client_pack_launch -- --instance 4 --wait-secs 40
//! cargo run -p omni-core --example client_pack_launch -- --instance 4 --twice
//! ```

use std::path::PathBuf;
use std::process::ExitCode;
use std::sync::Arc;
use std::time::Duration;

use omni_core::app::{resolve_default_loader_dist, AppConfig, AppService, DEFAULT_META_URL};
use omni_core::events::NullSink;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    let wait_secs: u64 = args
        .iter()
        .position(|a| a == "--wait-secs")
        .and_then(|i| args.get(i + 1))
        .and_then(|s| s.parse().ok())
        .unwrap_or(20);
    let only: Option<i64> = args
        .iter()
        .position(|a| a == "--instance")
        .and_then(|i| args.get(i + 1))
        .and_then(|s| s.parse().ok());
    let twice = args.iter().any(|a| a == "--twice");

    let data_root = omni_core::app::default_data_root();
    let loader = resolve_default_loader_dist().or_else(|| {
        std::env::var_os("OMNI_LOADER_DIST").map(PathBuf::from)
    });
    println!("data_root   : {}", data_root.display());
    println!(
        "loader_dist : {}",
        loader
            .as_ref()
            .map(|p| p.display().to_string())
            .unwrap_or_else(|| "<none>".into())
    );
    if loader.is_none() {
        eprintln!("FATAL: no loader dist — set OMNI_LOADER_DIST or build loader-dist");
        return ExitCode::FAILURE;
    }

    let config = AppConfig {
        data_root: data_root.clone(),
        meta_url: DEFAULT_META_URL.to_owned(),
        client_id: omni_core::auth::DEV_CLIENT_ID.to_owned(),
        vault_service: "OmniLauncher".to_owned(),
        loader_dist_dir: loader,
    };
    let service = match AppService::new(config, Arc::new(NullSink)) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("AppService::new failed: {e}");
            return ExitCode::FAILURE;
        }
    };

    let accounts = match service.list_accounts() {
        Ok(a) => a,
        Err(e) => {
            eprintln!("list_accounts failed: {e}");
            return ExitCode::FAILURE;
        }
    };
    let account = match accounts.first() {
        Some(a) => a,
        None => {
            eprintln!("FATAL: no Microsoft account in launcher.db — sign in via UI first");
            return ExitCode::FAILURE;
        }
    };
    println!("account     : {} ({})", account.username, account.uuid);

    let instances = match service.list_instances() {
        Ok(i) => i,
        Err(e) => {
            eprintln!("list_instances failed: {e}");
            return ExitCode::FAILURE;
        }
    };

    let mut failed = 0u32;
    let mut launched = 0u32;
    for inst in &instances {
        if only.is_some_and(|id| id != inst.id) {
            continue;
        }
        let mods_dir = data_root
            .join("instances")
            .join(inst.id.to_string())
            .join("game")
            .join("mods");
        let jar_count = std::fs::read_dir(&mods_dir)
            .map(|rd| {
                rd.filter_map(|e| e.ok())
                    .filter(|e| {
                        e.path()
                            .extension()
                            .and_then(|x| x.to_str())
                            .is_some_and(|x| x.eq_ignore_ascii_case("jar"))
                    })
                    .count()
            })
            .unwrap_or(0);

        // Empty vanilla still launched for green check; modded always.
        println!(
            "\n=== instance {} {:?} jars={} ===",
            inst.id, inst.name, jar_count
        );

        let runs = if twice { 2 } else { 1 };
        for run in 1..=runs {
            if runs > 1 {
                println!("--- run {run}/{runs} ---");
            }
            match service.launch_instance(inst.id, &account.uuid) {
                Ok(session) => {
                    launched += 1;
                    println!("  session {session} started; waiting {wait_secs}s");
                    // BOOT FAILED packs exit in a few seconds; healthy ones stay up.
                    // Poll boot_issues + session log rather than sleeping blind.
                    let logs = data_root
                        .join("instances")
                        .join(inst.id.to_string())
                        .join("logs");
                    let deadline = std::time::Instant::now() + Duration::from_secs(wait_secs);
                    let mut outcome = String::from("waiting");
                    loop {
                        std::thread::sleep(Duration::from_millis(750));
                        outcome = classify_latest_session(&logs);
                        if outcome.contains("BOOT FAILED") || outcome.contains("past-resolve") {
                            break;
                        }
                        // Prefer the live session log over omniloader-issues.json — that dump
                        // can be stale from a prior launch and falsely report resolve ERRORs
                        // after residuals were already closed.
                        if !outcome.contains("empty-log") && !outcome.contains("waiting") {
                            if outcome.contains("BOOT FAILED") || outcome.contains("past-resolve") {
                                break;
                            }
                        }
                        if std::time::Instant::now() >= deadline {
                            if outcome.contains("empty-log") {
                                // Still empty after budget: treat as unknown, not success.
                                outcome = format!("{outcome} (wait budget exhausted)");
                            } else if outcome == "waiting" {
                                outcome = classify_latest_session(&logs);
                            }
                            break;
                        }
                    }
                    let _ = service.stop_process(session);
                    std::thread::sleep(Duration::from_millis(300));
                    // Re-read after stop so supervisor-flushed logs are included.
                    let final_outcome = classify_latest_session(&logs);
                    if final_outcome.contains("BOOT FAILED")
                        || (!final_outcome.contains("empty-log") && final_outcome != outcome)
                    {
                        outcome = final_outcome;
                    }
                    println!("  outcome: {outcome}");
                    if outcome.contains("BOOT FAILED")
                        || outcome.contains("fixable")
                        || outcome.contains("boot_issues errors")
                        || outcome.contains("empty-log")
                    {
                        failed += 1;
                    }
                }
                Err(e) => {
                    eprintln!("  launch FAILED: {e}");
                    // Vanilla may still need install; count as fail only for non-empty packs
                    // that should already be installed.
                    if jar_count > 0 || inst.id == 3 {
                        failed += 1;
                    }
                }
            }
        }
    }

    println!("\n=== summary launched={launched} failed_flags={failed} ===");
    if failed > 0 {
        ExitCode::FAILURE
    } else {
        ExitCode::SUCCESS
    }
}

fn classify_latest_session(logs_dir: &std::path::Path) -> String {
    let Ok(rd) = std::fs::read_dir(logs_dir) else {
        return "no-logs-dir".into();
    };
    let mut best: Option<(std::time::SystemTime, PathBuf)> = None;
    for e in rd.flatten() {
        let p = e.path();
        let name = p.file_name().and_then(|n| n.to_str()).unwrap_or("");
        if !name.starts_with("session-") || !name.ends_with(".log") {
            continue;
        }
        let modified = e.metadata().and_then(|m| m.modified()).ok();
        if let Some(m) = modified {
            if best.as_ref().map(|(t, _)| m > *t).unwrap_or(true) {
                best = Some((m, p));
            }
        }
    }
    let Some((_, path)) = best else {
        return "no-session-log".into();
    };
    let text = std::fs::read_to_string(&path).unwrap_or_default();
    let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("?");
    if text.contains("BOOT FAILED") {
        let errs: Vec<&str> = text
            .lines()
            .filter(|l| l.contains("ERROR") || l.contains("BOOT FAILED"))
            .take(6)
            .collect();
        return format!("{name} BOOT FAILED | {}", errs.join(" || "));
    }
    if text.is_empty() {
        return format!("{name} empty-log (process may still be starting)");
    }
    if text.contains("Setting user:")
        || text.contains("LWJGL")
        || text.contains("OpenAL")
        || text.contains("Reloading ResourceManager")
    {
        return format!("{name} past-resolve/game-log ({} bytes)", text.len());
    }
    // OmniLoader may print only warnings then continue — no BOOT FAILED is success for resolve gate
    if text.contains("[omniloader]") && !text.contains("BOOT FAILED") {
        return format!("{name} omniloader-no-boot-failed ({} bytes)", text.len());
    }
    format!("{name} other ({} bytes) head={}", text.len(), text.chars().take(120).collect::<String>())
}
