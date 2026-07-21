"""Phase 7 port-queue library — license classification + intake (ADR-0023).

Runtime: Python stdlib only (same posture as census / ADR-0004).
Never logs secrets. Never auto-sends outreach (human-gated).
"""

from __future__ import annotations

import json
import re
import sqlite3
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

INTEL_DIR = Path(__file__).resolve().parent.parent
PORTING_DIR = INTEL_DIR / "porting"
SCHEMA_PATH = PORTING_DIR / "schema.sql"
DB_PATH = INTEL_DIR / "omni_port.db"
CENSUS_DB_PATH = INTEL_DIR / "omni_census.db"
TEMPLATES_DIR = PORTING_DIR / "templates"

# SPDX-ish tokens → class. Longest-match first after normalize.
_PERMISSIVE = frozenset(
    {
        "mit",
        "apache-2.0",
        "apache2",
        "apache",
        "bsd-2-clause",
        "bsd-3-clause",
        "bsd",
        "isc",
        "unlicense",
        "cc0-1.0",
        "cc0",
        "zlib",
        "wtfpl",
        "0bsd",
        "boost",
        "bsl-1.0",
    }
)
_WEAK = frozenset(
    {
        "lgpl-3.0-only",
        "lgpl-3.0-or-later",
        "lgpl-2.1-only",
        "lgpl-2.1-or-later",
        "lgpl",
        "mpl-2.0",
        "mpl",
        "epl-2.0",
        "epl",
        "cpl-1.0",
    }
)
_STRONG = frozenset(
    {
        "gpl-3.0-only",
        "gpl-3.0-or-later",
        "gpl-2.0-only",
        "gpl-2.0-or-later",
        "gpl",
        "agpl-3.0-only",
        "agpl-3.0-or-later",
        "agpl",
    }
)
_ALL_RIGHTS = frozenset(
    {
        "arr",
        "all-rights-reserved",
        "all rights reserved",
        "proprietary",
        "custom",
        "license",
        "licence",
    }
)


class PortError(RuntimeError):
    """Operator-visible port-queue failure."""


def utc_now_iso() -> str:
    return datetime.now(tz=UTC).replace(microsecond=0).isoformat()


def open_port_db(path: Path = DB_PATH) -> sqlite3.Connection:
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(path))
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    schema = SCHEMA_PATH.read_text(encoding="utf-8")
    conn.executescript(schema)
    conn.commit()
    return conn


def normalize_license_token(raw: str) -> str:
    text = (raw or "").strip().lower()
    text = text.replace("_", "-")
    text = re.sub(r"\s+", " ", text)
    # Strip common URL wrappers.
    text = re.sub(r"^https?://spdx\.org/licenses/", "", text)
    text = text.removesuffix(".html").removesuffix(".txt")
    return text


@dataclass(frozen=True)
class LicenseDecision:
    license_class: str
    port_eligible: bool
    requires_outreach: bool
    rationale: str


def classify_license(license_id: str) -> LicenseDecision:
    """Map a platform license string to an ADR-0003 port decision.

    permissive      → eligible, no outreach required
    weak_copyleft   → eligible with conditions; outreach recommended not required
    strong_copyleft → outreach required before native port
    all_rights      → outreach required; default path is compat_only until yes
    unknown         → license_review; not auto-eligible
    """
    token = normalize_license_token(license_id)
    if not token:
        return LicenseDecision(
            "unknown",
            False,
            True,
            "empty license_id — human review before any native port",
        )

    # Exact token first, then containment for noisy CF strings.
    compact = token.replace(" ", "-")
    if compact in _PERMISSIVE or token in _PERMISSIVE:
        return LicenseDecision(
            "permissive",
            True,
            False,
            f"permissive license ({license_id!r}) permits derivative ports",
        )
    if compact in _WEAK or any(t in compact for t in ("lgpl", "mpl-2", "mpl/", "epl")):
        return LicenseDecision(
            "weak_copyleft",
            True,
            False,
            f"weak copyleft ({license_id!r}) — port allowed with license obligations",
        )
    if compact in _STRONG or any(
        t in compact for t in ("agpl", "gpl-3", "gpl-2", "gpl/")
    ):
        # Bare "gpl" without L → strong; already excluded lgpl via order above.
        if "lgpl" in compact:
            return LicenseDecision(
                "weak_copyleft",
                True,
                False,
                f"LGPL variant ({license_id!r})",
            )
        return LicenseDecision(
            "strong_copyleft",
            False,
            True,
            f"strong copyleft ({license_id!r}) — author outreach required",
        )
    if compact in _ALL_RIGHTS or "all rights" in token or "all-rights" in compact:
        return LicenseDecision(
            "all_rights",
            False,
            True,
            f"all-rights / proprietary ({license_id!r}) — written permission required",
        )
    # Custom / unknown SPDX.
    return LicenseDecision(
        "unknown",
        False,
        True,
        f"unrecognised license_id ({license_id!r}) — classify manually",
    )


def render_outreach_template(
    *,
    template_id: str = "port_permission_v1",
    title: str,
    author: str,
    slug: str,
    platform: str,
    license_id: str,
    source_url: str,
) -> tuple[str, str]:
    """Return (subject, body) for human review/send. Never auto-sends."""
    path = TEMPLATES_DIR / f"{template_id}.md"
    if not path.is_file():
        raise PortError(f"missing outreach template: {path}")
    text = path.read_text(encoding="utf-8")
    mapping = {
        "TITLE": title or slug,
        "AUTHOR": author or "mod author",
        "SLUG": slug,
        "PLATFORM": platform,
        "LICENSE": license_id or "(unspecified)",
        "SOURCE_URL": source_url or "(no source URL on file)",
        "PROJECT": "Omni-Framework / OmniLoader",
        "ORG": "Wizard Productions AI Studio",
        "SITE": "https://wpaistudio.net",
    }
    for key, value in mapping.items():
        text = text.replace("{{" + key + "}}", value)
    lines = text.splitlines()
    subject = f"Permission to create a native Omni port of {title or slug}"
    if lines and lines[0].lower().startswith("subject:"):
        subject = lines[0].split(":", 1)[1].strip()
        body = "\n".join(lines[1:]).lstrip("\n")
    else:
        body = text
    return subject, body


def intake_from_census(
    port_conn: sqlite3.Connection,
    census_path: Path = CENSUS_DB_PATH,
    *,
    target_version: str = "1.21.1",
    tiers: tuple[str, ...] = ("S", "A"),
    batch_label: str,
    limit: int | None = None,
    notes: str = "",
) -> int:
    """Upsert census rows in the given tiers into the port queue.

    Returns number of queue rows inserted or updated this call.
    """
    if not census_path.is_file():
        raise PortError(f"census DB not found: {census_path}")
    if not tiers:
        raise PortError("at least one tier is required")
    placeholders = ",".join("?" for _ in tiers)
    census = sqlite3.connect(str(census_path))
    census.row_factory = sqlite3.Row
    try:
        sql = f"""
            SELECT p.platform, p.platform_project_id, p.slug, p.title, p.author,
                   p.license_id, p.allow_distribution, p.downloads,
                   p.source_url, p.issues_url,
                   s.tier, s.rank, s.composite
            FROM scores s
            JOIN projects p ON p.id = s.project_id
            WHERE p.has_target_version = 1 AND s.tier IN ({placeholders})
            ORDER BY s.rank ASC
        """
        rows = list(census.execute(sql, tuple(tiers)))
        if limit is not None:
            rows = rows[:limit]
    finally:
        census.close()

    now = utc_now_iso()
    written = 0
    for row in rows:
        decision = classify_license(str(row["license_id"] or ""))
        status = "queued"
        if decision.license_class == "unknown":
            status = "license_review"
        elif decision.requires_outreach and not decision.port_eligible:
            status = "outreach_pending"
        elif decision.port_eligible:
            status = "permitted" if not decision.requires_outreach else "outreach_pending"

        # CF distribution opt-out does not block *porting permission* work, but
        # native redistribution of CF files remains forbidden — note it.
        allow = row["allow_distribution"]
        rationale = {
            "license": decision.rationale,
            "allow_distribution": allow,
            "census_tier": row["tier"],
            "census_rank": row["rank"],
        }
        port_conn.execute(
            """
            INSERT INTO port_queue (
                platform, platform_project_id, slug, title, author,
                license_id, license_class, port_eligible, requires_outreach,
                allow_distribution, census_tier, census_rank, census_composite,
                downloads, source_url, issues_url, status, status_note,
                rationale_json, ingested_at, updated_at
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(platform, platform_project_id) DO UPDATE SET
                slug=excluded.slug,
                title=excluded.title,
                author=excluded.author,
                license_id=excluded.license_id,
                license_class=excluded.license_class,
                port_eligible=excluded.port_eligible,
                requires_outreach=excluded.requires_outreach,
                allow_distribution=excluded.allow_distribution,
                census_tier=excluded.census_tier,
                census_rank=excluded.census_rank,
                census_composite=excluded.census_composite,
                downloads=excluded.downloads,
                source_url=excluded.source_url,
                issues_url=excluded.issues_url,
                rationale_json=excluded.rationale_json,
                updated_at=excluded.updated_at
                -- status intentionally NOT overwritten on re-intake
            """,
            (
                str(row["platform"]),
                str(row["platform_project_id"]),
                str(row["slug"]),
                str(row["title"]),
                str(row["author"] or ""),
                str(row["license_id"] or ""),
                decision.license_class,
                1 if decision.port_eligible else 0,
                1 if decision.requires_outreach else 0,
                None if allow is None else int(allow),
                str(row["tier"]),
                int(row["rank"]) if row["rank"] is not None else None,
                float(row["composite"]) if row["composite"] is not None else None,
                int(row["downloads"] or 0),
                str(row["source_url"] or ""),
                str(row["issues_url"] or ""),
                status,
                "",
                json.dumps(rationale, ensure_ascii=False, sort_keys=True),
                now,
                now,
            ),
        )
        written += 1

    port_conn.execute(
        """
        INSERT INTO intake_batches (
            label, census_db_path, target_version, tiers, row_count, created_at, notes
        ) VALUES (?,?,?,?,?,?,?)
        ON CONFLICT(label) DO UPDATE SET
            row_count=excluded.row_count,
            created_at=excluded.created_at,
            notes=excluded.notes
        """,
        (
            batch_label,
            str(census_path),
            target_version,
            ",".join(tiers),
            written,
            now,
            notes,
        ),
    )
    port_conn.commit()
    return written


def draft_outreach_for_queue_row(
    conn: sqlite3.Connection,
    queue_id: int,
    *,
    template_id: str = "port_permission_v1",
    channel: str = "email",
) -> int:
    """Create a draft outreach row (sent_at empty). Returns outreach id."""
    row = conn.execute(
        "SELECT * FROM port_queue WHERE id = ?", (queue_id,)
    ).fetchone()
    if row is None:
        raise PortError(f"port_queue id {queue_id} not found")
    subject, body = render_outreach_template(
        template_id=template_id,
        title=str(row["title"]),
        author=str(row["author"]),
        slug=str(row["slug"]),
        platform=str(row["platform"]),
        license_id=str(row["license_id"]),
        source_url=str(row["source_url"]),
    )
    now = utc_now_iso()
    cur = conn.execute(
        """
        INSERT INTO outreach (
            queue_id, channel, template_id, subject, body,
            sent_at, response_at, response, response_note, created_at
        ) VALUES (?,?,?,?,?,'','','','',?)
        """,
        (queue_id, channel, template_id, subject, body, now),
    )
    conn.execute(
        "UPDATE port_queue SET status = 'outreach_pending', updated_at = ? WHERE id = ?",
        (now, queue_id),
    )
    conn.commit()
    row_id = cur.lastrowid
    if row_id is None:
        raise PortError("failed to insert outreach draft")
    return int(row_id)


def record_author_response(
    conn: sqlite3.Connection,
    outreach_id: int,
    response: str,
    *,
    note: str = "",
) -> None:
    """Apply author response; hard 'no' permanently refuses native porting."""
    if response not in {"yes", "no", "conditional", "no_reply"}:
        raise PortError(f"invalid response: {response}")
    row = conn.execute(
        "SELECT queue_id FROM outreach WHERE id = ?", (outreach_id,)
    ).fetchone()
    if row is None:
        raise PortError(f"outreach id {outreach_id} not found")
    queue_id = int(row["queue_id"])
    now = utc_now_iso()
    conn.execute(
        """
        UPDATE outreach SET response = ?, response_at = ?, response_note = ?
        WHERE id = ?
        """,
        (response, now, note, outreach_id),
    )
    if response == "yes":
        new_status = "permitted"
        eligible = 1
    elif response == "no":
        new_status = "refused"
        eligible = 0
    elif response == "conditional":
        new_status = "license_review"
        eligible = 0
    else:
        new_status = "awaiting_author"
        eligible = 0
    conn.execute(
        """
        UPDATE port_queue SET status = ?, port_eligible = ?, updated_at = ?,
            status_note = ?
        WHERE id = ?
        """,
        (new_status, eligible, now, note or f"author response: {response}", queue_id),
    )
    conn.commit()


def queue_summary(conn: sqlite3.Connection) -> dict[str, Any]:
    total = int(conn.execute("SELECT COUNT(*) AS c FROM port_queue").fetchone()["c"])
    by_status = {
        str(r["status"]): int(r["c"])
        for r in conn.execute(
            "SELECT status, COUNT(*) AS c FROM port_queue GROUP BY status ORDER BY status"
        )
    }
    by_class = {
        str(r["license_class"]): int(r["c"])
        for r in conn.execute(
            "SELECT license_class, COUNT(*) AS c FROM port_queue "
            "GROUP BY license_class ORDER BY license_class"
        )
    }
    eligible = int(
        conn.execute(
            "SELECT COUNT(*) AS c FROM port_queue WHERE port_eligible = 1"
        ).fetchone()["c"]
    )
    refused = int(
        conn.execute(
            "SELECT COUNT(*) AS c FROM port_queue WHERE status = 'refused'"
        ).fetchone()["c"]
    )
    return {
        "total": total,
        "eligible": eligible,
        "refused": refused,
        "by_status": by_status,
        "by_license_class": by_class,
    }
