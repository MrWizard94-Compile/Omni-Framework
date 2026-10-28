//! Server instance port / config surface (LNC-8 remainder).

use std::collections::BTreeMap;
use std::fs;
use std::path::PathBuf;

use crate::error::CoreError;
use crate::install::Paths;

/// Key server.properties settings exposed in the UI.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ServerConfig {
    /// `server-port` (default 25565).
    pub port: u16,
    /// `motd`.
    pub motd: String,
    /// `max-players`.
    pub max_players: u32,
    /// `online-mode`.
    pub online_mode: bool,
    /// `difficulty`.
    pub difficulty: String,
    /// `gamemode`.
    pub gamemode: String,
    /// `level-name`.
    pub level_name: String,
    /// Full properties map (including keys not modeled above).
    pub raw: BTreeMap<String, String>,
}

impl Default for ServerConfig {
    fn default() -> Self {
        Self {
            port: 25565,
            motd: "A Minecraft Server".into(),
            max_players: 20,
            online_mode: true,
            difficulty: "easy".into(),
            gamemode: "survival".into(),
            level_name: "world".into(),
            raw: BTreeMap::new(),
        }
    }
}

/// Path to `server.properties` for an instance.
#[must_use]
pub fn server_properties_path(paths: &Paths, instance_id: i64) -> PathBuf {
    paths.game_dir(instance_id).join("server.properties")
}

/// Loads server.properties (defaults when missing).
///
/// # Errors
///
/// [`CoreError::Io`] on read failure of an existing file.
pub fn load_server_config(paths: &Paths, instance_id: i64) -> Result<ServerConfig, CoreError> {
    let path = server_properties_path(paths, instance_id);
    if !path.is_file() {
        return Ok(ServerConfig::default());
    }
    let text = fs::read_to_string(&path).map_err(|source| CoreError::Io {
        action: "read server.properties",
        path: path.clone(),
        source,
    })?;
    Ok(parse_properties(&text))
}

/// Writes server.properties from a config (merges into existing raw keys).
///
/// # Errors
///
/// [`CoreError::Io`] on write failure.
pub fn save_server_config(
    paths: &Paths,
    instance_id: i64,
    config: &ServerConfig,
) -> Result<(), CoreError> {
    let path = server_properties_path(paths, instance_id);
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|source| CoreError::Io {
            action: "create server game dir",
            path: parent.to_path_buf(),
            source,
        })?;
    }
    let mut map = config.raw.clone();
    map.insert("server-port".into(), config.port.to_string());
    map.insert("motd".into(), config.motd.clone());
    map.insert("max-players".into(), config.max_players.to_string());
    map.insert("online-mode".into(), config.online_mode.to_string());
    map.insert("difficulty".into(), config.difficulty.clone());
    map.insert("gamemode".into(), config.gamemode.clone());
    map.insert("level-name".into(), config.level_name.clone());
    let text = write_properties(&map);
    fs::write(&path, text).map_err(|source| CoreError::Io {
        action: "write server.properties",
        path,
        source,
    })?;
    Ok(())
}

fn parse_properties(text: &str) -> ServerConfig {
    let mut raw = BTreeMap::new();
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        if let Some((k, v)) = line.split_once('=') {
            raw.insert(k.trim().to_owned(), v.trim().to_owned());
        }
    }
    let get = |key: &str| raw.get(key).cloned();
    ServerConfig {
        port: get("server-port").and_then(|s| s.parse().ok()).unwrap_or(25565),
        motd: get("motd").unwrap_or_else(|| "A Minecraft Server".into()),
        max_players: get("max-players").and_then(|s| s.parse().ok()).unwrap_or(20),
        online_mode: get("online-mode").is_none_or(|s| s == "true"),
        difficulty: get("difficulty").unwrap_or_else(|| "easy".into()),
        gamemode: get("gamemode").unwrap_or_else(|| "survival".into()),
        level_name: get("level-name").unwrap_or_else(|| "world".into()),
        raw,
    }
}

fn write_properties(map: &BTreeMap<String, String>) -> String {
    let mut lines = Vec::new();
    lines.push("#Minecraft server properties — managed by OmniLauncher".to_owned());
    for (k, v) in map {
        lines.push(format!("{k}={v}"));
    }
    lines.push(String::new());
    lines.join("\n")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::install::Paths;
    use tempfile::TempDir;

    #[test]
    fn round_trip_port_and_motd() {
        let tmp = TempDir::new().unwrap();
        let paths = Paths::new(tmp.path());
        let cfg = ServerConfig {
            port: 25575,
            motd: "Omni Test".into(),
            max_players: 8,
            online_mode: false,
            ..ServerConfig::default()
        };
        save_server_config(&paths, 3, &cfg).unwrap();
        let loaded = load_server_config(&paths, 3).unwrap();
        assert_eq!(loaded.port, 25575);
        assert_eq!(loaded.motd, "Omni Test");
        assert_eq!(loaded.max_players, 8);
        assert!(!loaded.online_mode);
        let text = std::fs::read_to_string(server_properties_path(&paths, 3)).unwrap();
        assert!(text.contains("server-port=25575"));
    }
}
