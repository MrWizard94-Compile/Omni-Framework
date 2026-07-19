//! Steps 2–3 — Xbox Live user authentication and XSTS authorization
//! (endpoints and shapes per the prism-launcher dossier / wiki.vg).

use serde_json::json;

use crate::auth::http::AuthHttp;
use crate::auth::{AuthError, XstsErrorKind};

const XBL_URL: &str = "https://user.auth.xboxlive.com/user/authenticate";
const XSTS_URL: &str = "https://xsts.auth.xboxlive.com/xsts/authorize";
/// The relying party Minecraft services accept.
const MC_RELYING_PARTY: &str = "rp://api.minecraftservices.com/";

/// An XBL token plus the user hash needed for the Minecraft identity header.
#[derive(Debug, Clone)]
pub struct XblToken {
    /// The XBL JWT.
    pub token: String,
    /// The `uhs` user-hash claim.
    pub user_hash: String,
}

/// An XSTS token authorized for the Minecraft relying party.
#[derive(Debug, Clone)]
pub struct XstsToken {
    /// The XSTS JWT.
    pub token: String,
    /// User hash (must match the XBL one; carried for convenience).
    pub user_hash: String,
}

fn extract_token_and_hash(
    value: &serde_json::Value,
    step: &'static str,
) -> Result<(String, String), AuthError> {
    let token = value["Token"].as_str().ok_or_else(|| AuthError::Protocol {
        step,
        detail: "response is missing Token".to_owned(),
    })?;
    let user_hash = value["DisplayClaims"]["xui"][0]["uhs"]
        .as_str()
        .ok_or_else(|| AuthError::Protocol {
            step,
            detail: "response is missing DisplayClaims.xui[0].uhs".to_owned(),
        })?;
    Ok((token.to_owned(), user_hash.to_owned()))
}

/// Exchanges a Microsoft access token for an XBL token.
///
/// # Errors
///
/// Transport/protocol errors as their [`AuthError`] variants.
pub fn authenticate_xbl(http: &dyn AuthHttp, msa_access_token: &str) -> Result<XblToken, AuthError> {
    let body = json!({
        "Properties": {
            "AuthMethod": "RPS",
            "SiteName": "user.auth.xboxlive.com",
            "RpsTicket": format!("d={msa_access_token}"),
        },
        "RelyingParty": "http://auth.xboxlive.com",
        "TokenType": "JWT",
    });
    let response = http.post_json(XBL_URL, &body, None)?;
    if response.status != 200 {
        return Err(AuthError::Protocol {
            step: "xbl",
            detail: format!("status {}", response.status),
        });
    }
    let value = response.json("xbl")?;
    let (token, user_hash) = extract_token_and_hash(&value, "xbl")?;
    Ok(XblToken { token, user_hash })
}

/// Authorizes an XBL token against XSTS for the Minecraft relying party,
/// mapping refusals through the `XErr` taxonomy.
///
/// # Errors
///
/// [`AuthError::Xsts`] with a classified [`XstsErrorKind`] on refusal;
/// transport/protocol errors otherwise.
pub fn authorize_xsts(http: &dyn AuthHttp, xbl: &XblToken) -> Result<XstsToken, AuthError> {
    let body = json!({
        "Properties": {
            "SandboxId": "RETAIL",
            "UserTokens": [xbl.token],
        },
        "RelyingParty": MC_RELYING_PARTY,
        "TokenType": "JWT",
    });
    let response = http.post_json(XSTS_URL, &body, None)?;
    if response.status == 401 {
        let value = response.json("xsts")?;
        let xerr = value["XErr"].as_u64().unwrap_or(0);
        return Err(AuthError::Xsts { xerr, kind: XstsErrorKind::from_xerr(xerr) });
    }
    if response.status != 200 {
        return Err(AuthError::Protocol {
            step: "xsts",
            detail: format!("status {}", response.status),
        });
    }
    let value = response.json("xsts")?;
    let (token, user_hash) = extract_token_and_hash(&value, "xsts")?;
    Ok(XstsToken { token, user_hash })
}
