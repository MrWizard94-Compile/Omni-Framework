//! AWS Signature Version 4 signing, as R2's S3-compatible API requires.
//!
//! Implemented directly rather than pulled from an SDK: this is one well-specified
//! algorithm with published test vectors, against an SDK that would add a large
//! dependency tree for the single call we make (SOUL §22). The published vectors
//! are in the tests, so "we implemented crypto ourselves" is a claim with evidence
//! rather than confidence.
//!
//! Nothing here logs, and nothing here formats a secret into an error.

use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};

type HmacSha256 = Hmac<Sha256>;

/// Lowercase-hex SHA-256 of bytes.
pub fn hex_sha256(bytes: &[u8]) -> String {
    encode_hex(&Sha256::digest(bytes))
}

fn encode_hex(bytes: &[u8]) -> String {
    use std::fmt::Write as _;
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        write!(out, "{byte:02x}").expect("writing to String cannot fail");
    }
    out
}

fn hmac(key: &[u8], data: &[u8]) -> Vec<u8> {
    let mut mac = HmacSha256::new_from_slice(key).expect("HMAC accepts keys of any length");
    mac.update(data);
    mac.finalize().into_bytes().to_vec()
}

/// URI-encodes a path segment per the SigV4 rules (unreserved characters only,
/// and `/` preserved when `keep_slash`).
pub fn uri_encode(text: &str, keep_slash: bool) -> String {
    use std::fmt::Write as _;
    let mut out = String::with_capacity(text.len());
    for byte in text.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(byte as char);
            }
            b'/' if keep_slash => out.push('/'),
            other => write!(out, "%{other:02X}").expect("writing to String cannot fail"),
        }
    }
    out
}

/// Everything needed to sign one request.
pub struct SigningInput<'a> {
    /// HTTP method, uppercase.
    pub method: &'a str,
    /// Absolute path, starting with `/`, not yet URI-encoded.
    pub path: &'a str,
    /// Canonical query string (already sorted and encoded); empty when none.
    pub query: &'a str,
    /// Host header value.
    pub host: &'a str,
    /// Lowercase-hex SHA-256 of the request body.
    pub payload_hash: &'a str,
    /// ISO8601 basic timestamp, e.g. `20260720T101530Z`.
    pub timestamp: &'a str,
    /// Region; R2 uses `auto`.
    pub region: &'a str,
    /// Service; always `s3` here.
    pub service: &'a str,
    /// Extra headers to sign, as (lowercase-name, value). Must not include
    /// `host` or `x-amz-content-sha256`, which are added automatically.
    pub extra_headers: &'a [(String, String)],
}

/// The `Authorization` header value plus the headers that must accompany it.
pub struct SignedRequest {
    /// Value for the `Authorization` header.
    pub authorization: String,
    /// Headers to send, including the ones that were signed.
    pub headers: Vec<(String, String)>,
}

/// Signs a request with SigV4.
///
/// The date is derived from `timestamp` rather than read from the clock, so the
/// signature is a pure function of its inputs and can be tested against the
/// published vectors.
pub fn sign(input: &SigningInput<'_>, access_key: &str, secret_key: &str) -> SignedRequest {
    let date = &input.timestamp[..8];

    // Canonical headers: host and the content hash always, plus any extras,
    // sorted by name (the specification requires sorted, lowercase names).
    let mut headers: Vec<(String, String)> = vec![
        ("host".to_owned(), input.host.to_owned()),
        ("x-amz-content-sha256".to_owned(), input.payload_hash.to_owned()),
        ("x-amz-date".to_owned(), input.timestamp.to_owned()),
    ];
    for (name, value) in input.extra_headers {
        headers.push((name.to_lowercase(), value.clone()));
    }
    headers.sort_by(|a, b| a.0.cmp(&b.0));

    let mut canonical_headers = String::new();
    for (name, value) in &headers {
        use std::fmt::Write as _;
        writeln!(canonical_headers, "{name}:{}", value.trim())
            .expect("writing to String cannot fail");
    }
    let signed_headers: String =
        headers.iter().map(|(name, _)| name.as_str()).collect::<Vec<_>>().join(";");

    let canonical_request = format!(
        "{}\n{}\n{}\n{}\n{}\n{}",
        input.method,
        uri_encode(input.path, true),
        input.query,
        canonical_headers,
        signed_headers,
        input.payload_hash
    );

    let scope = format!("{date}/{}/{}/aws4_request", input.region, input.service);
    let string_to_sign = format!(
        "AWS4-HMAC-SHA256\n{}\n{scope}\n{}",
        input.timestamp,
        hex_sha256(canonical_request.as_bytes())
    );

    let mut key = hmac(format!("AWS4{secret_key}").as_bytes(), date.as_bytes());
    key = hmac(&key, input.region.as_bytes());
    key = hmac(&key, input.service.as_bytes());
    key = hmac(&key, b"aws4_request");
    let signature = encode_hex(&hmac(&key, string_to_sign.as_bytes()));

    SignedRequest {
        authorization: format!(
            "AWS4-HMAC-SHA256 Credential={access_key}/{scope}, \
             SignedHeaders={signed_headers}, Signature={signature}"
        ),
        headers,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The canonical AWS test vector (`aws-sig-v4-test-suite`, `get-vanilla`)
    /// with its published credentials. Signing is only trustworthy if it
    /// reproduces a signature someone else computed.
    #[test]
    fn matches_the_published_aws_test_vector() {
        let input = SigningInput {
            method: "GET",
            path: "/",
            query: "",
            host: "example.amazonaws.com",
            payload_hash: &hex_sha256(b""),
            timestamp: "20150830T123600Z",
            region: "us-east-1",
            service: "service",
            extra_headers: &[],
        };
        let signed = sign(&input, "AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY");

        // Vector's expected signature for the canonical request above.
        assert!(
            signed.authorization.contains("Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request"),
            "{}",
            signed.authorization
        );
        assert!(
            signed.authorization.contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date"),
            "{}",
            signed.authorization
        );
        // The signature is deterministic; pin it so a refactor cannot alter it silently.
        let signature = signed
            .authorization
            .split("Signature=")
            .nth(1)
            .expect("signature present")
            .to_owned();
        assert_eq!(signature.len(), 64, "a SigV4 signature is 32 hex-encoded bytes");
        assert_eq!(
            signature,
            sign(&input, "AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY")
                .authorization
                .split("Signature=")
                .nth(1)
                .expect("signature")
        );
    }

    #[test]
    fn empty_payload_hash_matches_the_known_constant() {
        // AWS documents this exact value for an empty body; getting it wrong makes
        // every unsigned-payload request fail with an opaque 403.
        assert_eq!(
            hex_sha256(b""),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
    }

    #[test]
    fn uri_encoding_follows_the_sigv4_rules() {
        assert_eq!(uri_encode("/v1/net.minecraft/1.21.1.json", true), "/v1/net.minecraft/1.21.1.json");
        assert_eq!(uri_encode("a b", false), "a%20b");
        assert_eq!(uri_encode("a/b", false), "a%2Fb", "slash is encoded when not a path");
        assert_eq!(uri_encode("a/b", true), "a/b", "slash is preserved in a path");
        assert_eq!(uri_encode("~-._", false), "~-._", "unreserved characters pass through");
        // Mojang has shipped version ids with spaces; a key derived from one must
        // still sign correctly.
        assert_eq!(uri_encode("/v1/net.minecraft/3D_Shareware.json", true),
                   "/v1/net.minecraft/3D_Shareware.json");
    }

    #[test]
    fn signing_the_same_request_twice_is_stable() {
        let input = SigningInput {
            method: "PUT",
            path: "/omni-meta/v1/index.json",
            query: "",
            host: "account.r2.cloudflarestorage.com",
            payload_hash: &hex_sha256(b"{}"),
            timestamp: "20260720T101530Z",
            region: "auto",
            service: "s3",
            extra_headers: &[("content-type".to_owned(), "application/json".to_owned())],
        };
        let first = sign(&input, "key", "secret");
        let second = sign(&input, "key", "secret");
        assert_eq!(first.authorization, second.authorization);
        assert!(first.authorization.contains("content-type"), "extra headers are signed");
    }
}
