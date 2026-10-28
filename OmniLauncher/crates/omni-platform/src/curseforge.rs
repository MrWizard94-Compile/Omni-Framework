//! CurseForge Core API client (official endpoints only).

use std::collections::BTreeMap;
use std::env;
use std::sync::Arc;

use serde::Deserialize;
use serde_json::json;

use crate::error::{PlatformError, PlatformResult};
use crate::query::{HashAlgorithm, Platform, ResourceQuery, SearchHit, VersionFile};
use crate::rate::RateLimiter;
use crate::transport::HttpTransport;

const API: &str = "https://api.curseforge.com/v1";
/// Minecraft game id on CurseForge.
const MINECRAFT_GAME_ID: u32 = 432;

/// Typed CurseForge client. Requires an API key (`CF_API_KEY` or constructor).
pub struct CurseForgeClient {
    transport: Arc<dyn HttpTransport>,
    limiter: RateLimiter,
    api_key: Option<String>,
}

impl CurseForgeClient {
    /// Creates a client; key from `api_key` or `CF_API_KEY` / `CURSEFORGE_API_KEY` env.
    #[must_use]
    pub fn new(transport: Arc<dyn HttpTransport>, api_key: Option<String>) -> Self {
        let api_key = api_key
            .or_else(|| env::var("CF_API_KEY").ok())
            .or_else(|| env::var("CURSEFORGE_API_KEY").ok());
        Self { transport, limiter: RateLimiter::default(), api_key }
    }

    fn headers(&self) -> PlatformResult<BTreeMap<String, String>> {
        let key = self.api_key.as_deref().filter(|k| !k.is_empty()).ok_or(
            PlatformError::MissingApiKey { platform: "curseforge" },
        )?;
        Ok(BTreeMap::from([
            ("x-api-key".into(), key.to_owned()),
            ("Accept".into(), "application/json".into()),
        ]))
    }

    fn get_json<T: for<'de> Deserialize<'de>>(&self, path: &str) -> PlatformResult<T> {
        self.limiter.wait();
        let headers = self.headers()?;
        let url = format!("{API}{path}");
        let bytes = self.transport.get(&url, &headers)?;
        serde_json::from_slice(&bytes).map_err(|source| PlatformError::Parse {
            context: "curseforge response",
            source,
        })
    }

    fn post_json<T: for<'de> Deserialize<'de>>(
        &self,
        path: &str,
        body: &serde_json::Value,
    ) -> PlatformResult<T> {
        self.limiter.wait();
        let headers = self.headers()?;
        let url = format!("{API}{path}");
        let raw = serde_json::to_vec(body).map_err(|source| PlatformError::Parse {
            context: "curseforge request body",
            source,
        })?;
        let bytes = self.transport.post_json(&url, &headers, &raw)?;
        serde_json::from_slice(&bytes).map_err(|source| PlatformError::Parse {
            context: "curseforge response",
            source,
        })
    }

    /// Bulk murmur2 fingerprint lookup (official Core API).
    ///
    /// # Errors
    ///
    /// Transport / parse / missing API key.
    pub fn files_by_fingerprints(
        &self,
        fingerprints: &[u32],
    ) -> PlatformResult<Vec<VersionFile>> {
        if fingerprints.is_empty() {
            return Ok(Vec::new());
        }
        let body = json!({ "fingerprints": fingerprints });
        let path = format!("/fingerprints/{MINECRAFT_GAME_ID}");
        let response: CfFingerprintResponse = self.post_json(&path, &body)?;
        let mut out = Vec::new();
        for match_row in response.data.exact_matches {
            if let Some(file) = match_row.file {
                out.push(cf_file_to_version(file, match_row.id));
            }
        }
        Ok(out)
    }

    /// File download metadata for a project file; blocked distribution is typed.
    ///
    /// # Errors
    ///
    /// Transport / parse / missing key / distribution blocked.
    pub fn file_download(
        &self,
        project_id: u64,
        file_id: u64,
    ) -> PlatformResult<VersionFile> {
        let path = format!("/mods/{project_id}/files/{file_id}");
        let body: CfData<CfFile> = self.get_json(&path)?;
        let file = body.data;
        if file.is_available.unwrap_or(true)
            && file.download_url.as_ref().is_some_and(|u| !u.is_empty())
        {
            return Ok(cf_file_to_version(file, project_id));
        }
        Err(blocked_distribution(&project_id.to_string()))
    }

    /// Lists files for a mod project, optionally filtered by Minecraft game version.
    ///
    /// # Errors
    ///
    /// Transport / parse / missing API key.
    pub fn mod_files(
        &self,
        project_id: u64,
        game_version: Option<&str>,
    ) -> PlatformResult<Vec<VersionFile>> {
        let mut path = format!("/mods/{project_id}/files?pageSize=50");
        if let Some(version) = game_version {
            path.push_str("&gameVersion=");
            path.push_str(&percent_encode(version));
        }
        let body: CfData<Vec<CfFile>> = self.get_json(&path)?;
        Ok(body
            .data
            .into_iter()
            .map(|file| cf_file_to_version(file, project_id))
            .collect())
    }
}

impl ResourceQuery for CurseForgeClient {
    fn platform(&self) -> Platform {
        Platform::CurseForge
    }

    fn search(
        &self,
        query: &str,
        game_version: Option<&str>,
        limit: u32,
    ) -> PlatformResult<Vec<SearchHit>> {
        let limit = limit.clamp(1, 50);
        let mut path = format!(
            "/mods/search?gameId={MINECRAFT_GAME_ID}&searchFilter={}&pageSize={limit}&classId=6",
            percent_encode(query)
        );
        if let Some(version) = game_version {
            path.push_str("&gameVersion=");
            path.push_str(&percent_encode(version));
        }
        let body: CfData<Vec<CfMod>> = self.get_json(&path)?;
        Ok(body
            .data
            .into_iter()
            .map(|m| SearchHit {
                platform: Platform::CurseForge,
                project_id: m.id.to_string(),
                slug: m.slug.unwrap_or_default(),
                title: m.name.unwrap_or_default(),
                description: m.summary.unwrap_or_default(),
                author: m
                    .authors
                    .and_then(|a| a.into_iter().next())
                    .map(|a| a.name)
                    .unwrap_or_default(),
                downloads: m.download_count.unwrap_or(0).cast_unsigned(),
            })
            .collect())
    }

    fn version_by_hash(
        &self,
        hash: &str,
        algorithm: HashAlgorithm,
    ) -> PlatformResult<Option<VersionFile>> {
        if algorithm != HashAlgorithm::Murmur2 {
            return Ok(None);
        }
        let fingerprint: u32 = hash.parse().map_err(|_| PlatformError::Transport {
            action: "parse murmur2",
            url: hash.to_owned(),
            message: "murmur2 fingerprint must be a decimal u32 string".into(),
        })?;
        let matches = self.files_by_fingerprints(&[fingerprint])?;
        Ok(matches.into_iter().next())
    }
}

fn cf_file_to_version(file: CfFile, project_id: u64) -> VersionFile {
    let sha1 = file
        .hashes
        .as_ref()
        .and_then(|hashes| {
            hashes.iter().find(|h| h.algo == Some(1)).map(|h| h.value.to_ascii_lowercase())
        });
    VersionFile {
        platform: Platform::CurseForge,
        project_id: project_id.to_string(),
        file_id: file.id.to_string(),
        file_name: file.file_name.unwrap_or_default(),
        download_url: file.download_url.unwrap_or_default(),
        sha1,
        sha512: None,
        size: file.file_length.map(u64::from),
        game_versions: file.game_versions.unwrap_or_default(),
        loaders: Vec::new(),
    }
}

/// Official CF deep-link for a project (used when distribution is blocked).
#[must_use]
pub fn official_app_link(project_id: &str) -> String {
    format!("https://www.curseforge.com/minecraft/mc-mods/{project_id}")
}

fn percent_encode(input: &str) -> String {
    let mut out = String::with_capacity(input.len() * 2);
    for byte in input.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(char::from(byte));
            }
            _ => {
                use std::fmt::Write as _;
                write!(&mut out, "%{byte:02X}").expect("String write");
            }
        }
    }
    out
}

#[derive(Debug, Deserialize)]
struct CfData<T> {
    data: T,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CfFingerprintResponse {
    data: CfFingerprintData,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CfFingerprintData {
    #[serde(default)]
    exact_matches: Vec<CfExactMatch>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CfExactMatch {
    id: u64,
    file: Option<CfFile>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CfFile {
    id: u64,
    file_name: Option<String>,
    download_url: Option<String>,
    file_length: Option<u32>,
    is_available: Option<bool>,
    #[serde(default)]
    hashes: Option<Vec<CfHash>>,
    #[serde(default)]
    game_versions: Option<Vec<String>>,
}

#[derive(Debug, Deserialize)]
struct CfHash {
    value: String,
    algo: Option<u32>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CfMod {
    id: u64,
    name: Option<String>,
    slug: Option<String>,
    summary: Option<String>,
    download_count: Option<i64>,
    authors: Option<Vec<CfAuthor>>,
}

#[derive(Debug, Deserialize)]
struct CfAuthor {
    name: String,
}

/// Returns [`PlatformError::DistributionBlocked`] when a CF file has no download URL
/// because the author opted out of third-party distribution.
pub fn blocked_distribution(project_id: &str) -> PlatformError {
    PlatformError::DistributionBlocked {
        project_id: project_id.to_owned(),
        open_in_app: Some(official_app_link(project_id)),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::transport::HttpTransport;
    use std::sync::Mutex;

    struct MapTransport {
        get: Mutex<BTreeMap<String, Vec<u8>>>,
        post: Mutex<BTreeMap<String, Vec<u8>>>,
    }

    impl HttpTransport for MapTransport {
        fn get(&self, url: &str, _headers: &BTreeMap<String, String>) -> PlatformResult<Vec<u8>> {
            self.get.lock().unwrap().get(url).cloned().ok_or_else(|| PlatformError::Transport {
                action: "GET",
                url: url.to_owned(),
                message: "HTTP 404".into(),
            })
        }

        fn post_json(
            &self,
            url: &str,
            _headers: &BTreeMap<String, String>,
            _body: &[u8],
        ) -> PlatformResult<Vec<u8>> {
            self.post.lock().unwrap().get(url).cloned().ok_or_else(|| PlatformError::Transport {
                action: "POST",
                url: url.to_owned(),
                message: "HTTP 404".into(),
            })
        }
    }

    #[test]
    fn missing_key_is_typed() {
        let client = CurseForgeClient {
            transport: Arc::new(crate::UreqTransport::new()),
            limiter: RateLimiter::default(),
            api_key: None,
        };
        let err = client.search("jei", Some("1.21.1"), 5).unwrap_err();
        assert!(matches!(err, PlatformError::MissingApiKey { .. }));
    }

    #[test]
    fn official_link_shape() {
        assert!(official_app_link("238222").contains("curseforge.com"));
    }

    #[test]
    fn files_by_fingerprints_maps_exact_matches() {
        let url = format!("{API}/fingerprints/{MINECRAFT_GAME_ID}");
        let body = br#"{
          "data": {
            "exactMatches": [{
              "id": 238222,
              "file": {
                "id": 99,
                "fileName": "jei.jar",
                "downloadUrl": "https://edge.forgecdn.net/files/99/jei.jar",
                "fileLength": 12,
                "isAvailable": true,
                "hashes": [{"value": "abcd", "algo": 1}],
                "gameVersions": ["1.21.1"]
              }
            }]
          }
        }"#;
        let transport = Arc::new(MapTransport {
            get: Mutex::new(BTreeMap::new()),
            post: Mutex::new(BTreeMap::from([(url, body.to_vec())])),
        });
        let client = CurseForgeClient::new(transport, Some("test-key".into()));
        let files = client.files_by_fingerprints(&[1_743_015_372]).expect("lookup");
        assert_eq!(files.len(), 1);
        assert_eq!(files[0].file_name, "jei.jar");
        assert_eq!(files[0].project_id, "238222");
        assert!(files[0].download_url.contains("forgecdn.net"));
    }

    #[test]
    fn file_download_blocked_when_url_missing() {
        let url = format!("{API}/mods/1/files/2");
        let body = br#"{"data":{"id":2,"fileName":"x.jar","downloadUrl":null,"isAvailable":false}}"#;
        let transport = Arc::new(MapTransport {
            get: Mutex::new(BTreeMap::from([(url, body.to_vec())])),
            post: Mutex::new(BTreeMap::new()),
        });
        let client = CurseForgeClient::new(transport, Some("k".into()));
        let err = client.file_download(1, 2).unwrap_err();
        assert!(matches!(err, PlatformError::DistributionBlocked { .. }));
    }

    #[test]
    fn mod_files_lists_downloadable_versions() {
        let url = format!("{API}/mods/238222/files?pageSize=50&gameVersion=1.21.1");
        let body = br#"{
          "data": [{
            "id": 99,
            "fileName": "jei.jar",
            "downloadUrl": "https://edge.forgecdn.net/files/99/jei.jar",
            "fileLength": 12,
            "isAvailable": true,
            "hashes": [{"value": "abcd", "algo": 1}],
            "gameVersions": ["1.21.1"]
          }]
        }"#;
        let transport = Arc::new(MapTransport {
            get: Mutex::new(BTreeMap::from([(url, body.to_vec())])),
            post: Mutex::new(BTreeMap::new()),
        });
        let client = CurseForgeClient::new(transport, Some("test-key".into()));
        let files = client.mod_files(238_222, Some("1.21.1")).expect("files");
        assert_eq!(files.len(), 1);
        assert_eq!(files[0].file_name, "jei.jar");
        assert!(files[0].download_url.contains("forgecdn.net"));
    }
}
