//! Token storage: OS credential vault only (SOUL §36 / COMPLIANCE §2).
//!
//! The database's `accounts.vault_ref` column names an entry here; the entry
//! holds the serialized [`TokenSet`] — tokens AND the ADR-0011 entitlement
//! verification record live together in the vault, while the database keeps
//! only the window-expiry timestamp for scheduling.

use serde::{Deserialize, Serialize};

use crate::auth::AuthError;

/// The ADR-0011 verification record, vault-anchored beside the tokens.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EntitlementRecord {
    /// Last successful verification (Unix seconds).
    pub verified_at: i64,
    /// Window expiry: `verified_at + 14 days` (Unix seconds).
    pub window_expires_at: i64,
    /// Profile UUID the verification was bound to.
    pub profile_uuid: String,
}

/// Everything persisted per account, as one vault entry.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TokenSet {
    /// Microsoft refresh token (silent renewal).
    pub msa_refresh_token: String,
    /// Current Minecraft services access token.
    pub mc_access_token: String,
    /// Expiry of the MC token (Unix seconds).
    pub mc_token_expires_at: i64,
    /// The vault-anchored entitlement verification record.
    pub entitlement: EntitlementRecord,
}

/// Abstract credential vault. Production: [`KeyringVault`] (Windows Credential
/// Manager via the keyring crate); tests: [`MemoryVault`]. Never a file, never
/// the database, never logs.
pub trait TokenVault: Send + Sync {
    /// Stores (creating or replacing) a token set under a vault reference.
    ///
    /// # Errors
    ///
    /// [`AuthError::Vault`] on backend failure.
    fn store(&self, vault_ref: &str, tokens: &TokenSet) -> Result<(), AuthError>;

    /// Loads the token set for a vault reference, `None` when absent.
    ///
    /// # Errors
    ///
    /// [`AuthError::Vault`] on backend failure (absence is not an error).
    fn load(&self, vault_ref: &str) -> Result<Option<TokenSet>, AuthError>;

    /// Deletes the entry (sign-out). Deleting an absent entry is a no-op.
    ///
    /// # Errors
    ///
    /// [`AuthError::Vault`] on backend failure.
    fn delete(&self, vault_ref: &str) -> Result<(), AuthError>;
}

/// Production vault over the OS credential store.
pub struct KeyringVault {
    service: String,
}

impl KeyringVault {
    /// Creates the vault with the given service namespace (e.g.
    /// `"OmniLauncher"` — entries appear under this name in the OS UI).
    pub fn new(service: impl Into<String>) -> Self {
        Self { service: service.into() }
    }

    fn entry(&self, vault_ref: &str) -> Result<keyring::Entry, AuthError> {
        keyring::Entry::new(&self.service, vault_ref).map_err(|err| AuthError::Vault {
            action: "open vault entry",
            message: err.to_string(),
        })
    }

    /// Suffix for the entry holding the Microsoft refresh token.
    const MSA_PART: &'static str = "/msa";
    /// Suffix for the entry holding the Minecraft token and entitlement record.
    const GAME_PART: &'static str = "/game";

    /// Reads and parses one half, treating absence as `None`.
    fn read_part<T: serde::de::DeserializeOwned>(
        &self,
        vault_ref: &str,
        part: &str,
    ) -> Result<Option<T>, AuthError> {
        match self.entry(&format!("{vault_ref}{part}"))?.get_password() {
            Ok(json) => serde_json::from_str(&json).map(Some).map_err(|err| AuthError::Vault {
                action: "parse stored tokens",
                message: err.to_string(),
            }),
            Err(keyring::Error::NoEntry) => Ok(None),
            Err(err) => {
                Err(AuthError::Vault { action: "load tokens", message: err.to_string() })
            }
        }
    }

    /// Deletes one half; absence is a no-op.
    fn delete_part(&self, vault_ref: &str, part: &str) -> Result<(), AuthError> {
        match self.entry(&format!("{vault_ref}{part}"))?.delete_credential() {
            Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
            Err(err) => {
                Err(AuthError::Vault { action: "delete tokens", message: err.to_string() })
            }
        }
    }
}

/// The Microsoft half of a stored account.
#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct MsaPart {
    msa_refresh_token: String,
}

/// The Minecraft half: the services token plus the ADR-0011 verification record.
#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct GamePart {
    mc_access_token: String,
    mc_token_expires_at: i64,
    entitlement: EntitlementRecord,
}

impl TokenVault for KeyringVault {
    /// Stores an account across **two** credential entries.
    ///
    /// # Why it is split
    ///
    /// Windows Credential Manager caps one credential blob at 2560 UTF-16
    /// characters. A `TokenSet` holds two JWTs — the Microsoft refresh token
    /// and the Minecraft services token — and real ones run 1–2 KB *each*, so
    /// the combined JSON exceeds the limit and `CredWrite` refuses it outright.
    ///
    /// This was unreachable until 2026-07-20: a Minecraft token cannot be
    /// stored before Mojang's allowlist starts issuing one, so every earlier
    /// test stored fixtures that happened to fit. The first real sign-in failed
    /// here immediately.
    ///
    /// Splitting rather than dropping the game token: it is genuinely wanted,
    /// because ADR-0011's offline window has to hand the game an access token
    /// without a network round trip. Splitting rather than compressing: JWTs
    /// are base64 and compress poorly, so that would only move the cliff
    /// instead of removing it.
    fn store(&self, vault_ref: &str, tokens: &TokenSet) -> Result<(), AuthError> {
        let msa = serde_json::to_string(&MsaPart {
            msa_refresh_token: tokens.msa_refresh_token.clone(),
        })
        .map_err(|err| AuthError::Vault {
            action: "serialize tokens",
            message: err.to_string(),
        })?;
        let game = serde_json::to_string(&GamePart {
            mc_access_token: tokens.mc_access_token.clone(),
            mc_token_expires_at: tokens.mc_token_expires_at,
            entitlement: tokens.entitlement.clone(),
        })
        .map_err(|err| AuthError::Vault {
            action: "serialize tokens",
            message: err.to_string(),
        })?;

        // Game half first. A crash between the two writes then leaves a game
        // entry with no Microsoft half, which `load` treats as "no credentials"
        // and recovers from by re-authenticating. The reverse order would leave
        // a refresh token that looks usable but has no entitlement record
        // beside it — recoverable too, but only after a confusing launch gate
        // refusal.
        self.entry(&format!("{vault_ref}{}", Self::GAME_PART))?
            .set_password(&game)
            .map_err(|err| AuthError::Vault {
                action: "store tokens",
                message: err.to_string(),
            })?;
        self.entry(&format!("{vault_ref}{}", Self::MSA_PART))?
            .set_password(&msa)
            .map_err(|err| AuthError::Vault {
                action: "store tokens",
                message: err.to_string(),
            })
    }

    /// Loads both halves, treating a missing half as no stored account.
    fn load(&self, vault_ref: &str) -> Result<Option<TokenSet>, AuthError> {
        let Some(msa) = self.read_part::<MsaPart>(vault_ref, Self::MSA_PART)? else {
            return Ok(None);
        };
        let Some(game) = self.read_part::<GamePart>(vault_ref, Self::GAME_PART)? else {
            // Half an account is not an account. Reporting `None` sends the
            // caller down the re-authentication path, which is exactly right —
            // the alternative is inventing an entitlement record we never saw.
            return Ok(None);
        };
        Ok(Some(TokenSet {
            msa_refresh_token: msa.msa_refresh_token,
            mc_access_token: game.mc_access_token,
            mc_token_expires_at: game.mc_token_expires_at,
            entitlement: game.entitlement,
        }))
    }

    /// Deletes both halves. Absence of either is not an error.
    fn delete(&self, vault_ref: &str) -> Result<(), AuthError> {
        self.delete_part(vault_ref, Self::MSA_PART)?;
        self.delete_part(vault_ref, Self::GAME_PART)
    }
}

/// In-memory vault for tests and headless tooling.
#[derive(Debug, Default)]
pub struct MemoryVault(std::sync::Mutex<std::collections::BTreeMap<String, TokenSet>>);

impl MemoryVault {
    /// Creates an empty vault.
    pub fn new() -> Self {
        Self::default()
    }
}

impl TokenVault for MemoryVault {
    fn store(&self, vault_ref: &str, tokens: &TokenSet) -> Result<(), AuthError> {
        self.0
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .insert(vault_ref.to_owned(), tokens.clone());
        Ok(())
    }

    fn load(&self, vault_ref: &str) -> Result<Option<TokenSet>, AuthError> {
        Ok(self
            .0
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .get(vault_ref)
            .cloned())
    }

    fn delete(&self, vault_ref: &str) -> Result<(), AuthError> {
        self.0.lock().unwrap_or_else(std::sync::PoisonError::into_inner).remove(vault_ref);
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn memory_vault_roundtrip_and_delete() {
        let vault = MemoryVault::new();
        let tokens = TokenSet {
            msa_refresh_token: "r".into(),
            mc_access_token: "a".into(),
            mc_token_expires_at: 100,
            entitlement: EntitlementRecord {
                verified_at: 50,
                window_expires_at: 50 + crate::auth::ENTITLEMENT_WINDOW_SECS,
                profile_uuid: "u".into(),
            },
        };
        assert!(vault.load("omni/msa/u").expect("load").is_none());
        vault.store("omni/msa/u", &tokens).expect("store");
        assert_eq!(vault.load("omni/msa/u").expect("load"), Some(tokens));
        vault.delete("omni/msa/u").expect("delete");
        assert!(vault.load("omni/msa/u").expect("load").is_none());
        vault.delete("omni/msa/u").expect("double delete is a no-op");
    }

    /// A token set the size real sign-ins actually produce.
    ///
    /// The fixtures above use one-character tokens, which is precisely why the
    /// 2560-character credential limit shipped undetected: every stored blob
    /// fit comfortably until a real Microsoft refresh token and a real
    /// Minecraft services token arrived together.
    fn realistic_tokens() -> TokenSet {
        // Observed sizes on 2026-07-20: both are JWTs in the 1–2 KB range.
        TokenSet {
            msa_refresh_token: "M".repeat(1800),
            mc_access_token: "J".repeat(1600),
            mc_token_expires_at: 1_760_000_000,
            entitlement: EntitlementRecord {
                verified_at: 1_759_000_000,
                window_expires_at: 1_759_000_000 + crate::auth::ENTITLEMENT_WINDOW_SECS,
                profile_uuid: "0123456789abcdef0123456789abcdef".into(),
            },
        }
    }

    #[test]
    fn realistic_tokens_exceed_a_single_credential_blob() {
        // Documents WHY the vault is split, in a form that fails if someone
        // recombines the entries: one JSON blob of real tokens does not fit in
        // Windows Credential Manager's 2560 UTF-16 character limit.
        const WINDOWS_CREDENTIAL_LIMIT_CHARS: usize = 2560;
        let combined = serde_json::to_string(&realistic_tokens()).expect("serialize");
        assert!(
            combined.chars().count() > WINDOWS_CREDENTIAL_LIMIT_CHARS,
            "realistic tokens now fit in one credential ({} chars) — if that is genuinely \
             true the split could be revisited, but more likely this fixture drifted away \
             from real token sizes and stopped guarding anything",
            combined.chars().count()
        );
    }

    #[test]
    fn each_half_fits_within_the_credential_limit() {
        // The actual property the split buys: neither entry can hit the cap.
        const WINDOWS_CREDENTIAL_LIMIT_CHARS: usize = 2560;
        let tokens = realistic_tokens();

        let msa = serde_json::to_string(&MsaPart {
            msa_refresh_token: tokens.msa_refresh_token.clone(),
        })
        .expect("serialize msa half");
        let game = serde_json::to_string(&GamePart {
            mc_access_token: tokens.mc_access_token.clone(),
            mc_token_expires_at: tokens.mc_token_expires_at,
            entitlement: tokens.entitlement.clone(),
        })
        .expect("serialize game half");

        assert!(
            msa.chars().count() < WINDOWS_CREDENTIAL_LIMIT_CHARS,
            "msa half is {} chars, over the limit",
            msa.chars().count()
        );
        assert!(
            game.chars().count() < WINDOWS_CREDENTIAL_LIMIT_CHARS,
            "game half is {} chars, over the limit",
            game.chars().count()
        );
    }

    #[test]
    fn memory_vault_roundtrips_realistic_tokens() {
        // Size must not change behaviour anywhere else in the chain.
        let vault = MemoryVault::new();
        let tokens = realistic_tokens();
        vault.store("omni/msa/u", &tokens).expect("store");
        assert_eq!(vault.load("omni/msa/u").expect("load"), Some(tokens));
    }
}
