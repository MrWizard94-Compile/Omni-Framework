//! Steps 4–5 — Minecraft services: login, entitlement, profile
//! (`api.minecraftservices.com`; this is the surface gated by Mojang's
//! client-ID allowlist — ROADMAP external dependencies).

use serde_json::json;

use crate::auth::http::AuthHttp;
use crate::auth::xbox::XstsToken;
use crate::auth::AuthError;

const LOGIN_URL: &str = "https://api.minecraftservices.com/authentication/login_with_xbox";
const ENTITLEMENTS_URL: &str = "https://api.minecraftservices.com/entitlements/mcstore";
const PROFILE_URL: &str = "https://api.minecraftservices.com/minecraft/profile";

/// A Minecraft services access token.
#[derive(Debug, Clone)]
pub struct McToken {
    /// Bearer for the Minecraft services API and game launch.
    pub access_token: String,
    /// Lifetime in seconds (~24h upstream).
    pub expires_in_secs: i64,
}

/// The entitlement document, kept verbatim for the audit trail.
#[derive(Debug, Clone)]
pub struct EntitlementDoc {
    /// Raw `items` array from `/entitlements/mcstore`.
    pub items: serde_json::Value,
}

/// The player profile — its existence is the practical ownership proof
/// (profiles only exist for accounts that own Minecraft: Java Edition).
#[derive(Debug, Clone)]
pub struct McProfile {
    /// Profile UUID (undashed hex, as served).
    pub uuid: String,
    /// Current player name.
    pub name: String,
}

/// Logs into Minecraft services with an XSTS token.
///
/// # Errors
///
/// [`AuthError::Protocol`] on refusal (including the 403 an unapproved client
/// ID receives — the detail names it); transport errors as [`AuthError::Http`].
pub fn login_with_xbox(http: &dyn AuthHttp, xsts: &XstsToken) -> Result<McToken, AuthError> {
    let body = json!({
        "identityToken": format!("XBL3.0 x={};{}", xsts.user_hash, xsts.token),
    });
    let response = http.post_json(LOGIN_URL, &body, None)?;
    if response.status == 403 {
        return Err(AuthError::Protocol {
            step: "minecraft login",
            detail: "Minecraft services refused this application (403). The Azure \
                     client ID is not yet on Mojang's third-party-launcher allowlist \
                     — approval is pending (see ROADMAP external dependencies)."
                .to_owned(),
        });
    }
    if response.status != 200 {
        return Err(AuthError::Protocol {
            step: "minecraft login",
            detail: format!("status {}", response.status),
        });
    }
    let value = response.json("minecraft login")?;
    let access_token = value["access_token"].as_str().ok_or_else(|| AuthError::Protocol {
        step: "minecraft login",
        detail: "response is missing access_token".to_owned(),
    })?;
    Ok(McToken {
        access_token: access_token.to_owned(),
        expires_in_secs: value["expires_in"].as_i64().unwrap_or(86_400),
    })
}

/// Fetches the entitlement document (kept for the audit record).
///
/// # Errors
///
/// Transport/protocol errors as their [`AuthError`] variants.
pub fn fetch_entitlements(http: &dyn AuthHttp, mc: &McToken) -> Result<EntitlementDoc, AuthError> {
    let response = http.get_bearer(ENTITLEMENTS_URL, &mc.access_token)?;
    if response.status != 200 {
        return Err(AuthError::Protocol {
            step: "entitlements",
            detail: format!("status {}", response.status),
        });
    }
    let value = response.json("entitlements")?;
    Ok(EntitlementDoc { items: value["items"].clone() })
}

/// Fetches the player profile. A 404 means the account owns no Java Edition
/// profile — mapped to [`AuthError::NotEntitled`] (COMPLIANCE §1: ownership is
/// verified before any launch; Game Pass owners have a profile and pass).
///
/// # Errors
///
/// [`AuthError::NotEntitled`] on 404; transport/protocol errors otherwise.
pub fn fetch_profile(http: &dyn AuthHttp, mc: &McToken) -> Result<McProfile, AuthError> {
    let response = http.get_bearer(PROFILE_URL, &mc.access_token)?;
    if response.status == 404 {
        return Err(AuthError::NotEntitled);
    }
    if response.status != 200 {
        return Err(AuthError::Protocol {
            step: "profile",
            detail: format!("status {}", response.status),
        });
    }
    let value = response.json("profile")?;
    let field = |name: &str| -> Result<String, AuthError> {
        value[name]
            .as_str()
            .map(str::to_owned)
            .ok_or_else(|| AuthError::Protocol {
                step: "profile",
                detail: format!("response is missing {name:?}"),
            })
    };
    Ok(McProfile { uuid: field("id")?, name: field("name")? })
}
