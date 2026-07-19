//! End-to-end pipeline tests over the committed fixture snapshot and golden tree.
//!
//! The golden tree (`tests/golden/v1/**`) is itself generator output, committed
//! for review exactly like the intelligence dossiers (ADR-0004 pattern): the
//! pipeline is deterministic, so any behavior change shows up as a git diff.
//! Regenerate after an intentional format change with:
//!
//! ```text
//! cargo run -p omni-meta-gen -- generate \
//!   --snapshot OmniMeta/generator/tests/fixtures/snapshot \
//!   --definitions OmniMeta/generator/tests/fixtures/definitions \
//!   --policy OmniMeta/generator/tests/fixtures/policy.json \
//!   --out OmniMeta/generator/tests/golden --force
//! ```

use std::collections::BTreeSet;
use std::path::{Path, PathBuf};

use omni_meta_format::{Argument, ComponentPayload};
use omni_meta_gen::emit::{EmitOptions, emit};
use omni_meta_gen::normalize::{Options, Policy, Tree, load_definitions, normalize};
use omni_meta_gen::snapshot;
use omni_meta_gen::verify::verify;

fn fixtures() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("tests").join("fixtures")
}

fn golden_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("tests").join("golden")
}

fn build_tree() -> Tree {
    let snap = snapshot::load(&fixtures().join("snapshot")).expect("snapshot loads");
    let definitions =
        load_definitions(&fixtures().join("definitions")).expect("definitions load");
    let policy_bytes = std::fs::read(fixtures().join("policy.json")).expect("policy readable");
    let policy = Policy::parse(&policy_bytes, "policy.json").expect("policy parses");
    let tree =
        normalize(&snap, &Options { versions: None, policy, definitions }).expect("normalizes");
    assert!(tree.warnings.is_empty(), "unexpected warnings: {:?}", tree.warnings);
    tree
}

/// Collects every file under `dir` as (tree-relative path with `/` separators,
/// bytes), sorted by path.
fn walk(dir: &Path) -> Vec<(String, Vec<u8>)> {
    fn recurse(root: &Path, dir: &Path, out: &mut Vec<(String, Vec<u8>)>) {
        let mut entries: Vec<PathBuf> = std::fs::read_dir(dir)
            .unwrap_or_else(|e| panic!("read_dir {}: {e}", dir.display()))
            .map(|entry| entry.expect("dir entry").path())
            .collect();
        entries.sort();
        for path in entries {
            if path.is_dir() {
                recurse(root, &path, out);
            } else {
                let rel = path
                    .strip_prefix(root)
                    .expect("under root")
                    .to_string_lossy()
                    .replace('\\', "/");
                let bytes =
                    std::fs::read(&path).unwrap_or_else(|e| panic!("read {}: {e}", path.display()));
                out.push((rel, bytes));
            }
        }
    }
    let mut out = Vec::new();
    recurse(dir, dir, &mut out);
    out
}

fn copy_tree(from: &Path, to: &Path) {
    for (rel, bytes) in walk(from) {
        let dest = to.join(rel.replace('/', std::path::MAIN_SEPARATOR_STR));
        std::fs::create_dir_all(dest.parent().expect("parent")).expect("mkdir");
        std::fs::write(&dest, bytes).expect("copy file");
    }
}

fn payload(tree: &Tree, uid: &str, version: &str) -> ComponentPayload {
    tree.components
        .get(uid)
        .and_then(|versions| versions.get(version))
        .unwrap_or_else(|| panic!("tree missing {uid}/{version}"))
        .clone()
}

#[test]
fn golden_tree_is_reproduced_byte_for_byte() {
    let tree = build_tree();
    let out = tempfile::tempdir().expect("tempdir");
    emit(&tree, &EmitOptions { out_root: out.path().to_path_buf(), force: false })
        .expect("emits");

    let expected = walk(&golden_root());
    let actual = walk(out.path());
    let expected_paths: Vec<&String> = expected.iter().map(|(path, _)| path).collect();
    let actual_paths: Vec<&String> = actual.iter().map(|(path, _)| path).collect();
    assert_eq!(expected_paths, actual_paths, "file sets differ from golden tree");
    for ((path, expected_bytes), (_, actual_bytes)) in expected.iter().zip(actual.iter()) {
        assert_eq!(
            expected_bytes, actual_bytes,
            "{path} differs from the golden tree (regenerate goldens only for \
             intentional format changes; see file header)"
        );
    }
}

#[test]
fn emission_is_deterministic() {
    let tree = build_tree();
    let first = tempfile::tempdir().expect("tempdir");
    let second = tempfile::tempdir().expect("tempdir");
    emit(&tree, &EmitOptions { out_root: first.path().to_path_buf(), force: false })
        .expect("first emit");
    emit(&tree, &EmitOptions { out_root: second.path().to_path_buf(), force: false })
        .expect("second emit");
    assert_eq!(walk(first.path()), walk(second.path()));
}

#[test]
fn golden_tree_verifies_clean() {
    let issues = verify(&golden_root()).expect("verify runs");
    assert!(issues.is_empty(), "golden tree has issues: {issues:?}");
}

#[test]
fn verify_detects_corruption() {
    let temp = tempfile::tempdir().expect("tempdir");
    copy_tree(&golden_root(), temp.path());
    let target = temp
        .path()
        .join("v1")
        .join("net.minecraft")
        .join("1.21.1.json");
    let mut bytes = std::fs::read(&target).expect("payload readable");
    bytes.extend_from_slice(b" ");
    std::fs::write(&target, bytes).expect("corrupt payload");

    let issues = verify(temp.path()).expect("verify runs");
    assert!(
        issues.iter().any(|issue| issue.message.contains("sha256 mismatch")),
        "expected a sha256 mismatch issue, got: {issues:?}"
    );
}

#[test]
fn verify_detects_missing_file() {
    let temp = tempfile::tempdir().expect("tempdir");
    copy_tree(&golden_root(), temp.path());
    std::fs::remove_file(temp.path().join("v1").join("org.lwjgl3").join("3.3.3.json"))
        .expect("remove payload");

    let issues = verify(temp.path()).expect("verify runs");
    assert!(
        issues.iter().any(|issue| issue.message.contains("unreadable")),
        "expected an unreadable-file issue, got: {issues:?}"
    );
}

#[test]
fn verify_detects_dangling_requires() {
    // Filter to 1.7.10 only, but keep the loader definition that requires
    // net.minecraft 1.21.1: the emitted tree must fail the requires audit.
    let snap = snapshot::load(&fixtures().join("snapshot")).expect("snapshot loads");
    let definitions =
        load_definitions(&fixtures().join("definitions")).expect("definitions load");
    let tree = normalize(
        &snap,
        &Options {
            versions: Some(BTreeSet::from(["1.7.10".to_owned()])),
            policy: Policy::default(),
            definitions,
        },
    )
    .expect("normalizes");
    let out = tempfile::tempdir().expect("tempdir");
    emit(&tree, &EmitOptions { out_root: out.path().to_path_buf(), force: false })
        .expect("emits");

    let issues = verify(out.path()).expect("verify runs");
    assert!(
        issues.iter().any(|issue| issue.message.contains("requires")),
        "expected dangling-requires issues, got: {issues:?}"
    );
}

#[test]
fn version_filter_restricts_components() {
    let snap = snapshot::load(&fixtures().join("snapshot")).expect("snapshot loads");
    let tree = normalize(
        &snap,
        &Options {
            versions: Some(BTreeSet::from(["1.21.1".to_owned()])),
            policy: Policy::default(),
            definitions: Vec::new(),
        },
    )
    .expect("normalizes");

    let minecraft = tree.components.get("net.minecraft").expect("net.minecraft");
    assert_eq!(minecraft.keys().collect::<Vec<_>>(), vec!["1.21.1"]);
    assert!(tree.components.contains_key("org.lwjgl3"));
    assert!(!tree.components.contains_key("org.lwjgl2"));
    assert!(tree.components.contains_key("org.omniframework.mappings-1.21.1"));
    assert!(!tree.components.contains_key("org.omniframework.mappings-1.7.10"));
    // Default policy: manifest latest.release is recommended.
    assert!(
        tree.recommended
            .get("net.minecraft")
            .is_some_and(|set| set.contains("1.21.1"))
    );
}

#[test]
fn unknown_filter_version_is_a_usage_error() {
    let snap = snapshot::load(&fixtures().join("snapshot")).expect("snapshot loads");
    let err = normalize(
        &snap,
        &Options {
            versions: Some(BTreeSet::from(["9.9.9".to_owned()])),
            policy: Policy::default(),
            definitions: Vec::new(),
        },
    )
    .expect_err("must reject unknown id");
    assert!(matches!(err, omni_meta_gen::GenError::Usage(_)));
}

#[test]
fn normalization_content_spot_checks() {
    let tree = build_tree();

    // Modern game version: LWJGL split out, requires edge carries the version.
    let modern = payload(&tree, "net.minecraft", "1.21.1");
    let requires: Vec<(String, Option<String>)> = modern
        .requires
        .iter()
        .map(|r| (r.uid.clone(), r.range.clone()))
        .collect();
    assert_eq!(requires, vec![("org.lwjgl3".to_owned(), Some("3.3.3".to_owned()))]);
    assert!(modern.libraries.iter().all(|lib| !lib.coord.starts_with("org.lwjgl")));
    assert!(modern.data.contains_key("downloads"));
    assert!(modern.data.contains_key("logging"));
    assert_eq!(modern.java_version.expect("java version").major_version, 21);
    assert!(modern.arguments.is_some());
    assert!(modern.legacy_arguments.is_none());
    let game_args = modern.arguments.expect("arguments").game;
    assert!(matches!(game_args[0], Argument::Plain(_)));

    // Legacy game version: verbatim legacy arguments, LWJGL 2 split, natives
    // expansion with the absent-osx classifier skipped.
    let legacy = payload(&tree, "net.minecraft", "1.7.10");
    assert!(legacy.legacy_arguments.is_some());
    assert!(legacy.arguments.is_none());
    assert_eq!(legacy.requires.len(), 1);
    assert_eq!(legacy.requires[0].uid, "org.lwjgl2");
    let jinput: Vec<&str> = legacy
        .libraries
        .iter()
        .map(|lib| lib.coord.as_str())
        .filter(|coord| coord.contains("jinput-platform"))
        .collect();
    assert_eq!(
        jinput,
        vec![
            "net.java.jinput:jinput-platform:2.0.5:natives-linux",
            "net.java.jinput:jinput-platform:2.0.5:natives-windows",
        ]
    );

    // LWJGL components aggregate the split libraries.
    let lwjgl3 = payload(&tree, "org.lwjgl3", "3.3.3");
    assert_eq!(lwjgl3.libraries.len(), 3);
    assert_eq!(lwjgl3.release_time, "2024-08-08T12:24:45+00:00");
    let lwjgl2 = payload(&tree, "org.lwjgl2", "2.9.1");
    assert_eq!(lwjgl2.libraries.len(), 4);

    // Java runtimes merge platforms per version, min released wins.
    let java = payload(&tree, "net.minecraft.java", "21.0.3");
    assert_eq!(java.release_time, "2024-04-16T12:00:00+00:00");
    let platforms = java.data.get("platforms").and_then(|v| v.as_object()).expect("platforms");
    assert_eq!(platforms.keys().collect::<Vec<_>>(), vec!["linux", "windows-x64"]);

    // Mappings component links back to its game version.
    let mappings = payload(&tree, "org.omniframework.mappings-1.21.1", "1.21.1");
    assert_eq!(mappings.requires[0].uid, "net.minecraft");
    assert!(mappings.data.contains_key("clientMappings"));
    assert!(mappings.data.contains_key("serverMappings"));

    // Definitions and policy landed.
    assert!(tree.components.contains_key("org.omniframework.omniloader"));
    assert!(
        tree.recommended
            .get("org.omniframework.omniloader")
            .is_some_and(|set| set.contains("0.1.0"))
    );
}
