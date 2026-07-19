//! Client integration tests over the generator's committed golden tree —
//! the same fixture both sides of the wire are tested against, so a format
//! drift breaks one build instead of one user.

use std::path::{Path, PathBuf};

use omni_meta_client::{DirSource, Freshness, MetaClient, MetaError, MetaSource, SourceError};

/// The golden `/v1` tree committed with the generator (workspace-internal
/// fixture reuse; regeneration instructions live in the generator's tests).
fn golden_v1() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../OmniMeta/generator/tests/golden/v1")
        .canonicalize()
        .expect("golden tree exists (build omni-meta-gen goldens first)")
}

/// A source that always fails: simulates full network loss.
struct DeadSource;

impl MetaSource for DeadSource {
    fn fetch(&self, rel_path: &str) -> Result<Vec<u8>, SourceError> {
        Err(SourceError { path: rel_path.to_owned(), message: "network unreachable".to_owned() })
    }

    fn describe(&self) -> String {
        "dead://".to_owned()
    }
}

fn copy_tree(from: &Path, to: &Path) {
    for entry in std::fs::read_dir(from).expect("read_dir") {
        let path = entry.expect("entry").path();
        let dest = to.join(path.file_name().expect("name"));
        if path.is_dir() {
            std::fs::create_dir_all(&dest).expect("mkdir");
            copy_tree(&path, &dest);
        } else {
            std::fs::copy(&path, &dest).expect("copy");
        }
    }
}

#[test]
fn resolves_payload_through_the_hash_chain() {
    let cache = tempfile::tempdir().expect("tempdir");
    let client = MetaClient::new(DirSource::new(golden_v1()), cache.path()).expect("client");

    let (payload, freshness) = client.resolve("net.minecraft", "1.21.1").expect("resolves");
    assert_eq!(freshness, Freshness::Fresh);
    assert_eq!(payload.main_class.as_deref(), Some("net.minecraft.client.main.Main"));
    assert_eq!(payload.requires[0].uid, "org.lwjgl3");

    let (root, _) = client.root().expect("root");
    let index = client.component_index(&root, "net.minecraft").expect("index");
    let recommended: Vec<&str> =
        client.recommended(&index).iter().map(|s| s.version.as_str()).collect();
    assert_eq!(recommended, vec!["1.21.1"]);
}

#[test]
fn warm_cache_survives_total_source_loss() {
    let cache = tempfile::tempdir().expect("tempdir");
    {
        let online = MetaClient::new(DirSource::new(golden_v1()), cache.path()).expect("client");
        online.resolve("org.omniframework.omniloader", "0.1.0").expect("warm the cache");
    }

    let offline = MetaClient::new(DeadSource, cache.path()).expect("client");
    let (payload, freshness) =
        offline.resolve("org.omniframework.omniloader", "0.1.0").expect("served from cache");
    assert_eq!(freshness, Freshness::CachedFallback);
    assert_eq!(payload.main_class.as_deref(), Some("org.omniframework.loader.launch.OmniClientMain"));
}

#[test]
fn cold_cache_plus_dead_source_is_offline() {
    let cache = tempfile::tempdir().expect("tempdir");
    let client = MetaClient::new(DeadSource, cache.path()).expect("client");
    let err = client.root().expect_err("must fail offline");
    assert!(matches!(err, MetaError::Offline { .. }), "got: {err:?}");
}

#[test]
fn tampered_payload_is_rejected() {
    let tampered = tempfile::tempdir().expect("tempdir");
    copy_tree(&golden_v1(), tampered.path());
    let target = tampered.path().join("net.minecraft").join("1.21.1.json");
    let mut bytes = std::fs::read(&target).expect("read payload");
    bytes.extend_from_slice(b"\n");
    std::fs::write(&target, bytes).expect("tamper");

    let cache = tempfile::tempdir().expect("tempdir");
    let client = MetaClient::new(DirSource::new(tampered.path()), cache.path()).expect("client");
    let err = client.resolve("net.minecraft", "1.21.1").expect_err("must reject");
    assert!(matches!(err, MetaError::HashMismatch { .. }), "got: {err:?}");
}

#[test]
fn corrupted_cache_object_self_heals() {
    let cache = tempfile::tempdir().expect("tempdir");
    let client = MetaClient::new(DirSource::new(golden_v1()), cache.path()).expect("client");
    client.resolve("net.minecraft", "1.7.10").expect("warm the cache");

    // Corrupt every cached object, then resolve again with the source intact.
    let objects = cache.path().join("objects");
    for entry in walkdir(&objects) {
        std::fs::write(&entry, b"garbage").expect("corrupt object");
    }
    let (payload, _) = client.resolve("net.minecraft", "1.7.10").expect("self-heals");
    assert!(payload.legacy_arguments.is_some());
}

#[test]
fn unknown_component_and_version_are_typed() {
    let cache = tempfile::tempdir().expect("tempdir");
    let client = MetaClient::new(DirSource::new(golden_v1()), cache.path()).expect("client");
    let (root, _) = client.root().expect("root");

    let err = client.component_index(&root, "org.nonexistent").expect_err("unknown uid");
    assert!(matches!(err, MetaError::UnknownComponent { .. }), "got: {err:?}");

    let index = client.component_index(&root, "net.minecraft").expect("index");
    let err = client.payload(&index, "0.0.0").expect_err("unknown version");
    assert!(matches!(err, MetaError::UnknownVersion { .. }), "got: {err:?}");
}

fn walkdir(dir: &Path) -> Vec<PathBuf> {
    let mut files = Vec::new();
    if let Ok(entries) = std::fs::read_dir(dir) {
        for entry in entries {
            let path = entry.expect("entry").path();
            if path.is_dir() {
                files.extend(walkdir(&path));
            } else {
                files.push(path);
            }
        }
    }
    files
}
