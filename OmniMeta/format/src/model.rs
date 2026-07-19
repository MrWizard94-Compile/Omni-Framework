//! The format-v1 data model.
//!
//! Field order in these structs **is** the canonical serialization order — do not
//! reorder fields without treating it as a format change (the golden-tree tests in
//! `omni-meta-gen` will catch accidental drift).

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use crate::FormatError;

/// `index.json` at the tree root: the entry point every client fetches first.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IndexRoot {
    /// Format generation; readers reject values other than [`crate::FORMAT_VERSION`].
    pub format_version: u32,
    /// One entry per component uid, sorted by uid (canonical order).
    pub components: Vec<ComponentRef>,
}

impl IndexRoot {
    /// Parses and validates a root index document.
    ///
    /// # Errors
    ///
    /// [`FormatError::Json`] on malformed JSON;
    /// [`FormatError::UnsupportedFormatVersion`] when the document's
    /// `formatVersion` is not the one this crate implements.
    pub fn parse(bytes: &[u8]) -> Result<Self, FormatError> {
        let root: Self = serde_json::from_slice(bytes)
            .map_err(|source| FormatError::Json { what: "root index", source })?;
        if root.format_version != crate::FORMAT_VERSION {
            return Err(FormatError::UnsupportedFormatVersion { found: root.format_version });
        }
        Ok(root)
    }
}

/// Root-index entry pointing at one component's version index.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ComponentRef {
    /// Component uid, e.g. `net.minecraft` or `org.omniframework.omniloader`.
    pub uid: String,
    /// Location of the component index, relative to the `/v1/` root.
    pub versions_url: String,
    /// Lowercase-hex sha256 of the component index file's exact bytes.
    pub sha256: String,
}

/// `<uid>/index.json`: every published version of one component.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ComponentIndex {
    /// The component this index describes (must match the referencing root entry).
    pub uid: String,
    /// Version summaries, newest `releaseTime` first (ties broken by version
    /// string, ascending) — canonical order.
    pub versions: Vec<VersionSummary>,
}

impl ComponentIndex {
    /// Parses a component index document.
    ///
    /// # Errors
    ///
    /// [`FormatError::Json`] on malformed JSON.
    pub fn parse(bytes: &[u8]) -> Result<Self, FormatError> {
        serde_json::from_slice(bytes)
            .map_err(|source| FormatError::Json { what: "component index", source })
    }
}

/// One row of a [`ComponentIndex`].
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VersionSummary {
    /// The version string exactly as published (may not be filesystem-safe;
    /// always locate the payload through [`VersionSummary::url`]).
    pub version: String,
    /// Upstream release timestamp (RFC 3339, as published by the source).
    pub release_time: String,
    /// Release channel: `release`, `snapshot`, `old_beta`, `old_alpha`, …
    #[serde(rename = "type")]
    pub release_type: String,
    /// True when the recommended-version policy marks this version (SVC-1).
    #[serde(default, skip_serializing_if = "is_false")]
    pub recommended: bool,
    /// Location of the payload file, relative to the `/v1/` root.
    pub url: String,
    /// Lowercase-hex sha256 of the payload file's exact bytes.
    pub sha256: String,
}

/// `<uid>/<version>.json`: everything a launcher/loader needs about one
/// component version. Superset of the OneSix-style component lessons from the
/// corpus (multimc/prism/modrinth-app dossiers).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ComponentPayload {
    /// Owning component uid (must match the directory it is served from).
    pub uid: String,
    /// Version string (must match its [`VersionSummary`] row).
    pub version: String,
    /// Upstream release timestamp; duplicated from the index row so payloads
    /// are self-describing when cached standalone.
    pub release_time: String,
    /// Release channel; duplicated from the index row (same reason).
    #[serde(rename = "type")]
    pub release_type: String,
    /// Other components this version needs, e.g. `net.minecraft` 1.21.1
    /// requiring `org.lwjgl3` 3.3.3. See the crate docs for the v1 range dialect.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub requires: Vec<Requirement>,
    /// JVM entry point, when this component provides one.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub main_class: Option<String>,
    /// Modern (1.13+) structured launch arguments, normalized from client.json.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub arguments: Option<Arguments>,
    /// Pre-1.13 `minecraftArguments` template, passed through verbatim.
    /// Exactly one of `arguments` / `legacy_arguments` is set for game components.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub legacy_arguments: Option<String>,
    /// Maven-coordinate libraries with download location, integrity data, and
    /// applicability rules.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub libraries: Vec<Library>,
    /// Asset index reference (game components only).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub asset_index: Option<AssetIndex>,
    /// Required Java runtime (game components only; absent for pre-runtime-manifest
    /// versions).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub java_version: Option<JavaVersion>,
    /// Component-specific data. Established keys:
    /// - `net.minecraft`: `downloads` (client/server jars + official mappings),
    ///   `logging` (Mojang log4j client config, passed through).
    /// - `net.minecraft.java`: `platforms` (per-platform runtime manifest refs).
    /// - `org.omniframework.mappings-*`: `clientMappings` / `serverMappings`.
    #[serde(default, skip_serializing_if = "serde_json::Map::is_empty")]
    pub data: serde_json::Map<String, serde_json::Value>,
}

impl ComponentPayload {
    /// Parses a component payload document.
    ///
    /// # Errors
    ///
    /// [`FormatError::Json`] on malformed JSON.
    pub fn parse(bytes: &[u8]) -> Result<Self, FormatError> {
        serde_json::from_slice(bytes)
            .map_err(|source| FormatError::Json { what: "component payload", source })
    }
}

/// A dependency edge between components.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Requirement {
    /// Required component uid.
    pub uid: String,
    /// v1 dialect: absent = any version; present = exact version string.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub range: Option<String>,
}

/// Structured launch arguments (modern client.json shape, normalized).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Arguments {
    /// Program arguments handed to the game's `main`.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub game: Vec<Argument>,
    /// JVM arguments (classpath, natives path, platform quirks).
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub jvm: Vec<Argument>,
}

/// One argument entry: either unconditional, or gated by rules.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(untagged)]
pub enum Argument {
    /// Always applied. May contain `${placeholder}` substitution variables.
    Plain(String),
    /// Applied only when the rule chain resolves to allow.
    Conditional {
        /// Rules evaluated by the consumer against its platform/feature set.
        rules: Vec<Rule>,
        /// The argument value(s) to apply when allowed.
        value: ArgumentValue,
    },
}

/// Value side of a conditional argument.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(untagged)]
pub enum ArgumentValue {
    /// A single argument token.
    One(String),
    /// Several tokens applied in order.
    Many(Vec<String>),
}

/// An allow/disallow rule, matching the client.json rule semantics: with no
/// rules everything applies; with rules, the default is disallow and the last
/// matching rule wins.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Rule {
    /// Whether a match allows or disallows the carrying item.
    pub action: RuleAction,
    /// OS constraint; absent matches every OS.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub os: Option<OsSpec>,
    /// Feature-flag constraints (e.g. `is_demo_user`); absent matches always.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub features: Option<BTreeMap<String, bool>>,
}

/// Rule verdict when the rule matches.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum RuleAction {
    /// The item applies when this rule matches.
    Allow,
    /// The item is excluded when this rule matches.
    Disallow,
}

/// OS/arch matcher used by [`Rule`].
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OsSpec {
    /// `windows` / `linux` / `osx` (client.json vocabulary, kept verbatim).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    /// OS version regex, as published upstream.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub version: Option<String>,
    /// CPU architecture, e.g. `x86`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub arch: Option<String>,
}

/// One downloadable library.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Library {
    /// Maven coordinate `group:artifact:version[:classifier]`.
    pub coord: String,
    /// Absolute download URL (official CDNs only, COMPLIANCE §1).
    pub url: String,
    /// Lowercase-hex sha1 as published upstream (Mojang's integrity dialect).
    pub sha1: String,
    /// File size in bytes.
    pub size: u64,
    /// Applicability rules; empty = applies everywhere.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub rules: Vec<Rule>,
}

/// Reference to a Mojang asset index.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AssetIndex {
    /// Asset index id (e.g. `17`).
    pub id: String,
    /// Absolute URL of the asset index JSON.
    pub url: String,
    /// Lowercase-hex sha1 of the index file.
    pub sha1: String,
    /// Size of the index file in bytes.
    pub size: u64,
    /// Total size of all objects the index references, in bytes.
    pub total_size: u64,
}

/// Java runtime requirement, as published in client.json.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct JavaVersion {
    /// Mojang runtime component name, e.g. `java-runtime-delta`.
    pub component: String,
    /// Required major version, e.g. `21`.
    pub major_version: u32,
}

/// `skip_serializing_if` helper: canonical output omits `recommended: false`.
#[expect(clippy::trivially_copy_pass_by_ref, reason = "serde skip_serializing_if signature")]
fn is_false(value: &bool) -> bool {
    !*value
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn argument_parses_both_shapes() {
        let json = r#"["--demo", {"rules": [{"action": "allow",
            "features": {"is_demo_user": true}}], "value": "--demo"},
            {"rules": [{"action": "allow", "os": {"name": "windows"}}],
             "value": ["-Da=b", "-Dc=d"]}]"#;
        let args: Vec<Argument> = serde_json::from_str(json).expect("parses");
        assert_eq!(args.len(), 3);
        assert!(matches!(args[0], Argument::Plain(_)));
        match &args[1] {
            Argument::Conditional { rules, value } => {
                assert_eq!(rules[0].action, RuleAction::Allow);
                assert_eq!(value, &ArgumentValue::One("--demo".into()));
            }
            Argument::Plain(_) => panic!("expected conditional"),
        }
        match &args[2] {
            Argument::Conditional { value: ArgumentValue::Many(v), .. } => assert_eq!(v.len(), 2),
            _ => panic!("expected conditional many"),
        }
    }

    #[test]
    fn root_rejects_wrong_format_version() {
        let bytes = br#"{"formatVersion": 2, "components": []}"#;
        let err = IndexRoot::parse(bytes).expect_err("must reject v2");
        assert!(matches!(err, FormatError::UnsupportedFormatVersion { found: 2 }));
    }

    #[test]
    fn payload_roundtrip_preserves_value() {
        let payload = ComponentPayload {
            uid: "net.minecraft".into(),
            version: "1.21.1".into(),
            release_time: "2024-08-08T12:24:45+00:00".into(),
            release_type: "release".into(),
            requires: vec![Requirement { uid: "org.lwjgl3".into(), range: Some("3.3.3".into()) }],
            main_class: Some("net.minecraft.client.main.Main".into()),
            arguments: Some(Arguments {
                game: vec![Argument::Plain("--username".into())],
                jvm: vec![],
            }),
            legacy_arguments: None,
            libraries: vec![Library {
                coord: "com.mojang:brigadier:1.2.9".into(),
                url: "https://libraries.minecraft.net/x.jar".into(),
                sha1: "0".repeat(40),
                size: 77392,
                rules: vec![],
            }],
            asset_index: None,
            java_version: Some(JavaVersion { component: "java-runtime-delta".into(), major_version: 21 }),
            data: serde_json::Map::new(),
        };
        let bytes = crate::to_canonical_json(&payload).expect("serializes");
        let back = ComponentPayload::parse(&bytes).expect("parses");
        assert_eq!(back, payload);
    }

    #[test]
    fn lenient_read_ignores_unknown_keys() {
        let bytes = br#"{"uid": "x", "version": "1", "releaseTime": "t",
            "type": "release", "futureKey": {"nested": true}}"#;
        let payload = ComponentPayload::parse(bytes).expect("unknown keys tolerated");
        assert_eq!(payload.uid, "x");
    }
}
