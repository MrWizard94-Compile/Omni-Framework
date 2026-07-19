//! Mod-file identification: CurseForge murmur2 fingerprints, sha1/sha512, and
//! the shared mods-folder scanner (hash recovery per LNC-7; the hashes feed
//! the Phase 6 census).

use std::path::Path;

use sha2::Digest as _;

use crate::error::CoreError;
use crate::install::sha1_hex;
use crate::store::Ecosystem;

/// CurseForge's fingerprint: murmur2 (32-bit, seed 1) over the file bytes
/// with whitespace bytes (9, 10, 13, 32) removed first — matches the
/// `packageFingerprint` values in `minecraftinstance.json` and the Core API's
/// fingerprint-match endpoint.
pub fn murmur2_fingerprint(data: &[u8]) -> u32 {
    const M: u32 = 0x5bd1_e995;
    let filtered: Vec<u8> =
        data.iter().copied().filter(|b| !matches!(b, 9 | 10 | 13 | 32)).collect();
    let len = u32::try_from(filtered.len()).unwrap_or(u32::MAX);
    let mut hash: u32 = 1 ^ len;
    let mut chunks = filtered.chunks_exact(4);
    for chunk in &mut chunks {
        let mut k = u32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]);
        k = k.wrapping_mul(M);
        k ^= k >> 24;
        k = k.wrapping_mul(M);
        hash = hash.wrapping_mul(M);
        hash ^= k;
    }
    let tail = chunks.remainder();
    if tail.len() == 3 {
        hash ^= u32::from(tail[2]) << 16;
    }
    if tail.len() >= 2 {
        hash ^= u32::from(tail[1]) << 8;
    }
    if !tail.is_empty() {
        hash ^= u32::from(tail[0]);
        hash = hash.wrapping_mul(M);
    }
    hash ^= hash >> 13;
    hash = hash.wrapping_mul(M);
    hash ^= hash >> 15;
    hash
}

/// One scanned mod file with its identification hashes.
#[derive(Debug, Clone)]
pub struct ScannedMod {
    /// File name as on disk (including a `.disabled` suffix when present).
    pub file_name: String,
    /// Whether the file is enabled (no `.disabled` suffix).
    pub enabled: bool,
    /// Lowercase-hex sha1.
    pub sha1: String,
    /// Lowercase-hex sha512 (Modrinth dialect).
    pub sha512: String,
    /// CurseForge murmur2 fingerprint, decimal.
    pub murmur2: String,
    /// Best-effort ecosystem guess from the jar's manifests.
    pub ecosystem: Ecosystem,
}

fn sha512_hex(bytes: &[u8]) -> String {
    let digest = sha2::Sha512::digest(bytes);
    let mut out = String::with_capacity(128);
    for byte in digest {
        use std::fmt::Write as _;
        write!(out, "{byte:02x}").expect("writing to String cannot fail");
    }
    out
}

/// Best-effort ecosystem detection by manifest presence inside the jar.
fn detect_ecosystem(bytes: &[u8]) -> Ecosystem {
    let cursor = std::io::Cursor::new(bytes);
    let Ok(mut archive) = zip::ZipArchive::new(cursor) else {
        return Ecosystem::Unknown;
    };
    let names: Vec<String> = (0..archive.len())
        .filter_map(|i| archive.by_index(i).ok().map(|f| f.name().to_owned()))
        .collect();
    let has = |name: &str| names.iter().any(|n| n == name);
    if has("omni.mod.json") {
        Ecosystem::Omni
    } else if has("fabric.mod.json") || has("quilt.mod.json") {
        Ecosystem::Fabric
    } else if has("META-INF/neoforge.mods.toml") {
        Ecosystem::Neoforge
    } else if has("META-INF/mods.toml") {
        Ecosystem::Forge
    } else {
        Ecosystem::Unknown
    }
}

/// Scans a mods folder: every `*.jar` / `*.jar.disabled` is hashed and
/// ecosystem-sniffed. Non-jar files are ignored (packs/configs are other
/// surfaces). Deterministic order (sorted by file name).
///
/// # Errors
///
/// [`CoreError::Io`] when the folder or a jar cannot be read.
pub fn scan_mods_dir(dir: &Path) -> Result<Vec<ScannedMod>, CoreError> {
    let mut out = Vec::new();
    if !dir.is_dir() {
        return Ok(out);
    }
    let mut entries: Vec<std::path::PathBuf> = std::fs::read_dir(dir)
        .map_err(|e| CoreError::Io { action: "list mods dir", path: dir.to_path_buf(), source: e })?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| CoreError::Io { action: "list mods dir", path: dir.to_path_buf(), source: e })?
        .into_iter()
        .map(|entry| entry.path())
        .collect();
    entries.sort();
    for path in entries {
        let Some(file_name) = path.file_name().map(|n| n.to_string_lossy().into_owned()) else {
            continue;
        };
        // Case-insensitive extension matching: Windows tolerates `.JAR`, and
        // disabled mods carry the launcher-conventional `.disabled` suffix.
        let extension_is = |path: &Path, wanted: &str| {
            path.extension()
                .and_then(|ext| ext.to_str())
                .is_some_and(|ext| ext.eq_ignore_ascii_case(wanted))
        };
        let as_path = Path::new(&file_name);
        let enabled = extension_is(as_path, "jar");
        let disabled = extension_is(as_path, "disabled")
            && as_path.file_stem().is_some_and(|stem| extension_is(Path::new(stem), "jar"));
        if !enabled && !disabled {
            continue;
        }
        let bytes = std::fs::read(&path)
            .map_err(|e| CoreError::Io { action: "read mod file", path: path.clone(), source: e })?;
        out.push(ScannedMod {
            enabled,
            sha1: sha1_hex(&bytes),
            sha512: sha512_hex(&bytes),
            murmur2: murmur2_fingerprint(&bytes).to_string(),
            ecosystem: detect_ecosystem(&bytes),
            file_name,
        });
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Cross-checked against an independent Python implementation
    /// (2026-07-19; CurseForge whitespace-stripping murmur2, seed 1).
    #[test]
    fn murmur2_matches_independent_vectors() {
        assert_eq!(murmur2_fingerprint(b"omni-framework-fingerprint-vector"), 1_743_015_372);
        assert_eq!(murmur2_fingerprint(b"a b\tc\nd\re fgh"), 1_080_400_154);
        assert_eq!(murmur2_fingerprint(b""), 1_540_447_798);
    }

    #[test]
    fn whitespace_bytes_are_stripped_before_hashing() {
        assert_eq!(
            murmur2_fingerprint(b"a b\tc\nd\re fgh"),
            murmur2_fingerprint(b"abcdefgh"),
            "9/10/13/32 must not affect the fingerprint"
        );
    }
}
