"""Phase 7 port-queue tests (ADR-0023). Assert real classification + intake."""

from __future__ import annotations

from pathlib import Path

from census_lib import ProjectRecord, open_census_db, upsert_project, utc_now_iso
from port_lib import (
    classify_license,
    draft_outreach_for_queue_row,
    intake_from_census,
    open_port_db,
    queue_summary,
    record_author_response,
    render_outreach_template,
)


def _project(
    *,
    platform: str,
    pid: str,
    slug: str,
    title: str,
    author: str,
    license_id: str,
    downloads: int,
) -> ProjectRecord:
    return ProjectRecord(
        platform=platform,
        platform_project_id=pid,
        slug=slug,
        title=title,
        description="desc",
        author=author,
        license_id=license_id,
        project_type="mod",
        categories=("utility",),
        loaders=("fabric",),
        game_versions=("1.21.1",),
        side="both",
        downloads=downloads,
        followers=10,
        date_created="2024-01-01T00:00:00Z",
        date_modified="2026-01-01T00:00:00Z",
        source_url="https://example.com/src",
        issues_url="",
        discord_url="",
        wiki_url="",
        icon_url="",
        allow_distribution=True if platform == "curseforge" else None,
        has_target_version=True,
        target_file_count=1,
        raw_json="{}",
    )


def test_classify_license_matrix() -> None:
    mit = classify_license("MIT")
    assert mit.license_class == "permissive"
    assert mit.port_eligible is True
    assert mit.requires_outreach is False

    lgpl = classify_license("LGPL-3.0-only")
    assert lgpl.license_class == "weak_copyleft"
    assert lgpl.port_eligible is True

    gpl = classify_license("GPL-3.0-or-later")
    assert gpl.license_class == "strong_copyleft"
    assert gpl.port_eligible is False
    assert gpl.requires_outreach is True

    arr = classify_license("All Rights Reserved")
    assert arr.license_class == "all_rights"
    assert arr.port_eligible is False

    empty = classify_license("")
    assert empty.license_class == "unknown"
    assert empty.port_eligible is False


def test_intake_and_hard_no(tmp_path: Path) -> None:
    census = tmp_path / "census.db"
    port = tmp_path / "port.db"
    cconn = open_census_db(census)
    try:
        now = utc_now_iso()
        a = upsert_project(
            cconn,
            _project(
                platform="modrinth",
                pid="aaa",
                slug="alpha",
                title="Alpha",
                author="Ann",
                license_id="MIT",
                downloads=1000,
            ),
            now,
        )
        b = upsert_project(
            cconn,
            _project(
                platform="curseforge",
                pid="99",
                slug="beta",
                title="Beta",
                author="Bob",
                license_id="All Rights Reserved",
                downloads=500,
            ),
            now,
        )
        # Minimal scores (intake joins scores).
        for pid, tier, rank, composite in (
            (a, "S", 1, 90.0),
            (b, "A", 2, 70.0),
        ):
            cconn.execute(
                """
                INSERT INTO scores (
                    project_id, completeness, support, quality, composite,
                    tier, rank, scored_at, rationale_json
                ) VALUES (?,?,?,?,?,?,?,?,?)
                """,
                (pid, 90, 90, 90, composite, tier, rank, now, "{}"),
            )
        cconn.commit()
    finally:
        cconn.close()

    pconn = open_port_db(port)
    try:
        n = intake_from_census(
            pconn,
            census,
            tiers=("S", "A"),
            batch_label="test-batch",
        )
        assert n == 2
        summary = queue_summary(pconn)
        assert summary["total"] == 2
        assert summary["eligible"] == 1  # MIT only

        # Outreach draft + hard no.
        arr_row = pconn.execute(
            "SELECT id FROM port_queue WHERE slug = 'beta'"
        ).fetchone()
        assert arr_row is not None
        oid = draft_outreach_for_queue_row(pconn, int(arr_row["id"]))
        record_author_response(pconn, oid, "no", note="author declined")
        refused = pconn.execute(
            "SELECT status, port_eligible FROM port_queue WHERE slug = 'beta'"
        ).fetchone()
        assert refused is not None
        assert refused["status"] == "refused"
        assert int(refused["port_eligible"]) == 0
    finally:
        pconn.close()


def test_outreach_template_renders() -> None:
    subject, body = render_outreach_template(
        title="Example Mod",
        author="Ada",
        slug="example-mod",
        platform="modrinth",
        license_id="ARR",
        source_url="https://example.com",
    )
    assert "Example Mod" in subject
    assert "Ada" in body
    assert "example-mod" in body
    assert "{{" not in body
