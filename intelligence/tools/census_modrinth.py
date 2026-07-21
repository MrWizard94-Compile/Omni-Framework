"""Modrinth public API indexer for the Phase 6 census (ADR-0022).

Uses the documented Labrinth v2 search endpoint with facets for project_type
and game version. No API key. Rate limits are enforced by the shared transport.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from census_lib import (
    DB_PATH,
    CensusError,
    HttpTransport,
    ProgressCallback,
    ProjectRecord,
    default_progress,
    encode_query,
    json_get,
    load_fetch_state,
    open_census_db,
    save_fetch_state,
    upsert_project,
    utc_now_iso,
)

MODRINTH_API = "https://api.modrinth.com/v2"
# Max page size documented for /search is 100.
PAGE_SIZE = 100


def _side_from_project(hit: dict[str, Any]) -> str:
    client = hit.get("client_side") or hit.get("client_side_type") or "unknown"
    server = hit.get("server_side") or hit.get("server_side_type") or "unknown"
    # Search hits use client_side / server_side as required|optional|unsupported.
    c = str(client).lower()
    s = str(server).lower()
    if c in {"required", "optional"} and s in {"required", "optional"}:
        return "both"
    if c in {"required", "optional"} and s == "unsupported":
        return "client"
    if s in {"required", "optional"} and c == "unsupported":
        return "server"
    return "unknown"


def hit_to_record(hit: dict[str, Any], *, target_version: str) -> ProjectRecord:
    """Map a Modrinth search hit (or project object) to a ProjectRecord."""
    versions = tuple(str(v) for v in (hit.get("versions") or hit.get("game_versions") or []))
    loaders = tuple(str(loader).lower() for loader in (hit.get("loaders") or []))
    categories = tuple(str(c) for c in (hit.get("categories") or []) if c not in loaders)
    license_field = hit.get("license")
    if isinstance(license_field, dict):
        license_id = str(license_field.get("id") or license_field.get("name") or "")
    else:
        license_id = str(license_field or "")
    project_id = str(hit.get("project_id") or hit.get("id") or "")
    slug = str(hit.get("slug") or project_id)
    if not project_id:
        raise CensusError(f"modrinth hit missing id: {hit!r:.200}")
    has_target = target_version in versions
    # Search hits do not always include per-version file counts; use 1 as a
    # presence signal when the version list contains the target.
    target_files = 1 if has_target else 0
    author = ""
    if isinstance(hit.get("author"), str):
        author = str(hit["author"])
    description = str(hit.get("description") or hit.get("body") or "")
    return ProjectRecord(
        platform="modrinth",
        platform_project_id=project_id,
        slug=slug,
        title=str(hit.get("title") or slug),
        description=description,
        author=author,
        license_id=license_id,
        project_type=str(hit.get("project_type") or "mod"),
        categories=categories,
        loaders=loaders,
        game_versions=versions,
        side=_side_from_project(hit),
        downloads=int(hit.get("downloads") or 0),
        followers=int(hit.get("follows") or hit.get("followers") or 0),
        date_created=str(hit.get("date_created") or ""),
        date_modified=str(hit.get("date_modified") or ""),
        source_url=str(hit.get("source_url") or ""),
        issues_url=str(hit.get("issues_url") or ""),
        discord_url=str(hit.get("discord_url") or ""),
        wiki_url=str(hit.get("wiki_url") or ""),
        icon_url=str(hit.get("icon_url") or ""),
        allow_distribution=None,
        has_target_version=has_target,
        target_file_count=target_files,
        raw_json=json.dumps(hit, ensure_ascii=False, sort_keys=True),
    )


def fetch_modrinth(
    transport: HttpTransport,
    *,
    target_version: str,
    db_path: Path = DB_PATH,
    max_pages: int | None = None,
    progress: ProgressCallback = default_progress,
    resume: bool = True,
) -> int:
    """Page through Modrinth search for mods on target_version. Returns upserts."""
    conn = open_census_db(db_path)
    try:
        cursor, status = load_fetch_state(conn, "modrinth", target_version)
        offset = int(cursor.get("offset", 0)) if resume and status != "done" else 0
        if status == "done" and resume and max_pages is None:
            progress(f"modrinth: already done for {target_version} (use --no-resume to redo)")
            return 0

        facets = json.dumps(
            [["project_type:mod"], [f"versions:{target_version}"]],
            separators=(",", ":"),
        )
        total_upserts = 0
        pages = 0
        save_fetch_state(
            conn,
            "modrinth",
            target_version,
            {"offset": offset},
            "running",
            "fetching",
        )
        conn.commit()

        while True:
            if max_pages is not None and pages >= max_pages:
                save_fetch_state(
                    conn,
                    "modrinth",
                    target_version,
                    {"offset": offset},
                    "running",
                    f"paused after {pages} page(s)",
                )
                conn.commit()
                progress(f"modrinth: paused at offset={offset} after {pages} page(s)")
                break

            query = encode_query(
                {
                    "facets": facets,
                    "offset": offset,
                    "limit": PAGE_SIZE,
                    "index": "downloads",
                }
            )
            url = f"{MODRINTH_API}/search?{query}"
            payload = json_get(transport, url)
            hits = list(payload.get("hits") or [])
            total_hits = int(payload.get("total_hits") or 0)
            if not hits:
                save_fetch_state(
                    conn,
                    "modrinth",
                    target_version,
                    {"offset": offset, "total_hits": total_hits},
                    "done",
                    f"complete ({total_upserts} upserts this run)",
                )
                conn.commit()
                progress(f"modrinth: done — {total_upserts} upserts, total_hits={total_hits}")
                break

            fetched_at = utc_now_iso()
            for hit in hits:
                if not isinstance(hit, dict):
                    continue
                record = hit_to_record(hit, target_version=target_version)
                upsert_project(conn, record, fetched_at)
                total_upserts += 1
            offset += len(hits)
            pages += 1
            save_fetch_state(
                conn,
                "modrinth",
                target_version,
                {"offset": offset, "total_hits": total_hits},
                "running",
                f"offset={offset}",
            )
            conn.commit()
            progress(
                f"modrinth: page {pages} offset={offset}/{total_hits} "
                f"(+{len(hits)}, run total {total_upserts})"
            )

            if offset >= total_hits:
                save_fetch_state(
                    conn,
                    "modrinth",
                    target_version,
                    {"offset": offset, "total_hits": total_hits},
                    "done",
                    f"complete ({total_upserts} upserts this run)",
                )
                conn.commit()
                progress(f"modrinth: done — {total_upserts} upserts")
                break

        return total_upserts
    finally:
        conn.close()
