//! Naming rules for tree files: uid validation, version-to-filename
//! sanitization, and the relative-path builders used by generator and client.
//!
//! Uids are Omni-controlled, so they are *validated* (reject on violation).
//! Version strings come from upstreams we do not control — Mojang has published
//! ids containing spaces (e.g. the 2019 April Fools build `3D Shareware v1.34`)
//! — so versions are *sanitized* into file stems instead, and the explicit
//! `url` fields in the indexes remain the source of truth for locating files.

use crate::FormatError;

/// Characters allowed verbatim in tree file names.
fn is_safe_char(c: char) -> bool {
    c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-' | '+')
}

/// Validates a component uid for use as a tree directory name.
///
/// Rules: non-empty, at most 128 chars, ASCII alphanumerics plus `._-+` only,
/// must start with an alphanumeric, and must not be `.`/`..` or contain a
/// `..` sequence.
///
/// # Errors
///
/// [`FormatError::InvalidUid`] naming the violated rule.
pub fn validate_uid(uid: &str) -> Result<(), FormatError> {
    let fail = |reason: &'static str| {
        Err(FormatError::InvalidUid { uid: uid.to_owned(), reason })
    };
    if uid.is_empty() {
        return fail("must not be empty");
    }
    if uid.len() > 128 {
        return fail("must be at most 128 characters");
    }
    if !uid.chars().next().is_some_and(|c| c.is_ascii_alphanumeric()) {
        return fail("must start with an ASCII alphanumeric");
    }
    if !uid.chars().all(is_safe_char) {
        return fail("may only contain ASCII alphanumerics and . _ - +");
    }
    if uid.contains("..") {
        return fail("must not contain '..'");
    }
    Ok(())
}

/// Maps an arbitrary version string to a filesystem/URL-safe file stem:
/// every character outside the safe set becomes `_`.
///
/// Distinct versions can sanitize to the same stem; the generator detects such
/// collisions at emit time and fails rather than overwrite.
///
/// # Errors
///
/// [`FormatError::InvalidVersion`] when the input is empty, longer than 128
/// characters, or would collide with the reserved `index` stem.
pub fn sanitize_file_stem(version: &str) -> Result<String, FormatError> {
    let fail = |reason: &'static str| {
        Err(FormatError::InvalidVersion { version: version.to_owned(), reason })
    };
    if version.is_empty() {
        return fail("must not be empty");
    }
    if version.len() > 128 {
        return fail("must be at most 128 characters");
    }
    let stem: String = version
        .chars()
        .map(|c| if is_safe_char(c) { c } else { '_' })
        .collect();
    if stem == "index" {
        return fail("collides with the reserved index.json file");
    }
    if stem.chars().all(|c| c == '.') {
        return fail("must contain a non-dot character");
    }
    Ok(stem)
}

/// Path of the root index, relative to the `/v1/` root.
pub fn root_index_path() -> String {
    "index.json".to_owned()
}

/// Path of a component's version index, relative to the `/v1/` root.
/// The uid must already have passed [`validate_uid`].
pub fn component_index_path(uid: &str) -> String {
    format!("{uid}/index.json")
}

/// Path of a version payload, relative to the `/v1/` root.
///
/// # Errors
///
/// Propagates [`sanitize_file_stem`] errors for the version string.
pub fn payload_path(uid: &str, version: &str) -> Result<String, FormatError> {
    let stem = sanitize_file_stem(version)?;
    Ok(format!("{uid}/{stem}.json"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_real_uids() {
        for uid in [
            "net.minecraft",
            "net.minecraft.java",
            "org.lwjgl3",
            "org.omniframework.omniloader",
            "org.omniframework.mappings-1.21.1",
        ] {
            validate_uid(uid).expect(uid);
        }
    }

    #[test]
    fn rejects_hostile_uids() {
        for uid in ["", "../etc", "a/b", ".hidden", "a..b", "-flag", &"x".repeat(200)] {
            assert!(validate_uid(uid).is_err(), "should reject {uid:?}");
        }
    }

    #[test]
    fn sanitizes_hostile_versions() {
        assert_eq!(sanitize_file_stem("1.21.1").expect("safe"), "1.21.1");
        assert_eq!(
            sanitize_file_stem("3D Shareware v1.34").expect("spaces become underscores"),
            "3D_Shareware_v1.34"
        );
        assert_eq!(sanitize_file_stem("a/b\\c").expect("separators neutralized"), "a_b_c");
        assert!(sanitize_file_stem("").is_err());
        assert!(sanitize_file_stem("index").is_err());
        assert!(sanitize_file_stem("..").is_err());
    }

    #[test]
    fn payload_paths_stay_relative() {
        assert_eq!(
            payload_path("net.minecraft", "1.21.1").expect("safe"),
            "net.minecraft/1.21.1.json"
        );
        assert_eq!(component_index_path("net.minecraft"), "net.minecraft/index.json");
        assert_eq!(root_index_path(), "index.json");
    }
}
