-- Omni-Framework Intelligence Database schema.
-- Source of truth for DB structure. The database file (omni_intel.db) is a
-- build artifact: analyze.py rebuilds it from scratch on every run from the
-- hand-audited analysis/*.toml files (ADR-0004). Never edit the DB directly.
--
-- Provenance model: every fact row carries source_keys, a comma-separated list
-- of keys that MUST resolve to sources.key rows of the same subject (enforced
-- by analyze.py at load time; kept as a validated list rather than a join
-- table to keep the hand-authored TOML humane).

PRAGMA foreign_keys = ON;

CREATE TABLE subjects (
    id            INTEGER PRIMARY KEY,
    slug          TEXT NOT NULL UNIQUE,              -- e.g. 'fabric-loader'
    name          TEXT NOT NULL,
    kind          TEXT NOT NULL CHECK (kind IN ('launcher', 'loader', 'subsystem', 'compat-layer')),
    method        TEXT NOT NULL CHECK (method IN ('open-source-deep', 'black-box')),
    repo_url      TEXT NOT NULL,                     -- upstream repo ('' for black-box subjects)
    license       TEXT NOT NULL,                     -- SPDX id or description
    pinned_commit TEXT NOT NULL,                     -- full sha of the vivisected snapshot ('' for black-box)
    language      TEXT NOT NULL,                     -- primary implementation language(s)
    summary       TEXT NOT NULL,                     -- executive summary of the subject
    analyzed_at   TEXT NOT NULL                      -- ISO date of the analysis
);

CREATE TABLE sources (
    id         INTEGER PRIMARY KEY,
    subject_id INTEGER NOT NULL REFERENCES subjects(id) ON DELETE CASCADE,
    key        TEXT NOT NULL,                        -- short handle used by fact rows
    kind       TEXT NOT NULL CHECK (kind IN ('code', 'doc', 'url')),
    ref        TEXT NOT NULL,                        -- repo-relative path (code) or URL (doc/url)
    note       TEXT NOT NULL DEFAULT '',
    retrieved  TEXT NOT NULL DEFAULT '',             -- ISO date for url/doc kinds
    UNIQUE (subject_id, key)
);

CREATE TABLE components (
    id          INTEGER PRIMARY KEY,
    subject_id  INTEGER NOT NULL REFERENCES subjects(id) ON DELETE CASCADE,
    name        TEXT NOT NULL,
    path        TEXT NOT NULL,                       -- repo-relative location of the subsystem
    role        TEXT NOT NULL,                       -- one-line responsibility
    description TEXT NOT NULL,
    source_keys TEXT NOT NULL,
    UNIQUE (subject_id, name)
);

CREATE TABLE features (
    id              INTEGER PRIMARY KEY,
    subject_id      INTEGER NOT NULL REFERENCES subjects(id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    category        TEXT NOT NULL,                   -- free-form grouping, e.g. 'mod-discovery'
    description     TEXT NOT NULL,
    maturity        TEXT NOT NULL CHECK (maturity IN ('core', 'stable', 'experimental', 'legacy')),
    future_proofing INTEGER NOT NULL CHECK (future_proofing BETWEEN 1 AND 5),
    omni_relevance  TEXT NOT NULL CHECK (omni_relevance IN ('adopt', 'adapt', 'observe', 'avoid')),
    notes           TEXT NOT NULL DEFAULT '',
    source_keys     TEXT NOT NULL,
    UNIQUE (subject_id, name)
);

CREATE TABLE auth_flows (
    id          INTEGER PRIMARY KEY,
    subject_id  INTEGER NOT NULL REFERENCES subjects(id) ON DELETE CASCADE,
    name        TEXT NOT NULL,
    description TEXT NOT NULL,
    steps       TEXT NOT NULL,                       -- ordered human-readable step list
    source_keys TEXT NOT NULL,
    UNIQUE (subject_id, name)
);

CREATE TABLE file_formats (
    id           INTEGER PRIMARY KEY,
    subject_id   INTEGER NOT NULL REFERENCES subjects(id) ON DELETE CASCADE,
    name         TEXT NOT NULL,
    format_kind  TEXT NOT NULL,                      -- json / toml / ini / cfg / binary / ...
    path_pattern TEXT NOT NULL,                      -- where instances of the format live
    description  TEXT NOT NULL,
    source_keys  TEXT NOT NULL,
    UNIQUE (subject_id, name)
);

CREATE TABLE protocols (
    id          INTEGER PRIMARY KEY,
    subject_id  INTEGER NOT NULL REFERENCES subjects(id) ON DELETE CASCADE,
    name        TEXT NOT NULL,
    endpoint    TEXT NOT NULL,                       -- URL / API surface / IPC boundary
    description TEXT NOT NULL,
    source_keys TEXT NOT NULL,
    UNIQUE (subject_id, name)
);

CREATE TABLE analysis_notes (
    id          INTEGER PRIMARY KEY,
    subject_id  INTEGER NOT NULL REFERENCES subjects(id) ON DELETE CASCADE,
    title       TEXT NOT NULL,
    body        TEXT NOT NULL,
    source_keys TEXT NOT NULL,
    UNIQUE (subject_id, title)
);

-- Cross-subject comparison views used by Phase 2 feature synthesis.
CREATE VIEW feature_matrix AS
SELECT s.slug, s.kind, f.category, f.name, f.maturity, f.future_proofing, f.omni_relevance
FROM features f JOIN subjects s ON s.id = f.subject_id
ORDER BY f.category, f.name, s.slug;

CREATE VIEW subject_stats AS
SELECT s.slug,
       (SELECT COUNT(*) FROM components  c WHERE c.subject_id = s.id) AS components,
       (SELECT COUNT(*) FROM features    f WHERE f.subject_id = s.id) AS features,
       (SELECT COUNT(*) FROM file_formats x WHERE x.subject_id = s.id) AS file_formats,
       (SELECT COUNT(*) FROM auth_flows  a WHERE a.subject_id = s.id) AS auth_flows,
       (SELECT COUNT(*) FROM protocols   p WHERE p.subject_id = s.id) AS protocols,
       (SELECT COUNT(*) FROM sources     r WHERE r.subject_id = s.id) AS sources
FROM subjects s
ORDER BY s.slug;
