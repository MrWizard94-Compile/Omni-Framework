-- Schema v1 — OmniLauncher/ARCHITECTURE.md §2 (LNC-2, D5), realized as DDL.
-- Conventions (ADR-0015): timestamps INTEGER Unix seconds UTC; booleans INTEGER
-- 0/1; *_json columns hold JSON documents as TEXT; enum-like TEXT columns carry
-- CHECK constraints mirrored by typed Rust enums in store::types.

CREATE TABLE instances (
    id              INTEGER PRIMARY KEY,
    name            TEXT    NOT NULL,
    icon            TEXT,
    kind            TEXT    NOT NULL CHECK (kind IN ('client', 'server')),
    game_version    TEXT    NOT NULL,
    created_at      INTEGER NOT NULL,
    last_played_at  INTEGER,
    settings_json   TEXT    NOT NULL DEFAULT '{}',
    notes           TEXT    NOT NULL DEFAULT ''
);

-- Component stack (prism/multimc lesson): ordered, one entry per component uid.
CREATE TABLE components (
    instance_id  INTEGER NOT NULL REFERENCES instances(id) ON DELETE CASCADE,
    position     INTEGER NOT NULL,
    uid          TEXT    NOT NULL,
    version      TEXT    NOT NULL,
    pinned       INTEGER NOT NULL DEFAULT 0,
    source       TEXT    NOT NULL CHECK (source IN ('meta', 'custom')),
    custom_json  TEXT,
    UNIQUE (instance_id, uid),
    UNIQUE (instance_id, position)
);

-- Mods as entities (atlauncher lesson); hashes feed census + hash recovery.
CREATE TABLE mods (
    id                   INTEGER PRIMARY KEY,
    instance_id          INTEGER NOT NULL REFERENCES instances(id) ON DELETE CASCADE,
    file_name            TEXT    NOT NULL,
    sha1                 TEXT,
    sha512               TEXT,
    murmur2              TEXT,
    display_name         TEXT,
    version              TEXT,
    ecosystem            TEXT    NOT NULL
                         CHECK (ecosystem IN ('omni', 'fabric', 'neoforge', 'forge', 'unknown')),
    enabled              INTEGER NOT NULL DEFAULT 1,
    platform             TEXT,
    platform_project_id  TEXT,
    platform_file_id     TEXT,
    port_status          TEXT    NOT NULL DEFAULT 'unknown'
                         CHECK (port_status IN ('native', 'compat', 'ported', 'excluded', 'unknown')),
    UNIQUE (instance_id, file_name)
);

-- Tokens live in the OS credential vault ONLY (SOUL §36); this row stores the
-- vault reference and the CPL-2/ADR-0011 entitlement-window bookkeeping.
CREATE TABLE accounts (
    id                             INTEGER PRIMARY KEY,
    uuid                           TEXT    NOT NULL UNIQUE,
    username                       TEXT    NOT NULL,
    kind                           TEXT    NOT NULL CHECK (kind IN ('microsoft')),
    vault_ref                      TEXT    NOT NULL,
    entitlement_verified_at        INTEGER,
    entitlement_window_expires_at  INTEGER
);

CREATE TABLE java_runtimes (
    id        INTEGER PRIMARY KEY,
    path      TEXT    NOT NULL UNIQUE,
    vendor    TEXT,
    version   TEXT    NOT NULL,
    arch      TEXT    NOT NULL,
    source    TEXT    NOT NULL CHECK (source IN ('detected', 'mojang-manifest')),
    valid_at  INTEGER
);

-- Game process sessions (modrinth-app lesson): who ran, when, how it ended.
CREATE TABLE processes (
    id            INTEGER PRIMARY KEY,
    instance_id   INTEGER NOT NULL REFERENCES instances(id) ON DELETE CASCADE,
    pid           INTEGER,
    started_at    INTEGER NOT NULL,
    ended_at      INTEGER,
    exit_code     INTEGER,
    session_json  TEXT    NOT NULL DEFAULT '{}'
);

CREATE TABLE settings (
    key         TEXT PRIMARY KEY,
    value_json  TEXT NOT NULL
);

-- Resumable jobs (modrinth-app install-recovery lesson): resume_json is the
-- exact point to continue from; progress_json is what the UI renders.
CREATE TABLE jobs (
    id             INTEGER PRIMARY KEY,
    kind           TEXT    NOT NULL,
    instance_id    INTEGER REFERENCES instances(id) ON DELETE SET NULL,
    state          TEXT    NOT NULL DEFAULT 'pending'
                   CHECK (state IN ('pending', 'running', 'paused', 'failed', 'done')),
    progress_json  TEXT    NOT NULL DEFAULT '{}',
    resume_json    TEXT,
    error_json     TEXT,
    created_at     INTEGER NOT NULL,
    updated_at     INTEGER NOT NULL
);

CREATE INDEX idx_components_instance ON components (instance_id, position);
CREATE INDEX idx_mods_instance       ON mods (instance_id);
CREATE INDEX idx_mods_sha1           ON mods (sha1);
CREATE INDEX idx_processes_instance  ON processes (instance_id);
CREATE INDEX idx_jobs_state          ON jobs (state);
