//! Transport abstraction for the auth chain: the seam that lets every step be
//! tested against recorded fixtures (ARCHITECTURE §8 — never live secrets).

use std::io::Read as _;
use std::time::Duration;

use crate::auth::AuthError;

/// A completed HTTP exchange. Non-2xx statuses are returned (not errors):
/// several chain steps carry their semantics in error bodies (OAuth
/// `authorization_pending`, XSTS `XErr`), so callers interpret status + body.
#[derive(Debug, Clone)]
pub struct HttpResponse {
    /// HTTP status code.
    pub status: u16,
    /// Raw response body.
    pub body: Vec<u8>,
}

impl HttpResponse {
    /// Parses the body as JSON.
    ///
    /// # Errors
    ///
    /// [`AuthError::Protocol`] when the body is not valid JSON.
    pub fn json(&self, step: &'static str) -> Result<serde_json::Value, AuthError> {
        serde_json::from_slice(&self.body).map_err(|err| AuthError::Protocol {
            step,
            detail: format!("invalid JSON in response (status {}): {err}", self.status),
        })
    }
}

/// The auth chain's view of HTTP. Implementations must be thread-safe.
pub trait AuthHttp: Send + Sync {
    /// `application/x-www-form-urlencoded` POST (OAuth endpoints).
    ///
    /// # Errors
    ///
    /// [`AuthError::Http`] on transport failure only.
    fn post_form(&self, url: &str, form: &[(&str, &str)]) -> Result<HttpResponse, AuthError>;

    /// JSON POST, optionally with a `Bearer` authorization.
    ///
    /// # Errors
    ///
    /// [`AuthError::Http`] on transport failure only.
    fn post_json(
        &self,
        url: &str,
        body: &serde_json::Value,
        bearer: Option<&str>,
    ) -> Result<HttpResponse, AuthError>;

    /// GET with a `Bearer` authorization.
    ///
    /// # Errors
    ///
    /// [`AuthError::Http`] on transport failure only.
    fn get_bearer(&self, url: &str, bearer: &str) -> Result<HttpResponse, AuthError>;
}

/// Production transport over ureq (rustls). Response size is capped — no auth
/// endpoint returns more than a few kilobytes.
pub struct UreqAuthHttp {
    agent: ureq::Agent,
}

const MAX_BODY_BYTES: u64 = 4 * 1024 * 1024;

impl UreqAuthHttp {
    /// Creates the production transport with sane timeouts.
    pub fn new() -> Self {
        Self {
            agent: ureq::AgentBuilder::new()
                .timeout(Duration::from_secs(30))
                .user_agent(concat!("OmniLauncher/", env!("CARGO_PKG_VERSION")))
                .build(),
        }
    }

    fn finish(url: &str, outcome: Result<ureq::Response, ureq::Error>) -> Result<HttpResponse, AuthError> {
        let response = match outcome {
            // 4xx/5xx also keep their body — chain semantics live in error bodies.
            Ok(response) | Err(ureq::Error::Status(_, response)) => response,
            Err(err) => {
                return Err(AuthError::Http { url: url.to_owned(), message: err.to_string() });
            }
        };
        let status = response.status();
        let mut body = Vec::new();
        response
            .into_reader()
            .take(MAX_BODY_BYTES)
            .read_to_end(&mut body)
            .map_err(|err| AuthError::Http { url: url.to_owned(), message: err.to_string() })?;
        Ok(HttpResponse { status, body })
    }
}

impl Default for UreqAuthHttp {
    fn default() -> Self {
        Self::new()
    }
}

impl AuthHttp for UreqAuthHttp {
    fn post_form(&self, url: &str, form: &[(&str, &str)]) -> Result<HttpResponse, AuthError> {
        Self::finish(url, self.agent.post(url).send_form(form))
    }

    fn post_json(
        &self,
        url: &str,
        body: &serde_json::Value,
        bearer: Option<&str>,
    ) -> Result<HttpResponse, AuthError> {
        let mut request = self.agent.post(url).set("Accept", "application/json");
        if let Some(token) = bearer {
            request = request.set("Authorization", &format!("Bearer {token}"));
        }
        Self::finish(url, request.send_json(body.clone()))
    }

    fn get_bearer(&self, url: &str, bearer: &str) -> Result<HttpResponse, AuthError> {
        Self::finish(
            url,
            self.agent
                .get(url)
                .set("Accept", "application/json")
                .set("Authorization", &format!("Bearer {bearer}"))
                .call(),
        )
    }
}
