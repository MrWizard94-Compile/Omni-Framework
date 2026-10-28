//! OmniLoader → launcher issue dump handoff (LDR-10).
//!
//! Boot writes `game/logs/omniloader-issues.json` under the instance game
//! directory (same folder Minecraft uses for logs). The launcher reads that
//! path for the boot-failure tree UI.

use std::path::{Path, PathBuf};

use crate::app::BootIssueDto;
use crate::error::CoreError;
use crate::install::Paths;

/// File name written by OmniLoader bootstrap.
pub const ISSUE_DUMP_FILE: &str = "omniloader-issues.json";

/// Absolute path where OmniLoader dumps issues for `instance_id`.
#[must_use]
pub fn issue_dump_path(paths: &Paths, instance_id: i64) -> PathBuf {
    paths.game_dir(instance_id).join("logs").join(ISSUE_DUMP_FILE)
}

/// Parses an OmniLoader issue dump JSON array into DTOs.
///
/// # Errors
///
/// [`CoreError::Io`] when the file exists but cannot be read or parsed.
pub fn read_issue_dump(path: &Path) -> Result<Vec<BootIssueDto>, CoreError> {
    if !path.is_file() {
        return Ok(Vec::new());
    }
    let text = std::fs::read_to_string(path).map_err(|source| CoreError::Io {
        action: "read omniloader issue dump",
        path: path.to_path_buf(),
        source,
    })?;
    parse_issue_dump_json(&text).map_err(|source| CoreError::Io {
        action: "parse omniloader issue dump",
        path: path.to_path_buf(),
        source: std::io::Error::other(source.to_string()),
    })
}

/// Parses dump JSON text (same shape as `BootSequence.writeIssueDump`).
///
/// # Errors
///
/// Serde JSON errors.
pub fn parse_issue_dump_json(text: &str) -> Result<Vec<BootIssueDto>, serde_json::Error> {
    let value: serde_json::Value = serde_json::from_str(text)?;
    let mut out = Vec::new();
    if let Some(arr) = value.as_array() {
        for item in arr {
            out.push(BootIssueDto {
                level: item.get("level").and_then(|v| v.as_str()).unwrap_or("error").into(),
                code: item.get("code").and_then(|v| v.as_str()).unwrap_or("unknown").into(),
                message: item
                    .get("message")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .into(),
                at: item.get("at").and_then(|v| v.as_str()).map(str::to_owned),
                remedy: item.get("remedy").and_then(|v| v.as_str()).map(str::to_owned),
            });
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    #[test]
    fn dump_path_is_under_game_logs_not_launcher_logs() {
        let tmp = TempDir::new().unwrap();
        let paths = Paths::new(tmp.path());
        let dump = issue_dump_path(&paths, 42);
        let s = dump.to_string_lossy().replace('\\', "/");
        assert!(s.ends_with("instances/42/game/logs/omniloader-issues.json"), "{s}");
        assert!(!s.contains("instances/42/logs/omniloader"), "must not use launcher logs_dir");
    }

    #[test]
    fn read_issue_dump_round_trips_loader_shape() {
        let tmp = TempDir::new().unwrap();
        let paths = Paths::new(tmp.path());
        let path = issue_dump_path(&paths, 7);
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        // Shape produced by BootSequence.writeIssueDump
        std::fs::write(
            &path,
            r#"[{"level":"error","code":"boot.resolve.unsat","message":"unsat","at":"mods","remedy":"remove conflicting mod"}]"#,
        )
        .unwrap();
        let issues = read_issue_dump(&path).unwrap();
        assert_eq!(issues.len(), 1);
        assert_eq!(issues[0].code, "boot.resolve.unsat");
        assert_eq!(issues[0].level, "error");
        assert_eq!(issues[0].remedy.as_deref(), Some("remove conflicting mod"));
        assert_eq!(issues[0].at.as_deref(), Some("mods"));
    }
}
