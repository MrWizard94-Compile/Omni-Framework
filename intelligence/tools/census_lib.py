"""Phase 6 mod census — shared library (ADR-0022).

Network-sourced indexing of Modrinth + CurseForge into omni_census.db, with a
deterministic completeness/support/quality tier model and a ranked export
capped at ≤50_000 candidates for a target Minecraft version (TGT-1: 1.21.1).

Runtime: Python 3.11+ standard library only (ADR-0004 posture for intelligence).
"""

from __future__ import annotations

import json
import math
import os
import re
import sqlite3
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable, Mapping
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Protocol

INTEL_DIR = Path(__file__).resolve().parent.parent
CENSUS_DIR = INTEL_DIR / "census"
SCHEMA_PATH = CENSUS_DIR / "schema.sql"
DB_PATH = INTEL_DIR / "omni_census.db"
REPORT_PATH = CENSUS_DIR / "report.md"
RANKED_JSON_PATH = CENSUS_DIR / "ranked.json"

DEFAULT_TARGET = "1.21.1"
DEFAULT_CAP = 50_000
USER_AGENT = "OmniFramework-Census/0.1 (+https://wpaistudio.net; Phase-6-mod-census)"

# Composite weights (must sum to 1.0). Documented in census/README.md + ADR-0022.
WEIGHT_COMPLETENESS = 0.35
WEIGHT_SUPPORT = 0.35
WEIGHT_QUALITY = 0.30

TIER_THRESHOLDS: tuple[tuple[str, float], ...] = (
    ("S", 80.0),
    ("A", 65.0),
    ("B", 50.0),
    ("C", 35.0),
    ("D", 0.0),
)

# CurseForge constants (public; Minecraft game id and mc-mods class id).
CF_GAME_ID_MINECRAFT = 432
CF_CLASS_ID_MC_MODS = 6

# Env names for the Core API key (primary + aliases used by other Minecraft tools).
CF_ENV_API_KEY = "CURSEFORGE_API_KEY"
CF_ENV_API_KEY_ALIASES: tuple[str, ...] = (
    "CURSEFORGE_API_KEY",
    "CF_API_KEY",
    "CURSE_FORGE_API_KEY",
)
CF_ENV_API_KEY_FILE = "CURSEFORGE_API_KEY_FILE"
CF_ENV_API_KEY_FILE_ALIASES: tuple[str, ...] = (
    "CURSEFORGE_API_KEY_FILE",
    "CF_API_KEY_FILE",
)


class CensusError(RuntimeError):
    """A census operation failed in a way the operator must fix (not a 404)."""


def normalize_cf_api_key(raw: str) -> str:
    """Undo common shell/compose damage without inventing a different secret.

    Real failure modes that yield CF HTTP 403 with an empty body:
    - PowerShell double quotes eating ``$2a`` / ``$10`` out of bcrypt-shaped keys
    - Docker Compose / ``.env`` doubling ``$`` → ``$$2a$$10$$...``
    - Surrounding single/double quotes left in the value from a hand-edited file
    - Trailing CR/LF or spaces from copy-paste
    """
    key = raw.strip()
    if len(key) >= 2 and key[0] == key[-1] and key[0] in {"'", '"'}:
        key = key[1:-1].strip()
    # Compose escapes a literal $ as $$; collapse runs of $$ so a good key
    # (three single $) is unchanged while $$2a$$10$$… becomes $2a$10$….
    while "$$" in key:
        key = key.replace("$$", "$")
    return key


def describe_cf_api_key(key: str) -> str:
    """Safe fingerprint for logs/errors — never includes the secret material."""
    if not key:
        return "empty"
    prefix = key[:7] if len(key) >= 7 else key
    safe_prefix = (
        prefix
        if prefix.startswith("$2a$10") or prefix.startswith("$2y$")
        else "<non-bcrypt>"
    )
    looks = key.startswith("$2a$10$") or key.startswith("$2y$10$")
    return (
        f"len={len(key)} dollars={key.count('$')} "
        f"prefix={safe_prefix!r} looks_bcrypt={looks}"
    )


def validate_cf_api_key_shape(key: str) -> str | None:
    """Return a human fix hint if the key is clearly mangled; else None."""
    if not key:
        return "key is empty after normalization"
    if key.startswith("$$"):
        return (
            "key still starts with '$$' — Docker/compose escaping was not fully "
            "undone; prefer a key file or single-quoted PowerShell assignment"
        )
    # Canonical CF console keys are bcrypt digests: $2a$10$ + 53 chars ≈ 60 total.
    if key.startswith("$2a$10$") or key.startswith("$2y$10$"):
        if len(key) < 50:
            return (
                f"bcrypt-shaped key is too short ({describe_cf_api_key(key)}); "
                "PowerShell double quotes often eat $2a/$10 — re-set with single "
                "quotes or use --cf-api-key-file"
            )
        return None
    if key.startswith("$.") or (key.startswith("$") and not key.startswith("$2")):
        return (
            f"key looks PowerShell-mangled ({describe_cf_api_key(key)}); "
            "re-set with single quotes: $env:CURSEFORGE_API_KEY = '$2a$10$...'"
        )
    if key.startswith("cfc_pat_"):
        return (
            f"key is a cfc_pat_* token ({describe_cf_api_key(key)}), not a Core "
            "API console key ($2a$10$…). PATs often authorize only a subset of "
            "endpoints (e.g. categories) and 403 on /mods/search — use the key "
            "from https://console.curseforge.com/"
        )
    if len(key) < 20:
        return f"key is suspiciously short ({describe_cf_api_key(key)})"
    return None


def _read_cf_key_file(path: Path) -> str:
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise CensusError(f"cannot read CurseForge API key file {path}: {exc}") from exc
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        return normalize_cf_api_key(stripped)
    raise CensusError(f"CurseForge API key file {path} has no non-empty key line")


def resolve_cf_api_key(
    explicit: str | None = None,
    *,
    key_file: str | Path | None = None,
) -> str | None:
    """Resolve a CF API key from CLI / file / env. Returns None if unset.

    Order: explicit string → key file path / env file aliases → env key aliases.
    """
    if explicit is not None and str(explicit).strip():
        return normalize_cf_api_key(str(explicit))

    file_candidates: list[Path] = []
    if key_file is not None and str(key_file).strip():
        file_candidates.append(Path(str(key_file)).expanduser())
    for env_name in CF_ENV_API_KEY_FILE_ALIASES:
        raw = os.environ.get(env_name)
        if raw and raw.strip():
            file_candidates.append(Path(raw.strip()).expanduser())
    explicit_file = bool(key_file is not None and str(key_file).strip())
    for path in file_candidates:
        if path.is_file():
            return _read_cf_key_file(path)
        if explicit_file:
            raise CensusError(f"CurseForge API key file not found: {path}")

    for env_name in CF_ENV_API_KEY_ALIASES:
        raw = os.environ.get(env_name)
        if raw and raw.strip():
            return normalize_cf_api_key(raw)
    return None


def require_cf_api_key(
    explicit: str | None = None,
    *,
    key_file: str | Path | None = None,
) -> str:
    """Like resolve_cf_api_key but raises when missing; hard-fails mangled shapes."""
    key = resolve_cf_api_key(explicit, key_file=key_file)
    if not key:
        raise CensusError(
            f"CurseForge requires {CF_ENV_API_KEY} (or CF_API_KEY) in the "
            "environment, --cf-api-key, or --cf-api-key-file "
            "(https://console.curseforge.com/). The key is never logged or stored."
        )
    hint = validate_cf_api_key_shape(key)
    if hint and (key.startswith("$.") or key.startswith("$$") or len(key) < 20):
        raise CensusError(
            f"CurseForge API key looks unusable before any request ({hint}). "
            f"Fingerprint: {describe_cf_api_key(key)}"
        )
    return key


class HttpTransport(Protocol):
    """Minimal HTTP GET/POST surface so tests inject recorded responses."""

    def request(
        self,
        method: str,
        url: str,
        *,
        headers: Mapping[str, str] | None = None,
        body: bytes | None = None,
        timeout: float = 60.0,
    ) -> tuple[int, dict[str, str], bytes]:
        """Return (status, response_headers, body_bytes)."""


@dataclass
class RateLimit:
    """Token-bucket style minimum interval between requests to one host."""

    min_interval_s: float = 0.25
    _last: float = field(default=0.0, init=False, repr=False)

    def wait(self) -> None:
        now = time.monotonic()
        delay = self.min_interval_s - (now - self._last)
        if delay > 0:
            time.sleep(delay)
        self._last = time.monotonic()


class UrllibTransport:
    """Production transport: urllib with retries on 429/5xx."""

    def __init__(
        self,
        *,
        user_agent: str = USER_AGENT,
        max_retries: int = 5,
        rate_limit: RateLimit | None = None,
    ) -> None:
        self.user_agent = user_agent
        self.max_retries = max_retries
        self.rate_limit = rate_limit or RateLimit()

    def request(
        self,
        method: str,
        url: str,
        *,
        headers: Mapping[str, str] | None = None,
        body: bytes | None = None,
        timeout: float = 60.0,
    ) -> tuple[int, dict[str, str], bytes]:
        hdrs = {"User-Agent": self.user_agent, "Accept": "application/json"}
        if headers:
            hdrs.update(headers)
        last_err: Exception | None = None
        for attempt in range(self.max_retries):
            self.rate_limit.wait()
            req = urllib.request.Request(url, data=body, headers=hdrs, method=method)
            try:
                with urllib.request.urlopen(req, timeout=timeout) as resp:
                    status = int(resp.status)
                    resp_headers = {k.lower(): v for k, v in resp.headers.items()}
                    data = resp.read()
                    return status, resp_headers, data
            except urllib.error.HTTPError as exc:
                last_err = exc
                payload = exc.read() if exc.fp is not None else b""
                if exc.code in {429, 500, 502, 503, 504} and attempt + 1 < self.max_retries:
                    retry_after = exc.headers.get("Retry-After") if exc.headers else None
                    try:
                        sleep_s = float(retry_after) if retry_after else 2.0 ** attempt
                    except ValueError:
                        sleep_s = 2.0 ** attempt
                    time.sleep(min(sleep_s, 60.0))
                    continue
                resp_headers = (
                    {k.lower(): v for k, v in exc.headers.items()} if exc.headers else {}
                )
                return int(exc.code), resp_headers, payload
            except urllib.error.URLError as exc:
                last_err = exc
                if attempt + 1 < self.max_retries:
                    time.sleep(2.0 ** attempt)
                    continue
                raise CensusError(f"network error for {url}: {exc}") from exc
        raise CensusError(f"exhausted retries for {url}: {last_err}")


@dataclass(frozen=True)
class ProjectRecord:
    """Normalized project row ready for upsert into the census DB."""

    platform: str
    platform_project_id: str
    slug: str
    title: str
    description: str
    author: str
    license_id: str
    project_type: str
    categories: tuple[str, ...]
    loaders: tuple[str, ...]
    game_versions: tuple[str, ...]
    side: str
    downloads: int
    followers: int
    date_created: str
    date_modified: str
    source_url: str
    issues_url: str
    discord_url: str
    wiki_url: str
    icon_url: str
    allow_distribution: bool | None
    has_target_version: bool
    target_file_count: int
    raw_json: str


@dataclass(frozen=True)
class ScoreBreakdown:
    completeness: float
    support: float
    quality: float
    composite: float
    tier: str
    rationale: dict[str, Any]


@dataclass(frozen=True)
class FileRecord:
    """One published file/version artifact belonging to a census project."""

    platform_file_id: str
    filename: str
    game_versions: tuple[str, ...]
    loaders: tuple[str, ...]
    sha1: str
    sha512: str
    murmur2: str
    downloads: int
    date_published: str
    raw_json: str


def utc_now_iso() -> str:
    return datetime.now(UTC).replace(microsecond=0).isoformat()


def _table_columns(conn: sqlite3.Connection, table: str) -> set[str]:
    return {str(row[1]) for row in conn.execute(f"PRAGMA table_info({table})")}


def migrate_census_schema(conn: sqlite3.Connection) -> None:
    """Bring an existing census DB up to the current schema without a wipe.

    CREATE IF NOT EXISTS covers new tables. Column adds and CHECK expansions
    need explicit migration because SQLite will not alter them in place.
    """
    if "projects" in {
        str(r[0]) for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")
    }:
        cols = _table_columns(conn, "projects")
        if "enriched_at" not in cols:
            conn.execute(
                "ALTER TABLE projects ADD COLUMN enriched_at TEXT NOT NULL DEFAULT ''"
            )

    # Expand project_links.method CHECK (slug → +hash +name_author).
    row = conn.execute(
        "SELECT sql FROM sqlite_master WHERE type='table' AND name='project_links'"
    ).fetchone()
    if row is not None:
        sql = str(row[0] or "")
        if "name_author" not in sql or "evidence" not in sql:
            conn.execute("ALTER TABLE project_links RENAME TO project_links_legacy")
            conn.execute(
                """
                CREATE TABLE project_links (
                    id            INTEGER PRIMARY KEY,
                    left_id       INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
                    right_id      INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
                    method        TEXT NOT NULL CHECK (
                                      method IN ('slug', 'hash', 'name_author', 'manual')
                                  ),
                    confidence    REAL NOT NULL CHECK (confidence BETWEEN 0 AND 1),
                    evidence      TEXT NOT NULL DEFAULT '',
                    UNIQUE (left_id, right_id),
                    CHECK (left_id < right_id)
                )
                """
            )
            legacy_cols = _table_columns(conn, "project_links_legacy")
            if "evidence" in legacy_cols:
                conn.execute(
                    """
                    INSERT INTO project_links(id, left_id, right_id, method, confidence, evidence)
                    SELECT id, left_id, right_id, method, confidence, evidence
                    FROM project_links_legacy
                    """
                )
            else:
                conn.execute(
                    """
                    INSERT INTO project_links(id, left_id, right_id, method, confidence, evidence)
                    SELECT id, left_id, right_id, method, confidence, ''
                    FROM project_links_legacy
                    """
                )
            conn.execute("DROP TABLE project_links_legacy")


def open_census_db(path: Path = DB_PATH) -> sqlite3.Connection:
    """Open (or create) the census DB and apply schema + migrations."""
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    # Column adds must happen *before* schema.sql indexes that reference them;
    # CREATE TABLE IF NOT EXISTS will not alter an existing projects table.
    tables = {
        str(r[0]) for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")
    }
    if "projects" in tables and "enriched_at" not in _table_columns(conn, "projects"):
        conn.execute(
            "ALTER TABLE projects ADD COLUMN enriched_at TEXT NOT NULL DEFAULT ''"
        )
        conn.commit()
    schema = SCHEMA_PATH.read_text(encoding="utf-8")
    conn.executescript(schema)
    migrate_census_schema(conn)
    conn.commit()
    return conn


def set_meta(conn: sqlite3.Connection, key: str, value: str) -> None:
    conn.execute(
        "INSERT INTO meta(key, value) VALUES (?, ?) "
        "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        (key, value),
    )


def get_meta(conn: sqlite3.Connection, key: str) -> str | None:
    row = conn.execute("SELECT value FROM meta WHERE key = ?", (key,)).fetchone()
    return None if row is None else str(row["value"])


def upsert_project(conn: sqlite3.Connection, project: ProjectRecord, fetched_at: str) -> int:
    """Insert or replace a project; return its row id."""
    conn.execute(
        """
        INSERT INTO projects (
            platform, platform_project_id, slug, title, description, author,
            license_id, project_type, categories_json, loaders_json, game_versions_json,
            side, downloads, followers, date_created, date_modified,
            source_url, issues_url, discord_url, wiki_url, icon_url,
            allow_distribution, has_target_version, target_file_count,
            raw_json, fetched_at
        ) VALUES (
            ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?
        )
        ON CONFLICT(platform, platform_project_id) DO UPDATE SET
            slug = excluded.slug,
            title = excluded.title,
            description = excluded.description,
            author = excluded.author,
            license_id = excluded.license_id,
            project_type = excluded.project_type,
            categories_json = excluded.categories_json,
            loaders_json = excluded.loaders_json,
            game_versions_json = excluded.game_versions_json,
            side = excluded.side,
            downloads = excluded.downloads,
            followers = excluded.followers,
            date_created = excluded.date_created,
            date_modified = excluded.date_modified,
            source_url = excluded.source_url,
            issues_url = excluded.issues_url,
            discord_url = excluded.discord_url,
            wiki_url = excluded.wiki_url,
            icon_url = excluded.icon_url,
            allow_distribution = excluded.allow_distribution,
            has_target_version = excluded.has_target_version,
            target_file_count = excluded.target_file_count,
            raw_json = excluded.raw_json,
            fetched_at = excluded.fetched_at
        """,
        (
            project.platform,
            project.platform_project_id,
            project.slug,
            project.title,
            project.description,
            project.author,
            project.license_id,
            project.project_type,
            json.dumps(list(project.categories), ensure_ascii=False),
            json.dumps(list(project.loaders), ensure_ascii=False),
            json.dumps(list(project.game_versions), ensure_ascii=False),
            project.side,
            project.downloads,
            project.followers,
            project.date_created,
            project.date_modified,
            project.source_url,
            project.issues_url,
            project.discord_url,
            project.wiki_url,
            project.icon_url,
            None
            if project.allow_distribution is None
            else (1 if project.allow_distribution else 0),
            1 if project.has_target_version else 0,
            project.target_file_count,
            project.raw_json,
            fetched_at,
        ),
    )
    row = conn.execute(
        "SELECT id FROM projects WHERE platform = ? AND platform_project_id = ?",
        (project.platform, project.platform_project_id),
    ).fetchone()
    assert row is not None
    return int(row["id"])


def upsert_file(conn: sqlite3.Connection, project_id: int, file_rec: FileRecord) -> None:
    """Insert or replace one project file/hash row."""
    conn.execute(
        """
        INSERT INTO project_files (
            project_id, platform_file_id, filename, game_versions_json, loaders_json,
            sha1, sha512, murmur2, downloads, date_published, raw_json
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(project_id, platform_file_id) DO UPDATE SET
            filename = excluded.filename,
            game_versions_json = excluded.game_versions_json,
            loaders_json = excluded.loaders_json,
            sha1 = excluded.sha1,
            sha512 = excluded.sha512,
            murmur2 = excluded.murmur2,
            downloads = excluded.downloads,
            date_published = excluded.date_published,
            raw_json = excluded.raw_json
        """,
        (
            project_id,
            file_rec.platform_file_id,
            file_rec.filename,
            json.dumps(list(file_rec.game_versions), ensure_ascii=False),
            json.dumps(list(file_rec.loaders), ensure_ascii=False),
            file_rec.sha1.lower(),
            file_rec.sha512.lower(),
            file_rec.murmur2,
            file_rec.downloads,
            file_rec.date_published,
            file_rec.raw_json,
        ),
    )


def mark_enriched(conn: sqlite3.Connection, project_id: int, when: str | None = None) -> None:
    conn.execute(
        "UPDATE projects SET enriched_at = ? WHERE id = ?",
        (when or utc_now_iso(), project_id),
    )


def save_fetch_state(
    conn: sqlite3.Connection,
    platform: str,
    target_version: str,
    cursor: Mapping[str, Any],
    status: str,
    message: str = "",
) -> None:
    conn.execute(
        """
        INSERT INTO fetch_state(platform, target_version, cursor_json, status, message, updated_at)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT(platform, target_version) DO UPDATE SET
            cursor_json = excluded.cursor_json,
            status = excluded.status,
            message = excluded.message,
            updated_at = excluded.updated_at
        """,
        (
            platform,
            target_version,
            json.dumps(dict(cursor), ensure_ascii=False),
            status,
            message,
            utc_now_iso(),
        ),
    )


def load_fetch_state(
    conn: sqlite3.Connection, platform: str, target_version: str
) -> tuple[dict[str, Any], str]:
    row = conn.execute(
        "SELECT cursor_json, status FROM fetch_state "
        "WHERE platform = ? AND target_version = ?",
        (platform, target_version),
    ).fetchone()
    if row is None:
        return {}, "missing"
    return json.loads(str(row["cursor_json"])), str(row["status"])


def _clamp(value: float, low: float = 0.0, high: float = 100.0) -> float:
    return max(low, min(high, value))


def _log_scale(count: int, ceiling: float, points: float) -> float:
    """Map a non-negative count onto [0, points] with log10 growth to ceiling."""
    if count <= 0:
        return 0.0
    if ceiling <= 1:
        return points
    return points * min(1.0, math.log10(count + 1) / math.log10(ceiling))


def _parse_iso_date(value: str) -> datetime | None:
    if not value:
        return None
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        dt = datetime.fromisoformat(text)
    except ValueError:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=UTC)
    return dt


def _days_since(iso: str, *, now: datetime | None = None) -> float | None:
    dt = _parse_iso_date(iso)
    if dt is None:
        return None
    ref = now or datetime.now(UTC)
    return max(0.0, (ref - dt).total_seconds() / 86400.0)


def score_project(
    project: ProjectRecord | Mapping[str, Any],
    *,
    now: datetime | None = None,
) -> ScoreBreakdown:
    """Compute completeness / support / quality for one project.

    Accepts a ProjectRecord or a DB row mapping (with *_json fields).
    """
    if isinstance(project, ProjectRecord):
        loaders = set(project.loaders)
        description = project.description
        license_id = project.license_id
        downloads = project.downloads
        followers = project.followers
        date_modified = project.date_modified
        source_url = project.source_url
        issues_url = project.issues_url
        discord_url = project.discord_url
        wiki_url = project.wiki_url
        icon_url = project.icon_url
        categories = project.categories
        side = project.side
        has_target = project.has_target_version
        target_files = project.target_file_count
        project_type = project.project_type
    else:
        loaders = set(json.loads(str(project["loaders_json"] or "[]")))
        description = str(project["description"] or "")
        license_id = str(project["license_id"] or "")
        downloads = int(project["downloads"] or 0)
        followers = int(project["followers"] or 0)
        date_modified = str(project["date_modified"] or "")
        source_url = str(project["source_url"] or "")
        issues_url = str(project["issues_url"] or "")
        discord_url = str(project["discord_url"] or "")
        wiki_url = str(project["wiki_url"] or "")
        icon_url = str(project["icon_url"] or "")
        categories = tuple(json.loads(str(project["categories_json"] or "[]")))
        side = str(project["side"] or "unknown")
        has_target = bool(int(project["has_target_version"] or 0))
        target_files = int(project["target_file_count"] or 0)
        project_type = str(project["project_type"] or "mod")

    rationale: dict[str, Any] = {"axes": {}}

    # ── Completeness ──────────────────────────────────────────────────────
    c_target = 25.0 if has_target else 0.0
    known_loaders = {"fabric", "quilt", "neoforge", "forge", "liteloader", "rift", "omni"}
    loader_hits = len(loaders & known_loaders)
    c_loaders = min(40.0, 10.0 * loader_hits)
    c_side = 15.0 if side in {"client", "server", "both"} else 0.0
    c_files = min(20.0, 5.0 * max(0, target_files))
    lic = license_id.strip().lower()
    c_license = (
        10.0
        if lic and lic not in {"", "unknown", "arr", "all rights reserved", "licenseref-unknown"}
        else 0.0
    )
    # Lightweight deps signal: description mentions dependency-ish words, or
    # project_type is a library. We do not call per-version dependency APIs
    # in brick 1 (rate-limit budget); this is a soft completeness cue.
    deps_signal = any(
        token in description.lower()
        for token in ("depends", "dependency", "requires fabric-api", "requires")
    ) or project_type in {"mod", "library"}
    c_deps = 10.0 if deps_signal else 0.0
    completeness = _clamp(c_target + c_loaders + c_side + c_files + c_license + c_deps)
    rationale["axes"]["completeness"] = {
        "target_version": c_target,
        "loaders": c_loaders,
        "side": c_side,
        "target_files": c_files,
        "license": c_license,
        "deps_signal": c_deps,
    }

    # ── Support ───────────────────────────────────────────────────────────
    days = _days_since(date_modified, now=now)
    if days is None:
        s_recency = 5.0 if has_target else 0.0
    elif days <= 30:
        s_recency = 50.0
    elif days <= 90:
        s_recency = 40.0
    elif days <= 180:
        s_recency = 30.0
    elif days <= 365:
        s_recency = 20.0
    else:
        s_recency = 10.0 if has_target else 0.0
    s_source = 15.0 if source_url else 0.0
    s_issues = 15.0 if issues_url else 0.0
    s_community = (10.0 if discord_url else 0.0) + (5.0 if wiki_url else 0.0)
    s_community = min(15.0, s_community)
    s_license = 10.0 if c_license > 0 else 0.0
    support = _clamp(s_recency + s_source + s_issues + s_community + s_license)
    rationale["axes"]["support"] = {
        "recency": s_recency,
        "source": s_source,
        "issues": s_issues,
        "community": s_community,
        "license": s_license,
        "days_since_modified": days,
    }

    # ── Quality ───────────────────────────────────────────────────────────
    # Ceilings chosen so "very large" mods saturate without drowning the rest.
    q_downloads = _log_scale(downloads, ceiling=5_000_000, points=40.0)
    q_followers = _log_scale(followers, ceiling=50_000, points=20.0)
    desc_len = len(description.strip())
    if desc_len >= 400:
        q_desc = 15.0
    elif desc_len >= 120:
        q_desc = 10.0
    elif desc_len >= 40:
        q_desc = 5.0
    else:
        q_desc = 0.0
    q_icon = 5.0 if icon_url else 0.0
    q_cats = min(10.0, 2.5 * len(categories))
    q_type = 10.0 if project_type in {"mod", "library"} else 5.0
    quality = _clamp(q_downloads + q_followers + q_desc + q_icon + q_cats + q_type)
    rationale["axes"]["quality"] = {
        "downloads": q_downloads,
        "followers": q_followers,
        "description": q_desc,
        "icon": q_icon,
        "categories": q_cats,
        "project_type": q_type,
    }

    composite = _clamp(
        WEIGHT_COMPLETENESS * completeness
        + WEIGHT_SUPPORT * support
        + WEIGHT_QUALITY * quality
    )
    tier = "D"
    for name, threshold in TIER_THRESHOLDS:
        if composite >= threshold:
            tier = name
            break
    rationale["weights"] = {
        "completeness": WEIGHT_COMPLETENESS,
        "support": WEIGHT_SUPPORT,
        "quality": WEIGHT_QUALITY,
    }
    return ScoreBreakdown(
        completeness=round(completeness, 3),
        support=round(support, 3),
        quality=round(quality, 3),
        composite=round(composite, 3),
        tier=tier,
        rationale=rationale,
    )


def rescore_all(
    conn: sqlite3.Connection,
    *,
    target_version: str,
    now: datetime | None = None,
) -> int:
    """Recompute scores for every project that supports the target version.

    Projects without the target version are removed from scores (they stay in
    projects for audit but never enter the ranked census).
    """
    scored_at = utc_now_iso()
    rows = conn.execute(
        "SELECT * FROM projects WHERE has_target_version = 1"
    ).fetchall()
    # Clear previous ranks so a shrink cannot leave stale high ranks.
    conn.execute("DELETE FROM scores")
    scored: list[tuple[int, ScoreBreakdown, int]] = []
    for row in rows:
        # Soft filter: prefer projects whose game_versions list mentions the
        # target; has_target_version is authoritative when platforms set it.
        versions = json.loads(str(row["game_versions_json"] or "[]"))
        if versions and target_version not in versions and not int(row["has_target_version"]):
            continue
        breakdown = score_project(row, now=now)
        scored.append((int(row["id"]), breakdown, int(row["downloads"] or 0)))

    # Deterministic order for rank assignment.
    scored.sort(
        key=lambda item: (
            -item[1].composite,
            -item[2],
            # stable tie-break via project id (assignment order after fetch)
            item[0],
        )
    )
    for rank, (project_id, breakdown, _downloads) in enumerate(scored, start=1):
        conn.execute(
            """
            INSERT INTO scores(
                project_id, completeness, support, quality, composite,
                tier, rank, scored_at, rationale_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                project_id,
                breakdown.completeness,
                breakdown.support,
                breakdown.quality,
                breakdown.composite,
                breakdown.tier,
                rank,
                scored_at,
                json.dumps(breakdown.rationale, ensure_ascii=False, sort_keys=True),
            ),
        )
    set_meta(conn, "last_scored_at", scored_at)
    set_meta(conn, "last_score_target", target_version)
    conn.commit()
    return len(scored)


def normalize_identity(text: str) -> str:
    """Collapse a title/author to a comparable token (lowercase alnum only)."""
    return re.sub(r"[^a-z0-9]+", "", text.lower())


def upsert_link(
    conn: sqlite3.Connection,
    left_id: int,
    right_id: int,
    *,
    method: str,
    confidence: float,
    evidence: str = "",
) -> bool:
    """Insert or upgrade a cross-platform link. Returns True if row written/upgraded."""
    if left_id == right_id:
        return False
    a, b = (left_id, right_id) if left_id < right_id else (right_id, left_id)
    existing = conn.execute(
        "SELECT confidence, method FROM project_links WHERE left_id = ? AND right_id = ?",
        (a, b),
    ).fetchone()
    if existing is None:
        conn.execute(
            """
            INSERT INTO project_links(left_id, right_id, method, confidence, evidence)
            VALUES (?, ?, ?, ?, ?)
            """,
            (a, b, method, confidence, evidence),
        )
        return True
    if float(existing["confidence"]) < confidence:
        conn.execute(
            """
            UPDATE project_links
            SET method = ?, confidence = ?, evidence = ?
            WHERE left_id = ? AND right_id = ?
            """,
            (method, confidence, evidence, a, b),
        )
        return True
    return False


def _cross_platform_pairs(
    members: list[tuple[int, str]],
) -> list[tuple[int, int]]:
    """Return sorted (left, right) pairs where platforms differ."""
    pairs: list[tuple[int, int]] = []
    for i, (left_id, left_plat) in enumerate(members):
        for right_id, right_plat in members[i + 1 :]:
            if left_plat == right_plat:
                continue
            a, b = (left_id, right_id) if left_id < right_id else (right_id, left_id)
            pairs.append((a, b))
    return pairs


def link_by_slug(conn: sqlite3.Connection) -> int:
    """Link projects that share a case-insensitive slug across platforms."""
    rows = conn.execute(
        "SELECT id, platform, lower(slug) AS slug_l FROM projects"
    ).fetchall()
    by_slug: dict[str, list[tuple[int, str]]] = {}
    for row in rows:
        by_slug.setdefault(str(row["slug_l"]), []).append(
            (int(row["id"]), str(row["platform"]))
        )
    written = 0
    for slug, group in by_slug.items():
        if len({p for _id, p in group}) < 2:
            continue
        for left, right in _cross_platform_pairs(group):
            if upsert_link(
                conn, left, right, method="slug", confidence=0.85, evidence=slug
            ):
                written += 1
    conn.commit()
    return written


def link_by_hash(conn: sqlite3.Connection) -> int:
    """Link projects that share a file hash across platforms (confidence 0.99).

    Preference order for evidence: sha512 → sha1 → murmur2. Empty hashes ignored.
    """
    written = 0
    for column in ("sha512", "sha1", "murmur2"):
        rows = conn.execute(
            f"""
            SELECT f.{column} AS hv, p.id AS project_id, p.platform
            FROM project_files f
            JOIN projects p ON p.id = f.project_id
            WHERE f.{column} != ''
            """
        ).fetchall()
        by_hash: dict[str, list[tuple[int, str]]] = {}
        for row in rows:
            by_hash.setdefault(str(row["hv"]), []).append(
                (int(row["project_id"]), str(row["platform"]))
            )
        for hv, group in by_hash.items():
            # De-dupe project ids while keeping a platform.
            seen: dict[int, str] = {}
            for pid, platform in group:
                seen[pid] = platform
            members = list(seen.items())
            if len({p for _id, p in members}) < 2:
                continue
            evidence = f"{column}:{hv[:24]}"
            for left, right in _cross_platform_pairs(members):
                if upsert_link(
                    conn,
                    left,
                    right,
                    method="hash",
                    confidence=0.99,
                    evidence=evidence,
                ):
                    written += 1
    conn.commit()
    return written


def link_by_name_author(conn: sqlite3.Connection) -> int:
    """Link projects with identical normalized title+author across platforms.

    Requires both sides to have non-trivial author and title (length ≥ 3 after
    normalize). Confidence 0.70 — weaker than slug/hash; never upgrades a
    stronger existing link (upsert_link enforces that).
    """
    rows = conn.execute(
        "SELECT id, platform, title, author FROM projects"
    ).fetchall()
    by_key: dict[str, list[tuple[int, str]]] = {}
    for row in rows:
        title_n = normalize_identity(str(row["title"] or ""))
        author_n = normalize_identity(str(row["author"] or ""))
        if len(title_n) < 3 or len(author_n) < 3:
            continue
        key = f"{title_n}|{author_n}"
        by_key.setdefault(key, []).append((int(row["id"]), str(row["platform"])))
    written = 0
    for key, group in by_key.items():
        if len({p for _id, p in group}) < 2:
            continue
        for left, right in _cross_platform_pairs(group):
            if upsert_link(
                conn,
                left,
                right,
                method="name_author",
                confidence=0.70,
                evidence=key[:80],
            ):
                written += 1
    conn.commit()
    return written


def link_all(conn: sqlite3.Connection) -> dict[str, int]:
    """Run all automatic linkers; return per-method write counts."""
    # Hash first so a later slug pass cannot overwrite a stronger link
    # (upsert only upgrades confidence).
    return {
        "hash": link_by_hash(conn),
        "slug": link_by_slug(conn),
        "name_author": link_by_name_author(conn),
    }


def export_census(
    conn: sqlite3.Connection,
    *,
    target_version: str,
    cap: int = DEFAULT_CAP,
    report_path: Path = REPORT_PATH,
    ranked_json_path: Path = RANKED_JSON_PATH,
) -> tuple[int, int]:
    """Materialize the ranked snapshot and write report + JSON.

    Returns (snapshot_id, candidate_count).
    """
    if cap < 1:
        raise CensusError("cap must be ≥ 1")
    generated_at = utc_now_iso()
    rows = conn.execute(
        """
        SELECT s.rank, s.tier, s.composite, s.completeness, s.support, s.quality,
               p.id AS project_id, p.platform, p.platform_project_id, p.slug,
               p.title, p.author, p.license_id, p.downloads, p.followers,
               p.loaders_json, p.allow_distribution, p.date_modified, p.source_url
        FROM scores s
        JOIN projects p ON p.id = s.project_id
        WHERE p.has_target_version = 1
        ORDER BY s.rank ASC
        LIMIT ?
        """,
        (cap,),
    ).fetchall()

    cur = conn.execute(
        """
        INSERT INTO census_snapshot(target_version, generated_at, cap, candidate_count, notes)
        VALUES (?, ?, ?, ?, ?)
        """,
        (
            target_version,
            generated_at,
            cap,
            len(rows),
            f"weighted {WEIGHT_COMPLETENESS}/{WEIGHT_SUPPORT}/{WEIGHT_QUALITY} "
            f"completeness/support/quality; cap={cap}",
        ),
    )
    if cur.lastrowid is None:
        raise CensusError("failed to allocate census_snapshot id")
    snapshot_id = int(cur.lastrowid)
    for row in rows:
        conn.execute(
            """
            INSERT INTO census_snapshot_rows(snapshot_id, rank, project_id, tier, composite)
            VALUES (?, ?, ?, ?, ?)
            """,
            (
                snapshot_id,
                int(row["rank"]),
                int(row["project_id"]),
                str(row["tier"]),
                float(row["composite"]),
            ),
        )
    conn.commit()

    report_path.parent.mkdir(parents=True, exist_ok=True)
    tier_counts: dict[str, int] = {name: 0 for name, _ in TIER_THRESHOLDS}
    opted_out = 0
    for row in rows:
        tier_counts[str(row["tier"])] = tier_counts.get(str(row["tier"]), 0) + 1
        if row["allow_distribution"] is not None and int(row["allow_distribution"]) == 0:
            opted_out += 1

    lines: list[str] = [
        f"# Omni-Framework Mod Census — Minecraft {target_version}",
        "",
        f"*Generated {generated_at} · snapshot `{snapshot_id}` · cap {cap} · "
        f"{len(rows)} candidates.*",
        "",
        "Governing decision: ADR-0022. Scores are deterministic from stored "
        "project fields (completeness 0.35 / support 0.35 / quality 0.30).",
        "",
        "## Tier distribution",
        "",
        "| Tier | Count |",
        "|------|------:|",
    ]
    for name, _threshold in TIER_THRESHOLDS:
        lines.append(f"| {name} | {tier_counts.get(name, 0)} |")
    lines.extend(
        [
            "",
            f"CurseForge distribution opt-outs in this export: **{opted_out}** "
            "(kept for ranking; downloads must use the official app — COMPLIANCE §5).",
            "",
            "## Top 50",
            "",
            "| Rank | Tier | Score | Platform | Slug | Title | Downloads | Opt-out |",
            "|-----:|:----:|------:|:---------|:-----|:------|----------:|:-------:|",
        ]
    )
    for row in rows[:50]:
        opt = ""
        if row["allow_distribution"] is not None and int(row["allow_distribution"]) == 0:
            opt = "yes"
        lines.append(
            f"| {int(row['rank'])} | {row['tier']} | {float(row['composite']):.1f} | "
            f"{row['platform']} | `{row['slug']}` | {row['title'].replace('|', '/')} | "
            f"{int(row['downloads'])} | {opt} |"
        )
    lines.extend(
        [
            "",
            "## Regenerating",
            "",
            "```powershell",
            r".venv\Scripts\python tools\census_index.py run --target " + target_version,
            "```",
            "",
        ]
    )
    report_path.write_text("\n".join(lines), encoding="utf-8")

    payload = {
        "target_version": target_version,
        "generated_at": generated_at,
        "snapshot_id": snapshot_id,
        "cap": cap,
        "candidate_count": len(rows),
        "tier_counts": tier_counts,
        "distribution_opt_outs": opted_out,
        "candidates": [
            {
                "rank": int(row["rank"]),
                "tier": str(row["tier"]),
                "composite": float(row["composite"]),
                "completeness": float(row["completeness"]),
                "support": float(row["support"]),
                "quality": float(row["quality"]),
                "platform": str(row["platform"]),
                "platform_project_id": str(row["platform_project_id"]),
                "slug": str(row["slug"]),
                "title": str(row["title"]),
                "author": str(row["author"]),
                "license_id": str(row["license_id"]),
                "downloads": int(row["downloads"]),
                "followers": int(row["followers"]),
                "loaders": json.loads(str(row["loaders_json"] or "[]")),
                "allow_distribution": (
                    None
                    if row["allow_distribution"] is None
                    else bool(int(row["allow_distribution"]))
                ),
                "date_modified": str(row["date_modified"]),
            }
            for row in rows
        ],
    }
    ranked_json_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    set_meta(conn, "last_export_at", generated_at)
    set_meta(conn, "last_export_snapshot", str(snapshot_id))
    conn.commit()
    return snapshot_id, len(rows)


def json_get(
    transport: HttpTransport,
    url: str,
    *,
    headers: Mapping[str, str] | None = None,
) -> Any:
    status, _resp_headers, body = transport.request("GET", url, headers=headers)
    if status != 200:
        hint = ""
        if status == 403 and "api.curseforge.com" in url:
            sent = ""
            if headers:
                for hk, hv in headers.items():
                    if hk.lower() == "x-api-key":
                        sent = str(hv)
                        break
            if sent:
                shape = describe_cf_api_key(sent)
                mangled = validate_cf_api_key_shape(sent)
                looks_console = sent.startswith("$2a$10$") or sent.startswith(
                    "$2y$10$"
                )
                if mangled or not looks_console:
                    detail = mangled or (
                        f"not a bcrypt-shaped Core console key ({shape}); "
                        "expected $2a$10$… from https://console.curseforge.com/"
                    )
                    hint = (
                        f"\n\nCurseForge 403 — API key looks wrong before/at the wire "
                        f"({shape}): {detail}\n"
                        "  Fix (pick one):\n"
                        "    • PowerShell single quotes: "
                        "$env:CURSEFORGE_API_KEY = '$2a$10$...'\n"
                        "    • Key file (avoids shell $ expansion):\n"
                        "        Set-Content -NoNewline cf.key '$2a$10$...'\n"
                        "        $env:CURSEFORGE_API_KEY_FILE = (Resolve-Path cf.key)\n"
                        "    • Do not use cfc_pat_* tokens for /mods/search — "
                        "use the Core API console key\n"
                        "    • Docker Compose: $$ per literal $ in .env "
                        "(we also collapse $$ → $ on read)\n"
                        "  Aliases accepted: CURSEFORGE_API_KEY, CF_API_KEY."
                    )
                else:
                    hint = (
                        f"\n\nCurseForge 403 with a well-shaped console key ({shape}). "
                        "Transport is fine for known-good keys; this usually means "
                        "the key is invalid/revoked or not approved for Minecraft "
                        "Core API (Studios-only keys often 403 here).\n"
                        "  1. Confirm at https://console.curseforge.com/\n"
                        "  2. Third-party apply: https://forms.monday.com/forms/"
                        "dce5ccb7afda9a1c21dab1a1aa1d84eb?r=use1\n"
                        "  3. Re-set without shell corruption (single quotes or key file).\n"
                        "  Docs: https://support.curseforge.com/en/support/solutions/"
                        "articles/9000208346-about-the-curseforge-api-and-how-to-apply-for-a-key"
                    )
            else:
                hint = (
                    "\n\nCurseForge 403 and no x-api-key header was attached — "
                    "check resolve_cf_api_key / --cf-api-key wiring."
                )
        raise CensusError(f"GET {url} → HTTP {status}: {body[:200]!r}{hint}")
    return json.loads(body.decode("utf-8"))


def encode_query(params: Mapping[str, Any]) -> str:
    pairs: list[tuple[str, str]] = []
    for key, value in params.items():
        if value is None:
            continue
        pairs.append((key, str(value)))
    return urllib.parse.urlencode(pairs)


ProgressCallback = Callable[[str], None]


def default_progress(message: str) -> None:
    print(message, flush=True)
