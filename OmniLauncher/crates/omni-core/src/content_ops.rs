//! Content browse + install helpers over `omni-platform` (LNC-9).

use std::path::PathBuf;
use std::sync::Arc;

use omni_platform::{
    CurseForgeClient, HashAlgorithm, ModrinthClient, Platform, ResourceQuery, SearchHit,
    UreqTransport, VersionFile,
};

use crate::error::CoreError;
use crate::import::murmur2_fingerprint;
use crate::install::{BlobSource, ContentCache, Paths, UreqBlobSource, sha1_hex};
use crate::store::{Ecosystem, NewMod, PortStatus};

/// Unified search across platforms.
///
/// # Errors
///
/// Currently only fails on unexpected internal errors; platform failures are skipped per side.
pub fn search_content(
    query: &str,
    game_version: Option<&str>,
    limit: u32,
    cf_api_key: Option<String>,
) -> Result<Vec<SearchHit>, CoreError> {
    let transport = Arc::new(UreqTransport::new());
    let mut hits = Vec::new();
    let mr = ModrinthClient::new(Arc::clone(&transport) as Arc<dyn omni_platform::HttpTransport>);
    if let Ok(list) = mr.search(query, game_version, limit) {
        hits.extend(list);
    }
    let cf = CurseForgeClient::new(transport, cf_api_key);
    if let Ok(list) = cf.search(query, game_version, limit) {
        hits.extend(list);
    }
    Ok(hits)
}

/// Identifies a local jar via Modrinth sha1 then CurseForge murmur2 bulk fingerprint.
///
/// # Errors
///
/// Platform transport failures (missing CF key is treated as no-match).
pub fn identify_local_jar(
    jar_bytes: &[u8],
    cf_api_key: Option<String>,
) -> Result<Option<VersionFile>, CoreError> {
    let transport = Arc::new(UreqTransport::new());
    let sha1 = sha1_hex(jar_bytes);
    let mr = ModrinthClient::new(Arc::clone(&transport) as Arc<dyn omni_platform::HttpTransport>);
    if let Ok(Some(file)) = mr.version_by_hash(&sha1, HashAlgorithm::Sha1) {
        return Ok(Some(file));
    }
    let fp = murmur2_fingerprint(jar_bytes);
    let cf = CurseForgeClient::new(transport, cf_api_key);
    match cf.version_by_hash(&fp.to_string(), HashAlgorithm::Murmur2) {
        Ok(v) => Ok(v),
        Err(omni_platform::PlatformError::MissingApiKey { .. }) => Ok(None),
        Err(err) => Err(CoreError::Io {
            action: "platform fingerprint lookup",
            path: PathBuf::from("curseforge"),
            source: std::io::Error::other(err.to_string()),
        }),
    }
}

/// Resolves a browse project hit to the newest installable file for optional game version.
///
/// Picks the first version with a non-empty official download URL (Modrinth CDN or CF CDN).
/// Distribution-blocked CF projects surface as [`CoreError::Io`] with the blocked message.
///
/// # Errors
///
/// Unknown platform, transport/parse failures, or blocked distribution without a usable file.
pub fn resolve_project_version(
    platform: &str,
    project_id: &str,
    game_version: Option<&str>,
    cf_api_key: Option<String>,
) -> Result<Option<VersionFile>, CoreError> {
    resolve_project_version_with(
        platform,
        project_id,
        game_version,
        cf_api_key,
        Arc::new(UreqTransport::new()) as Arc<dyn omni_platform::HttpTransport>,
    )
}

/// Same as [`resolve_project_version`] with an injectable transport (tests + callers that share a client).
///
/// # Errors
///
/// Unknown platform, transport/parse failures, or blocked distribution without a usable file.
pub fn resolve_project_version_with(
    platform: &str,
    project_id: &str,
    game_version: Option<&str>,
    cf_api_key: Option<String>,
    transport: Arc<dyn omni_platform::HttpTransport>,
) -> Result<Option<VersionFile>, CoreError> {
    let plat = platform_from_slug(platform).ok_or_else(|| CoreError::Io {
        action: "resolve project version",
        path: PathBuf::from(platform),
        source: std::io::Error::other(format!("unknown platform {platform:?}")),
    })?;
    match plat {
        Platform::Modrinth => {
            let client = ModrinthClient::new(transport);
            let files = client
                .project_versions(project_id, game_version)
                .map_err(|e| platform_err(&e))?;
            Ok(files.into_iter().find(|f| !f.download_url.is_empty()))
        }
        Platform::CurseForge => {
            let project_num: u64 = project_id.parse().map_err(|_| CoreError::Io {
                action: "parse curseforge project id",
                path: PathBuf::from(project_id),
                source: std::io::Error::other("curseforge project id must be numeric"),
            })?;
            let client = CurseForgeClient::new(transport, cf_api_key);
            let files = client
                .mod_files(project_num, game_version)
                .map_err(|e| platform_err(&e))?;
            if let Some(file) = files.into_iter().find(|f| !f.download_url.is_empty()) {
                return Ok(Some(file));
            }
            // No downloadable file: treat as blocked/unavailable for third-party install.
            Err(platform_err(&omni_platform::blocked_distribution(project_id)))
        }
    }
}

fn platform_err(err: &omni_platform::PlatformError) -> CoreError {
    CoreError::Io {
        action: "platform resolve",
        path: PathBuf::from("platform"),
        source: std::io::Error::other(err.to_string()),
    }
}

/// Downloads a version file into the instance mods folder and upserts a mod row.
///
/// # Errors
///
/// Empty URL, download/IO failures, or store errors.
pub fn install_content_file(
    paths: &Paths,
    db: &crate::db::Db,
    instance_id: i64,
    file: &VersionFile,
    source: &dyn BlobSource,
) -> Result<PathBuf, CoreError> {
    if file.download_url.is_empty() {
        return Err(CoreError::Io {
            action: "install content",
            path: PathBuf::from(&file.file_name),
            source: std::io::Error::other("empty download URL (distribution may be blocked)"),
        });
    }
    let dest_dir = paths.game_dir(instance_id).join("mods");
    std::fs::create_dir_all(&dest_dir).map_err(|source| CoreError::Io {
        action: "create mods dir",
        path: dest_dir.clone(),
        source,
    })?;
    let dest = dest_dir.join(&file.file_name);
    if let Some(sha1) = file.sha1.as_deref() {
        let cache = ContentCache::new(paths.objects_root());
        let object = cache.ensure(source, &file.download_url, sha1)?;
        std::fs::copy(&object, &dest).map_err(|source| CoreError::Io {
            action: "place content jar",
            path: dest.clone(),
            source,
        })?;
    } else {
        let bytes = source.fetch(&file.download_url).map_err(|err| CoreError::Io {
            action: "download content",
            path: PathBuf::from(&file.download_url),
            source: std::io::Error::other(err.message),
        })?;
        std::fs::write(&dest, &bytes).map_err(|source| CoreError::Io {
            action: "write content jar",
            path: dest.clone(),
            source,
        })?;
    }
    let ecosystem = if file.loaders.iter().any(|l| l.contains("fabric") || l.contains("quilt")) {
        Ecosystem::Fabric
    } else if file.loaders.iter().any(|l| l.contains("neoforge")) {
        Ecosystem::Neoforge
    } else if file.loaders.iter().any(|l| l.contains("forge")) {
        Ecosystem::Forge
    } else {
        Ecosystem::Unknown
    };
    db.with_store(|store| {
        store.upsert_mod(&NewMod {
            instance_id,
            file_name: file.file_name.clone(),
            sha1: file.sha1.clone(),
            sha512: file.sha512.clone(),
            murmur2: None,
            display_name: Some(file.file_name.clone()),
            version: None,
            ecosystem,
            enabled: true,
            platform: Some(file.platform.slug().into()),
            platform_project_id: Some(file.project_id.clone()),
            platform_file_id: Some(file.file_id.clone()),
            port_status: PortStatus::Compat,
        })
    })?;
    Ok(dest)
}

/// Default production blob source for content downloads.
#[must_use]
pub fn default_content_blob_source() -> UreqBlobSource {
    UreqBlobSource::new()
}

/// Which platform a slug refers to.
pub fn platform_from_slug(slug: &str) -> Option<Platform> {
    match slug {
        "modrinth" => Some(Platform::Modrinth),
        "curseforge" => Some(Platform::CurseForge),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use omni_platform::{HttpTransport, PlatformError, PlatformResult};
    use std::collections::BTreeMap;
    use std::sync::Mutex;

    struct MapTransport {
        get: Mutex<BTreeMap<String, Vec<u8>>>,
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
            Err(PlatformError::Transport {
                action: "POST",
                url: url.to_owned(),
                message: "unused".into(),
            })
        }
    }

    #[test]
    fn resolve_modrinth_project_returns_primary_file() {
        let body = br#"[{
            "id":"ver1","project_id":"AA",
            "game_versions":["1.21.1"],"loaders":["fabric"],
            "files":[{"url":"https://cdn.modrinth.com/sodium.jar","filename":"sodium.jar","primary":true,"size":9,"hashes":{"sha1":"deadbeef"}}]
        }]"#;
        // Match urlencoding used by ModrinthClient for game_versions JSON array.
        let url = "https://api.modrinth.com/v2/project/AA/version?game_versions=%5B%221.21.1%22%5D"
            .to_owned();
        let transport = Arc::new(MapTransport {
            get: Mutex::new(BTreeMap::from([(url, body.to_vec())])),
        });
        let file = resolve_project_version_with(
            "modrinth",
            "AA",
            Some("1.21.1"),
            None,
            transport as Arc<dyn HttpTransport>,
        )
        .expect("resolve")
        .expect("some file");
        assert_eq!(file.file_name, "sodium.jar");
        assert_eq!(file.platform, Platform::Modrinth);
        assert!(file.download_url.contains("cdn.modrinth.com"));
    }

    #[test]
    fn resolve_curseforge_project_returns_first_downloadable() {
        let url =
            "https://api.curseforge.com/v1/mods/238222/files?pageSize=50&gameVersion=1.21.1".to_owned();
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
        });
        let file = resolve_project_version_with(
            "curseforge",
            "238222",
            Some("1.21.1"),
            Some("test-key".into()),
            transport as Arc<dyn HttpTransport>,
        )
        .expect("resolve")
        .expect("some file");
        assert_eq!(file.file_name, "jei.jar");
        assert_eq!(file.platform, Platform::CurseForge);
    }
}
