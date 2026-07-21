"""CLI for the Phase 6 mod census (ADR-0022).

Stages:
  fetch   — pull Modrinth (+ CurseForge when key present) into omni_census.db
  score   — recompute completeness/support/quality tiers
  link    — cross-platform slug links
  export  — ranked snapshot ≤ cap + report.md / ranked.json
  run     — fetch → score → link → export
  status  — print DB counts and fetch_state

Usage examples:
  python tools/census_index.py run --target 1.21.1
  python tools/census_index.py run --target 1.21.1 --platforms modrinth --max-pages 2
  python tools/census_index.py status
"""

from __future__ import annotations

import argparse
import os
import sqlite3
import sys
from pathlib import Path

from census_curseforge import ENV_API_KEY, fetch_curseforge
from census_lib import (
    DB_PATH,
    DEFAULT_CAP,
    DEFAULT_TARGET,
    CensusError,
    UrllibTransport,
    export_census,
    link_by_slug,
    open_census_db,
    rescore_all,
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
        if not (args.cf_api_key or os.environ.get(ENV_API_KEY)):
            print(
                f"curseforge: skipped — set {ENV_API_KEY} or pass --cf-api-key "
                "(never commit the key).",
                file=sys.stderr,
            )
            if args.platforms.strip().lower() == "curseforge":
                raise CensusError(f"{ENV_API_KEY} is required for a curseforge-only fetch")
        else:
            total += fetch_curseforge(
                transport,
                target_version=args.target,
                api_key=args.cf_api_key,
                db_path=db_path,
                max_pages=args.max_pages,
                resume=not args.no_resume,
            )
    print(f"fetch complete: {total} upsert(s)")
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
        created = link_by_slug(conn)
    finally:
        conn.close()
    print(f"created {created} cross-platform link(s)")
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
        print(f"db: {path}")
        print(f"  projects:          {int(projects['c'])}")
        print(f"  with target flag:  {int(target['c'])}")
        print(f"  scored:            {int(scores['c'])}")
        print(f"  cross-links:       {int(links['c'])}")
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
            help=f"CurseForge API key (else env {ENV_API_KEY})",
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

    for name, help_text in (
        ("fetch", "index platforms into the census DB"),
        ("score", "recompute tiers"),
        ("link", "slug-based cross-platform links"),
        ("export", "write ranked snapshot + report"),
        ("run", "fetch + score + link + export"),
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
