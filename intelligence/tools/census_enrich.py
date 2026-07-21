"""Phase 6 C3 — enrich census projects with platform detail + file hashes.

Modrinth: GET /project/{id} for license/links/body; GET /project/{id}/version
filtered to the target game version for sha1/sha512 and accurate file counts.

CurseForge: when a key is present, GET /mods/{id}/files filtered by game
version for fingerprints (murmur2) and hashes if the API supplies them. Search
latestFiles are also ingested during fetch; this pass fills gaps.

Resumable via projects.enriched_at: only rows with empty enriched_at (or
--force) are visited.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any
from urllib.parse import quote

from census_lib import (
    DB_PATH,
    CensusError,
    FileRecord,
    HttpTransport,
    ProgressCallback,
    default_progress,
    encode_query,
    json_get,
    mark_enriched,
    open_census_db,
    upsert_file,
)

MODRINTH_API = "https://api.modrinth.com/v2"
CF_API = "https://api.curseforge.com/v1"


def _license_id(payload: dict[str, Any]) -> str:
    lic = payload.get("license")
    if isinstance(lic, dict):
        return str(lic.get("id") or lic.get("name") or "")
    return str(lic or "")


def _modrinth_side(payload: dict[str, Any]) -> str:
    c = str(payload.get("client_side") or "unknown").lower()
    s = str(payload.get("server_side") or "unknown").lower()
    if c in {"required", "optional"} and s in {"required", "optional"}:
        return "both"
    if c in {"required", "optional"} and s == "unsupported":
        return "client"
    if s in {"required", "optional"} and c == "unsupported":
        return "server"
    return "unknown"


def apply_modrinth_project_detail(
    conn: Any,
    project_id: int,
    detail: dict[str, Any],
    *,
    target_version: str,
) -> None:
    """Merge full project JSON into the projects row (license, links, body)."""
    license_id = _license_id(detail)
    body = str(detail.get("body") or detail.get("description") or "")
    # Prefer the longer description (search hits are one-liners).
    row = conn.execute(
        "SELECT description FROM projects WHERE id = ?", (project_id,)
    ).fetchone()
    current_desc = str(row["description"] or "") if row else ""
    description = body if len(body) > len(current_desc) else current_desc
    loaders = [str(x).lower() for x in (detail.get("loaders") or [])]
    versions = [str(x) for x in (detail.get("game_versions") or [])]
    categories = [
        str(c)
        for c in (detail.get("categories") or [])
        if str(c).lower() not in set(loaders)
    ]
    conn.execute(
        """
        UPDATE projects SET
            title = COALESCE(NULLIF(?, ''), title),
            description = ?,
            license_id = CASE WHEN ? != '' THEN ? ELSE license_id END,
            project_type = COALESCE(NULLIF(?, ''), project_type),
            categories_json = ?,
            loaders_json = ?,
            game_versions_json = ?,
            side = ?,
            downloads = CASE WHEN ? > 0 THEN ? ELSE downloads END,
            followers = CASE WHEN ? > 0 THEN ? ELSE followers END,
            date_created = COALESCE(NULLIF(?, ''), date_created),
            date_modified = COALESCE(NULLIF(?, ''), date_modified),
            source_url = COALESCE(NULLIF(?, ''), source_url),
            issues_url = COALESCE(NULLIF(?, ''), issues_url),
            discord_url = COALESCE(NULLIF(?, ''), discord_url),
            wiki_url = COALESCE(NULLIF(?, ''), wiki_url),
            icon_url = COALESCE(NULLIF(?, ''), icon_url),
            has_target_version = CASE WHEN ? THEN 1 ELSE has_target_version END,
            raw_json = ?
        WHERE id = ?
        """,
        (
            str(detail.get("title") or ""),
            description,
            license_id,
            license_id,
            str(detail.get("project_type") or ""),
            json.dumps(categories, ensure_ascii=False),
            json.dumps(loaders, ensure_ascii=False),
            json.dumps(versions, ensure_ascii=False),
            _modrinth_side(detail),
            int(detail.get("downloads") or 0),
            int(detail.get("downloads") or 0),
            int(detail.get("followers") or detail.get("follows") or 0),
            int(detail.get("followers") or detail.get("follows") or 0),
            str(detail.get("published") or detail.get("date_created") or ""),
            str(detail.get("updated") or detail.get("date_modified") or ""),
            str(detail.get("source_url") or ""),
            str(detail.get("issues_url") or ""),
            str(detail.get("discord_url") or ""),
            str(detail.get("wiki_url") or ""),
            str(detail.get("icon_url") or ""),
            1 if target_version in versions else 0,
            json.dumps(detail, ensure_ascii=False, sort_keys=True),
            project_id,
        ),
    )


def version_to_file_records(
    version: dict[str, Any], *, target_version: str
) -> list[FileRecord]:
    """Turn a Modrinth version object into one FileRecord per primary file."""
    game_versions = tuple(str(v) for v in (version.get("game_versions") or []))
    if target_version not in game_versions:
        return []
    loaders = tuple(str(loader).lower() for loader in (version.get("loaders") or []))
    version_id = str(version.get("id") or "")
    records: list[FileRecord] = []
    files = version.get("files") or []
    if not isinstance(files, list):
        return records
    for index, file_obj in enumerate(files):
        if not isinstance(file_obj, dict):
            continue
        # Prefer primary; still store others for hash coverage.
        hashes = file_obj.get("hashes") or {}
        if not isinstance(hashes, dict):
            hashes = {}
        sha1 = str(hashes.get("sha1") or "").lower()
        sha512 = str(hashes.get("sha512") or "").lower()
        # Stable id: version id + filename
        filename = str(file_obj.get("filename") or f"file-{index}")
        platform_file_id = f"{version_id}:{filename}"
        records.append(
            FileRecord(
                platform_file_id=platform_file_id,
                filename=filename,
                game_versions=game_versions,
                loaders=loaders,
                sha1=sha1,
                sha512=sha512,
                murmur2="",
                downloads=int(version.get("downloads") or 0),
                date_published=str(version.get("date_published") or ""),
                raw_json=json.dumps(file_obj, ensure_ascii=False, sort_keys=True),
            )
        )
    return records


def enrich_modrinth(
    transport: HttpTransport,
    *,
    target_version: str,
    db_path: Path = DB_PATH,
    max_projects: int | None = None,
    force: bool = False,
    progress: ProgressCallback = default_progress,
) -> int:
    """Enrich Modrinth projects lacking enriched_at (or all when force)."""
    conn = open_census_db(db_path)
    try:
        if force:
            rows = conn.execute(
                "SELECT id, platform_project_id, slug FROM projects "
                "WHERE platform = 'modrinth' AND has_target_version = 1 "
                "ORDER BY downloads DESC"
            ).fetchall()
        else:
            rows = conn.execute(
                "SELECT id, platform_project_id, slug FROM projects "
                "WHERE platform = 'modrinth' AND has_target_version = 1 "
                "AND (enriched_at IS NULL OR enriched_at = '') "
                "ORDER BY downloads DESC"
            ).fetchall()
        if max_projects is not None:
            rows = rows[:max_projects]
        done = 0
        for row in rows:
            project_id = int(row["id"])
            pid = str(row["platform_project_id"])
            slug = str(row["slug"])
            detail_url = f"{MODRINTH_API}/project/{quote(pid, safe='')}"
            try:
                detail = json_get(transport, detail_url)
            except CensusError as exc:
                progress(f"modrinth enrich: skip {slug}: {exc}")
                mark_enriched(conn, project_id)  # avoid hot-loop on permanent 404
                conn.commit()
                continue
            if not isinstance(detail, dict):
                mark_enriched(conn, project_id)
                conn.commit()
                continue
            apply_modrinth_project_detail(
                conn, project_id, detail, target_version=target_version
            )
            # Versions for target game version only.
            facets = encode_query(
                {
                    "game_versions": json.dumps([target_version]),
                }
            )
            versions_url = f"{MODRINTH_API}/project/{quote(pid, safe='')}/version?{facets}"
            try:
                versions = json_get(transport, versions_url)
            except CensusError as exc:
                progress(f"modrinth enrich versions: skip {slug}: {exc}")
                versions = []
            if not isinstance(versions, list):
                versions = []
            file_count = 0
            for version in versions:
                if not isinstance(version, dict):
                    continue
                for file_rec in version_to_file_records(
                    version, target_version=target_version
                ):
                    upsert_file(conn, project_id, file_rec)
                    file_count += 1
            if file_count:
                conn.execute(
                    "UPDATE projects SET target_file_count = ?, has_target_version = 1 "
                    "WHERE id = ?",
                    (file_count, project_id),
                )
            mark_enriched(conn, project_id)
            conn.commit()
            done += 1
            if done % 25 == 0 or done == len(rows):
                progress(f"modrinth enrich: {done}/{len(rows)} ({slug}, {file_count} files)")
        progress(f"modrinth enrich: done — {done} project(s)")
        return done
    finally:
        conn.close()


def cf_file_to_record(file_obj: dict[str, Any], *, target_version: str) -> FileRecord | None:
    """Map a CurseForge file object to FileRecord if it matches target_version."""
    game_versions = [str(v) for v in (file_obj.get("gameVersions") or [])]
    # CF packs loaders into gameVersions; accept if target present.
    if target_version not in game_versions:
        # sortableGameVersions may carry the version
        for entry in file_obj.get("sortableGameVersions") or []:
            if isinstance(entry, dict) and str(entry.get("gameVersionName")) == target_version:
                break
        else:
            return None
    loaders: list[str] = []
    for token in game_versions:
        low = token.lower()
        if low in {"fabric", "quilt", "neoforge", "forge", "liteloader", "rift"}:
            loaders.append(low)
    hashes = file_obj.get("hashes") or []
    sha1 = ""
    sha512 = ""
    if isinstance(hashes, list):
        for item in hashes:
            if not isinstance(item, dict):
                continue
            algo = item.get("algo")
            value = str(item.get("value") or "").lower()
            # CF: 1 = Sha1, 2 = Md5
            if algo == 1:
                sha1 = value
    murmur = file_obj.get("fileFingerprint") or file_obj.get("packageFingerprint")
    murmur2 = str(int(murmur)) if murmur is not None else ""
    file_id = str(file_obj.get("id") or file_obj.get("fileName") or "")
    if not file_id:
        return None
    loader_tokens = {
        "fabric",
        "quilt",
        "neoforge",
        "forge",
        "liteloader",
        "rift",
        "client",
        "server",
    }
    return FileRecord(
        platform_file_id=file_id,
        filename=str(file_obj.get("fileName") or ""),
        game_versions=tuple(v for v in game_versions if v.lower() not in loader_tokens),
        loaders=tuple(sorted(set(loaders))),
        sha1=sha1,
        sha512=sha512,
        murmur2=murmur2,
        downloads=int(file_obj.get("downloadCount") or 0),
        date_published=str(file_obj.get("fileDate") or ""),
        raw_json=json.dumps(file_obj, ensure_ascii=False, sort_keys=True),
    )


def ingest_cf_latest_files(
    conn: Any, project_id: int, mod: dict[str, Any], *, target_version: str
) -> int:
    """Store hashes from a CF mod summary's latestFiles (used during fetch)."""
    count = 0
    for file_obj in mod.get("latestFiles") or []:
        if not isinstance(file_obj, dict):
            continue
        rec = cf_file_to_record(file_obj, target_version=target_version)
        if rec is None:
            # Still store fingerprint if game version is only in indexes.
            gvs = [str(v) for v in (file_obj.get("gameVersions") or [])]
            if target_version not in gvs:
                continue
            rec = cf_file_to_record(
                {**file_obj, "gameVersions": [*gvs, target_version]},
                target_version=target_version,
            )
        if rec is None:
            continue
        upsert_file(conn, project_id, rec)
        count += 1
    return count


def enrich_curseforge(
    transport: HttpTransport,
    *,
    target_version: str,
    api_key: str,
    db_path: Path = DB_PATH,
    max_projects: int | None = None,
    force: bool = False,
    progress: ProgressCallback = default_progress,
) -> int:
    """Enrich CurseForge projects with /mods/{id}/files for the target version."""
    headers = {"x-api-key": api_key, "Accept": "application/json"}
    conn = open_census_db(db_path)
    try:
        if force:
            rows = conn.execute(
                "SELECT id, platform_project_id, slug FROM projects "
                "WHERE platform = 'curseforge' AND has_target_version = 1 "
                "ORDER BY downloads DESC"
            ).fetchall()
        else:
            rows = conn.execute(
                "SELECT id, platform_project_id, slug FROM projects "
                "WHERE platform = 'curseforge' AND has_target_version = 1 "
                "AND (enriched_at IS NULL OR enriched_at = '') "
                "ORDER BY downloads DESC"
            ).fetchall()
        if max_projects is not None:
            rows = rows[:max_projects]
        done = 0
        for row in rows:
            project_id = int(row["id"])
            mod_id = str(row["platform_project_id"])
            slug = str(row["slug"])
            query = encode_query(
                {
                    "gameVersion": target_version,
                    "pageSize": 50,
                    "index": 0,
                }
            )
            url = f"{CF_API}/mods/{mod_id}/files?{query}"
            try:
                payload = json_get(transport, url, headers=headers)
            except CensusError as exc:
                progress(f"curseforge enrich: skip {slug}: {exc}")
                mark_enriched(conn, project_id)
                conn.commit()
                continue
            files = payload.get("data") or []
            file_count = 0
            if isinstance(files, list):
                for file_obj in files:
                    if not isinstance(file_obj, dict):
                        continue
                    rec = cf_file_to_record(file_obj, target_version=target_version)
                    if rec is None:
                        continue
                    upsert_file(conn, project_id, rec)
                    file_count += 1
            if file_count:
                conn.execute(
                    "UPDATE projects SET target_file_count = ?, has_target_version = 1 "
                    "WHERE id = ?",
                    (file_count, project_id),
                )
            mark_enriched(conn, project_id)
            conn.commit()
            done += 1
            if done % 25 == 0 or done == len(rows):
                progress(
                    f"curseforge enrich: {done}/{len(rows)} ({slug}, {file_count} files)"
                )
        progress(f"curseforge enrich: done — {done} project(s)")
        return done
    finally:
        conn.close()
