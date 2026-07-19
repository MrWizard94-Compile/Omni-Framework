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
}

impl TokenVault for KeyringVault {
    fn store(&self, vault_ref: &str, tokens: &TokenSet) -> Result<(), AuthError> {
        let json = serde_json::to_string(tokens).map_err(|err| AuthError::Vault {
            action: "serialize tokens",
            message: err.to_string(),
        })?;
        self.entry(vault_ref)?.set_password(&json).map_err(|err| AuthError::Vault {
            action: "store tokens",
            message: err.to_string(),
        })
    }

    fn load(&self, vault_ref: &str) -> Result<Option<TokenSet>, AuthError> {
        match self.entry(vault_ref)?.get_password() {
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

    fn delete(&self, vault_ref: &str) -> Result<(), AuthError> {
        match self.entry(vault_ref)?.delete_credential() {
            Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
            Err(err) => {
                Err(AuthError::Vault { action: "delete tokens", message: err.to_string() })
            }
        }
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
}
