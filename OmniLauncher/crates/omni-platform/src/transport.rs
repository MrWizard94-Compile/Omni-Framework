//! HTTP transport abstraction for platform APIs.

use std::collections::BTreeMap;
use std::io::Read as _;
use std::time::Duration;

use crate::error::{PlatformError, PlatformResult};
use crate::USER_AGENT;

/// Minimal HTTP interface (sync).
pub trait HttpTransport: Send + Sync {
    /// GET `url` with optional extra headers; returns response body bytes.
    ///
    /// # Errors
    ///
    /// [`PlatformError::Transport`] on network / non-success status.
    fn get(&self, url: &str, headers: &BTreeMap<String, String>) -> PlatformResult<Vec<u8>>;

    /// POST `url` with JSON body and optional headers.
    ///
    /// # Errors
    ///
    /// [`PlatformError::Transport`] on network / non-success status.
    fn post_json(
        &self,
        url: &str,
        headers: &BTreeMap<String, String>,
        body: &[u8],
    ) -> PlatformResult<Vec<u8>>;
}

/// Production transport over ureq/rustls.
pub struct UreqTransport {
    agent: ureq::Agent,
}

impl UreqTransport {
    /// Builds the default agent with Omni user-agent and timeouts.
    #[must_use]
    pub fn new() -> Self {
        Self {
            agent: ureq::AgentBuilder::new()
                .timeout(Duration::from_mins(1))
                .user_agent(USER_AGENT)
                .build(),
        }
    }
}

impl Default for UreqTransport {
    fn default() -> Self {
        Self::new()
    }
}

fn read_body(url: &str, response: ureq::Response) -> PlatformResult<Vec<u8>> {
    let status = response.status();
    let mut bytes = Vec::new();
    response.into_reader().read_to_end(&mut bytes).map_err(|err| PlatformError::Transport {
        action: "read body",
        url: url.to_owned(),
        message: err.to_string(),
    })?;
    if !(200..300).contains(&status) {
        let preview = String::from_utf8_lossy(&bytes);
        let preview = preview.chars().take(200).collect::<String>();
        return Err(PlatformError::Transport {
            action: "HTTP",
            url: url.to_owned(),
            message: format!("HTTP {status}: {preview}"),
        });
    }
    Ok(bytes)
}

impl HttpTransport for UreqTransport {
    fn get(&self, url: &str, headers: &BTreeMap<String, String>) -> PlatformResult<Vec<u8>> {
        let mut request = self.agent.get(url);
        for (key, value) in headers {
            request = request.set(key, value);
        }
        let response = request.call().map_err(|err| PlatformError::Transport {
            action: "GET",
            url: url.to_owned(),
            message: err.to_string(),
        })?;
        read_body(url, response)
    }

    fn post_json(
        &self,
        url: &str,
        headers: &BTreeMap<String, String>,
        body: &[u8],
    ) -> PlatformResult<Vec<u8>> {
        let mut request = self.agent.post(url).set("Content-Type", "application/json");
        for (key, value) in headers {
            request = request.set(key, value);
        }
        let response = request.send_bytes(body).map_err(|err| PlatformError::Transport {
            action: "POST",
            url: url.to_owned(),
            message: err.to_string(),
        })?;
        read_body(url, response)
    }
}
