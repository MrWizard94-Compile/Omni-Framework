"""Phase 6 census tests (ADR-0022).

Asserts real properties of the tier model, platform mappers, resume state, and
export cap — never "did not throw". Network is fully mocked.
"""

from __future__ import annotations

import json
import sqlite3
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import pytest
from census_curseforge import fetch_curseforge, mod_to_record
from census_enrich import (
    apply_modrinth_project_detail,
    enrich_modrinth,
    version_to_file_records,
)
from census_lib import (
    DEFAULT_CAP,
    FileRecord,
    export_census,
    link_all,
    link_by_hash,
    link_by_name_author,
    link_by_slug,
    normalize_identity,
    open_census_db,
    rescore_all,
    score_project,
    upsert_file,
    upsert_link,
    upsert_project,
    utc_now_iso,
)
from census_modrinth import fetch_modrinth, hit_to_record

FIXED_NOW = datetime(2026, 7, 21, 12, 0, 0, tzinfo=UTC)


class ScriptedTransport:
    """Returns pre-recorded (status, headers, body) per URL substring match."""

    def __init__(self, scripts: list[tuple[str, int, dict[str, str], bytes]]) -> None:
        self.scripts = list(scripts)
        self.calls: list[str] = []

    def request(
        self,
        method: str,
        url: str,
        *,
        headers: Any = None,
        body: bytes | None = None,
        timeout: float = 60.0,
    ) -> tuple[int, dict[str, str], bytes]:
        self.calls.append(url)
        for needle, status, resp_headers, payload in self.scripts:
            if needle in url:
                return status, resp_headers, payload
        raise AssertionError(f"no script for URL: {url}")


def _modrinth_hit(**overrides: Any) -> dict[str, Any]:
    base: dict[str, Any] = {
        "project_id": "aaabbb",
        "slug": "sodium",
        "title": "Sodium",
        "description": "A modern rendering engine for Minecraft. " * 5,
        "author": "jelly",
        "project_type": "mod",
        "downloads": 10_000_000,
        "follows": 50_000,
        "date_created": "2020-01-01T00:00:00Z",
        "date_modified": "2026-07-01T00:00:00Z",
        "categories": ["optimization", "utility"],
        "loaders": ["fabric", "quilt"],
        "versions": ["1.21.1", "1.21"],
        "client_side": "required",
        "server_side": "unsupported",
        "license": "LicenseRef-Sodium",
        "source_url": "https://example.invalid/sodium",
        "issues_url": "https://example.invalid/sodium/issues",
        "icon_url": "https://example.invalid/icon.png",
    }
    base.update(overrides)
    return base


def _cf_mod(**overrides: Any) -> dict[str, Any]:
    base: dict[str, Any] = {
        "id": 394468,
        "slug": "sodium",
        "name": "Sodium",
        "summary": "A modern rendering engine.",
        "downloadCount": 50_000_000,
        "dateCreated": "2020-01-01T00:00:00Z",
        "dateModified": "2026-06-15T00:00:00Z",
        "authors": [{"name": "jelly"}],
        "categories": [{"name": "Performance"}],
        "allowModDistribution": True,
        "links": {
            "sourceUrl": "https://example.invalid/sodium",
            "issuesUrl": "https://example.invalid/sodium/issues",
            "wikiUrl": "",
        },
        "logo": {"thumbnailUrl": "https://example.invalid/icon.png"},
        "latestFilesIndexes": [
            {"gameVersion": "1.21.1", "modLoader": 4},
            {"gameVersion": "1.21", "modLoader": 4},
        ],
        "latestFiles": [
            {
                "gameVersions": ["1.21.1", "Fabric", "Client"],
            }
        ],
    }
    base.update(overrides)
    return base


def test_score_prefers_complete_supported_popular_mods() -> None:
    strong = hit_to_record(_modrinth_hit(), target_version="1.21.1")
    weak = hit_to_record(
        _modrinth_hit(
            project_id="zzz",
            slug="obscure-mod",
            title="Obscure",
            description="x",
            downloads=3,
            follows=0,
            date_modified="2019-01-01T00:00:00Z",
            loaders=["forge"],
            source_url="",
            issues_url="",
            icon_url="",
            license="",
            categories=[],
        ),
        target_version="1.21.1",
    )
    s_strong = score_project(strong, now=FIXED_NOW)
    s_weak = score_project(weak, now=FIXED_NOW)
    assert s_strong.composite > s_weak.composite
    assert s_strong.tier in {"S", "A", "B"}
    assert s_weak.composite < s_strong.composite
    # Axes are populated with the contributions the report audits.
    assert "target_version" in s_strong.rationale["axes"]["completeness"]
    assert s_strong.completeness >= 25.0  # target version present


def test_score_is_deterministic() -> None:
    record = hit_to_record(_modrinth_hit(), target_version="1.21.1")
    a = score_project(record, now=FIXED_NOW)
    b = score_project(record, now=FIXED_NOW)
    assert a == b


def test_missing_target_version_zeroes_target_component() -> None:
    record = hit_to_record(
        _modrinth_hit(versions=["1.20.1"], project_id="old"),
        target_version="1.21.1",
    )
    assert record.has_target_version is False
    scored = score_project(record, now=FIXED_NOW)
    assert scored.rationale["axes"]["completeness"]["target_version"] == 0.0


def test_modrinth_mapper_side_and_license() -> None:
    record = hit_to_record(_modrinth_hit(), target_version="1.21.1")
    assert record.platform == "modrinth"
    assert record.side == "client"
    assert record.license_id == "LicenseRef-Sodium"
    assert "fabric" in record.loaders
    assert record.has_target_version is True


def test_curseforge_mapper_opt_out_and_loaders() -> None:
    opted = mod_to_record(
        _cf_mod(allowModDistribution=False),
        target_version="1.21.1",
    )
    assert opted.allow_distribution is False
    assert opted.has_target_version is True
    assert "fabric" in opted.loaders
    assert opted.platform_project_id == "394468"


def test_fetch_modrinth_pages_and_resumes(tmp_path: Path) -> None:
    page1 = {
        "hits": [
            _modrinth_hit(project_id="p1", slug="one"),
            _modrinth_hit(project_id="p2", slug="two", downloads=100),
        ],
        "total_hits": 3,
    }
    page2 = {
        "hits": [_modrinth_hit(project_id="p3", slug="three", downloads=50)],
        "total_hits": 3,
    }
    # First run: only one page allowed.
    transport = ScriptedTransport(
        [
            (
                "offset=0",
                200,
                {},
                json.dumps(page1).encode(),
            ),
        ]
    )
    db = tmp_path / "census.db"
    n = fetch_modrinth(
        transport,
        target_version="1.21.1",
        db_path=db,
        max_pages=1,
        progress=lambda _m: None,
    )
    assert n == 2
    conn = open_census_db(db)
    try:
        assert conn.execute("SELECT COUNT(*) AS c FROM projects").fetchone()["c"] == 2
        state = conn.execute(
            "SELECT status, cursor_json FROM fetch_state WHERE platform='modrinth'"
        ).fetchone()
        assert state["status"] == "running"
        assert json.loads(state["cursor_json"])["offset"] == 2
    finally:
        conn.close()

    # Resume from offset=2.
    transport2 = ScriptedTransport(
        [
            (
                "offset=2",
                200,
                {},
                json.dumps(page2).encode(),
            ),
        ]
    )
    n2 = fetch_modrinth(
        transport2,
        target_version="1.21.1",
        db_path=db,
        progress=lambda _m: None,
    )
    assert n2 == 1
    conn = open_census_db(db)
    try:
        assert conn.execute("SELECT COUNT(*) AS c FROM projects").fetchone()["c"] == 3
        state = conn.execute(
            "SELECT status FROM fetch_state WHERE platform='modrinth'"
        ).fetchone()
        assert state["status"] == "done"
    finally:
        conn.close()


def test_fetch_curseforge_requires_key(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("CURSEFORGE_API_KEY", raising=False)
    transport = ScriptedTransport([])
    with pytest.raises(Exception, match="CURSEFORGE_API_KEY"):
        fetch_curseforge(
            transport,
            target_version="1.21.1",
            db_path=tmp_path / "c.db",
            api_key=None,
            progress=lambda _m: None,
        )


def test_fetch_curseforge_indexes_and_flags_opt_out(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("CURSEFORGE_API_KEY", "test-key-not-real")
    payload = {
        "data": [
            _cf_mod(id=1, slug="alpha", allowModDistribution=True),
            _cf_mod(id=2, slug="beta", allowModDistribution=False, downloadCount=10),
        ],
        "pagination": {"index": 0, "pageSize": 50, "resultCount": 2, "totalCount": 2},
    }
    transport = ScriptedTransport(
        [("mods/search", 200, {}, json.dumps(payload).encode())]
    )
    db = tmp_path / "cf.db"
    n = fetch_curseforge(
        transport,
        target_version="1.21.1",
        db_path=db,
        progress=lambda _m: None,
    )
    assert n == 2
    # Key must never appear in stored raw_json.
    conn = open_census_db(db)
    try:
        raws = [
            str(r["raw_json"])
            for r in conn.execute("SELECT raw_json FROM projects").fetchall()
        ]
        assert all("test-key-not-real" not in raw for raw in raws)
        opted = conn.execute(
            "SELECT slug FROM projects WHERE allow_distribution = 0"
        ).fetchall()
        assert [r["slug"] for r in opted] == ["beta"]
    finally:
        conn.close()


def test_rescore_ranks_and_export_cap(tmp_path: Path) -> None:
    db = tmp_path / "rank.db"
    conn = open_census_db(db)
    try:
        for i in range(5):
            record = hit_to_record(
                _modrinth_hit(
                    project_id=f"id{i}",
                    slug=f"mod-{i}",
                    title=f"Mod {i}",
                    downloads=1000 * (5 - i),
                    follows=100 * (5 - i),
                ),
                target_version="1.21.1",
            )
            upsert_project(conn, record, utc_now_iso())
        # One project without the target version must not enter scores.
        old = hit_to_record(
            _modrinth_hit(
                project_id="old",
                slug="old-mod",
                versions=["1.16.5"],
                downloads=99_000_000,
            ),
            target_version="1.21.1",
        )
        upsert_project(conn, old, utc_now_iso())
        conn.commit()
        count = rescore_all(conn, target_version="1.21.1", now=FIXED_NOW)
        assert count == 5
        ranks = conn.execute(
            "SELECT rank, project_id FROM scores ORDER BY rank"
        ).fetchall()
        assert [int(r["rank"]) for r in ranks] == [1, 2, 3, 4, 5]
        # Highest downloads among equal-ish completeness should lead.
        top = conn.execute(
            """
            SELECT p.slug FROM scores s JOIN projects p ON p.id = s.project_id
            WHERE s.rank = 1
            """
        ).fetchone()
        assert top["slug"] == "mod-0"

        report = tmp_path / "report.md"
        ranked = tmp_path / "ranked.json"
        snapshot_id, exported = export_census(
            conn,
            target_version="1.21.1",
            cap=3,
            report_path=report,
            ranked_json_path=ranked,
        )
        assert snapshot_id >= 1
        assert exported == 3
        assert report.is_file()
        body = report.read_text(encoding="utf-8")
        assert "Minecraft 1.21.1" in body
        assert "Top 50" in body
        data = json.loads(ranked.read_text(encoding="utf-8"))
        assert data["candidate_count"] == 3
        assert len(data["candidates"]) == 3
        assert data["candidates"][0]["rank"] == 1
    finally:
        conn.close()


def test_link_by_slug_cross_platform(tmp_path: Path) -> None:
    db = tmp_path / "link.db"
    conn = open_census_db(db)
    try:
        mr = hit_to_record(_modrinth_hit(slug="Sodium"), target_version="1.21.1")
        cf = mod_to_record(_cf_mod(slug="sodium"), target_version="1.21.1")
        upsert_project(conn, mr, utc_now_iso())
        upsert_project(conn, cf, utc_now_iso())
        # Same platform duplicate slug must not link to itself.
        upsert_project(
            conn,
            hit_to_record(
                _modrinth_hit(project_id="other", slug="unique-only"),
                target_version="1.21.1",
            ),
            utc_now_iso(),
        )
        conn.commit()
        created = link_by_slug(conn)
        assert created == 1
        row = conn.execute(
            "SELECT method, confidence FROM project_links"
        ).fetchone()
        assert row["method"] == "slug"
        assert float(row["confidence"]) == 0.85
    finally:
        conn.close()


def test_export_cap_constant_matches_roadmap() -> None:
    # ROADMAP Phase 6: ranked census capped at ≤50,000 candidates.
    assert DEFAULT_CAP == 50_000


def test_schema_rejects_unknown_platform(tmp_path: Path) -> None:
    conn = open_census_db(tmp_path / "bad.db")
    try:
        with pytest.raises(sqlite3.IntegrityError):
            conn.execute(
                """
                INSERT INTO projects(
                    platform, platform_project_id, slug, title, fetched_at
                ) VALUES ('other', '1', 'x', 'x', '2026-07-21')
                """
            )
            conn.commit()
    finally:
        conn.close()


def test_score_project_from_db_row_roundtrip(tmp_path: Path) -> None:
    """Scoring a DB row must match scoring the original ProjectRecord."""
    record = hit_to_record(_modrinth_hit(), target_version="1.21.1")
    from_record = score_project(record, now=FIXED_NOW)
    conn = open_census_db(tmp_path / "rt.db")
    try:
        upsert_project(conn, record, utc_now_iso())
        conn.commit()
        row = conn.execute("SELECT * FROM projects").fetchone()
        from_row = score_project(row, now=FIXED_NOW)
        assert from_row.composite == from_record.composite
        assert from_row.tier == from_record.tier
    finally:
        conn.close()


# ── C3: enrichment + identity ─────────────────────────────────────────────


def test_normalize_identity_strips_noise() -> None:
    assert normalize_identity("Xaero's Mini-Map!") == "xaerosminimap"
    assert normalize_identity("  Sodium  ") == "sodium"


def test_link_by_hash_cross_platform(tmp_path: Path) -> None:
    conn = open_census_db(tmp_path / "hash.db")
    try:
        mr_id = upsert_project(
            conn,
            hit_to_record(_modrinth_hit(slug="sodium-mr"), target_version="1.21.1"),
            utc_now_iso(),
        )
        cf_id = upsert_project(
            conn,
            mod_to_record(_cf_mod(slug="sodium-cf", id=99), target_version="1.21.1"),
            utc_now_iso(),
        )
        shared = "a" * 128
        upsert_file(
            conn,
            mr_id,
            FileRecord(
                platform_file_id="v1:a.jar",
                filename="a.jar",
                game_versions=("1.21.1",),
                loaders=("fabric",),
                sha1="b" * 40,
                sha512=shared,
                murmur2="",
                downloads=1,
                date_published="2026-01-01T00:00:00Z",
                raw_json="{}",
            ),
        )
        upsert_file(
            conn,
            cf_id,
            FileRecord(
                platform_file_id="1001",
                filename="a.jar",
                game_versions=("1.21.1",),
                loaders=("fabric",),
                sha1="c" * 40,
                sha512=shared,
                murmur2="12345",
                downloads=1,
                date_published="2026-01-01T00:00:00Z",
                raw_json="{}",
            ),
        )
        conn.commit()
        written = link_by_hash(conn)
        assert written == 1
        row = conn.execute("SELECT method, confidence, evidence FROM project_links").fetchone()
        assert row["method"] == "hash"
        assert float(row["confidence"]) == 0.99
        assert str(row["evidence"]).startswith("sha512:")
    finally:
        conn.close()


def test_hash_link_upgrades_weaker_slug_link(tmp_path: Path) -> None:
    conn = open_census_db(tmp_path / "upgrade.db")
    try:
        mr_id = upsert_project(
            conn,
            hit_to_record(_modrinth_hit(slug="same"), target_version="1.21.1"),
            utc_now_iso(),
        )
        cf_id = upsert_project(
            conn,
            mod_to_record(_cf_mod(slug="same", id=7), target_version="1.21.1"),
            utc_now_iso(),
        )
        assert upsert_link(conn, mr_id, cf_id, method="slug", confidence=0.85, evidence="same")
        shared = "d" * 128
        upsert_file(
            conn,
            mr_id,
            FileRecord(
                platform_file_id="m",
                filename="m.jar",
                game_versions=("1.21.1",),
                loaders=("fabric",),
                sha1="",
                sha512=shared,
                murmur2="",
                downloads=0,
                date_published="",
                raw_json="{}",
            ),
        )
        upsert_file(
            conn,
            cf_id,
            FileRecord(
                platform_file_id="c",
                filename="c.jar",
                game_versions=("1.21.1",),
                loaders=("fabric",),
                sha1="",
                sha512=shared,
                murmur2="",
                downloads=0,
                date_published="",
                raw_json="{}",
            ),
        )
        conn.commit()
        assert link_by_hash(conn) == 1
        row = conn.execute("SELECT method, confidence FROM project_links").fetchone()
        assert row["method"] == "hash"
        assert float(row["confidence"]) == 0.99
        # Weaker name_author must not downgrade.
        assert (
            upsert_link(
                conn, mr_id, cf_id, method="name_author", confidence=0.70, evidence="x"
            )
            is False
        )
    finally:
        conn.close()


def test_link_by_name_author(tmp_path: Path) -> None:
    conn = open_census_db(tmp_path / "name.db")
    try:
        upsert_project(
            conn,
            hit_to_record(
                _modrinth_hit(
                    project_id="m1",
                    slug="mr-slug",
                    title="Cool Mod",
                    author="AuthorOne",
                ),
                target_version="1.21.1",
            ),
            utc_now_iso(),
        )
        upsert_project(
            conn,
            mod_to_record(
                _cf_mod(
                    id=55,
                    slug="cf-slug",
                    name="Cool Mod",
                    authors=[{"name": "AuthorOne"}],
                ),
                target_version="1.21.1",
            ),
            utc_now_iso(),
        )
        conn.commit()
        assert link_by_name_author(conn) == 1
        row = conn.execute("SELECT method FROM project_links").fetchone()
        assert row["method"] == "name_author"
    finally:
        conn.close()


def test_version_to_file_records_filters_target() -> None:
    version = {
        "id": "ver1",
        "game_versions": ["1.21.1", "1.21"],
        "loaders": ["fabric"],
        "downloads": 9,
        "date_published": "2026-01-02T00:00:00Z",
        "files": [
            {
                "filename": "mod.jar",
                "hashes": {"sha1": "aa" * 20, "sha512": "bb" * 64},
                "primary": True,
            }
        ],
    }
    recs = version_to_file_records(version, target_version="1.21.1")
    assert len(recs) == 1
    assert recs[0].sha512 == "bb" * 64
    assert version_to_file_records(version, target_version="1.20.1") == []


def test_enrich_modrinth_writes_license_and_files(tmp_path: Path) -> None:
    db = tmp_path / "en.db"
    conn = open_census_db(db)
    try:
        upsert_project(
            conn,
            hit_to_record(
                _modrinth_hit(
                    project_id="proj1",
                    slug="enrich-me",
                    license="",
                    description="short",
                    source_url="",
                ),
                target_version="1.21.1",
            ),
            utc_now_iso(),
        )
        conn.commit()
    finally:
        conn.close()

    detail = {
        "id": "proj1",
        "title": "Enrich Me",
        "description": "short",
        "body": "A much longer body describing the mod with enough text. " * 4,
        "license": {"id": "MIT"},
        "project_type": "mod",
        "categories": ["utility"],
        "loaders": ["fabric"],
        "game_versions": ["1.21.1"],
        "client_side": "optional",
        "server_side": "optional",
        "downloads": 5000,
        "followers": 100,
        "published": "2024-01-01T00:00:00Z",
        "updated": "2026-07-01T00:00:00Z",
        "source_url": "https://example.invalid/src",
        "issues_url": "https://example.invalid/issues",
        "icon_url": "https://example.invalid/i.png",
    }
    versions = [
        {
            "id": "v1",
            "game_versions": ["1.21.1"],
            "loaders": ["fabric"],
            "downloads": 12,
            "date_published": "2026-06-01T00:00:00Z",
            "files": [
                {
                    "filename": "enrich-me.jar",
                    "hashes": {"sha1": "11" * 20, "sha512": "22" * 64},
                }
            ],
        }
    ]
    transport = ScriptedTransport(
        [
            ("/project/proj1/version", 200, {}, json.dumps(versions).encode()),
            ("/project/proj1", 200, {}, json.dumps(detail).encode()),
        ]
    )
    n = enrich_modrinth(
        transport,
        target_version="1.21.1",
        db_path=db,
        progress=lambda _m: None,
    )
    assert n == 1
    conn = open_census_db(db)
    try:
        row = conn.execute("SELECT * FROM projects WHERE slug='enrich-me'").fetchone()
        assert row["license_id"] == "MIT"
        assert row["source_url"] == "https://example.invalid/src"
        assert str(row["enriched_at"]) != ""
        assert int(row["target_file_count"]) == 1
        files = conn.execute("SELECT sha512 FROM project_files").fetchall()
        assert len(files) == 1
        assert files[0]["sha512"] == "22" * 64
    finally:
        conn.close()


def test_link_all_runs_three_methods(tmp_path: Path) -> None:
    conn = open_census_db(tmp_path / "all.db")
    try:
        upsert_project(
            conn,
            hit_to_record(_modrinth_hit(slug="shared"), target_version="1.21.1"),
            utc_now_iso(),
        )
        upsert_project(
            conn,
            mod_to_record(_cf_mod(slug="shared", id=1), target_version="1.21.1"),
            utc_now_iso(),
        )
        conn.commit()
        counts = link_all(conn)
        assert counts["slug"] == 1
        assert "hash" in counts and "name_author" in counts
    finally:
        conn.close()


def test_apply_modrinth_detail_prefers_longer_description(tmp_path: Path) -> None:
    conn = open_census_db(tmp_path / "desc.db")
    try:
        pid = upsert_project(
            conn,
            hit_to_record(
                _modrinth_hit(project_id="d1", description="short"),
                target_version="1.21.1",
            ),
            utc_now_iso(),
        )
        apply_modrinth_project_detail(
            conn,
            pid,
            {
                "title": "T",
                "body": "long body " * 20,
                "license": {"id": "Apache-2.0"},
                "loaders": ["fabric"],
                "game_versions": ["1.21.1"],
                "client_side": "required",
                "server_side": "unsupported",
                "downloads": 1,
                "followers": 1,
            },
            target_version="1.21.1",
        )
        conn.commit()
        row = conn.execute("SELECT description, license_id FROM projects").fetchone()
        assert "long body" in row["description"]
        assert row["license_id"] == "Apache-2.0"
    finally:
        conn.close()
