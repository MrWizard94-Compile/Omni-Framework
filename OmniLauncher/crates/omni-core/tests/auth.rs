//! Auth-chain integration tests over recorded fixtures (ARCHITECTURE §8:
//! never live endpoints, never real secrets). The `ScriptedHttp` transport
//! replays realistic response bodies per endpoint and records every request,
//! so tests assert both outcomes and wire behavior.
#![expect(
    clippy::redundant_closure_for_method_calls,
    reason = "with_store takes a closure over &mut Store<'_>; clippy's suggested bare \
              method paths fail HRTB inference (`implementation of FnOnce is not \
              general enough`), so these closures are load-bearing"
)]

use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde_json::json;

use omni_core::auth::{
    AuthError, AuthHttp, AuthService, HttpResponse, LaunchAuth, MemoryVault, TokenVault,
    XstsErrorKind, DEV_CLIENT_ID, ENTITLEMENT_WINDOW_SECS,
};
use omni_core::clock::FixedClock;
use omni_core::Db;

const NOW: i64 = 1_800_000_000;

/// One scripted exchange: URL substring -> canned response.
struct Rule {
    url_contains: &'static str,
    status: u16,
    body: serde_json::Value,
    /// 0 = unlimited.
    max_uses: usize,
    uses: usize,
}

/// Fixture transport: matches requests to rules in order, records the wire.
#[derive(Default)]
struct ScriptedHttp {
    rules: Mutex<Vec<Rule>>,
    log: Mutex<Vec<String>>,
}

impl ScriptedHttp {
    fn new() -> Self {
        Self::default()
    }

    fn rule(self, url_contains: &'static str, status: u16, body: serde_json::Value) -> Self {
        self.rule_limited(url_contains, status, body, 0)
    }

    fn rule_limited(
        self,
        url_contains: &'static str,
        status: u16,
        body: serde_json::Value,
        max_uses: usize,
    ) -> Self {
        self.rules
            .lock()
            .expect("rules")
            .push(Rule { url_contains, status, body, max_uses, uses: 0 });
        self
    }

    fn requests(&self) -> Vec<String> {
        self.log.lock().expect("log").clone()
    }

    fn respond(&self, url: &str) -> Result<HttpResponse, AuthError> {
        self.log.lock().expect("log").push(url.to_owned());
        let mut rules = self.rules.lock().expect("rules");
        for rule in rules.iter_mut() {
            if url.contains(rule.url_contains) && (rule.max_uses == 0 || rule.uses < rule.max_uses)
            {
                rule.uses += 1;
                return Ok(HttpResponse {
                    status: rule.status,
                    body: serde_json::to_vec(&rule.body).expect("body"),
                });
            }
        }
        // No rule -> simulated network failure (also used to test offline).
        Err(AuthError::Http { url: url.to_owned(), message: "scripted: unreachable".into() })
    }
}

impl AuthHttp for ScriptedHttp {
    fn post_form(&self, url: &str, _form: &[(&str, &str)]) -> Result<HttpResponse, AuthError> {
        self.respond(url)
    }

    fn post_json(
        &self,
        url: &str,
        _body: &serde_json::Value,
        _bearer: Option<&str>,
    ) -> Result<HttpResponse, AuthError> {
        self.respond(url)
    }

    fn get_bearer(&self, url: &str, _bearer: &str) -> Result<HttpResponse, AuthError> {
        self.respond(url)
    }
}

fn fresh_db() -> (Db, Arc<FixedClock>) {
    let clock = Arc::new(FixedClock::new(NOW));
    (Db::open_in_memory(clock.clone()).expect("db"), clock)
}

/// The full happy chain, shaped like the real endpoints.
fn happy_chain() -> ScriptedHttp {
    ScriptedHttp::new()
        .rule(
            "/devicecode",
            200,
            json!({
                "device_code": "DEV-CODE", "user_code": "ABC-DEF",
                "verification_uri": "https://www.microsoft.com/link",
                "interval": 5, "expires_in": 900,
            }),
        )
        // First poll: pending; second: tokens (exercises the poll loop).
        .rule_limited("/token", 400, json!({ "error": "authorization_pending" }), 1)
        .rule(
            "/token",
            200,
            json!({
                "access_token": "MSA-ACCESS", "refresh_token": "MSA-REFRESH",
                "expires_in": 3600, "token_type": "Bearer",
            }),
        )
        .rule(
            "user.auth.xboxlive.com",
            200,
            json!({
                "Token": "XBL-TOKEN",
                "DisplayClaims": { "xui": [ { "uhs": "USERHASH" } ] },
            }),
        )
        .rule(
            "xsts.auth.xboxlive.com",
            200,
            json!({
                "Token": "XSTS-TOKEN",
                "DisplayClaims": { "xui": [ { "uhs": "USERHASH" } ] },
            }),
        )
        .rule(
            "login_with_xbox",
            200,
            json!({ "access_token": "MC-ACCESS", "expires_in": 86400 }),
        )
        .rule(
            "entitlements/mcstore",
            200,
            json!({ "items": [ { "name": "product_minecraft" }, { "name": "game_minecraft" } ] }),
        )
        .rule(
            "minecraft/profile",
            200,
            json!({ "id": "069a79f444e94726a5befca90e38aaf5", "name": "MrWizard94" }),
        )
}

#[test]
fn device_code_login_runs_the_whole_chain_and_stamps_the_window() {
    let (db, _clock) = fresh_db();
    let http = happy_chain();
    let vault = MemoryVault::new();
    let service = AuthService::new(&http, &vault, DEV_CLIENT_ID);

    let shown = Mutex::new(None);
    let session = service
        .login_device_code(
            &db,
            &|prompt| {
                *shown.lock().expect("prompt") =
                    Some((prompt.user_code.clone(), prompt.verification_uri.clone()));
            },
            &|_| {},
        )
        .expect("login succeeds");

    // The user was shown the device-code prompt.
    assert_eq!(
        shown.lock().expect("prompt").clone(),
        Some(("ABC-DEF".to_owned(), "https://www.microsoft.com/link".to_owned()))
    );
    // Session identity + token.
    assert_eq!(session.profile.name, "MrWizard94");
    assert_eq!(session.mc_access_token, "MC-ACCESS");
    // Account row exists, entitlement window stamped per ADR-0011.
    assert_eq!(session.account.entitlement_verified_at, Some(NOW));
    assert_eq!(
        session.account.entitlement_window_expires_at,
        Some(NOW + ENTITLEMENT_WINDOW_SECS)
    );
    assert!(session.account.entitlement_valid(NOW + ENTITLEMENT_WINDOW_SECS - 1));
    assert!(!session.account.entitlement_valid(NOW + ENTITLEMENT_WINDOW_SECS));
    // Vault holds tokens + the verification record; DB holds no tokens.
    let stored = vault
        .load(&session.account.vault_ref)
        .expect("vault")
        .expect("entry exists");
    assert_eq!(stored.msa_refresh_token, "MSA-REFRESH");
    assert_eq!(stored.entitlement.window_expires_at, NOW + ENTITLEMENT_WINDOW_SECS);
    // The wire saw the documented endpoint order.
    let wire = http.requests();
    let order = ["devicecode", "token", "token", "user.auth.xboxlive.com",
        "xsts.auth.xboxlive.com", "login_with_xbox", "entitlements", "profile"];
    assert_eq!(wire.len(), order.len(), "wire: {wire:?}");
    for (url, expected) in wire.iter().zip(order) {
        assert!(url.contains(expected), "expected {expected} in {url}");
    }
}

#[test]
fn xsts_refusals_carry_the_taxonomy() {
    let (db, _clock) = fresh_db();
    let http = ScriptedHttp::new()
        .rule("/devicecode", 200, json!({
            "device_code": "D", "user_code": "U", "verification_uri": "V",
            "interval": 1, "expires_in": 900,
        }))
        .rule("/token", 200, json!({
            "access_token": "A", "refresh_token": "R", "expires_in": 3600,
        }))
        .rule("user.auth.xboxlive.com", 200, json!({
            "Token": "X", "DisplayClaims": { "xui": [ { "uhs": "H" } ] },
        }))
        .rule("xsts.auth.xboxlive.com", 401, json!({
            "Identity": "0", "XErr": 2_148_916_238_u64, "Message": "",
        }));
    let vault = MemoryVault::new();
    let service = AuthService::new(&http, &vault, DEV_CLIENT_ID);

    let err = service
        .login_device_code(&db, &|_| {}, &|_| {})
        .expect_err("child account must fail");
    match err {
        AuthError::Xsts { xerr, kind } => {
            assert_eq!(xerr, 2_148_916_238);
            assert_eq!(kind, XstsErrorKind::ChildAccount);
            assert!(kind.remedy().contains("family"));
        }
        other => panic!("expected Xsts error, got {other:?}"),
    }
    // Nothing was persisted for the failed login.
    assert!(db.with_store(|store| store.accounts()).expect("accounts").is_empty());
}

#[test]
fn missing_profile_is_not_entitled_and_persists_nothing() {
    let (db, _clock) = fresh_db();
    let http = happy_chain();
    // Override profile with 404 by inserting a matching rule EARLIER is not
    // possible with the builder, so build a custom script instead.
    let http = {
        let base = http;
        let mut rules = base.rules.lock().expect("rules");
        let profile = rules.iter_mut().find(|r| r.url_contains == "minecraft/profile");
        let rule = profile.expect("profile rule");
        rule.status = 404;
        rule.body = json!({ "error": "NOT_FOUND" });
        drop(rules);
        base
    };
    let vault = MemoryVault::new();
    let service = AuthService::new(&http, &vault, DEV_CLIENT_ID);

    let err = service.login_device_code(&db, &|_| {}, &|_| {}).expect_err("must fail");
    assert!(matches!(err, AuthError::NotEntitled), "got: {err:?}");
    assert!(db.with_store(|store| store.accounts()).expect("accounts").is_empty());
}

#[test]
fn cached_token_is_reused_and_stale_token_triggers_silent_refresh() {
    let (db, clock) = fresh_db();
    let http = happy_chain();
    let vault = MemoryVault::new();
    let service = AuthService::new(&http, &vault, DEV_CLIENT_ID);
    let session = service.login_device_code(&db, &|_| {}, &|_| {}).expect("login");
    let uuid = session.profile.uuid.clone();
    let wire_after_login = http.requests().len();

    // Within token lifetime: no network traffic at all.
    let cached = service.ensure_session(&db, &uuid).expect("cached session");
    assert_eq!(cached.mc_access_token, "MC-ACCESS");
    assert_eq!(http.requests().len(), wire_after_login, "cached path must be offline");

    // Push past the MC token expiry: silent refresh re-runs the chain and
    // slides the entitlement window (ADR-0011).
    clock.advance(86_400 + 60);
    let refreshed = service.ensure_session(&db, &uuid).expect("refreshed session");
    assert_eq!(refreshed.mc_access_token, "MC-ACCESS");
    let account = db
        .with_store(|store| store.account_by_uuid(&uuid))
        .expect("query")
        .expect("account");
    assert_eq!(
        account.entitlement_window_expires_at,
        Some(NOW + 86_400 + 60 + ENTITLEMENT_WINDOW_SECS),
        "window must slide on re-verification"
    );
    assert!(http.requests().len() > wire_after_login);
}

#[test]
fn dead_refresh_token_requires_reauth() {
    let (db, clock) = fresh_db();
    let http = happy_chain();
    let vault = MemoryVault::new();
    let service = AuthService::new(&http, &vault, DEV_CLIENT_ID);
    let session = service.login_device_code(&db, &|_| {}, &|_| {}).expect("login");
    let uuid = session.profile.uuid.clone();

    // Expire the MC token, then make the token endpoint reject the refresh.
    clock.advance(86_400 + 60);
    http.rules.lock().expect("rules").retain(|r| r.url_contains != "/token");
    let http2 = ScriptedHttp::new().rule("/token", 400, json!({ "error": "invalid_grant" }));
    let service = AuthService::new(&http2, &vault, DEV_CLIENT_ID);
    let err = service.ensure_session(&db, &uuid).expect_err("must require reauth");
    assert!(
        matches!(&err, AuthError::ReauthRequired { oauth_error } if oauth_error == "invalid_grant"),
        "got: {err:?}"
    );
}

#[test]
fn launch_gate_offline_inside_and_outside_the_window() {
    let (db, clock) = fresh_db();
    let http = happy_chain();
    let vault = MemoryVault::new();
    let service = AuthService::new(&http, &vault, DEV_CLIENT_ID);
    let session = service.login_device_code(&db, &|_| {}, &|_| {}).expect("login");
    let uuid = session.profile.uuid.clone();

    // Simulate total network loss with an empty script.
    let offline_http = ScriptedHttp::new();
    let offline = AuthService::new(&offline_http, &vault, DEV_CLIENT_ID);

    // Day 13: offline play allowed within the vault-anchored window.
    clock.advance(13 * 86_400);
    match offline.launch_gate(&db, &uuid).expect("gate") {
        LaunchAuth::OfflineWithinWindow { profile_uuid, window_expires_at, .. } => {
            assert_eq!(profile_uuid, uuid);
            assert_eq!(window_expires_at, NOW + ENTITLEMENT_WINDOW_SECS);
        }
        LaunchAuth::Online(_) => panic!("cannot be online with a dead network"),
    }

    // Day 15: window expired -> hard typed stop (CPL-2).
    clock.advance(2 * 86_400);
    let err = offline.launch_gate(&db, &uuid).expect_err("gate must close");
    assert!(matches!(err, AuthError::EntitlementWindowExpired), "got: {err:?}");
}

#[test]
fn launch_gate_online_when_network_returns() {
    let (db, _clock) = fresh_db();
    let http = happy_chain();
    let vault = MemoryVault::new();
    let service = AuthService::new(&http, &vault, DEV_CLIENT_ID);
    let session = service.login_device_code(&db, &|_| {}, &|_| {}).expect("login");

    match service.launch_gate(&db, &session.profile.uuid).expect("gate") {
        LaunchAuth::Online(live) => assert_eq!(live.mc_access_token, "MC-ACCESS"),
        LaunchAuth::OfflineWithinWindow { .. } => panic!("network is up; must verify online"),
    }
}

#[test]
fn sign_out_removes_vault_entry_and_account_row() {
    let (db, _clock) = fresh_db();
    let http = happy_chain();
    let vault = MemoryVault::new();
    let service = AuthService::new(&http, &vault, DEV_CLIENT_ID);
    let session = service.login_device_code(&db, &|_| {}, &|_| {}).expect("login");
    let uuid = session.profile.uuid.clone();

    service.sign_out(&db, &uuid).expect("sign out");
    assert!(vault.load(&session.account.vault_ref).expect("vault").is_none());
    assert!(db.with_store(|store| store.accounts()).expect("accounts").is_empty());
    // Post-sign-out, the gate demands a fresh login.
    let offline = ScriptedHttp::new();
    let gate = AuthService::new(&offline, &vault, DEV_CLIENT_ID);
    assert!(gate.launch_gate(&db, &uuid).is_err());
}

#[test]
fn device_code_expiry_and_decline_are_typed() {
    let (db, _clock) = fresh_db();
    let vault = MemoryVault::new();

    let expired = ScriptedHttp::new()
        .rule("/devicecode", 200, json!({
            "device_code": "D", "user_code": "U", "verification_uri": "V",
            "interval": 1, "expires_in": 900,
        }))
        .rule("/token", 400, json!({ "error": "expired_token" }));
    let service = AuthService::new(&expired, &vault, DEV_CLIENT_ID);
    let err = service.login_device_code(&db, &|_| {}, &|_| {}).expect_err("expired");
    assert!(matches!(err, AuthError::DeviceCodeExpired), "got: {err:?}");

    let declined = ScriptedHttp::new()
        .rule("/devicecode", 200, json!({
            "device_code": "D", "user_code": "U", "verification_uri": "V",
            "interval": 1, "expires_in": 900,
        }))
        .rule("/token", 400, json!({ "error": "authorization_declined" }));
    let service = AuthService::new(&declined, &vault, DEV_CLIENT_ID);
    let err = service.login_device_code(&db, &|_| {}, &|_| {}).expect_err("declined");
    assert!(matches!(err, AuthError::ReauthRequired { .. }), "got: {err:?}");
}

#[test]
fn slow_down_grows_the_poll_interval() {
    let (db, _clock) = fresh_db();
    let vault = MemoryVault::new();
    let http = ScriptedHttp::new()
        .rule("/devicecode", 200, json!({
            "device_code": "D", "user_code": "U", "verification_uri": "V",
            "interval": 5, "expires_in": 900,
        }))
        .rule_limited("/token", 400, json!({ "error": "slow_down" }), 1)
        .rule("/token", 200, json!({
            "access_token": "A", "refresh_token": "R", "expires_in": 3600,
        }))
        // Chain then fails at XBL (scripted network end) — irrelevant here.
        ;
    let service = AuthService::new(&http, &vault, DEV_CLIENT_ID);
    let sleeps: Mutex<Vec<Duration>> = Mutex::new(Vec::new());
    let _ = service.login_device_code(&db, &|_| {}, &|d| sleeps.lock().expect("s").push(d));
    let recorded = sleeps.lock().expect("s").clone();
    assert_eq!(recorded, vec![Duration::from_secs(10)], "5s interval + 5s slow_down penalty");
}
