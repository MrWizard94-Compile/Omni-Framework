//! Lenient typed views of the Mojang upstream formats.
//!
//! These are *foreign* formats (official-launcher dossier: the authoritative
//! upstream contract), so parsing is deliberately tolerant: unknown keys are
//! ignored, and fields we do not consume are not modeled. Consumed subset only —
//! the normalizer (`normalize.rs`) is the single place that maps these into the
//! Omni dialect.

use std::collections::BTreeMap;

use serde::Deserialize;

/// `version_manifest_v2.json` from `piston-meta.mojang.com`.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VersionManifest {
    /// Mojang's own latest-version pointers.
    pub latest: LatestPointers,
    /// Every published version, newest first (upstream order; not relied upon).
    pub versions: Vec<ManifestVersion>,
}

/// The `latest` block of the version manifest.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LatestPointers {
    /// Latest release id — the default recommended-version policy.
    pub release: String,
    /// Latest snapshot id.
    pub snapshot: String,
}

/// One row of the version manifest.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ManifestVersion {
    /// Version id (may contain URL-hostile characters; see format crate docs).
    pub id: String,
    /// `release` / `snapshot` / `old_beta` / `old_alpha`.
    #[serde(rename = "type")]
    pub release_type: String,
    /// Absolute URL of the per-version client.json.
    pub url: String,
    /// Release timestamp (RFC 3339).
    pub release_time: String,
    /// sha1 of the client.json document — verified at snapshot-fetch time.
    pub sha1: String,
}

/// A per-version client.json (piston-meta "version package").
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PistonVersion {
    /// Version id; must match the manifest row that referenced this document.
    pub id: String,
    /// Release channel.
    #[serde(rename = "type")]
    pub release_type: String,
    /// Release timestamp.
    pub release_time: String,
    /// JVM entry point. Absent only in some pre-classic entries.
    #[serde(default)]
    pub main_class: Option<String>,
    /// Pre-1.13 argument template (mutually exclusive with `arguments`).
    #[serde(default)]
    pub minecraft_arguments: Option<String>,
    /// 1.13+ structured arguments (mutually exclusive with `minecraft_arguments`).
    #[serde(default)]
    pub arguments: Option<PistonArguments>,
    /// Asset index reference.
    #[serde(default)]
    pub asset_index: Option<PistonAssetIndex>,
    /// Named downloads: `client`, `server`, `client_mappings`, `server_mappings`, …
    #[serde(default)]
    pub downloads: BTreeMap<String, PistonDownload>,
    /// Required Mojang Java runtime (absent before runtime manifests existed).
    #[serde(default)]
    pub java_version: Option<PistonJavaVersion>,
    /// Library list, including natives in both historical encodings.
    #[serde(default)]
    pub libraries: Vec<PistonLibrary>,
    /// Log4j client logging configuration; passed through into payload `data`.
    #[serde(default)]
    pub logging: Option<serde_json::Value>,
}

/// `arguments` block of a modern client.json.
#[derive(Debug, Clone, Deserialize)]
pub struct PistonArguments {
    /// Game (program) arguments.
    #[serde(default)]
    pub game: Vec<PistonArgument>,
    /// JVM arguments.
    #[serde(default)]
    pub jvm: Vec<PistonArgument>,
}

/// One upstream argument: plain token or rule-gated value.
#[derive(Debug, Clone, Deserialize)]
#[serde(untagged)]
pub enum PistonArgument {
    /// Unconditional token.
    Plain(String),
    /// Rule-gated token(s).
    Conditional {
        /// Upstream rules.
        rules: Vec<PistonRule>,
        /// One token or several.
        value: PistonArgumentValue,
    },
}

/// Value side of a conditional upstream argument.
#[derive(Debug, Clone, Deserialize)]
#[serde(untagged)]
pub enum PistonArgumentValue {
    /// Single token.
    One(String),
    /// Multiple tokens.
    Many(Vec<String>),
}

/// Upstream allow/disallow rule.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PistonRule {
    /// `allow` or `disallow` (kept as text; normalizer maps it).
    pub action: String,
    /// OS matcher.
    #[serde(default)]
    pub os: Option<PistonOs>,
    /// Feature-flag matcher.
    #[serde(default)]
    pub features: Option<BTreeMap<String, bool>>,
}

/// Upstream OS matcher.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PistonOs {
    /// OS name (`windows` / `linux` / `osx`).
    #[serde(default)]
    pub name: Option<String>,
    /// OS version regex.
    #[serde(default)]
    pub version: Option<String>,
    /// CPU architecture.
    #[serde(default)]
    pub arch: Option<String>,
}

/// A named download (client jar, server jar, mappings, runtime manifest, …).
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PistonDownload {
    /// Absolute URL on Mojang's CDN.
    pub url: String,
    /// Lowercase-hex sha1.
    pub sha1: String,
    /// Size in bytes.
    pub size: u64,
}

/// Upstream asset-index reference.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PistonAssetIndex {
    /// Asset index id.
    pub id: String,
    /// Absolute URL.
    pub url: String,
    /// Lowercase-hex sha1.
    pub sha1: String,
    /// Index file size.
    pub size: u64,
    /// Total size of referenced objects.
    pub total_size: u64,
}

/// Upstream Java runtime requirement.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PistonJavaVersion {
    /// Runtime component name (e.g. `java-runtime-delta`).
    pub component: String,
    /// Required major version.
    pub major_version: u32,
}

/// One library row of a client.json.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PistonLibrary {
    /// Maven coordinate `group:artifact:version[:classifier]`.
    pub name: String,
    /// Download locations (modern encoding).
    #[serde(default)]
    pub downloads: Option<PistonLibraryDownloads>,
    /// Legacy natives encoding: OS key → classifier template (may contain
    /// `${arch}`).
    #[serde(default)]
    pub natives: Option<BTreeMap<String, String>>,
    /// Applicability rules.
    #[serde(default)]
    pub rules: Vec<PistonRule>,
}

/// `downloads` block of a library row.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PistonLibraryDownloads {
    /// The main artifact, when the library has one.
    #[serde(default)]
    pub artifact: Option<PistonDownload>,
    /// Legacy classifier artifacts (natives), keyed by classifier name.
    #[serde(default)]
    pub classifiers: Option<BTreeMap<String, PistonDownload>>,
}

/// java-runtime `all.json`: platform → runtime component name → entries.
pub type JavaRuntimeAll = BTreeMap<String, BTreeMap<String, Vec<JavaRuntimeEntry>>>;

/// One runtime build offered for a (platform, component) pair.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct JavaRuntimeEntry {
    /// Per-platform runtime file manifest reference.
    pub manifest: PistonDownload,
    /// Runtime version identity.
    pub version: JavaRuntimeVersion,
}

/// Version identity of a runtime build.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct JavaRuntimeVersion {
    /// Runtime version name (e.g. `21.0.3`, `8u402`).
    pub name: String,
    /// Release timestamp.
    pub released: String,
}
