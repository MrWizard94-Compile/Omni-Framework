"""CurseForge Core API indexer for the Phase 6 census (ADR-0022).

Requires CURSEFORGE_API_KEY in the environment (never committed, never logged).
Honors allowModDistribution: projects that opt out stay in the census for
ranking/outreach but are flagged so downloads never bypass the official app
(COMPLIANCE §5).
"""

from __future__ import annotations

import json
import os
from dataclasses import replace
from pathlib import Path
from typing import Any

from census_lib import (
    CF_CLASS_ID_MC_MODS,
    CF_GAME_ID_MINECRAFT,
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

CF_API = "https://api.curseforge.com/v1"
# Documented default/max pageSize for search is 50.
PAGE_SIZE = 50
ENV_API_KEY = "CURSEFORGE_API_KEY"


def require_api_key(explicit: str | None = None) -> str:
    key = (explicit or os.environ.get(ENV_API_KEY) or "").strip()
    if not key:
        raise CensusError(
            f"CurseForge fetch requires {ENV_API_KEY} in the environment "
            "(https://console.curseforge.com/). The key is never logged or stored."
        )
    return key


def _cf_headers(api_key: str) -> dict[str, str]:
    return {"x-api-key": api_key, "Accept": "application/json"}


def _loaders_from_latest_files(mod: dict[str, Any], target_version: str) -> tuple[str, ...]:
    loaders: set[str] = set()
    for file_obj in mod.get("latestFiles") or []:
        if not isinstance(file_obj, dict):
            continue
        game_versions = [str(v).lower() for v in (file_obj.get("gameVersions") or [])]
        if target_version.lower() not in game_versions and target_version not in [
            str(v) for v in (file_obj.get("gameVersions") or [])
        ]:
            # Still collect loader tags from any file; target filter is separate.
            pass
        for entry in file_obj.get("gameVersions") or []:
            token = str(entry).lower()
            if token in {"fabric", "quilt", "neoforge", "forge", "liteloader", "rift"}:
                loaders.add(token)
        for index_entry in file_obj.get("sortableGameVersions") or []:
            if not isinstance(index_entry, dict):
                continue
            name = str(index_entry.get("gameVersionName") or "").lower()
            if name in {"fabric", "quilt", "neoforge", "forge", "liteloader", "rift"}:
                loaders.add(name)
    # Also scan latestFilesIndexes when present.
    for index_entry in mod.get("latestFilesIndexes") or []:
        if not isinstance(index_entry, dict):
            continue
        name = str(index_entry.get("gameVersion") or "").lower()
        if name in {"fabric", "quilt", "neoforge", "forge", "liteloader", "rift"}:
            loaders.add(name)
        mod_loader = index_entry.get("modLoader")
        # Core API modLoader enum: 0=Any 1=Forge 2=Cauldron 3=LiteLoader 4=Fabric 5=Quilt 6=NeoForge
        loader_map = {
            1: "forge",
            3: "liteloader",
            4: "fabric",
            5: "quilt",
            6: "neoforge",
        }
        if isinstance(mod_loader, int) and mod_loader in loader_map:
            loaders.add(loader_map[mod_loader])
    return tuple(sorted(loaders))


def _game_versions(mod: dict[str, Any]) -> tuple[str, ...]:
    versions: set[str] = set()
    for index_entry in mod.get("latestFilesIndexes") or []:
        if isinstance(index_entry, dict) and index_entry.get("gameVersion"):
            versions.add(str(index_entry["gameVersion"]))
    for file_obj in mod.get("latestFiles") or []:
        if not isinstance(file_obj, dict):
            continue
        for entry in file_obj.get("gameVersions") or []:
            token = str(entry)
            # Skip loader names that CF stuffs into gameVersions.
            if token.lower() in {
                "fabric",
                "quilt",
                "neoforge",
                "forge",
                "liteloader",
                "rift",
                "client",
                "server",
            }:
                continue
            versions.add(token)
    return tuple(sorted(versions))


def _target_file_count(mod: dict[str, Any], target_version: str) -> int:
    count = 0
    for index_entry in mod.get("latestFilesIndexes") or []:
        if isinstance(index_entry, dict) and str(index_entry.get("gameVersion")) == target_version:
            count += 1
    if count:
        return count
    for file_obj in mod.get("latestFiles") or []:
        if not isinstance(file_obj, dict):
            continue
        gvs = [str(v) for v in (file_obj.get("gameVersions") or [])]
        if target_version in gvs:
            count += 1
    return count


def _side_from_mod(mod: dict[str, Any]) -> str:
    # CF does not expose a clean side enum on the mod summary; categories may
    # hint. Leave unknown unless latest files name Client/Server only.
    seen_client = False
    seen_server = False
    for file_obj in mod.get("latestFiles") or []:
        if not isinstance(file_obj, dict):
            continue
        for entry in file_obj.get("gameVersions") or []:
            token = str(entry).lower()
            if token == "client":
                seen_client = True
            if token == "server":
                seen_server = True
    if seen_client and seen_server:
        return "both"
    if seen_client:
        return "client"
    if seen_server:
        return "server"
    return "unknown"


def mod_to_record(mod: dict[str, Any], *, target_version: str) -> ProjectRecord:
    mod_id = str(mod.get("id") or "")
    if not mod_id:
        raise CensusError(f"curseforge mod missing id: {mod!r:.200}")
    slug = str(mod.get("slug") or mod_id)
    authors = mod.get("authors") or []
    author = ""
    if authors and isinstance(authors, list) and isinstance(authors[0], dict):
        author = str(authors[0].get("name") or "")
    categories = tuple(
        str(cat.get("name") or cat.get("slug") or "")
        for cat in (mod.get("categories") or [])
        if isinstance(cat, dict)
    )
    versions = _game_versions(mod)
    target_files = _target_file_count(mod, target_version)
    has_target = target_files > 0 or target_version in versions
    links = mod.get("links") or {}
    if not isinstance(links, dict):
        links = {}
    logo = mod.get("logo") or {}
    icon_url = ""
    if isinstance(logo, dict):
        icon_url = str(logo.get("thumbnailUrl") or logo.get("url") or "")
    allow = mod.get("allowModDistribution")
    # Missing field: treat as permitted (older payloads); still never invent
    # download URLs outside the official API.
    allow_distribution: bool | None = True if allow is None else bool(allow)
    return ProjectRecord(
        platform="curseforge",
        platform_project_id=mod_id,
        slug=slug,
        title=str(mod.get("name") or slug),
        description=str(mod.get("summary") or ""),
        author=author,
        license_id="",  # not on search summary; left blank rather than guessed
        project_type="mod",
        categories=categories,
        loaders=_loaders_from_latest_files(mod, target_version),
        game_versions=versions,
        side=_side_from_mod(mod),
        downloads=int(mod.get("downloadCount") or 0),
        followers=0,  # not provided on CF mod summary
        date_created=str(mod.get("dateCreated") or ""),
        date_modified=str(mod.get("dateModified") or mod.get("dateReleased") or ""),
        source_url=str(links.get("sourceUrl") or ""),
        issues_url=str(links.get("issuesUrl") or ""),
        discord_url="",
        wiki_url=str(links.get("wikiUrl") or ""),
        icon_url=icon_url,
        allow_distribution=allow_distribution,
        has_target_version=has_target,
        target_file_count=target_files if target_files else (1 if has_target else 0),
        raw_json=json.dumps(mod, ensure_ascii=False, sort_keys=True),
    )


def fetch_curseforge(
    transport: HttpTransport,
    *,
    target_version: str,
    api_key: str | None = None,
    db_path: Path = DB_PATH,
    max_pages: int | None = None,
    progress: ProgressCallback = default_progress,
    resume: bool = True,
) -> int:
    """Page through CF mod search for Minecraft + target game version."""
    key = require_api_key(api_key)
    headers = _cf_headers(key)
    conn = open_census_db(db_path)
    try:
        cursor, status = load_fetch_state(conn, "curseforge", target_version)
        index = int(cursor.get("index", 0)) if resume and status != "done" else 0
        if status == "done" and resume and max_pages is None:
            progress(f"curseforge: already done for {target_version} (use --no-resume to redo)")
            return 0

        total_upserts = 0
        pages = 0
        save_fetch_state(
            conn,
            "curseforge",
            target_version,
            {"index": index},
            "running",
            "fetching",
        )
        conn.commit()

        while True:
            if max_pages is not None and pages >= max_pages:
                save_fetch_state(
                    conn,
                    "curseforge",
                    target_version,
                    {"index": index},
                    "running",
                    f"paused after {pages} page(s)",
                )
                conn.commit()
                progress(f"curseforge: paused at index={index} after {pages} page(s)")
                break

            query = encode_query(
                {
                    "gameId": CF_GAME_ID_MINECRAFT,
                    "classId": CF_CLASS_ID_MC_MODS,
                    "gameVersion": target_version,
                    "pageSize": PAGE_SIZE,
                    "index": index,
                    "sortField": 2,  # Popularity
                    "sortOrder": "desc",
                }
            )
            url = f"{CF_API}/mods/search?{query}"
            payload = json_get(transport, url, headers=headers)
            data = payload.get("data") or []
            pagination = payload.get("pagination") or {}
            total_count = int(pagination.get("totalCount") or 0)
            if not data:
                save_fetch_state(
                    conn,
                    "curseforge",
                    target_version,
                    {"index": index, "total_count": total_count},
                    "done",
                    f"complete ({total_upserts} upserts this run)",
                )
                conn.commit()
                progress(f"curseforge: done — {total_upserts} upserts, total={total_count}")
                break

            fetched_at = utc_now_iso()
            page_count = 0
            for mod in data:
                if not isinstance(mod, dict):
                    continue
                record = mod_to_record(mod, target_version=target_version)
                # Search was filtered by gameVersion; trust has_target when
                # indexes are sparse but still mark presence.
                if not record.has_target_version:
                    record = replace(
                        record,
                        has_target_version=True,
                        target_file_count=max(1, record.target_file_count),
                    )
                upsert_project(conn, record, fetched_at)
                total_upserts += 1
                page_count += 1

            result_count = int(pagination.get("resultCount") or page_count)
            index += result_count
            pages += 1
            save_fetch_state(
                conn,
                "curseforge",
                target_version,
                {"index": index, "total_count": total_count},
                "running",
                f"index={index}",
            )
            conn.commit()
            progress(
                f"curseforge: page {pages} index={index}/{total_count} "
                f"(+{page_count}, run total {total_upserts})"
            )

            if total_count and index >= total_count:
                save_fetch_state(
                    conn,
                    "curseforge",
                    target_version,
                    {"index": index, "total_count": total_count},
                    "done",
                    f"complete ({total_upserts} upserts this run)",
                )
                conn.commit()
                progress(f"curseforge: done — {total_upserts} upserts")
                break
            if result_count == 0:
                save_fetch_state(
                    conn,
                    "curseforge",
                    target_version,
                    {"index": index, "total_count": total_count},
                    "done",
                    f"complete ({total_upserts} upserts this run)",
                )
                conn.commit()
                break

        return total_upserts
    finally:
        conn.close()
