//! Modrinth (Labrinth) public API client.

use std::collections::BTreeMap;
use std::sync::Arc;

use serde::Deserialize;

use crate::error::{PlatformError, PlatformResult};
use crate::query::{HashAlgorithm, Platform, ResourceQuery, SearchHit, VersionFile};
use crate::rate::RateLimiter;
use crate::transport::HttpTransport;

const API: &str = "https://api.modrinth.com/v2";

/// Typed Modrinth client.
pub struct ModrinthClient {
    transport: Arc<dyn HttpTransport>,
    limiter: RateLimiter,
}

impl ModrinthClient {
    /// Creates a client over the given transport.
    #[must_use]
    pub fn new(transport: Arc<dyn HttpTransport>) -> Self {
        Self { transport, limiter: RateLimiter::default() }
    }

    fn get_json<T: for<'de> Deserialize<'de>>(&self, path: &str) -> PlatformResult<T> {
        self.limiter.wait();
        let url = format!("{API}{path}");
        let bytes = self.transport.get(&url, &BTreeMap::new())?;
        serde_json::from_slice(&bytes).map_err(|source| PlatformError::Parse {
            context: "modrinth response",
            source,
        })
    }

    /// Lists project versions (newest first per Labrinth), optionally filtered by game version.
    ///
    /// # Errors
    ///
    /// Transport / parse failures.
    pub fn project_versions(
        &self,
        project_id: &str,
        game_version: Option<&str>,
    ) -> PlatformResult<Vec<VersionFile>> {
        let mut path = format!("/project/{}/version", urlencoding_lite(project_id));
        if let Some(version) = game_version {
            let facets = serde_json::to_string(&vec![version]).unwrap_or_else(|_| "[]".into());
            path.push_str("?game_versions=");
            path.push_str(&urlencoding_lite(&facets));
        }
        let versions: Vec<VersionResponse> = self.get_json(&path)?;
        Ok(versions.into_iter().filter_map(version_response_to_file).collect())
    }
}

fn version_response_to_file(version: VersionResponse) -> Option<VersionFile> {
    let mut files = version.files;
    let primary = files.iter().position(|f| f.primary);
    let file = match primary {
        Some(i) => files.swap_remove(i),
        None => files.pop()?,
    };
    if file.url.is_empty() {
        return None;
    }
    Some(VersionFile {
        platform: Platform::Modrinth,
        project_id: version.project_id,
        file_id: version.id,
        file_name: file.filename,
        download_url: file.url,
        sha1: file.hashes.sha1.map(|h| h.to_lowercase()),
        sha512: file.hashes.sha512.map(|h| h.to_lowercase()),
        size: file.size,
        game_versions: version.game_versions,
        loaders: version.loaders,
    })
}

impl ResourceQuery for ModrinthClient {
    fn platform(&self) -> Platform {
        Platform::Modrinth
    }

    fn search(
        &self,
        query: &str,
        game_version: Option<&str>,
        limit: u32,
    ) -> PlatformResult<Vec<SearchHit>> {
        let limit = limit.clamp(1, 100);
        let mut facets: Vec<Vec<String>> = vec![vec!["project_type:mod".into()]];
        if let Some(version) = game_version {
            facets.push(vec![format!("versions:{version}")]);
        }
        let facets_json = serde_json::to_string(&facets).unwrap_or_else(|_| "[]".into());
        let path = format!(
            "/search?query={}&limit={limit}&facets={}",
            urlencoding_lite(query),
            urlencoding_lite(&facets_json)
        );
        let body: SearchResponse = self.get_json(&path)?;
        Ok(body
            .hits
            .into_iter()
            .map(|hit| SearchHit {
                platform: Platform::Modrinth,
                project_id: hit.project_id,
                slug: hit.slug,
                title: hit.title,
                description: hit.description.unwrap_or_default(),
                author: hit.author.unwrap_or_default(),
                downloads: hit.downloads.unwrap_or(0),
            })
            .collect())
    }

    fn version_by_hash(
        &self,
        hash: &str,
        algorithm: HashAlgorithm,
    ) -> PlatformResult<Option<VersionFile>> {
        let algo = match algorithm {
            HashAlgorithm::Sha1 => "sha1",
            HashAlgorithm::Sha512 => "sha512",
            HashAlgorithm::Murmur2 => {
                return Ok(None); // Modrinth does not use murmur2
            }
        };
        let path = format!("/version_file/{hash}?algorithm={algo}");
        self.limiter.wait();
        let url = format!("{API}{path}");
        let bytes = match self.transport.get(&url, &BTreeMap::new()) {
            Ok(b) => b,
            Err(PlatformError::Transport { message, .. }) if message.contains("HTTP 404") => {
                return Ok(None);
            }
            Err(err) => return Err(err),
        };
        let version: VersionResponse = serde_json::from_slice(&bytes).map_err(|source| {
            PlatformError::Parse { context: "modrinth version_file", source }
        })?;
        Ok(version_response_to_file(version))
    }
}

/// Minimal query escaping (percent-encode reserved characters).
fn urlencoding_lite(input: &str) -> String {
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
struct SearchResponse {
    hits: Vec<SearchHitJson>,
}

#[derive(Debug, Deserialize)]
struct SearchHitJson {
    project_id: String,
    slug: String,
    title: String,
    description: Option<String>,
    author: Option<String>,
    downloads: Option<u64>,
}

#[derive(Debug, Deserialize)]
struct VersionResponse {
    id: String,
    project_id: String,
    #[serde(default)]
    game_versions: Vec<String>,
    #[serde(default)]
    loaders: Vec<String>,
    #[serde(default)]
    files: Vec<VersionFileJson>,
}

#[derive(Debug, Deserialize)]
struct VersionFileJson {
    url: String,
    filename: String,
    #[serde(default)]
    primary: bool,
    size: Option<u64>,
    #[serde(default)]
    hashes: FileHashes,
}

#[derive(Debug, Default, Deserialize)]
struct FileHashes {
    sha1: Option<String>,
    sha512: Option<String>,
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::transport::HttpTransport;
    use std::collections::BTreeMap;
    use std::sync::Mutex;

    struct MapTransport {
        responses: Mutex<BTreeMap<String, Vec<u8>>>,
    }

    impl HttpTransport for MapTransport {
        fn get(&self, url: &str, _headers: &BTreeMap<String, String>) -> PlatformResult<Vec<u8>> {
            let map = self.responses.lock().unwrap();
            map.get(url).cloned().ok_or_else(|| PlatformError::Transport {
                action: "GET",
                url: url.to_owned(),
                message: "HTTP 404: not in fixture map".into(),
            })
        }

        fn post_json(
            &self,
            url: &str,
            _headers: &BTreeMap<String, String>,
            _body: &[u8],
        ) -> PlatformResult<Vec<u8>> {
            Err(PlatformError::Transport {
                action: "POST",
                url: url.to_owned(),
                message: "not used in Modrinth fixtures".into(),
            })
        }
    }

    #[test]
    fn search_maps_hits() {
        let body = br#"{"hits":[{"project_id":"AA","slug":"sodium","title":"Sodium","description":"opt","author":"jelly","downloads":1}]}"#;
        let url = format!(
            "{API}/search?query={}&limit=5&facets={}",
            urlencoding_lite("sodium"),
            urlencoding_lite(r#"[["project_type:mod"],["versions:1.21.1"]]"#)
        );
        let transport = Arc::new(MapTransport {
            responses: Mutex::new(BTreeMap::from([(url, body.to_vec())])),
        });
        let client = ModrinthClient::new(transport);
        let hits = client.search("sodium", Some("1.21.1"), 5).expect("search");
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].slug, "sodium");
        assert_eq!(hits[0].platform, Platform::Modrinth);
    }

    #[test]
    fn version_by_hash_maps_primary_file() {
        let body = br#"{
            "id":"ver1","project_id":"AA",
            "game_versions":["1.21.1"],"loaders":["fabric"],
            "files":[{"url":"https://cdn.modrinth.com/x.jar","filename":"x.jar","primary":true,"size":9,"hashes":{"sha1":"abcd"}}]
        }"#;
        let url = format!("{API}/version_file/abcd?algorithm=sha1");
        let transport = Arc::new(MapTransport {
            responses: Mutex::new(BTreeMap::from([(url, body.to_vec())])),
        });
        let client = ModrinthClient::new(transport);
        let file = client
            .version_by_hash("abcd", HashAlgorithm::Sha1)
            .expect("lookup")
            .expect("some");
        assert_eq!(file.file_name, "x.jar");
        assert_eq!(file.sha1.as_deref(), Some("abcd"));
        assert!(file.download_url.contains("cdn.modrinth.com"));
    }

    #[test]
    fn project_versions_picks_primary_file() {
        let body = br#"[{
            "id":"ver1","project_id":"AA",
            "game_versions":["1.21.1"],"loaders":["fabric"],
            "files":[{"url":"https://cdn.modrinth.com/sodium.jar","filename":"sodium.jar","primary":true,"size":9,"hashes":{"sha1":"deadbeef"}}]
        }]"#;
        let facets = urlencoding_lite(r#"["1.21.1"]"#);
        let url = format!("{API}/project/AA/version?game_versions={facets}");
        let transport = Arc::new(MapTransport {
            responses: Mutex::new(BTreeMap::from([(url, body.to_vec())])),
        });
        let client = ModrinthClient::new(transport);
        let files = client.project_versions("AA", Some("1.21.1")).expect("versions");
        assert_eq!(files.len(), 1);
        assert_eq!(files[0].file_name, "sodium.jar");
        assert_eq!(files[0].file_id, "ver1");
        assert!(files[0].download_url.contains("cdn.modrinth.com"));
    }
}
