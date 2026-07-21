"""CLI for the Phase 6 mod census (ADR-0022).

Stages:
  fetch   — pull Modrinth (+ CurseForge when key present) into omni_census.db
  enrich  — project detail + version/file hashes (C3; resume via enriched_at)
  score   — recompute completeness/support/quality tiers
  link    — cross-platform links: hash, slug, name_author (C3)
  export  — ranked snapshot ≤ cap + report.md / ranked.json
  run     — fetch → enrich → score → link → export
  status  — print DB counts and fetch_state

Usage examples:
  python tools/census_index.py run --target 1.21.1
  python tools/census_index.py run --target 1.21.1 --platforms modrinth --max-pages 2
  python tools/census_index.py enrich --target 1.21.1 --max-enrich 500
  python tools/census_index.py status
"""

from __future__ import annotations

import argparse
import sqlite3
import sys
from pathlib import Path

from census_curseforge import ENV_API_KEY, fetch_curseforge
from census_enrich import enrich_curseforge, enrich_modrinth
from census_lib import (
    DB_PATH,
    DEFAULT_CAP,
    DEFAULT_TARGET,
    CensusError,
    UrllibTransport,
    export_census,
    link_all,
    open_census_db,
    require_cf_api_key,
    rescore_all,
    resolve_cf_api_key,
)
from census_modrinth import fetch_modrinth


def _parse_platforms(text: str) -> list[str]:
    parts = [p.strip().lower() for p in text.split(",") if p.strip()]
    allowed = {"modrinth", "curseforge"}
    unknown = [p for p in parts if p not in allowed]
    if unknown:
        raise CensusError(f"unknown platform(s): {', '.join(unknown)}")
    if not parts:
        raise CensusError("at least one platform is required")
    return parts


def cmd_fetch(args: argparse.Namespace) -> int:
    platforms = _parse_platforms(args.platforms)
    transport = UrllibTransport()
    db_path = Path(args.db)
    total = 0
    if "modrinth" in platforms:
        total += fetch_modrinth(
            transport,
            target_version=args.target,
            db_path=db_path,
            max_pages=args.max_pages,
            resume=not args.no_resume,
        )
    if "curseforge" in platforms:
        key = resolve_cf_api_key(args.cf_api_key, key_file=args.cf_api_key_file)
        if not key:
            print(
                f"curseforge: skipped — set {ENV_API_KEY}/CF_API_KEY, "
                "--cf-api-key, or --cf-api-key-file (never commit the key).",
                file=sys.stderr,
            )
            if args.platforms.strip().lower() == "curseforge":
                raise CensusError(
                    f"{ENV_API_KEY} (or CF_API_KEY / --cf-api-key-file) is "
                    "required for a curseforge-only fetch"
                )
        else:
            total += fetch_curseforge(
                transport,
                target_version=args.target,
                api_key=key,
                db_path=db_path,
                max_pages=args.max_pages,
                resume=not args.no_resume,
            )
    print(f"fetch complete: {total} upsert(s)")
    return 0


def cmd_enrich(args: argparse.Namespace) -> int:
    platforms = _parse_platforms(args.platforms)
    transport = UrllibTransport()
    db_path = Path(args.db)
    total = 0
    if "modrinth" in platforms:
        total += enrich_modrinth(
            transport,
            target_version=args.target,
            db_path=db_path,
            max_projects=args.max_enrich,
            force=args.force_enrich,
        )
    if "curseforge" in platforms:
        key = resolve_cf_api_key(args.cf_api_key, key_file=args.cf_api_key_file)
        if not key:
            print(
                f"curseforge enrich: skipped — set {ENV_API_KEY}/CF_API_KEY or "
                "--cf-api-key / --cf-api-key-file",
                file=sys.stderr,
            )
            if args.platforms.strip().lower() == "curseforge":
                raise CensusError(
                    f"{ENV_API_KEY} is required for curseforge enrich"
                )
        else:
            # Normalize again through require so mangled keys fail before the loop.
            key = require_cf_api_key(key)
            total += enrich_curseforge(
                transport,
                target_version=args.target,
                api_key=key,
                db_path=db_path,
                max_projects=args.max_enrich,
                force=args.force_enrich,
            )
    print(f"enrich complete: {total} project(s)")
    return 0


def cmd_score(args: argparse.Namespace) -> int:
    conn = open_census_db(Path(args.db))
    try:
        count = rescore_all(conn, target_version=args.target)
    finally:
        conn.close()
    print(f"scored {count} target-version project(s)")
    return 0


def cmd_link(args: argparse.Namespace) -> int:
    conn = open_census_db(Path(args.db))
    try:
        counts = link_all(conn)
    finally:
        conn.close()
    print(
        "links written/upgraded: "
        + ", ".join(f"{method}={n}" for method, n in counts.items())
    )
    return 0


def cmd_export(args: argparse.Namespace) -> int:
    conn = open_census_db(Path(args.db))
    try:
        snapshot_id, count = export_census(
            conn,
            target_version=args.target,
            cap=args.cap,
            report_path=Path(args.report),
            ranked_json_path=Path(args.ranked_json),
        )
    finally:
        conn.close()
    print(f"export snapshot {snapshot_id}: {count} candidate(s) → {args.report}")
    return 0


def cmd_run(args: argparse.Namespace) -> int:
    cmd_fetch(args)
    # Enrich is rate-limit heavy; default run enriches unless --skip-enrich.
    if not args.skip_enrich:
        cmd_enrich(args)
    cmd_score(args)
    cmd_link(args)
    return cmd_export(args)


def cmd_status(args: argparse.Namespace) -> int:
    path = Path(args.db)
    if not path.exists():
        print(f"no census DB at {path}")
        return 0
    conn = open_census_db(path)
    try:
        projects = conn.execute("SELECT COUNT(*) AS c FROM projects").fetchone()
        target = conn.execute(
            "SELECT COUNT(*) AS c FROM projects WHERE has_target_version = 1"
        ).fetchone()
        scores = conn.execute("SELECT COUNT(*) AS c FROM scores").fetchone()
        links = conn.execute("SELECT COUNT(*) AS c FROM project_links").fetchone()
        files = conn.execute("SELECT COUNT(*) AS c FROM project_files").fetchone()
        enriched = conn.execute(
            "SELECT COUNT(*) AS c FROM projects "
            "WHERE enriched_at IS NOT NULL AND enriched_at != ''"
        ).fetchone()
        print(f"db: {path}")
        print(f"  projects:          {int(projects['c'])}")
        print(f"  with target flag:  {int(target['c'])}")
        print(f"  enriched:          {int(enriched['c'])}")
        print(f"  project_files:     {int(files['c'])}")
        print(f"  scored:            {int(scores['c'])}")
        print(f"  cross-links:       {int(links['c'])}")
        print("  links by method:")
        for row in conn.execute(
            "SELECT method, COUNT(*) AS c FROM project_links GROUP BY method ORDER BY method"
        ):
            print(f"    {row['method']}: {int(row['c'])}")
        print("  fetch_state:")
        for row in conn.execute(
            "SELECT platform, target_version, status, message, updated_at "
            "FROM fetch_state ORDER BY platform, target_version"
        ):
            print(
                f"    {row['platform']} @ {row['target_version']}: "
                f"{row['status']} — {row['message']} ({row['updated_at']})"
            )
        print("  tier counts:")
        try:
            for row in conn.execute(
                "SELECT tier, COUNT(*) AS c FROM scores GROUP BY tier ORDER BY tier"
            ):
                print(f"    {row['tier']}: {int(row['c'])}")
        except sqlite3.Error:
            print("    (none)")
    finally:
        conn.close()
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--db",
        default=str(DB_PATH),
        help=f"census sqlite path (default: {DB_PATH})",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    def add_common(p: argparse.ArgumentParser) -> None:
        p.add_argument("--target", default=DEFAULT_TARGET, help="Minecraft version (TGT-1)")
        p.add_argument(
            "--platforms",
            default="modrinth,curseforge",
            help="comma list: modrinth,curseforge",
        )
        p.add_argument(
            "--max-pages",
            type=int,
            default=None,
            help="stop each platform after N pages (smoke / budget runs)",
        )
        p.add_argument(
            "--no-resume",
            action="store_true",
            help="ignore fetch_state cursors and restart from page 0",
        )
        p.add_argument(
            "--cf-api-key",
            default=None,
            help=f"CurseForge API key (else env {ENV_API_KEY} / CF_API_KEY)",
        )
        p.add_argument(
            "--cf-api-key-file",
            default=None,
            help=(
                "path to a file whose first non-comment line is the CF key "
                f"(else env {ENV_API_KEY}_FILE); avoids shell $ expansion"
            ),
        )
        p.add_argument("--cap", type=int, default=DEFAULT_CAP, help="export rank cap")
        p.add_argument(
            "--report",
            default=str(DB_PATH.parent / "census" / "report.md"),
            help="markdown report path",
        )
        p.add_argument(
            "--ranked-json",
            default=str(DB_PATH.parent / "census" / "ranked.json"),
            help="json ranks path",
        )
        p.add_argument(
            "--max-enrich",
            type=int,
            default=None,
            help="enrich at most N projects per platform (budget)",
        )
        p.add_argument(
            "--force-enrich",
            action="store_true",
            help="re-enrich projects that already have enriched_at",
        )
        p.add_argument(
            "--skip-enrich",
            action="store_true",
            help="for run: skip the enrich stage (fetch-only then score)",
        )

    for name, help_text in (
        ("fetch", "index platforms into the census DB"),
        ("enrich", "detail + file hashes for unscored fields (C3)"),
        ("score", "recompute tiers"),
        ("link", "hash + slug + name_author cross-platform links (C3)"),
        ("export", "write ranked snapshot + report"),
        ("run", "fetch + enrich + score + link + export"),
    ):
        p = sub.add_parser(name, help=help_text)
        add_common(p)

    sub.add_parser("status", help="print census DB summary")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        handlers = {
            "fetch": cmd_fetch,
            "enrich": cmd_enrich,
            "score": cmd_score,
            "link": cmd_link,
            "export": cmd_export,
            "run": cmd_run,
            "status": cmd_status,
        }
        handler = handlers.get(args.command)
        if handler is None:
            parser.error(f"unknown command {args.command}")
        return handler(args)
    except CensusError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
