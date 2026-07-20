//! The R2 (S3-compatible) client: credentials, upload, verify.

use std::io::Read as _;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use crate::error::DeployError;
use crate::plan::PlannedUpload;
use crate::sigv4::{self, SigningInput};

/// R2 credentials, read from the environment and never from anywhere else.
///
/// **Why environment-only** (SOUL §36): a secret passed as a command-line argument
/// lands in shell history and in the process list; a secret read from a file in the
/// repository eventually gets committed. An environment variable exported in one
/// shell is the narrowest exposure that still allows automation.
pub struct Credentials {
    access_key_id: String,
    secret_access_key: String,
}

impl std::fmt::Debug for Credentials {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // Never let a debug print leak the secret.
        f.debug_struct("Credentials").finish_non_exhaustive()
    }
}

impl Credentials {
    /// Reads `R2_ACCESS_KEY_ID` and `R2_SECRET_ACCESS_KEY`.
    ///
    /// # Errors
    ///
    /// [`DeployError::MissingCredentials`] naming the variable that is unset.
    pub fn from_environment() -> Result<Self, DeployError> {
        let read = |name: &'static str| {
            std::env::var(name)
                .ok()
                .filter(|value| !value.trim().is_empty())
                .ok_or(DeployError::MissingCredentials { variable: name })
        };
        Ok(Self {
            access_key_id: read("R2_ACCESS_KEY_ID")?,
            secret_access_key: read("R2_SECRET_ACCESS_KEY")?,
        })
    }
}

/// Where to publish.
#[derive(Debug, Clone)]
pub struct Target {
    /// Cloudflare account id (forms the endpoint host).
    pub account_id: String,
    /// Bucket name, e.g. `omni-meta`.
    pub bucket: String,
}

impl Target {
    /// The S3 endpoint host for this account.
    pub fn host(&self) -> String {
        format!("{}.r2.cloudflarestorage.com", self.account_id)
    }
}

/// Uploads objects and verifies them.
pub struct R2Client {
    agent: ureq::Agent,
    target: Target,
    credentials: Credentials,
}

/// Cap on an error body we echo back, so a hostile or verbose endpoint cannot
/// flood the terminal.
const MAX_ERROR_BODY: u64 = 4096;

impl R2Client {
    /// Creates a client for `target` using environment credentials.
    ///
    /// # Errors
    ///
    /// [`DeployError::MissingCredentials`] when the environment is not set up.
    pub fn new(target: Target) -> Result<Self, DeployError> {
        Ok(Self {
            agent: ureq::AgentBuilder::new()
                .timeout(Duration::from_mins(5))
                .user_agent(concat!("omni-meta-deploy/", env!("CARGO_PKG_VERSION")))
                .build(),
            target,
            credentials: Credentials::from_environment()?,
        })
    }

    /// ISO8601 basic timestamp for signing.
    fn timestamp() -> String {
        let seconds = SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs());
        format_iso8601(seconds)
    }

    /// Uploads one planned object.
    ///
    /// # Errors
    ///
    /// [`DeployError::Io`] when the source cannot be read, [`DeployError::Transport`]
    /// on a network failure, [`DeployError::Endpoint`] when R2 rejects the write.
    pub fn put(&self, upload: &PlannedUpload) -> Result<(), DeployError> {
        let bytes = std::fs::read(&upload.source).map_err(|err| DeployError::Io {
            action: "read object for upload",
            path: upload.source.clone(),
            source: err,
        })?;
        let path = format!("/{}/{}", self.target.bucket, upload.key);
        let payload_hash = sigv4::hex_sha256(&bytes);
        let timestamp = Self::timestamp();
        let extra = vec![
            ("content-type".to_owned(), upload.content_type.clone()),
            ("cache-control".to_owned(), upload.cache_control.clone()),
        ];
        let signed = sigv4::sign(
            &SigningInput {
                method: "PUT",
                path: &path,
                query: "",
                host: &self.target.host(),
                payload_hash: &payload_hash,
                timestamp: &timestamp,
                region: "auto",
                service: "s3",
                extra_headers: &extra,
            },
            &self.credentials.access_key_id,
            &self.credentials.secret_access_key,
        );

        let url = format!("https://{}{path}", self.target.host());
        let mut request = self.agent.put(&url).set("Authorization", &signed.authorization);
        for (name, value) in &signed.headers {
            if name != "host" {
                request = request.set(name, value);
            }
        }
        match request.send_bytes(&bytes) {
            Ok(_) => Ok(()),
            Err(ureq::Error::Status(status, response)) => {
                Err(DeployError::Endpoint {
                    key: upload.key.clone(),
                    status,
                    body: read_capped(response),
                })
            }
            Err(err) => Err(DeployError::Transport {
                key: upload.key.clone(),
                message: err.to_string(),
            }),
        }
    }

    /// Fetches an object over the public CDN URL and checks its hash.
    ///
    /// Verifying through the *public* URL rather than the S3 API is deliberate:
    /// it proves what a launcher will actually receive, including that the custom
    /// domain and TLS are wired correctly, not merely that a write landed.
    ///
    /// # Errors
    ///
    /// [`DeployError::Verification`] on a hash mismatch; transport/endpoint errors
    /// as their variants.
    pub fn verify_public(
        &self,
        base_url: &str,
        upload: &PlannedUpload,
    ) -> Result<(), DeployError> {
        let url = format!("{}/{}", base_url.trim_end_matches('/'), upload.key);
        let response = self.agent.get(&url).call().map_err(|err| match err {
            ureq::Error::Status(status, response) => DeployError::Endpoint {
                key: upload.key.clone(),
                status,
                body: read_capped(response),
            },
            other @ ureq::Error::Transport(_) => DeployError::Transport {
                key: upload.key.clone(),
                message: other.to_string(),
            },
        })?;
        let mut bytes = Vec::new();
        response
            .into_reader()
            .take(64 * 1024 * 1024)
            .read_to_end(&mut bytes)
            .map_err(|err| DeployError::Transport {
                key: upload.key.clone(),
                message: err.to_string(),
            })?;
        let actual = sigv4::hex_sha256(&bytes);
        if actual == upload.sha256 {
            Ok(())
        } else {
            Err(DeployError::Verification {
                key: upload.key.clone(),
                expected: upload.sha256.clone(),
                actual,
            })
        }
    }
}

fn read_capped(response: ureq::Response) -> String {
    let mut body = Vec::new();
    let _ = response.into_reader().take(MAX_ERROR_BODY).read_to_end(&mut body);
    String::from_utf8_lossy(&body).trim().to_owned()
}

/// Formats Unix seconds as `YYYYMMDDTHHMMSSZ` without pulling in a date library.
///
/// Civil-date conversion from days since the epoch, using the standard
/// days-from-civil inverse (Howard Hinnant's algorithm) — correct for every date
/// in the supported range rather than only for the common case.
fn format_iso8601(unix_seconds: u64) -> String {
    // Everything below is i64 so the era arithmetic needs no casts, which is also
    // why it stays correct for pre-epoch inputs the algorithm is defined over.
    let total = i64::try_from(unix_seconds).unwrap_or(i64::MAX);
    let days = total.div_euclid(86_400);
    let seconds_of_day = total.rem_euclid(86_400);

    // Shift so the era starts on 0000-03-01, which removes leap-year special cases.
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let day_of_era = z - era * 146_097;
    let year_of_era =
        (day_of_era - day_of_era / 1460 + day_of_era / 36_524 - day_of_era / 146_096) / 365;
    let year = year_of_era + era * 400;
    let day_of_year = day_of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100);
    let shifted_month = (5 * day_of_year + 2) / 153;
    let day = day_of_year - (153 * shifted_month + 2) / 5 + 1;
    let month = if shifted_month < 10 { shifted_month + 3 } else { shifted_month - 9 };
    let year = if month <= 2 { year + 1 } else { year };

    format!(
        "{year:04}{month:02}{day:02}T{:02}{:02}{:02}Z",
        seconds_of_day / 3600,
        (seconds_of_day % 3600) / 60,
        seconds_of_day % 60
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn timestamps_format_correctly_across_known_dates() {
        assert_eq!(format_iso8601(0), "19700101T000000Z");
        assert_eq!(format_iso8601(1_440_938_160), "20150830T123600Z", "the AWS vector's time");
        // A leap day, the case naive implementations get wrong.
        assert_eq!(format_iso8601(1_709_208_000), "20240229T120000Z");
        // Cross-checked against an independent implementation (Python `time.gmtime`).
        assert_eq!(format_iso8601(1_800_000_000), "20270115T080000Z");
    }

    #[test]
    fn endpoint_host_follows_cloudflare_form() {
        let target = Target {
            account_id: "0c915370ac5cdcdde90ffd6911b87321".to_owned(),
            bucket: "omni-meta".to_owned(),
        };
        assert_eq!(target.host(), "0c915370ac5cdcdde90ffd6911b87321.r2.cloudflarestorage.com");
    }

    #[test]
    fn credentials_never_appear_in_debug_output() {
        let credentials = Credentials {
            access_key_id: "AKIAEXAMPLE".to_owned(),
            secret_access_key: "super-secret-value".to_owned(),
        };
        let rendered = format!("{credentials:?}");
        assert!(!rendered.contains("super-secret-value"), "leaked: {rendered}");
        assert!(!rendered.contains("AKIAEXAMPLE"), "leaked: {rendered}");
    }

    #[test]
    fn missing_credentials_name_the_variable_and_explain_why() {
        // Guard against a future refactor that starts accepting a --secret flag.
        let error = DeployError::MissingCredentials { variable: "R2_SECRET_ACCESS_KEY" };
        let text = error.to_string();
        assert!(text.contains("R2_SECRET_ACCESS_KEY"));
        assert!(text.contains("never reads them from a file or an argument"));
    }
}
