-- Omni-Framework Phase 7 — Port queue schema (ADR-0023).
--
-- Separate from vivisection (omni_intel.db) and census (omni_census.db).
-- Writers: tools/port_queue.py only. Never hand-edit rows for status.
--
-- Pipeline (ROADMAP Phase 7 / ADR-0003):
--   intake(census S/A…) → license classify → (if restrictive) outreach
--   → author decision → native port (later brick) → verify → publish
-- Hard "no" permanently blocks native porting of that project.

PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

-- One row per (platform, platform_project_id) considered for native porting.
CREATE TABLE IF NOT EXISTS port_queue (
    id                   INTEGER PRIMARY KEY,
    platform             TEXT NOT NULL CHECK (platform IN ('modrinth', 'curseforge')),
    platform_project_id  TEXT NOT NULL,
    slug                 TEXT NOT NULL,
    title                TEXT NOT NULL,
    author               TEXT NOT NULL DEFAULT '',
    license_id           TEXT NOT NULL DEFAULT '',
    license_class        TEXT NOT NULL DEFAULT 'unknown'
                         CHECK (license_class IN (
                             'permissive',      -- MIT/Apache/BSD/… → port allowed
                             'weak_copyleft',   -- LGPL/MPL/… → port with conditions
                             'strong_copyleft', -- GPL/AGPL → needs care + often outreach
                             'all_rights',      -- ARR / proprietary / CF All Rights Reserved
                             'unknown',         -- empty or unrecognised SPDX-ish string
                             'forbidden'        -- hard policy block (not from license alone)
                         )),
    port_eligible        INTEGER NOT NULL DEFAULT 0,  -- 1 if native port may proceed
    requires_outreach    INTEGER NOT NULL DEFAULT 0,  -- 1 if author yes required first
    allow_distribution   INTEGER,                     -- CF opt-out; NULL = n/a
    census_tier          TEXT NOT NULL DEFAULT '',
    census_rank          INTEGER,
    census_composite     REAL,
    downloads            INTEGER NOT NULL DEFAULT 0,
    source_url           TEXT NOT NULL DEFAULT '',
    issues_url           TEXT NOT NULL DEFAULT '',
    status               TEXT NOT NULL DEFAULT 'queued'
                         CHECK (status IN (
                             'queued',
                             'license_review',
                             'outreach_pending',
                             'outreach_sent',
                             'awaiting_author',
                             'permitted',
                             'refused',          -- hard no — permanent (ADR-0003)
                             'compat_only',      -- stay on compat layers only
                             'porting',
                             'verifying',
                             'published',
                             'blocked'
                         )),
    status_note          TEXT NOT NULL DEFAULT '',
    rationale_json       TEXT NOT NULL DEFAULT '{}',
    ingested_at          TEXT NOT NULL,
    updated_at           TEXT NOT NULL,
    UNIQUE (platform, platform_project_id)
);

CREATE INDEX IF NOT EXISTS idx_port_status ON port_queue(status);
CREATE INDEX IF NOT EXISTS idx_port_tier ON port_queue(census_tier);
CREATE INDEX IF NOT EXISTS idx_port_eligible ON port_queue(port_eligible);
CREATE INDEX IF NOT EXISTS idx_port_class ON port_queue(license_class);

-- Author outreach trail (COMPLIANCE § mods / ADR-0003).
CREATE TABLE IF NOT EXISTS outreach (
    id              INTEGER PRIMARY KEY,
    queue_id         INTEGER NOT NULL REFERENCES port_queue(id) ON DELETE CASCADE,
    channel         TEXT NOT NULL DEFAULT 'email'
                    CHECK (channel IN ('email', 'github', 'discord', 'curseforge', 'other')),
    template_id     TEXT NOT NULL DEFAULT 'port_permission_v1',
    subject         TEXT NOT NULL DEFAULT '',
    body            TEXT NOT NULL DEFAULT '',
    sent_at         TEXT NOT NULL DEFAULT '',   -- empty = draft only
    response_at     TEXT NOT NULL DEFAULT '',
    response        TEXT NOT NULL DEFAULT ''
                    CHECK (response IN ('', 'yes', 'no', 'conditional', 'no_reply')),
    response_note   TEXT NOT NULL DEFAULT '',
    created_at      TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_outreach_queue ON outreach(queue_id);

-- Frozen intake batches from a census export (audit of what Phase 7 saw).
CREATE TABLE IF NOT EXISTS intake_batches (
    id              INTEGER PRIMARY KEY,
    label           TEXT NOT NULL UNIQUE,
    census_db_path  TEXT NOT NULL,
    target_version  TEXT NOT NULL,
    tiers           TEXT NOT NULL,              -- e.g. "S,A"
    row_count       INTEGER NOT NULL,
    created_at      TEXT NOT NULL,
    notes           TEXT NOT NULL DEFAULT ''
);
