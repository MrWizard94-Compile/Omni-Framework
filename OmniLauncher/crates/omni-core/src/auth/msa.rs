//! Step 1 — Microsoft OAuth 2.0 against the `consumers` authority: device-code
//! flow (development default), refresh-token renewal, and the PKCE building
//! blocks for the interactive loopback flow (RFC 7636 / RFC 8252).

use std::io::{Read as _, Write as _};
use std::net::TcpListener;
use std::time::Duration;

use sha2::{Digest as _, Sha256};

use crate::auth::http::AuthHttp;
use crate::auth::{AuthError, MSA_SCOPE};

const AUTHORITY: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0";

/// Microsoft tokens after a successful OAuth exchange.
#[derive(Debug, Clone)]
pub struct MsaTokens {
    /// Bearer used as the XBL `RpsTicket`.
    pub access_token: String,
    /// Long-lived token for silent renewal (`offline_access` scope).
    pub refresh_token: String,
    /// Access-token lifetime in seconds, as reported by the endpoint.
    pub expires_in_secs: i64,
}

/// What the user must be shown to complete a device-code sign-in.
#[derive(Debug, Clone)]
pub struct DeviceCodePrompt {
    /// Where the user goes (e.g. `https://www.microsoft.com/link`).
    pub verification_uri: String,
    /// The code the user enters there.
    pub user_code: String,
    /// Opaque code this app polls with (not shown to the user).
    pub device_code: String,
    /// Polling interval requested by the endpoint.
    pub interval: Duration,
    /// How long the prompt stays valid.
    pub expires_in: Duration,
}

fn parse_tokens(value: &serde_json::Value) -> Result<MsaTokens, AuthError> {
    let field = |name: &str| -> Result<String, AuthError> {
        value[name]
            .as_str()
            .map(str::to_owned)
            .ok_or_else(|| AuthError::Protocol {
                step: "msa token",
                detail: format!("response is missing {name:?}"),
            })
    };
    Ok(MsaTokens {
        access_token: field("access_token")?,
        refresh_token: field("refresh_token")?,
        expires_in_secs: value["expires_in"].as_i64().unwrap_or(3600),
    })
}

/// Starts a device-code sign-in: returns the prompt to display.
///
/// # Errors
///
/// [`AuthError::Http`] on transport failure; [`AuthError::Protocol`] on an
/// unexpected response shape.
pub fn device_code_begin(
    http: &dyn AuthHttp,
    client_id: &str,
) -> Result<DeviceCodePrompt, AuthError> {
    let response = http.post_form(
        &format!("{AUTHORITY}/devicecode"),
        &[("client_id", client_id), ("scope", MSA_SCOPE)],
    )?;
    let value = response.json("device code begin")?;
    if response.status != 200 {
        return Err(AuthError::Protocol {
            step: "device code begin",
            detail: format!(
                "status {}: {}",
                response.status,
                value["error"].as_str().unwrap_or("unknown error")
            ),
        });
    }
    let text = |name: &str| -> Result<String, AuthError> {
        value[name]
            .as_str()
            .map(str::to_owned)
            .ok_or_else(|| AuthError::Protocol {
                step: "device code begin",
                detail: format!("response is missing {name:?}"),
            })
    };
    Ok(DeviceCodePrompt {
        verification_uri: text("verification_uri")?,
        user_code: text("user_code")?,
        device_code: text("device_code")?,
        interval: Duration::from_secs(value["interval"].as_u64().unwrap_or(5)),
        expires_in: Duration::from_secs(value["expires_in"].as_u64().unwrap_or(900)),
    })
}

/// Polls the token endpoint until the user completes (or abandons) the
/// device-code prompt. `sleep` is injected so tests run instantly.
///
/// # Errors
///
/// [`AuthError::DeviceCodeExpired`] when the prompt times out;
/// [`AuthError::ReauthRequired`] when the user declined;
/// transport/protocol errors as their variants.
pub fn device_code_poll(
    http: &dyn AuthHttp,
    client_id: &str,
    prompt: &DeviceCodePrompt,
    sleep: &dyn Fn(Duration),
) -> Result<MsaTokens, AuthError> {
    let mut interval = prompt.interval;
    loop {
        let response = http.post_form(
            &format!("{AUTHORITY}/token"),
            &[
                ("grant_type", "urn:ietf:params:oauth:grant-type:device_code"),
                ("client_id", client_id),
                ("device_code", &prompt.device_code),
            ],
        )?;
        let value = response.json("device code poll")?;
        if response.status == 200 {
            return parse_tokens(&value);
        }
        match value["error"].as_str().unwrap_or("") {
            "authorization_pending" => sleep(interval),
            "slow_down" => {
                interval += Duration::from_secs(5);
                sleep(interval);
            }
            "expired_token" => return Err(AuthError::DeviceCodeExpired),
            "authorization_declined" | "access_denied" => {
                return Err(AuthError::ReauthRequired {
                    oauth_error: "authorization_declined".to_owned(),
                });
            }
            other => {
                return Err(AuthError::Protocol {
                    step: "device code poll",
                    detail: format!("status {}: {other:?}", response.status),
                });
            }
        }
    }
}

/// Renews Microsoft tokens silently with a refresh token.
///
/// # Errors
///
/// [`AuthError::ReauthRequired`] when the refresh token is no longer valid
/// (revoked, expired, password changed); transport/protocol errors otherwise.
pub fn refresh(
    http: &dyn AuthHttp,
    client_id: &str,
    refresh_token: &str,
) -> Result<MsaTokens, AuthError> {
    let response = http.post_form(
        &format!("{AUTHORITY}/token"),
        &[
            ("grant_type", "refresh_token"),
            ("client_id", client_id),
            ("refresh_token", refresh_token),
            ("scope", MSA_SCOPE),
        ],
    )?;
    let value = response.json("msa refresh")?;
    if response.status == 200 {
        return parse_tokens(&value);
    }
    Err(AuthError::ReauthRequired {
        oauth_error: value["error"].as_str().unwrap_or("unknown").to_owned(),
    })
}

// ---------------------------------------------------------------------------
// PKCE (interactive flow building blocks; the Tauri shell opens the browser).
// ---------------------------------------------------------------------------

/// A PKCE verifier/challenge pair (RFC 7636, S256).
#[derive(Debug, Clone)]
pub struct PkceChallenge {
    /// The secret verifier, sent only in the code exchange.
    pub verifier: String,
    /// The S256 challenge, embedded in the authorize URL.
    pub challenge: String,
}

/// Unpadded base64url (RFC 4648 §5) — the encoding PKCE mandates.
fn base64url_nopad(bytes: &[u8]) -> String {
    const ALPHABET: &[u8; 64] =
        b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let mut out = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for chunk in bytes.chunks(3) {
        let b0 = u32::from(chunk[0]);
        let b1 = chunk.get(1).copied().map_or(0, u32::from);
        let b2 = chunk.get(2).copied().map_or(0, u32::from);
        let triple = (b0 << 16) | (b1 << 8) | b2;
        let take = chunk.len() + 1;
        for i in 0..take {
            let index = ((triple >> (18 - 6 * i)) & 0x3f) as usize;
            out.push(ALPHABET[index] as char);
        }
    }
    out
}

/// Generates a fresh cryptographically random PKCE pair.
///
/// # Errors
///
/// [`AuthError::Protocol`] if the OS entropy source fails (effectively never).
pub fn new_pkce_challenge() -> Result<PkceChallenge, AuthError> {
    let mut seed = [0_u8; 32];
    getrandom::fill(&mut seed).map_err(|err| AuthError::Protocol {
        step: "pkce",
        detail: format!("OS entropy source failed: {err}"),
    })?;
    let verifier = base64url_nopad(&seed);
    let challenge = base64url_nopad(&Sha256::digest(verifier.as_bytes()));
    Ok(PkceChallenge { verifier, challenge })
}

fn urlencode(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for byte in value.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(byte as char);
            }
            other => {
                use std::fmt::Write as _;
                write!(out, "%{other:02X}").expect("writing to String cannot fail");
            }
        }
    }
    out
}

/// Builds the browser authorize URL for the interactive PKCE flow.
pub fn build_authorize_url(client_id: &str, redirect_uri: &str, pkce: &PkceChallenge) -> String {
    format!(
        "{AUTHORITY}/authorize?client_id={}&response_type=code&redirect_uri={}\
         &scope={}&code_challenge={}&code_challenge_method=S256&prompt=select_account",
        urlencode(client_id),
        urlencode(redirect_uri),
        urlencode(MSA_SCOPE),
        urlencode(&pkce.challenge),
    )
}

/// Exchanges an authorization code (from the loopback redirect) for tokens.
///
/// # Errors
///
/// [`AuthError::ReauthRequired`] when the code is rejected;
/// transport/protocol errors otherwise.
pub fn exchange_code(
    http: &dyn AuthHttp,
    client_id: &str,
    redirect_uri: &str,
    code: &str,
    pkce: &PkceChallenge,
) -> Result<MsaTokens, AuthError> {
    let response = http.post_form(
        &format!("{AUTHORITY}/token"),
        &[
            ("grant_type", "authorization_code"),
            ("client_id", client_id),
            ("redirect_uri", redirect_uri),
            ("code", code),
            ("code_verifier", &pkce.verifier),
        ],
    )?;
    let value = response.json("code exchange")?;
    if response.status == 200 {
        return parse_tokens(&value);
    }
    Err(AuthError::ReauthRequired {
        oauth_error: value["error"].as_str().unwrap_or("unknown").to_owned(),
    })
}

/// Binds a loopback listener for the PKCE redirect (RFC 8252 §7.3). Returns
/// the bound listener and the exact `redirect_uri` to register in the
/// authorize URL (`http://127.0.0.1:<port>`).
///
/// # Errors
///
/// [`AuthError::Protocol`] when no loopback port can be bound.
pub fn bind_loopback() -> Result<(TcpListener, String), AuthError> {
    let listener = TcpListener::bind(("127.0.0.1", 0)).map_err(|err| AuthError::Protocol {
        step: "loopback",
        detail: format!("cannot bind 127.0.0.1: {err}"),
    })?;
    let port = listener
        .local_addr()
        .map_err(|err| AuthError::Protocol {
            step: "loopback",
            detail: format!("cannot read bound port: {err}"),
        })?
        .port();
    Ok((listener, format!("http://127.0.0.1:{port}")))
}

/// Accepts exactly one redirect hit on the loopback listener and extracts the
/// `code` query parameter, answering the browser with a small completion page.
///
/// # Errors
///
/// [`AuthError::ReauthRequired`] when the redirect carries an OAuth error
/// (user cancelled); [`AuthError::Protocol`] on malformed requests.
pub fn wait_for_loopback_code(listener: &TcpListener) -> Result<String, AuthError> {
    let (mut stream, _) = listener.accept().map_err(|err| AuthError::Protocol {
        step: "loopback",
        detail: format!("accept failed: {err}"),
    })?;
    let mut buffer = [0_u8; 8192];
    let read = stream.read(&mut buffer).map_err(|err| AuthError::Protocol {
        step: "loopback",
        detail: format!("read failed: {err}"),
    })?;
    let request = String::from_utf8_lossy(&buffer[..read]);
    let request_line = request.lines().next().unwrap_or_default();

    let body = "<html><body style=\"font-family:sans-serif\"><h2>Sign-in complete.</h2>\
                <p>You can close this tab and return to OmniLauncher.</p></body></html>";
    let _ = write!(
        stream,
        "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: {}\r\n\
         Connection: close\r\n\r\n{body}",
        body.len()
    );

    // GET /?code=...&state=... HTTP/1.1
    let query = request_line
        .split_whitespace()
        .nth(1)
        .and_then(|path| path.split_once('?'))
        .map(|(_, query)| query)
        .unwrap_or_default();
    let mut code = None;
    let mut oauth_error = None;
    for pair in query.split('&') {
        let (key, value) = pair.split_once('=').unwrap_or((pair, ""));
        match key {
            "code" => code = Some(value.to_owned()),
            "error" => oauth_error = Some(value.to_owned()),
            _ => {}
        }
    }
    if let Some(error) = oauth_error {
        return Err(AuthError::ReauthRequired { oauth_error: error });
    }
    code.ok_or_else(|| AuthError::Protocol {
        step: "loopback",
        detail: format!("redirect carried no code (request line: {request_line:?})"),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn base64url_matches_rfc_vectors() {
        // RFC 4648 test vectors, translated to the url-safe, unpadded alphabet.
        assert_eq!(base64url_nopad(b""), "");
        assert_eq!(base64url_nopad(b"f"), "Zg");
        assert_eq!(base64url_nopad(b"fo"), "Zm8");
        assert_eq!(base64url_nopad(b"foo"), "Zm9v");
        assert_eq!(base64url_nopad(b"foob"), "Zm9vYg");
        assert_eq!(base64url_nopad(b"fooba"), "Zm9vYmE");
        assert_eq!(base64url_nopad(b"foobar"), "Zm9vYmFy");
        // The url-safe alphabet must be in effect.
        assert_eq!(base64url_nopad(&[0xfb, 0xff]), "-_8");
    }

    #[test]
    fn pkce_challenge_matches_rfc7636_appendix_b() {
        // Appendix B: verifier -> S256 challenge.
        let pkce = PkceChallenge {
            verifier: "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk".to_owned(),
            challenge: String::new(),
        };
        let challenge = base64url_nopad(&Sha256::digest(pkce.verifier.as_bytes()));
        assert_eq!(challenge, "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }

    #[test]
    fn generated_pkce_pairs_are_wellformed_and_unique() {
        let a = new_pkce_challenge().expect("entropy");
        let b = new_pkce_challenge().expect("entropy");
        assert_eq!(a.verifier.len(), 43); // 32 bytes -> 43 base64url chars
        assert_eq!(a.challenge.len(), 43);
        assert_ne!(a.verifier, b.verifier);
    }

    #[test]
    fn authorize_url_is_fully_encoded() {
        let pkce = PkceChallenge { verifier: "v".into(), challenge: "c-hallenge_X".into() };
        let url = build_authorize_url("client-id", "http://127.0.0.1:8123", &pkce);
        assert!(url.contains("scope=XboxLive.signin%20offline_access"));
        assert!(url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A8123"));
        assert!(url.contains("code_challenge=c-hallenge_X"));
        assert!(url.contains("code_challenge_method=S256"));
    }

    #[test]
    fn loopback_roundtrip_extracts_code() {
        let (listener, redirect_uri) = bind_loopback().expect("bind");
        let port: u16 = redirect_uri.rsplit(':').next().expect("port").parse().expect("port");
        let sender = std::thread::spawn(move || {
            let mut stream = std::net::TcpStream::connect(("127.0.0.1", port)).expect("connect");
            write!(stream, "GET /?code=THECODE&state=x HTTP/1.1\r\nHost: local\r\n\r\n")
                .expect("send");
            let mut response = String::new();
            let _ = stream.read_to_string(&mut response);
            response
        });
        let code = wait_for_loopback_code(&listener).expect("code");
        assert_eq!(code, "THECODE");
        let browser_saw = sender.join().expect("thread");
        assert!(browser_saw.contains("Sign-in complete"));
    }

    #[test]
    fn loopback_cancel_maps_to_reauth() {
        let (listener, redirect_uri) = bind_loopback().expect("bind");
        let port: u16 = redirect_uri.rsplit(':').next().expect("port").parse().expect("port");
        let sender = std::thread::spawn(move || {
            let mut stream = std::net::TcpStream::connect(("127.0.0.1", port)).expect("connect");
            write!(stream, "GET /?error=access_denied HTTP/1.1\r\n\r\n").expect("send");
            let mut sink = String::new();
            let _ = stream.read_to_string(&mut sink);
        });
        let err = wait_for_loopback_code(&listener).expect_err("must be an error");
        assert!(matches!(err, AuthError::ReauthRequired { .. }));
        sender.join().expect("thread");
    }
}
