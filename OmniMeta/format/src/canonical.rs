//! Canonical serialization and content hashing.
//!
//! Determinism invariant (SOUL §21): for any value of the model types, the byte
//! output of [`to_canonical_json`] is a pure function of the value — 2-space
//! pretty printing, struct fields in declaration order, maps sorted (the crate
//! is built without `serde_json`'s `preserve_order` feature, so JSON objects are
//! `BTreeMap`-backed), and a single trailing newline. The generator's golden and
//! double-run tests enforce this end to end.

use serde::Serialize;
use sha2::{Digest, Sha256};

use crate::FormatError;

/// Serializes a value to canonical format-v1 bytes.
///
/// # Errors
///
/// [`FormatError::Json`] if the value cannot be represented as JSON (cannot
/// happen for the model types in this crate; the fallible signature exists for
/// caller-supplied `data` values).
pub fn to_canonical_json<T: Serialize>(value: &T) -> Result<Vec<u8>, FormatError> {
    let mut bytes = serde_json::to_vec_pretty(value)
        .map_err(|source| FormatError::Json { what: "canonical serialization", source })?;
    bytes.push(b'\n');
    Ok(bytes)
}

/// Lowercase-hex sha256 of exact bytes — the hash dialect used for every
/// `sha256` field in the tree.
pub fn sha256_hex(bytes: &[u8]) -> String {
    let digest = Sha256::digest(bytes);
    let mut out = String::with_capacity(64);
    for byte in digest {
        use std::fmt::Write as _;
        // Infallible for String; expect() keeps the signature clean.
        write!(out, "{byte:02x}").expect("writing to String cannot fail");
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeMap;

    #[test]
    fn canonical_bytes_are_stable_and_newline_terminated() {
        let mut map = BTreeMap::new();
        map.insert("zeta", 1);
        map.insert("alpha", 2);
        let first = to_canonical_json(&map).expect("serializes");
        let second = to_canonical_json(&map).expect("serializes");
        assert_eq!(first, second);
        assert_eq!(first.last(), Some(&b'\n'));
        let text = String::from_utf8(first).expect("utf8");
        // Sorted map order: alpha before zeta.
        assert!(text.find("alpha").expect("alpha") < text.find("zeta").expect("zeta"));
    }

    #[test]
    fn sha256_matches_known_vector() {
        // NIST test vector: sha256("abc").
        assert_eq!(
            sha256_hex(b"abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }
}
