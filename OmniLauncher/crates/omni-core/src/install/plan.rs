//! From meta payloads to a concrete, platform-filtered download plan.
//!
//! The planner consumes the Omni dialect (`omni-meta-format` payloads
//! resolved through `omni-meta-client`) plus two Mojang documents fetched at
//! install time (asset index, java-runtime file manifest — both
//! hash-addressed, both cached content-addressed like every other blob).

use std::collections::BTreeMap;

use serde::Deserialize;

use omni_meta_format::ComponentPayload;

use crate::error::CoreError;
use crate::platform::{Platform, rules_allow};

/// One file the install must ensure, with where it belongs.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PlannedFile {
    /// Download URL (official CDNs only).
    pub url: String,
    /// Expected sha1.
    pub sha1: String,
    /// Expected size (progress math; not an integrity input).
    pub size: u64,
    /// What the file is.
    pub kind: FileKind,
}

/// Role of a planned file.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FileKind {
    /// A classpath library (maven coordinate kept for diagnostics). For the
    /// modern target (TGT-1: 1.21.1) natives-classified jars are classpath
    /// entries exactly like the official launcher treats them; the legacy
    /// extract-era path is deliberately out of MVP scope (documented in
    /// `launch`).
    Library {
        /// Maven coordinate.
        coord: String,
    },
    /// The game jar (client or server per instance kind).
    GameJar,
    /// An asset object, stored under `assets/objects/<2>/<hash>`.
    AssetObject,
    /// A java-runtime file, extracted to a path under the runtime root.
    JavaFile {
        /// Path relative to the runtime installation root.
        rel_path: String,
        /// Whether the manifest marks it executable (no-op on Windows).
        executable: bool,
    },
}

/// The asset index document (Mojang dialect, lenient).
#[derive(Debug, Deserialize)]
pub struct AssetIndexDoc {
    /// name → object entry.
    pub objects: BTreeMap<String, AssetObjectEntry>,
}

/// One asset object entry.
#[derive(Debug, Deserialize)]
pub struct AssetObjectEntry {
    /// Lowercase-hex sha1 (doubles as the storage path).
    pub hash: String,
    /// Size in bytes.
    pub size: u64,
}

/// The java-runtime per-platform file manifest (Mojang dialect, lenient).
#[derive(Debug, Deserialize)]
pub struct JavaManifestDoc {
    /// path → entry.
    pub files: BTreeMap<String, JavaManifestEntry>,
}

/// One java-runtime manifest entry.
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct JavaManifestEntry {
    /// `file` / `directory` / `link`.
    #[serde(rename = "type")]
    pub entry_type: String,
    /// Present for files.
    #[serde(default)]
    pub downloads: Option<JavaManifestDownloads>,
    /// Executable bit (unix; recorded, no-op on Windows).
    #[serde(default)]
    pub executable: bool,
}

/// Download variants of a java file (we always use `raw`).
#[derive(Debug, Deserialize)]
pub struct JavaManifestDownloads {
    /// The uncompressed variant.
    pub raw: Option<JavaRawDownload>,
}

/// The raw download triple.
#[derive(Debug, Deserialize)]
pub struct JavaRawDownload {
    /// URL.
    pub url: String,
    /// sha1.
    pub sha1: String,
    /// Size.
    pub size: u64,
}

fn download_triple(
    value: &serde_json::Value,
    what: &'static str,
) -> Result<(String, String, u64), CoreError> {
    let get = |key: &str| value[key].as_str().map(str::to_owned);
    match (get("url"), get("sha1"), value["size"].as_u64()) {
        (Some(url), Some(sha1), Some(size)) => Ok((url, sha1, size)),
        _ => Err(CoreError::Json {
            context: "payload downloads",
            source: serde::de::Error::custom(format!("{what}: missing url/sha1/size")),
        }),
    }
}

/// Plans the library + game-jar downloads for a `net.minecraft` payload
/// (and any additional payloads in the stack, e.g. the loader's libraries),
/// filtered for `platform`.
///
/// # Errors
///
/// [`CoreError::Json`] when a payload's `data.downloads` lacks the required
/// entry for the instance kind.
pub fn plan_game_files(
    payloads: &[&ComponentPayload],
    platform: &Platform,
    server: bool,
) -> Result<Vec<PlannedFile>, CoreError> {
    let features = BTreeMap::new();
    let mut files = Vec::new();
    for payload in payloads {
        if !server {
            for library in &payload.libraries {
                if rules_allow(&library.rules, platform, &features) {
                    files.push(PlannedFile {
                        url: library.url.clone(),
                        sha1: library.sha1.clone(),
                        size: library.size,
                        kind: FileKind::Library { coord: library.coord.clone() },
                    });
                }
            }
        }
        if payload.uid == "net.minecraft" {
            let downloads = payload.data.get("downloads").cloned().unwrap_or_default();
            let key = if server { "server" } else { "client" };
            let (url, sha1, size) = download_triple(&downloads[key], "game jar")?;
            files.push(PlannedFile { url, sha1, size, kind: FileKind::GameJar });
        }
    }
    Ok(files)
}

/// Plans the asset-object downloads from a parsed asset index.
pub fn plan_assets(index: &AssetIndexDoc) -> Vec<PlannedFile> {
    index
        .objects
        .values()
        .map(|object| PlannedFile {
            url: format!(
                "https://resources.download.minecraft.net/{}/{}",
                object.hash.get(0..2).unwrap_or("xx"),
                object.hash
            ),
            sha1: object.hash.clone(),
            size: object.size,
            kind: FileKind::AssetObject,
        })
        .collect()
}

/// Plans the java-runtime file downloads from a parsed platform manifest.
/// Directories are created by the executor; `link` entries are skipped on
/// Windows (they do not occur in Windows manifests upstream).
pub fn plan_java_files(manifest: &JavaManifestDoc) -> Vec<PlannedFile> {
    manifest
        .files
        .iter()
        .filter(|(_, entry)| entry.entry_type == "file")
        .filter_map(|(rel_path, entry)| {
            let raw = entry.downloads.as_ref()?.raw.as_ref()?;
            Some(PlannedFile {
                url: raw.url.clone(),
                sha1: raw.sha1.clone(),
                size: raw.size,
                kind: FileKind::JavaFile {
                    rel_path: rel_path.clone(),
                    executable: entry.executable,
                },
            })
        })
        .collect()
}

/// Selects the java-runtime component version and platform manifest reference
/// from the `net.minecraft.java` payload set, given the required component
/// name (from the game payload's `javaVersion`) and the running platform.
///
/// Returns `(runtime_version, manifest_url, manifest_sha1)`.
///
/// # Errors
///
/// [`CoreError::NotFound`]-free: a missing offer surfaces as
/// [`CoreError::Json`] naming the component and platform.
pub fn select_java_offer(
    java_payloads: &[&ComponentPayload],
    required_component: &str,
    platform_key: &str,
) -> Result<(String, String, String), CoreError> {
    // Newest runtime version first (payload list order follows the component
    // index, which is releaseTime-descending).
    for payload in java_payloads {
        if let Some(offer) = payload
            .data
            .get("platforms")
            .and_then(|platforms| platforms.get(platform_key))
            .and_then(|offers| offers.get(required_component))
        {
            let (url, sha1, _) = download_triple(&offer["manifest"], "java manifest")?;
            return Ok((payload.version.clone(), url, sha1));
        }
    }
    Err(CoreError::Json {
        context: "java runtime selection",
        source: serde::de::Error::custom(format!(
            "no {required_component:?} runtime offered for platform {platform_key:?}"
        )),
    })
}

/// Maps the running platform to Mojang's java-runtime platform key.
pub fn java_platform_key(platform: &Platform) -> &'static str {
    match (platform.os_name, platform.arch) {
        ("windows", "x86") => "windows-x86",
        ("windows", "arm64") => "windows-arm64",
        ("windows", _) => "windows-x64",
        ("osx", "arm64") => "mac-os-arm64",
        ("osx", _) => "mac-os",
        (_, "x86") => "linux-i386",
        _ => "linux",
    }
}
