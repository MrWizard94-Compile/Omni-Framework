//! System JVM discovery and validation (LNC-6 remainder).

use std::path::{Path, PathBuf};
use std::process::Command;

use crate::error::CoreError;
use crate::store::JavaSource;

/// A discovered Java runtime on the host machine.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DiscoveredJvm {
    /// Path to the `java` / `java.exe` executable.
    pub path: PathBuf,
    /// Vendor string when `java -version` reports one.
    pub vendor: Option<String>,
    /// Parsed version (e.g. `21.0.3` or `1.8.0_392`).
    pub version: String,
    /// Architecture guess from path / version output.
    pub arch: String,
    /// Always [`JavaSource::Detected`] for system discovery.
    pub source: JavaSource,
}

/// Scans common install locations and `PATH` for usable JVMs.
///
/// # Errors
///
/// Never fails hard for missing dirs; only IO errors while reading PATH dirs.
pub fn discover_system_jvms() -> Result<Vec<DiscoveredJvm>, CoreError> {
    let mut candidates: Vec<PathBuf> = Vec::new();
    push_path_java(&mut candidates);
    push_common_roots(&mut candidates);

    let mut found = Vec::new();
    let mut seen = std::collections::BTreeSet::new();
    for exe in candidates {
        let Ok(canon) = exe.canonicalize() else {
            continue;
        };
        if !seen.insert(canon.clone()) {
            continue;
        }
        if let Some(jvm) = probe_java(&canon) {
            found.push(jvm);
        }
    }
    found.sort_by(|a, b| b.version.cmp(&a.version).then(a.path.cmp(&b.path)));
    Ok(found)
}

fn push_path_java(out: &mut Vec<PathBuf>) {
    let Some(path) = std::env::var_os("PATH") else {
        return;
    };
    for dir in std::env::split_paths(&path) {
        let exe = if cfg!(windows) {
            dir.join("java.exe")
        } else {
            dir.join("java")
        };
        if exe.is_file() {
            out.push(exe);
        }
    }
}

fn push_common_roots(out: &mut Vec<PathBuf>) {
    let mut roots = Vec::new();
    if cfg!(windows) {
        if let Some(pf) = std::env::var_os("ProgramFiles") {
            roots.push(PathBuf::from(pf).join("Java"));
            roots.push(PathBuf::from(std::env::var_os("ProgramFiles").unwrap()).join("Eclipse Adoptium"));
            roots.push(PathBuf::from(std::env::var_os("ProgramFiles").unwrap()).join("Microsoft"));
        }
        if let Some(pf86) = std::env::var_os("ProgramFiles(x86)") {
            roots.push(PathBuf::from(pf86).join("Java"));
        }
    } else {
        roots.push(PathBuf::from("/usr/lib/jvm"));
        roots.push(PathBuf::from("/Library/Java/JavaVirtualMachines"));
    }
    for root in roots {
        if !root.is_dir() {
            continue;
        }
        let Ok(entries) = std::fs::read_dir(&root) else {
            continue;
        };
        for entry in entries.flatten() {
            let bin = entry.path().join("bin").join(if cfg!(windows) { "java.exe" } else { "java" });
            if bin.is_file() {
                out.push(bin);
            }
            // Adoptium layout: jdk-21.x/bin/java.exe already covered; also jre
            let bin2 = entry
                .path()
                .join("Contents")
                .join("Home")
                .join("bin")
                .join("java");
            if bin2.is_file() {
                out.push(bin2);
            }
        }
    }
}

/// Probes a java executable with `-version`.
pub fn probe_java(exe: &Path) -> Option<DiscoveredJvm> {
    let output = Command::new(exe).arg("-version").output().ok()?;
    // java -version writes to stderr by convention.
    let text = String::from_utf8_lossy(&output.stderr);
    let text = if text.trim().is_empty() {
        String::from_utf8_lossy(&output.stdout).into_owned()
    } else {
        text.into_owned()
    };
    let version = parse_java_version(&text)?;
    let vendor = parse_java_vendor(&text);
    let arch = if text.to_ascii_lowercase().contains("64-bit")
        || text.contains("amd64")
        || text.contains("x86_64")
    {
        "x86_64".to_owned()
    } else if text.contains("aarch64") || text.contains("arm64") {
        "arm64".to_owned()
    } else {
        "x86".to_owned()
    };
    Some(DiscoveredJvm {
        path: exe.to_path_buf(),
        vendor,
        version,
        arch,
        source: JavaSource::Detected,
    })
}

fn parse_java_version(text: &str) -> Option<String> {
    // openjdk version "21.0.3"  or java version "1.8.0_392"
    for line in text.lines() {
        if let Some(start) = line.find('"') {
            let rest = &line[start + 1..];
            if let Some(end) = rest.find('"') {
                let v = &rest[..end];
                if !v.is_empty() {
                    return Some(v.to_owned());
                }
            }
        }
    }
    None
}

fn parse_java_vendor(text: &str) -> Option<String> {
    let lower = text.to_ascii_lowercase();
    if lower.contains("temurin") || lower.contains("adoptium") {
        Some("Eclipse Adoptium".into())
    } else if lower.contains("openjdk") {
        Some("OpenJDK".into())
    } else if lower.contains("oracle") {
        Some("Oracle".into())
    } else if lower.contains("microsoft") {
        Some("Microsoft".into())
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_version_from_openjdk_banner() {
        let banner = r#"openjdk version "21.0.3" 2024-04-16
OpenJDK Runtime Environment Temurin-21.0.3+9 (build 21.0.3+9-LTS)
OpenJDK 64-Bit Server VM Temurin-21.0.3+9 (build 21.0.3+9-LTS, mixed mode, sharing)"#;
        assert_eq!(parse_java_version(banner).as_deref(), Some("21.0.3"));
        assert_eq!(parse_java_vendor(banner).as_deref(), Some("Eclipse Adoptium"));
    }

    #[test]
    fn parse_legacy_1_8() {
        let banner = r#"java version "1.8.0_392"
Java(TM) SE Runtime Environment (build 1.8.0_392-b08)
Java HotSpot(TM) 64-Bit Server VM (build 25.392-b08, mixed mode)"#;
        assert_eq!(parse_java_version(banner).as_deref(), Some("1.8.0_392"));
    }
}
