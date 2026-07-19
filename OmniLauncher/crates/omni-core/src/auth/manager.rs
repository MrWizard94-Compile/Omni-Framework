//! The orchestrator: full chain runs, silent renewal, multi-account rows,
//! entitlement-window stamping, and the single launch gate (CPL-2).

use std::time::Duration;

use crate::auth::http::AuthHttp;
use crate::auth::minecraft::{self, McProfile};
use crate::auth::msa::{self, DeviceCodePrompt, MsaTokens};
use crate::auth::vault::{EntitlementRecord, TokenSet, TokenVault};
use crate::auth::xbox;
use crate::auth::{AuthError, ENTITLEMENT_WINDOW_SECS};
use crate::db::Db;
use crate::store::{Account, AccountKind};

/// Renew the MC token when less than this remains — launches never start with
/// a token about to die mid-handshake.
const TOKEN_RENEWAL_MARGIN_SECS: i64 = 15 * 60;

/// A ready-to-use online session.
#[derive(Debug, Clone)]
pub struct LiveSession {
    /// The account row (fresh from the store).
    pub account: Account,
    /// Player profile at last verification.
    pub profile: McProfile,
    /// Minecraft services bearer for launch.
    pub mc_access_token: String,
}

/// What the launch gate authorized (CPL-2: no third possibility exists).
#[derive(Debug, Clone)]
pub enum LaunchAuth {
    /// Online, verified now — window re-stamped.
    Online(LiveSession),
    /// Network unavailable, but inside the vault-anchored 14-day window.
    OfflineWithinWindow {
        /// The account row.
        account: Account,
        /// Cached profile identity from the vault record.
        profile_uuid: String,
        /// When offline play expires (Unix seconds).
        window_expires_at: i64,
    },
}

/// The auth façade the shell talks to. Transport and vault are injected;
/// everything is synchronous (job-runner/shell threads own scheduling —
/// LNC-4's "silent refresh scheduler" calls [`AuthService::ensure_session`]).
pub struct AuthService<'a> {
    http: &'a dyn AuthHttp,
    vault: &'a dyn TokenVault,
    client_id: String,
}

fn vault_ref_for(uuid: &str) -> String {
    format!("omni/msa/{uuid}")
}

impl<'a> AuthService<'a> {
    /// Creates the service. `client_id` is the Azure application id
    /// ([`crate::auth::DEV_CLIENT_ID`] until shell build-config overrides it).
    pub fn new(http: &'a dyn AuthHttp, vault: &'a dyn TokenVault, client_id: impl Into<String>) -> Self {
        Self { http, vault, client_id: client_id.into() }
    }

    /// Runs XBL → XSTS → MC login → entitlements → profile from Microsoft
    /// tokens, persists everything (vault entry, account row, entitlement
    /// window — the ADR-0011 stamp in both places), and returns the session.
    fn complete_chain(&self, db: &Db, msa_tokens: &MsaTokens) -> Result<LiveSession, AuthError> {
        let xbl = xbox::authenticate_xbl(self.http, &msa_tokens.access_token)?;
        let xsts = xbox::authorize_xsts(self.http, &xbl)?;
        let mc = minecraft::login_with_xbox(self.http, &xsts)?;
        // The entitlement document is fetched for the audit trail; the profile
        // is the ownership proof (404 -> NotEntitled, incl. the Game Pass case
        // where mcstore items are empty but a profile exists).
        let _entitlements = minecraft::fetch_entitlements(self.http, &mc)?;
        let profile = minecraft::fetch_profile(self.http, &mc)?;

        let now = db.clock().now_unix();
        let window_expires_at = now + ENTITLEMENT_WINDOW_SECS;
        let vault_ref = vault_ref_for(&profile.uuid);
        self.vault.store(
            &vault_ref,
            &TokenSet {
                msa_refresh_token: msa_tokens.refresh_token.clone(),
                mc_access_token: mc.access_token.clone(),
                mc_token_expires_at: now + mc.expires_in_secs,
                entitlement: EntitlementRecord {
                    verified_at: now,
                    window_expires_at,
                    profile_uuid: profile.uuid.clone(),
                },
            },
        )?;
        let account = db.with_store(|store| {
            let account = store.upsert_account(
                &profile.uuid,
                &profile.name,
                AccountKind::Microsoft,
                &vault_ref,
            )?;
            store.set_entitlement_window(account.id, now, window_expires_at)?;
            store.account_by_uuid(&profile.uuid)?.ok_or(crate::CoreError::NotFound {
                what: "account",
                id: account.id,
            })
        })?;
        Ok(LiveSession { account, profile, mc_access_token: mc.access_token })
    }

    /// Completes the chain from already-obtained Microsoft tokens — the entry
    /// point for callers that drive the OAuth step themselves (the launcher's
    /// background device-code poller, which must return its prompt to the UI
    /// before the user has finished consenting).
    ///
    /// # Errors
    ///
    /// Any [`AuthError`] from XBL/XSTS/Minecraft-services or persistence.
    pub fn complete_login(&self, db: &Db, msa_tokens: &MsaTokens) -> Result<LiveSession, AuthError> {
        self.complete_chain(db, msa_tokens)
    }

    /// Interactive sign-in via the device-code flow (the development path and
    /// the fallback path). `on_prompt` shows the code/URL to the user;
    /// `sleep` is the poll delay (std sleep in production, no-op in tests).
    ///
    /// # Errors
    ///
    /// Any [`AuthError`] from the chain; [`AuthError::NotEntitled`] accounts
    /// are NOT persisted (no row, no vault entry — COMPLIANCE §1).
    pub fn login_device_code(
        &self,
        db: &Db,
        on_prompt: &dyn Fn(&DeviceCodePrompt),
        sleep: &dyn Fn(Duration),
    ) -> Result<LiveSession, AuthError> {
        let prompt = msa::device_code_begin(self.http, &self.client_id)?;
        on_prompt(&prompt);
        let tokens = msa::device_code_poll(self.http, &self.client_id, &prompt, sleep)?;
        self.complete_chain(db, &tokens)
    }

    /// Completes an interactive PKCE sign-in from an authorization code the
    /// loopback listener captured (the shell owns opening the browser).
    ///
    /// # Errors
    ///
    /// Any [`AuthError`] from the exchange or the chain.
    pub fn login_authorization_code(
        &self,
        db: &Db,
        redirect_uri: &str,
        code: &str,
        pkce: &msa::PkceChallenge,
    ) -> Result<LiveSession, AuthError> {
        let tokens = msa::exchange_code(self.http, &self.client_id, redirect_uri, code, pkce)?;
        self.complete_chain(db, &tokens)
    }

    /// Returns a valid online session for the account, renewing silently when
    /// the cached MC token is (nearly) expired. Every full renewal re-verifies
    /// entitlement and slides the ADR-0011 window.
    ///
    /// # Errors
    ///
    /// [`AuthError::ReauthRequired`] when no vault entry exists or the refresh
    /// token is dead; any chain error during renewal.
    pub fn ensure_session(&self, db: &Db, uuid: &str) -> Result<LiveSession, AuthError> {
        let vault_ref = vault_ref_for(uuid);
        let tokens = self.vault.load(&vault_ref)?.ok_or_else(|| AuthError::ReauthRequired {
            oauth_error: "no stored credentials".to_owned(),
        })?;
        let now = db.clock().now_unix();
        if tokens.mc_token_expires_at - now > TOKEN_RENEWAL_MARGIN_SECS {
            // Cached MC token is still comfortably valid.
            let account = db
                .with_store(|store| store.account_by_uuid(uuid))?
                .ok_or_else(|| AuthError::ReauthRequired {
                    oauth_error: "account row missing".to_owned(),
                })?;
            let name = account.username.clone();
            return Ok(LiveSession {
                account,
                profile: McProfile { uuid: uuid.to_owned(), name },
                mc_access_token: tokens.mc_access_token,
            });
        }
        let fresh = msa::refresh(self.http, &self.client_id, &tokens.msa_refresh_token)?;
        self.complete_chain(db, &fresh)
    }

    /// **The launch gate (CPL-2).** Online verification when possible; the
    /// vault-anchored offline window otherwise; no launch without one of the
    /// two. Expired window + no network is a hard, typed stop.
    ///
    /// # Errors
    ///
    /// [`AuthError::EntitlementWindowExpired`] offline past the window;
    /// [`AuthError::ReauthRequired`]/chain errors when online renewal fails
    /// for non-network reasons.
    pub fn launch_gate(&self, db: &Db, uuid: &str) -> Result<LaunchAuth, AuthError> {
        match self.ensure_session(db, uuid) {
            Ok(session) => Ok(LaunchAuth::Online(session)),
            // Only NETWORK failure opens the offline path; auth refusals
            // (revoked token, lost entitlement) never do.
            Err(AuthError::Http { .. }) => {
                let tokens = self
                    .vault
                    .load(&vault_ref_for(uuid))?
                    .ok_or(AuthError::EntitlementWindowExpired)?;
                let now = db.clock().now_unix();
                if now < tokens.entitlement.window_expires_at
                    && tokens.entitlement.profile_uuid == uuid
                {
                    let account = db
                        .with_store(|store| store.account_by_uuid(uuid))?
                        .ok_or(AuthError::EntitlementWindowExpired)?;
                    Ok(LaunchAuth::OfflineWithinWindow {
                        account,
                        profile_uuid: uuid.to_owned(),
                        window_expires_at: tokens.entitlement.window_expires_at,
                    })
                } else {
                    Err(AuthError::EntitlementWindowExpired)
                }
            }
            Err(other) => Err(other),
        }
    }

    /// Signs an account out: vault entry gone, account row gone.
    ///
    /// # Errors
    ///
    /// [`AuthError::Vault`] / [`AuthError::Core`] on backend failures.
    pub fn sign_out(&self, db: &Db, uuid: &str) -> Result<(), AuthError> {
        self.vault.delete(&vault_ref_for(uuid))?;
        db.with_store(|store| {
            if let Some(account) = store.account_by_uuid(uuid)? {
                store.delete_account(account.id)?;
            }
            Ok(())
        })?;
        Ok(())
    }
}
