//! # Authentication (LNC-4) — the COMPLIANCE §2 Microsoft chain, exactly
//!
//! ```text
//! 1. Microsoft OAuth 2.0    device-code flow (dev) / auth-code + PKCE (interactive)
//! 2. Xbox Live              user.auth.xboxlive.com          -> XBL token
//! 3. XSTS                   xsts.auth.xboxlive.com          -> XSTS token (XErr taxonomy)
//! 4. Minecraft services     api.minecraftservices.com       -> MC access token
//! 5. Entitlement + profile  /entitlements/mcstore, /minecraft/profile
//! ```
//!
//! Hard rules implemented here (COMPLIANCE.md §2, SOUL §36):
//! - We never see, transmit, store, or prompt for a Microsoft password —
//!   consent happens in Microsoft's surfaces (browser / device-code page).
//! - Tokens live in the OS credential vault only ([`TokenVault`]); the
//!   database stores the vault reference and the entitlement-window expiry.
//! - Every successful entitlement verification stamps the CPL-2/ADR-0011
//!   offline window (14 days, non-configurable) in both the vault record and
//!   the `accounts` row, and every online login re-verifies opportunistically
//!   (the window "slides").
//! - No launch path exists without a verified entitlement record:
//!   [`AuthService::launch_gate`] is the single authority.
//!
//! Transport ([`AuthHttp`]) and vault ([`TokenVault`]) are trait-abstracted so
//! the whole chain is tested against recorded fixtures — tests never touch
//! live endpoints or real secrets (OmniLauncher/ARCHITECTURE.md §8).

mod http;
mod manager;
mod minecraft;
mod msa;
mod vault;
mod xbox;

pub use http::{AuthHttp, HttpResponse, UreqAuthHttp};
pub use manager::{AuthService, LaunchAuth, LiveSession};
pub use minecraft::{EntitlementDoc, McProfile, McToken};
pub use msa::{
    DeviceCodePrompt, MsaTokens, PkceChallenge, bind_loopback, build_authorize_url,
    device_code_begin, exchange_code, new_pkce_challenge, wait_for_loopback_code,
};
pub use vault::{EntitlementRecord, KeyringVault, MemoryVault, TokenSet, TokenVault};
pub use xbox::{XblToken, XstsToken};

use std::fmt;

/// The Azure application registered for OmniLauncher (ADR-0016; public-client
/// app id — not a secret, embedded in every build). The Tauri shell may
/// override it via build config; tools and tests default to it.
pub const DEV_CLIENT_ID: &str = "ff86f866-0ef7-4208-aa66-c17d8124dcc3";

/// The CPL-2/ADR-0011 offline entitlement window: 14 days, no configuration
/// to extend it.
pub const ENTITLEMENT_WINDOW_SECS: i64 = 14 * 86_400;

/// Scope requested from Microsoft: Xbox Live sign-in plus a refresh token.
pub const MSA_SCOPE: &str = "XboxLive.signin offline_access";

/// XSTS failure classes, mapped from the documented `XErr` codes so the UI can
/// render actionable guidance instead of a number (LNC-4 "full XSTS error
/// taxonomy").
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum XstsErrorKind {
    /// 2148916233 — the Microsoft account has no Xbox profile yet.
    NoXboxAccount,
    /// 2148916235 — Xbox Live is unavailable/banned in the account's region.
    RegionUnavailable,
    /// 2148916236 / 2148916237 — adult verification required (South Korea).
    AdultVerificationRequired,
    /// 2148916238 — child account: must be added to a family by an adult.
    ChildAccount,
    /// 2148916227 — the account is banned from Xbox Live.
    AccountBanned,
    /// Any other code — surfaced verbatim.
    Unknown,
}

impl XstsErrorKind {
    /// Maps a raw `XErr` code to its class.
    pub fn from_xerr(code: u64) -> Self {
        match code {
            2_148_916_233 => Self::NoXboxAccount,
            2_148_916_235 => Self::RegionUnavailable,
            2_148_916_236 | 2_148_916_237 => Self::AdultVerificationRequired,
            2_148_916_238 => Self::ChildAccount,
            2_148_916_227 => Self::AccountBanned,
            _ => Self::Unknown,
        }
    }

    /// Actionable remedy text (LDR-10 quality bar: never a bare code).
    pub fn remedy(self) -> &'static str {
        match self {
            Self::NoXboxAccount => {
                "This Microsoft account has no Xbox profile. Sign in once at xbox.com \
                 to create one, then try again."
            }
            Self::RegionUnavailable => {
                "Xbox Live is not available for this account's country/region."
            }
            Self::AdultVerificationRequired => {
                "This account needs adult verification on the Xbox website before it \
                 can sign in (required in South Korea)."
            }
            Self::ChildAccount => {
                "This is a child account. An adult must add it to a Microsoft family \
                 at account.microsoft.com/family, then try again."
            }
            Self::AccountBanned => "This account is banned from Xbox Live.",
            Self::Unknown => "Xbox sign-in failed with an unrecognized error.",
        }
    }
}

/// Everything the auth chain can fail with — typed, never log-only (SOUL §16).
#[derive(Debug)]
pub enum AuthError {
    /// Transport-level failure (network down, TLS, timeout).
    Http {
        /// The endpoint involved.
        url: String,
        /// Human-readable cause.
        message: String,
    },
    /// An endpoint returned a body we cannot interpret.
    Protocol {
        /// Which step.
        step: &'static str,
        /// What was wrong.
        detail: String,
    },
    /// The OAuth token endpoint rejected the request (e.g. `invalid_grant`
    /// on an expired refresh token). A fresh interactive login is required.
    ReauthRequired {
        /// The OAuth error code, when the endpoint supplied one.
        oauth_error: String,
    },
    /// The user did not complete the device-code prompt in time.
    DeviceCodeExpired,
    /// XSTS refused authorization (see [`XstsErrorKind`]).
    Xsts {
        /// Raw `XErr` code.
        xerr: u64,
        /// Classified kind.
        kind: XstsErrorKind,
    },
    /// Sign-in succeeded but the account owns no Minecraft: Java Edition
    /// profile — launching is impossible (COMPLIANCE §1: no piracy).
    NotEntitled,
    /// The CPL-2 offline window has expired and the network is unavailable —
    /// re-authentication is required before launch.
    EntitlementWindowExpired,
    /// OS credential vault failure.
    Vault {
        /// What was being done.
        action: &'static str,
        /// Underlying cause.
        message: String,
    },
    /// Database failure while persisting account state.
    Core(crate::CoreError),
}

impl fmt::Display for AuthError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Http { url, message } => write!(f, "network: {url}: {message}"),
            Self::Protocol { step, detail } => write!(f, "protocol ({step}): {detail}"),
            Self::ReauthRequired { oauth_error } => {
                write!(f, "session expired ({oauth_error}); a fresh sign-in is required")
            }
            Self::DeviceCodeExpired => {
                write!(f, "the sign-in code expired before it was entered; start again")
            }
            Self::Xsts { xerr, kind } => write!(f, "Xbox sign-in failed (XErr {xerr}): {}", kind.remedy()),
            Self::NotEntitled => write!(
                f,
                "this Microsoft account does not own Minecraft: Java Edition"
            ),
            Self::EntitlementWindowExpired => write!(
                f,
                "offline play window expired; connect to the internet and sign in again"
            ),
            Self::Vault { action, message } => write!(f, "credential vault: {action}: {message}"),
            Self::Core(err) => write!(f, "{err}"),
        }
    }
}

impl std::error::Error for AuthError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Core(err) => Some(err),
            _ => None,
        }
    }
}

impl From<crate::CoreError> for AuthError {
    fn from(err: crate::CoreError) -> Self {
        Self::Core(err)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn xerr_taxonomy_maps_documented_codes() {
        assert_eq!(XstsErrorKind::from_xerr(2_148_916_233), XstsErrorKind::NoXboxAccount);
        assert_eq!(XstsErrorKind::from_xerr(2_148_916_235), XstsErrorKind::RegionUnavailable);
        assert_eq!(
            XstsErrorKind::from_xerr(2_148_916_236),
            XstsErrorKind::AdultVerificationRequired
        );
        assert_eq!(
            XstsErrorKind::from_xerr(2_148_916_237),
            XstsErrorKind::AdultVerificationRequired
        );
        assert_eq!(XstsErrorKind::from_xerr(2_148_916_238), XstsErrorKind::ChildAccount);
        assert_eq!(XstsErrorKind::from_xerr(2_148_916_227), XstsErrorKind::AccountBanned);
        assert_eq!(XstsErrorKind::from_xerr(42), XstsErrorKind::Unknown);
        // Every kind carries human remedy text.
        for kind in [
            XstsErrorKind::NoXboxAccount,
            XstsErrorKind::RegionUnavailable,
            XstsErrorKind::AdultVerificationRequired,
            XstsErrorKind::ChildAccount,
            XstsErrorKind::AccountBanned,
            XstsErrorKind::Unknown,
        ] {
            assert!(!kind.remedy().is_empty());
        }
    }
}
