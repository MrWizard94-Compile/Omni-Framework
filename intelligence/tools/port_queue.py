"""CLI for Phase 7 port queue (ADR-0023).

Stages:
  intake   — pull S/A (or chosen) census tiers into omni_port.db
  draft    — write outreach draft for queue rows needing permission
  respond  — record author yes/no/conditional (never auto-send)
  status   — summary counts
  export   — JSON snapshot of the queue for review

Outreach is **human-gated**: this tool only drafts; it never emails.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from port_lib import (
    CENSUS_DB_PATH,
    DB_PATH,
    PortError,
    draft_outreach_for_queue_row,
    intake_from_census,
    open_port_db,
    queue_summary,
    record_author_response,
    utc_now_iso,
)


def cmd_intake(args: argparse.Namespace) -> int:
    tiers = tuple(t.strip().upper() for t in args.tiers.split(",") if t.strip())
    conn = open_port_db(Path(args.db))
    try:
        n = intake_from_census(
            conn,
            Path(args.census_db),
            target_version=args.target,
            tiers=tiers,
            batch_label=args.batch_label,
            limit=args.limit,
            notes=args.notes,
        )
    finally:
        conn.close()
    print(f"intake {args.batch_label}: {n} queue row(s) from tiers {','.join(tiers)}")
    return 0


def cmd_draft(args: argparse.Namespace) -> int:
    conn = open_port_db(Path(args.db))
    try:
        if args.queue_id is not None:
            ids = [int(args.queue_id)]
        else:
            rows = conn.execute(
                """
                SELECT id FROM port_queue
                WHERE requires_outreach = 1
                  AND status IN ('queued', 'outreach_pending', 'license_review')
                ORDER BY (census_rank IS NULL), census_rank ASC, downloads DESC
                """
            ).fetchall()
            ids = [int(r["id"]) for r in rows]
            if args.limit is not None:
                ids = ids[: args.limit]
        created = 0
        for qid in ids:
            oid = draft_outreach_for_queue_row(
                conn, qid, template_id=args.template, channel=args.channel
            )
            print(f"draft outreach id={oid} for queue_id={qid}")
            created += 1
        print(f"drafted {created} outreach row(s)")
    finally:
        conn.close()
    return 0


def cmd_respond(args: argparse.Namespace) -> int:
    conn = open_port_db(Path(args.db))
    try:
        record_author_response(
            conn, int(args.outreach_id), args.response, note=args.note or ""
        )
    finally:
        conn.close()
    print(f"recorded response={args.response} on outreach {args.outreach_id}")
    return 0


def cmd_status(args: argparse.Namespace) -> int:
    path = Path(args.db)
    if not path.exists():
        print(f"no port DB at {path}")
        return 0
    conn = open_port_db(path)
    try:
        summary = queue_summary(conn)
    finally:
        conn.close()
    print(f"db: {path}")
    print(f"  total:     {summary['total']}")
    print(f"  eligible:  {summary['eligible']}")
    print(f"  refused:   {summary['refused']}")
    print("  by status:")
    for k, v in summary["by_status"].items():
        print(f"    {k}: {v}")
    print("  by license class:")
    for k, v in summary["by_license_class"].items():
        print(f"    {k}: {v}")
    return 0


def cmd_export(args: argparse.Namespace) -> int:
    conn = open_port_db(Path(args.db))
    try:
        rows = [
            dict(r)
            for r in conn.execute(
                """
                SELECT platform, platform_project_id, slug, title, author,
                       license_id, license_class, port_eligible, requires_outreach,
                       census_tier, census_rank, census_composite, downloads,
                       status, status_note, source_url
                FROM port_queue
                ORDER BY census_rank ASC, downloads DESC
                """
            )
        ]
        summary = queue_summary(conn)
    finally:
        conn.close()
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "generated_at": utc_now_iso(),
        "summary": summary,
        "rows": rows,
    }
    out.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(f"exported {len(rows)} row(s) → {out}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--db", default=str(DB_PATH), help=f"port sqlite (default {DB_PATH})")
    sub = p.add_subparsers(dest="command", required=True)

    intake = sub.add_parser("intake", help="ingest census tiers into the queue")
    intake.add_argument("--census-db", default=str(CENSUS_DB_PATH))
    intake.add_argument("--target", default="1.21.1")
    intake.add_argument("--tiers", default="S,A", help="comma list of tiers")
    intake.add_argument(
        "--batch-label",
        default="c4-default",
        help="unique label for this intake batch",
    )
    intake.add_argument("--limit", type=int, default=None)
    intake.add_argument("--notes", default="")
    intake.set_defaults(func=cmd_intake)

    draft = sub.add_parser("draft", help="draft outreach (never sends)")
    draft.add_argument("--queue-id", type=int, default=None)
    draft.add_argument("--limit", type=int, default=None)
    draft.add_argument("--template", default="port_permission_v1")
    draft.add_argument("--channel", default="email")
    draft.set_defaults(func=cmd_draft)

    respond = sub.add_parser("respond", help="record author decision")
    respond.add_argument("--outreach-id", type=int, required=True)
    respond.add_argument(
        "--response",
        required=True,
        choices=["yes", "no", "conditional", "no_reply"],
    )
    respond.add_argument("--note", default="")
    respond.set_defaults(func=cmd_respond)

    status = sub.add_parser("status", help="queue summary")
    status.set_defaults(func=cmd_status)

    export = sub.add_parser("export", help="JSON dump of the queue")
    export.add_argument(
        "--out",
        default=str(PORTING_EXPORT_DEFAULT),
        help="output JSON path",
    )
    export.set_defaults(func=cmd_export)

    return p


# Late binding so path is next to schema without circular imports.
PORTING_EXPORT_DEFAULT = str(
    Path(__file__).resolve().parent.parent / "porting" / "queue_export.json"
)


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return int(args.func(args))
    except PortError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
