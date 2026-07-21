-- Omni-Framework Phase 6 — Mod Census schema.
--
-- Separate from the vivisection DB (omni_intel.db / ADR-0004). That DB is a
-- pure rebuild of hand-authored TOML. This DB is network-sourced, resumable,
-- and re-tierable without re-fetching (ADR-0022). Never edit rows by hand;
-- tools/census_index.py is the only writer.
--
-- Target game version for Phase 6 is Minecraft 1.21.1 (TGT-1). Rows may later
-- hold other versions; the ranked export always filters to the run's target.

PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

-- One row per platform project that is a candidate for the census.
CREATE TABLE IF NOT EXISTS projects (
    id                    INTEGER PRIMARY KEY,
    platform              TEXT NOT NULL CHECK (platform IN ('modrinth', 'curseforge')),
    platform_project_id   TEXT NOT NULL,              -- slug or numeric id as string
    slug                  TEXT NOT NULL,
    title                 TEXT NOT NULL,
    description           TEXT NOT NULL DEFAULT '',
    author                TEXT NOT NULL DEFAULT '',
    license_id            TEXT NOT NULL DEFAULT '',
    project_type          TEXT NOT NULL DEFAULT 'mod', -- mod / modpack / resourcepack / ...
    categories_json       TEXT NOT NULL DEFAULT '[]',
    loaders_json          TEXT NOT NULL DEFAULT '[]',  -- fabric, quilt, neoforge, forge, ...
    game_versions_json    TEXT NOT NULL DEFAULT '[]',  -- all versions the project claims
    side                  TEXT NOT NULL DEFAULT 'unknown'
                          CHECK (side IN ('client', 'server', 'both', 'unknown')),
    downloads             INTEGER NOT NULL DEFAULT 0,
    followers             INTEGER NOT NULL DEFAULT 0,
    date_created          TEXT NOT NULL DEFAULT '',    -- ISO-8601
    date_modified         TEXT NOT NULL DEFAULT '',    -- ISO-8601
    source_url            TEXT NOT NULL DEFAULT '',
    issues_url            TEXT NOT NULL DEFAULT '',
    discord_url           TEXT NOT NULL DEFAULT '',
    wiki_url              TEXT NOT NULL DEFAULT '',
    icon_url              TEXT NOT NULL DEFAULT '',
    -- CurseForge-only: whether third-party launchers may distribute files.
    -- NULL on Modrinth (always permitted via public CDN). False = opted out:
    -- census KEPT for outreach ranking, downloads MUST go through official app
    -- (COMPLIANCE §5, ADR-0003 posture).
    allow_distribution    INTEGER,                     -- 0/1/NULL
    has_target_version    INTEGER NOT NULL DEFAULT 0,  -- 1 if any file for target MC
    target_file_count     INTEGER NOT NULL DEFAULT 0,
    raw_json              TEXT NOT NULL DEFAULT '',    -- last platform payload (debug)
    fetched_at            TEXT NOT NULL,               -- ISO-8601 when row was written
    UNIQUE (platform, platform_project_id)
);

CREATE INDEX IF NOT EXISTS idx_projects_slug ON projects(slug);
CREATE INDEX IF NOT EXISTS idx_projects_target ON projects(has_target_version);
CREATE INDEX IF NOT EXISTS idx_projects_downloads ON projects(downloads DESC);

-- Per-project score breakdown. Recomputed by the tierer; never fetched.
CREATE TABLE IF NOT EXISTS scores (
    project_id     INTEGER PRIMARY KEY REFERENCES projects(id) ON DELETE CASCADE,
    completeness   REAL NOT NULL CHECK (completeness BETWEEN 0 AND 100),
    support        REAL NOT NULL CHECK (support BETWEEN 0 AND 100),
    quality        REAL NOT NULL CHECK (quality BETWEEN 0 AND 100),
    composite      REAL NOT NULL CHECK (composite BETWEEN 0 AND 100),
    tier           TEXT NOT NULL CHECK (tier IN ('S', 'A', 'B', 'C', 'D')),
    rank           INTEGER,                            -- 1 = best among target-version set
    scored_at      TEXT NOT NULL,
    rationale_json TEXT NOT NULL DEFAULT '{}'          -- component contributions for audit
);

CREATE INDEX IF NOT EXISTS idx_scores_rank ON scores(rank);
CREATE INDEX IF NOT EXISTS idx_scores_tier ON scores(tier);

-- Cross-platform identity links (same mod on both stores). Brick-1 links by
-- exact slug match (case-insensitive); later bricks may add hash/name links.
CREATE TABLE IF NOT EXISTS project_links (
    id            INTEGER PRIMARY KEY,
    left_id       INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    right_id      INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    method        TEXT NOT NULL CHECK (method IN ('slug', 'manual')),
    confidence    REAL NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    UNIQUE (left_id, right_id),
    CHECK (left_id < right_id)
);

-- Resumable fetch checkpoints (platform page cursors). Survives interrupt.
CREATE TABLE IF NOT EXISTS fetch_state (
    platform      TEXT NOT NULL,
    target_version TEXT NOT NULL,
    cursor_json   TEXT NOT NULL DEFAULT '{}',
    status        TEXT NOT NULL CHECK (status IN ('running', 'done', 'failed')),
    message       TEXT NOT NULL DEFAULT '',
    updated_at    TEXT NOT NULL,
    PRIMARY KEY (platform, target_version)
);

-- Ranked census export snapshot (the ≤50_000 candidate set for one run).
CREATE TABLE IF NOT EXISTS census_snapshot (
    id             INTEGER PRIMARY KEY,
    target_version TEXT NOT NULL,
    generated_at   TEXT NOT NULL,
    cap            INTEGER NOT NULL,
    candidate_count INTEGER NOT NULL,
    notes          TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS census_snapshot_rows (
    snapshot_id  INTEGER NOT NULL REFERENCES census_snapshot(id) ON DELETE CASCADE,
    rank         INTEGER NOT NULL,
    project_id   INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    tier         TEXT NOT NULL,
    composite    REAL NOT NULL,
    PRIMARY KEY (snapshot_id, rank)
);
