//! Developer auth tool (and live-endpoint probe) for the LNC-4 chain.
//!
//! ```text
//! cargo run -p omni-core --example auth_probe            # probe: request a device
//!                                                        # code, print it, exit
//! cargo run -p omni-core --example auth_probe -- --login # full interactive login:
//!                                                        # poll + complete the chain
//! ```
//!
//! The probe mode performs exactly one unauthenticated request (a device-code
//! grant that expires unused in ~15 minutes) — it validates the live endpoint
//! shape and the Azure app registration without touching any account. Login
//! mode is the real development sign-in; until Mojang's client-ID approval
//! lands it fails at the Minecraft-services step with the typed 403 message.

use std::process::ExitCode;
use std::sync::Arc;
use std::time::Duration;

use omni_core::auth::{AuthService, DEV_CLIENT_ID, KeyringVault, UreqAuthHttp};
use omni_core::clock::SystemClock;
use omni_core::Db;

fn main() -> ExitCode {
    let login = std::env::args().any(|arg| arg == "--login");
    let http = UreqAuthHttp::new();

    if !login {
        match omni_core::auth::device_code_begin(&http, DEV_CLIENT_ID) {
            Ok(prompt) => {
                println!("device-code endpoint OK");
                println!("  user code        : {}", prompt.user_code);
                println!("  verification uri : {}", prompt.verification_uri);
                println!("  expires in       : {}s (left unused)", prompt.expires_in.as_secs());
                println!("probe complete — no sign-in performed.");
                return ExitCode::SUCCESS;
            }
            Err(err) => {
                eprintln!("probe FAILED: {err}");
                return ExitCode::FAILURE;
            }
        }
    }

    // Full interactive dev login against a scratch database next to the exe.
    let db = match Db::open("auth_probe_dev.db", Arc::new(SystemClock)) {
        Ok(db) => db,
        Err(err) => {
            eprintln!("cannot open dev database: {err}");
            return ExitCode::FAILURE;
        }
    };
    let vault = KeyringVault::new("OmniLauncher-dev");
    let service = AuthService::new(&http, &vault, DEV_CLIENT_ID);
    let outcome = service.login_device_code(
        &db,
        &|prompt| {
            println!("To sign in, open {} and enter code: {}", prompt.verification_uri, prompt.user_code);
        },
        &|delay: Duration| std::thread::sleep(delay),
    );
    match outcome {
        Ok(session) => {
            println!(
                "signed in as {} ({}); entitlement window until {}",
                session.profile.name,
                session.profile.uuid,
                session.account.entitlement_window_expires_at.unwrap_or_default()
            );
            ExitCode::SUCCESS
        }
        Err(err) => {
            eprintln!("login failed: {err}");
            ExitCode::FAILURE
        }
    }
}
