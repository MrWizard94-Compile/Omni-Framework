//! Snapshot → tree normalization: the single place where Mojang's dialects are
//! mapped into the Omni format (OmniMeta/README.md "Upstreams normalized").
//!
//! Pure and deterministic: input maps are sorted (`BTreeMap`), every ordering
//! decision below is explicit, and nothing reads clocks, RNGs, or the
//! environment.
//!
//! Components produced:
//!
//! | uid | source | versions |
//! |---|---|---|
//! | `net.minecraft` | client.json per game version | game versions |
//! | `org.lwjgl3` / `org.lwjgl2` | LWJGL libraries split out of client.json | LWJGL versions |
//! | `org.omniframework.mappings-<gv>` | official-mapping downloads in client.json | the game version |
//! | `net.minecraft.java` | java-runtime `all.json` | runtime versions |
//! | anything | local definition files (Omni components: loader, compat layers) | as defined |

use std::collections::{BTreeMap, BTreeSet};
use std::path::Path;

use serde::Deserialize;
use serde_json::{Map, Value};

use omni_meta_format::{
    Argument, ArgumentValue, Arguments, AssetIndex, ComponentPayload, JavaVersion, Library,
    OsSpec, Requirement, Rule, RuleAction, sanitize_file_stem, validate_uid,
};

use crate::error::GenError;
use crate::mojang::{
    PistonArgument, PistonArgumentValue, PistonDownload, PistonLibrary, PistonRule, PistonVersion,
};
use crate::snapshot::Snapshot;

/// Recommended-version policy: uid → versions to flag (SVC-1 policy data).
#[derive(Debug, Clone, Default)]
pub struct Policy {
    /// Versions to mark `recommended: true`, per component uid.
    pub recommended: BTreeMap<String, BTreeSet<String>>,
}

/// On-disk policy file shape (strict: this is an Omni-owned format).
#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct PolicyFile {
    #[serde(default)]
    recommended: BTreeMap<String, Vec<String>>,
}

impl Policy {
    /// Parses a policy JSON document: `{"recommended": {"<uid>": ["<version>", …]}}`.
    ///
    /// # Errors
    ///
    /// [`GenError::Json`] for malformed or unknown-key documents.
    pub fn parse(bytes: &[u8], origin: &str) -> Result<Self, GenError> {
        let file: PolicyFile = serde_json::from_slice(bytes)
            .map_err(|source| GenError::Json { origin: origin.to_owned(), source })?;
        let recommended = file
            .recommended
            .into_iter()
            .map(|(uid, versions)| (uid, versions.into_iter().collect()))
            .collect();
        Ok(Self { recommended })
    }
}

/// Inputs for [`normalize`] beyond the snapshot itself.
#[derive(Debug, Clone, Default)]
pub struct Options {
    /// Restrict game versions to this set (`None` = every version in the
    /// snapshot). Ids must exist in the snapshot.
    pub versions: Option<BTreeSet<String>>,
    /// Recommended-version policy. When it has no `net.minecraft` entry, the
    /// manifest's `latest.release` is recommended by default (if selected).
    pub policy: Policy,
    /// Omni component definitions to merge in (loader/compat releases).
    pub definitions: Vec<ComponentPayload>,
}

/// The normalized tree, ready for [`crate::emit::emit`].
#[derive(Debug, Clone)]
pub struct Tree {
    /// uid → version → payload.
    pub components: BTreeMap<String, BTreeMap<String, ComponentPayload>>,
    /// uid → versions to flag as recommended (already validated against the tree).
    pub recommended: BTreeMap<String, BTreeSet<String>>,
    /// Non-fatal findings (skipped rows, unrecognized shapes) for the operator.
    pub warnings: Vec<String>,
}

fn convert_rules(rules: &[PistonRule], origin: &str) -> Result<Vec<Rule>, GenError> {
    rules
        .iter()
        .map(|rule| {
            let action = match rule.action.as_str() {
                "allow" => RuleAction::Allow,
                "disallow" => RuleAction::Disallow,
                other => {
                    return Err(GenError::Integrity {
                        detail: format!("{origin}: unknown rule action {other:?}"),
                    });
                }
            };
            Ok(Rule {
                action,
                os: rule.os.as_ref().map(|os| OsSpec {
                    name: os.name.clone(),
                    version: os.version.clone(),
                    arch: os.arch.clone(),
                }),
                features: rule.features.clone(),
            })
        })
        .collect()
}

fn convert_arguments(
    args: &[PistonArgument],
    origin: &str,
) -> Result<Vec<Argument>, GenError> {
    args.iter()
        .map(|arg| match arg {
            PistonArgument::Plain(token) => Ok(Argument::Plain(token.clone())),
            PistonArgument::Conditional { rules, value } => Ok(Argument::Conditional {
                rules: convert_rules(rules, origin)?,
                value: match value {
                    PistonArgumentValue::One(token) => ArgumentValue::One(token.clone()),
                    PistonArgumentValue::Many(tokens) => ArgumentValue::Many(tokens.clone()),
                },
            }),
        })
        .collect()
}

fn download_value(download: &PistonDownload) -> Value {
    let mut object = Map::new();
    object.insert("sha1".to_owned(), Value::String(download.sha1.clone()));
    object.insert("size".to_owned(), Value::Number(download.size.into()));
    object.insert("url".to_owned(), Value::String(download.url.clone()));
    Value::Object(object)
}

/// Maven coordinate helpers over `group:artifact:version[:classifier]`.
fn coord_parts(coord: &str) -> (&str, &str, &str) {
    let mut parts = coord.splitn(4, ':');
    let group = parts.next().unwrap_or("");
    let artifact = parts.next().unwrap_or("");
    let version = parts.next().unwrap_or("");
    (group, artifact, version)
}

/// Which LWJGL component a library group belongs to, if any.
/// `org.lwjgl` (LWJGL 3) → `org.lwjgl3`; `org.lwjgl.*` (LWJGL 2 era groups,
/// e.g. `org.lwjgl.lwjgl`) → `org.lwjgl2`. Anything else stays inline.
fn lwjgl_component(group: &str) -> Option<&'static str> {
    if group == "org.lwjgl" {
        Some("org.lwjgl3")
    } else if group.starts_with("org.lwjgl.") {
        Some("org.lwjgl2")
    } else {
        None
    }
}

/// Expands one upstream library row into format libraries, handling both
/// natives encodings:
/// - modern: separate rows per natives artifact (passed through with rules);
/// - legacy: `natives` map + `classifiers` — each (OS, classifier) pair becomes
///   its own library whose rules are the upstream rules **plus** an allow rule
///   pinning the OS (and the arch, `32`→`x86` / `64`→`x86_64`, when the
///   classifier template used `${arch}`).
fn expand_library(
    library: &PistonLibrary,
    origin: &str,
    warnings: &mut Vec<String>,
) -> Result<Vec<Library>, GenError> {
    let mut out = Vec::new();
    let rules = convert_rules(&library.rules, origin)?;
    let Some(downloads) = &library.downloads else {
        warnings.push(format!(
            "{origin}: library {:?} has no downloads block; skipped (pre-2017 encoding)",
            library.name
        ));
        return Ok(out);
    };
    if let Some(artifact) = &downloads.artifact {
        out.push(Library {
            coord: library.name.clone(),
            url: artifact.url.clone(),
            sha1: artifact.sha1.clone(),
            size: artifact.size,
            rules: rules.clone(),
        });
    }
    if let (Some(classifiers), Some(natives)) = (&downloads.classifiers, &library.natives) {
        for (os_key, template) in natives {
            let variants: Vec<(Option<&'static str>, String)> = if template.contains("${arch}") {
                vec![
                    (Some("x86"), template.replace("${arch}", "32")),
                    (Some("x86_64"), template.replace("${arch}", "64")),
                ]
            } else {
                vec![(None, template.clone())]
            };
            for (arch, classifier) in variants {
                let Some(artifact) = classifiers.get(&classifier) else {
                    // Absent classifiers for an OS are normal upstream
                    // (not every library ships every platform); not a warning.
                    continue;
                };
                let mut native_rules = rules.clone();
                native_rules.push(Rule {
                    action: RuleAction::Allow,
                    os: Some(OsSpec {
                        name: Some(os_key.clone()),
                        version: None,
                        arch: arch.map(str::to_owned),
                    }),
                    features: None,
                });
                out.push(Library {
                    coord: format!("{}:{classifier}", library.name),
                    url: artifact.url.clone(),
                    sha1: artifact.sha1.clone(),
                    size: artifact.size,
                    rules: native_rules,
                });
            }
        }
    }
    if downloads.artifact.is_none() && downloads.classifiers.is_none() {
        warnings.push(format!(
            "{origin}: library {:?} has an empty downloads block; skipped",
            library.name
        ));
    }
    Ok(out)
}

/// An LWJGL library contribution: which game version supplied it, so merge
/// conflicts resolve toward the newest upstream data deterministically.
struct LwjglContribution {
    source_release_time: String,
    library: Library,
}

/// Pool of LWJGL libraries split out of game versions:
/// component uid → LWJGL version → library coord → contribution.
type LwjglPool = BTreeMap<&'static str, BTreeMap<String, BTreeMap<String, LwjglContribution>>>;

/// Partitions a game version's libraries: LWJGL families are split into their
/// own components (README "LWJGL variants"; the (atlauncher) ARM substitution
/// lesson), everything else stays inline. Returns the inline libraries and the
/// requirement edges toward the split components.
fn split_lwjgl(
    piston: &PistonVersion,
    origin: &str,
    lwjgl_pool: &mut LwjglPool,
    warnings: &mut Vec<String>,
) -> Result<(Vec<Library>, Vec<Requirement>), GenError> {
    let mut inline_libraries: Vec<Library> = Vec::new();
    let mut lwjgl_local: BTreeMap<&'static str, Vec<Library>> = BTreeMap::new();
    for library in &piston.libraries {
        let expanded = expand_library(library, origin, warnings)?;
        let (group, _, _) = coord_parts(&library.name);
        match lwjgl_component(group) {
            Some(component) => lwjgl_local.entry(component).or_default().extend(expanded),
            None => inline_libraries.extend(expanded),
        }
    }

    let mut requires: Vec<Requirement> = Vec::new();
    for (component, libraries) in &lwjgl_local {
        // Family version = the core `lwjgl` artifact's version when present,
        // else the lexicographically greatest version among the family's libraries.
        let core_version = libraries
            .iter()
            .find(|lib| {
                let (_, artifact, _) = coord_parts(&lib.coord);
                artifact == "lwjgl"
            })
            .map(|lib| coord_parts(&lib.coord).2.to_owned());
        let family_version = core_version.unwrap_or_else(|| {
            libraries
                .iter()
                .map(|lib| coord_parts(&lib.coord).2.to_owned())
                .max()
                .unwrap_or_default()
        });
        if family_version.is_empty() {
            return Err(GenError::Integrity {
                detail: format!("{origin}: cannot determine {component} version"),
            });
        }
        requires.push(Requirement {
            uid: (*component).to_owned(),
            range: Some(family_version.clone()),
        });
        let bucket = lwjgl_pool
            .entry(component)
            .or_default()
            .entry(family_version)
            .or_default();
        for library in libraries {
            let candidate = LwjglContribution {
                source_release_time: piston.release_time.clone(),
                library: library.clone(),
            };
            match bucket.get(&library.coord) {
                // Newest upstream data wins deterministically (release times
                // are distinct across game versions; ties keep the incumbent).
                Some(existing) if existing.source_release_time >= candidate.source_release_time => {}
                _ => {
                    bucket.insert(library.coord.clone(), candidate);
                }
            }
        }
    }
    Ok((inline_libraries, requires))
}

/// Builds the official-mappings component for a game version (ADR-0010:
/// mapping artifacts ship through `OmniMeta`, never bundled). `None` when
/// Mojang published no mappings for this version.
fn mappings_component(piston: &PistonVersion) -> Result<Option<ComponentPayload>, GenError> {
    let client = piston.downloads.get("client_mappings");
    let server = piston.downloads.get("server_mappings");
    if client.is_none() && server.is_none() {
        return Ok(None);
    }
    let stem = sanitize_file_stem(&piston.id)?;
    let mut mapping_data = Map::new();
    if let Some(download) = client {
        mapping_data.insert("clientMappings".to_owned(), download_value(download));
    }
    if let Some(download) = server {
        mapping_data.insert("serverMappings".to_owned(), download_value(download));
    }
    Ok(Some(ComponentPayload {
        uid: format!("org.omniframework.mappings-{stem}"),
        version: piston.id.clone(),
        release_time: piston.release_time.clone(),
        release_type: piston.release_type.clone(),
        requires: vec![Requirement {
            uid: "net.minecraft".to_owned(),
            range: Some(piston.id.clone()),
        }],
        main_class: None,
        arguments: None,
        legacy_arguments: None,
        libraries: Vec::new(),
        asset_index: None,
        java_version: None,
        data: mapping_data,
    }))
}

fn normalize_game_version(
    piston: &PistonVersion,
    lwjgl_pool: &mut LwjglPool,
    warnings: &mut Vec<String>,
) -> Result<(ComponentPayload, Option<ComponentPayload>), GenError> {
    let origin = format!("client.json {}", piston.id);
    let (inline_libraries, requires) = split_lwjgl(piston, &origin, lwjgl_pool, warnings)?;

    // Component-specific data: named downloads + logging config, passed
    // through verbatim in Mojang's own key dialect (documented in the format).
    let mut data = Map::new();
    if !piston.downloads.is_empty() {
        let mut downloads = Map::new();
        for (name, download) in &piston.downloads {
            downloads.insert(name.clone(), download_value(download));
        }
        data.insert("downloads".to_owned(), Value::Object(downloads));
    }
    if let Some(logging) = &piston.logging {
        data.insert("logging".to_owned(), logging.clone());
    }

    let payload = ComponentPayload {
        uid: "net.minecraft".to_owned(),
        version: piston.id.clone(),
        release_time: piston.release_time.clone(),
        release_type: piston.release_type.clone(),
        requires,
        main_class: piston.main_class.clone(),
        arguments: match &piston.arguments {
            Some(args) => Some(Arguments {
                game: convert_arguments(&args.game, &origin)?,
                jvm: convert_arguments(&args.jvm, &origin)?,
            }),
            None => None,
        },
        legacy_arguments: piston.minecraft_arguments.clone(),
        libraries: inline_libraries,
        asset_index: piston.asset_index.as_ref().map(|index| AssetIndex {
            id: index.id.clone(),
            url: index.url.clone(),
            sha1: index.sha1.clone(),
            size: index.size,
            total_size: index.total_size,
        }),
        java_version: piston
            .java_version
            .as_ref()
            .map(|jv| JavaVersion { component: jv.component.clone(), major_version: jv.major_version }),
        data,
    };

    Ok((payload, mappings_component(piston)?))
}

fn normalize_java(snapshot: &Snapshot) -> BTreeMap<String, ComponentPayload> {
    // version name → (min released, platforms object being built).
    let mut versions: BTreeMap<String, (String, Map<String, Value>)> = BTreeMap::new();
    for (platform, components) in &snapshot.java {
        for (component_name, entries) in components {
            for entry in entries {
                let (min_released, platforms) = versions
                    .entry(entry.version.name.clone())
                    .or_insert_with(|| (entry.version.released.clone(), Map::new()));
                if entry.version.released < *min_released {
                    min_released.clone_from(&entry.version.released);
                }
                let platform_object = platforms
                    .entry(platform.clone())
                    .or_insert_with(|| Value::Object(Map::new()));
                if let Value::Object(offers) = platform_object {
                    let mut offer = Map::new();
                    offer.insert("manifest".to_owned(), download_value(&entry.manifest));
                    offer.insert("released".to_owned(), Value::String(entry.version.released.clone()));
                    // Same (version, platform, component) twice cannot happen
                    // upstream; last write wins deterministically if it did.
                    offers.insert(component_name.clone(), Value::Object(offer));
                }
            }
        }
    }

    versions
        .into_iter()
        .map(|(version, (released, platforms))| {
            let mut data = Map::new();
            data.insert("platforms".to_owned(), Value::Object(platforms));
            let payload = ComponentPayload {
                uid: "net.minecraft.java".to_owned(),
                version: version.clone(),
                release_time: released,
                release_type: "release".to_owned(),
                requires: Vec::new(),
                main_class: None,
                arguments: None,
                legacy_arguments: None,
                libraries: Vec::new(),
                asset_index: None,
                java_version: None,
                data,
            };
            (version, payload)
        })
        .collect()
}

/// Normalizes a snapshot (plus definitions and policy) into a [`Tree`].
///
/// # Errors
///
/// [`GenError::Usage`] when a filtered version id is not in the snapshot;
/// [`GenError::Integrity`] on irreconcilable inputs (duplicate components,
/// unknown rule actions, undeterminable LWJGL versions);
/// [`GenError::Format`] when a derived uid or version is invalid.
pub fn normalize(snapshot: &Snapshot, options: &Options) -> Result<Tree, GenError> {
    let mut warnings = Vec::new();

    if let Some(requested) = &options.versions {
        let missing: Vec<&str> = requested
            .iter()
            .map(String::as_str)
            .filter(|id| !snapshot.versions.contains_key(*id))
            .collect();
        if !missing.is_empty() {
            return Err(GenError::Usage(format!(
                "version id(s) not present in the snapshot: {}",
                missing.join(", ")
            )));
        }
    }

    let mut components: BTreeMap<String, BTreeMap<String, ComponentPayload>> = BTreeMap::new();
    let insert = |payload: ComponentPayload,
                      components: &mut BTreeMap<String, BTreeMap<String, ComponentPayload>>|
     -> Result<(), GenError> {
        validate_uid(&payload.uid)?;
        sanitize_file_stem(&payload.version)?;
        let uid = payload.uid.clone();
        let version = payload.version.clone();
        if components.entry(uid.clone()).or_default().insert(version.clone(), payload).is_some() {
            return Err(GenError::Integrity {
                detail: format!("component {uid} version {version:?} produced twice"),
            });
        }
        Ok(())
    };

    // Game versions (sorted iteration: BTreeMap by id).
    let mut lwjgl_pool = LwjglPool::new();
    for (id, piston) in &snapshot.versions {
        if options.versions.as_ref().is_some_and(|set| !set.contains(id)) {
            continue;
        }
        let (game, mappings) = normalize_game_version(piston, &mut lwjgl_pool, &mut warnings)?;
        insert(game, &mut components)?;
        if let Some(mappings) = mappings {
            insert(mappings, &mut components)?;
        }
    }

    // LWJGL components from the pooled split.
    for (component, versions) in lwjgl_pool {
        for (family_version, contributions) in versions {
            let release_time = contributions
                .values()
                .map(|c| c.source_release_time.as_str())
                .min()
                .unwrap_or_default()
                .to_owned();
            let libraries: Vec<Library> =
                contributions.into_values().map(|c| c.library).collect();
            insert(
                ComponentPayload {
                    uid: component.to_owned(),
                    version: family_version.clone(),
                    release_time,
                    release_type: "release".to_owned(),
                    requires: Vec::new(),
                    main_class: None,
                    arguments: None,
                    legacy_arguments: None,
                    libraries,
                    asset_index: None,
                    java_version: None,
                    data: Map::new(),
                },
                &mut components,
            )?;
        }
    }

    // Java runtimes.
    for payload in normalize_java(snapshot).into_values() {
        insert(payload, &mut components)?;
    }

    // Omni component definitions (loader, compat layers) — already-parsed payloads.
    for payload in &options.definitions {
        insert(payload.clone(), &mut components)?;
    }

    let recommended = resolve_recommended(snapshot, &options.policy, &components)?;
    Ok(Tree { components, recommended, warnings })
}

/// Validates the recommended-version policy against the tree and applies the
/// default (`latest.release` from the manifest) when the policy is silent
/// about `net.minecraft`.
fn resolve_recommended(
    snapshot: &Snapshot,
    policy: &Policy,
    components: &BTreeMap<String, BTreeMap<String, ComponentPayload>>,
) -> Result<BTreeMap<String, BTreeSet<String>>, GenError> {
    let mut recommended = policy.recommended.clone();
    for (uid, versions) in &recommended {
        let Some(known) = components.get(uid) else {
            return Err(GenError::Usage(format!(
                "policy recommends versions for unknown component {uid:?}"
            )));
        };
        for version in versions {
            if !known.contains_key(version) {
                return Err(GenError::Usage(format!(
                    "policy recommends {uid} {version:?}, which is not in the tree"
                )));
            }
        }
    }
    if !recommended.contains_key("net.minecraft") {
        let latest = &snapshot.manifest.latest.release;
        if components.get("net.minecraft").is_some_and(|v| v.contains_key(latest)) {
            recommended
                .entry("net.minecraft".to_owned())
                .or_default()
                .insert(latest.clone());
        }
    }
    Ok(recommended)
}

/// Loads Omni component definitions from `<dir>/<uid>/<version>.json` files.
///
/// Strict (this is an Omni-owned surface): every file must parse as a
/// [`ComponentPayload`], its `uid` must equal its directory name, and its
/// sanitized `version` must equal its file stem.
///
/// # Errors
///
/// [`GenError::Io`] on unreadable paths, [`GenError::Json`]/[`GenError::Format`]
/// on invalid documents, [`GenError::Integrity`] on path/content mismatches.
pub fn load_definitions(dir: &Path) -> Result<Vec<ComponentPayload>, GenError> {
    let mut uid_dirs: Vec<std::path::PathBuf> = std::fs::read_dir(dir)
        .map_err(|e| GenError::io("list definitions", dir, e))?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| GenError::io("list definitions", dir, e))?
        .into_iter()
        .map(|entry| entry.path())
        .collect();
    uid_dirs.sort();

    let mut definitions = Vec::new();
    for uid_dir in uid_dirs {
        if !uid_dir.is_dir() {
            return Err(GenError::Integrity {
                detail: format!(
                    "definitions layout is <uid>/<version>.json; unexpected file {}",
                    uid_dir.display()
                ),
            });
        }
        let uid = uid_dir
            .file_name()
            .map(|name| name.to_string_lossy().into_owned())
            .unwrap_or_default();
        validate_uid(&uid)?;
        let mut files: Vec<std::path::PathBuf> = std::fs::read_dir(&uid_dir)
            .map_err(|e| GenError::io("list definitions", &uid_dir, e))?
            .collect::<Result<Vec<_>, _>>()
            .map_err(|e| GenError::io("list definitions", &uid_dir, e))?
            .into_iter()
            .map(|entry| entry.path())
            .collect();
        files.sort();
        for file in files {
            let bytes =
                std::fs::read(&file).map_err(|e| GenError::io("read definition", &file, e))?;
            let payload = ComponentPayload::parse(&bytes)?;
            if payload.uid != uid {
                return Err(GenError::Integrity {
                    detail: format!(
                        "definition {} declares uid {:?} but lives under {uid:?}",
                        file.display(),
                        payload.uid
                    ),
                });
            }
            let expected_name = format!("{}.json", sanitize_file_stem(&payload.version)?);
            let actual_name = file
                .file_name()
                .map(|name| name.to_string_lossy().into_owned())
                .unwrap_or_default();
            if actual_name != expected_name {
                return Err(GenError::Integrity {
                    detail: format!(
                        "definition {} declares version {:?} (expected file name {expected_name})",
                        file.display(),
                        payload.version
                    ),
                });
            }
            definitions.push(payload);
        }
    }
    Ok(definitions)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mojang::PistonLibraryDownloads;

    fn download(url: &str) -> PistonDownload {
        PistonDownload { url: url.to_owned(), sha1: "a".repeat(40), size: 10 }
    }

    #[test]
    fn lwjgl_family_mapping() {
        assert_eq!(lwjgl_component("org.lwjgl"), Some("org.lwjgl3"));
        assert_eq!(lwjgl_component("org.lwjgl.lwjgl"), Some("org.lwjgl2"));
        assert_eq!(lwjgl_component("com.mojang"), None);
        assert_eq!(lwjgl_component("org.lwjglx"), None);
    }

    #[test]
    fn legacy_natives_expand_per_os_and_arch() {
        let mut natives = BTreeMap::new();
        natives.insert("windows".to_owned(), "natives-windows-${arch}".to_owned());
        natives.insert("linux".to_owned(), "natives-linux".to_owned());
        let mut classifiers = BTreeMap::new();
        classifiers.insert("natives-windows-32".to_owned(), download("u32"));
        classifiers.insert("natives-windows-64".to_owned(), download("u64"));
        classifiers.insert("natives-linux".to_owned(), download("ul"));
        let library = PistonLibrary {
            name: "org.lwjgl.lwjgl:lwjgl-platform:2.9.1".to_owned(),
            downloads: Some(PistonLibraryDownloads {
                artifact: None,
                classifiers: Some(classifiers),
            }),
            natives: Some(natives),
            rules: Vec::new(),
        };
        let mut warnings = Vec::new();
        let expanded = expand_library(&library, "test", &mut warnings).expect("expands");
        assert!(warnings.is_empty());
        let coords: Vec<&str> = expanded.iter().map(|l| l.coord.as_str()).collect();
        assert_eq!(
            coords,
            vec![
                "org.lwjgl.lwjgl:lwjgl-platform:2.9.1:natives-linux",
                "org.lwjgl.lwjgl:lwjgl-platform:2.9.1:natives-windows-32",
                "org.lwjgl.lwjgl:lwjgl-platform:2.9.1:natives-windows-64",
            ]
        );
        // The ${arch} variants carry pinned arch rules.
        let win32 = &expanded[1];
        assert_eq!(win32.rules.len(), 1);
        let os = win32.rules[0].os.as_ref().expect("os rule");
        assert_eq!(os.name.as_deref(), Some("windows"));
        assert_eq!(os.arch.as_deref(), Some("x86"));
    }

    #[test]
    fn unknown_rule_action_is_an_integrity_error() {
        let rules = vec![PistonRule { action: "maybe".to_owned(), os: None, features: None }];
        let err = convert_rules(&rules, "test").expect_err("must reject");
        assert!(matches!(err, GenError::Integrity { .. }));
    }

    #[test]
    fn policy_file_rejects_unknown_keys() {
        let err = Policy::parse(br#"{"recomended": {}}"#, "test").expect_err("typo must fail");
        assert!(matches!(err, GenError::Json { .. }));
    }
}
