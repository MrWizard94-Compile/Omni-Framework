"""Rebuild the intelligence database from the hand-audited analysis files.

The DB (omni_intel.db) is always rebuilt from scratch for full determinism —
analysis/*.toml is the only source of truth (ADR-0004). '--subject' still
rebuilds the whole DB but is accepted for symmetry with the other tools; it
additionally verifies the named subject exists.

Usage:
    python tools/analyze.py --all
    python tools/analyze.py --subject fabric-loader
"""

from __future__ import annotations

import argparse
import sqlite3
import sys

from intel_lib import DB_PATH, build_database, load_all_analyses


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--all", action="store_true", help="load every subject")
    group.add_argument("--subject", help="verify this slug exists, then rebuild all")
    args = parser.parse_args(argv)

    analyses = load_all_analyses()
    if args.subject and args.subject not in {analysis.slug for analysis in analyses}:
        parser.error(f"no analysis file for subject '{args.subject}'")
    build_database(analyses)

    conn = sqlite3.connect(DB_PATH)
    try:
        rows = conn.execute("SELECT * FROM subject_stats").fetchall()
    finally:
        conn.close()
    print(f"rebuilt {DB_PATH.name} with {len(analyses)} subject(s):")
    for slug, components, features, file_formats, auth_flows, protocols, sources in rows:
        print(
            f"  {slug}: {components} components, {features} features,"
            f" {file_formats} formats, {auth_flows} auth flows,"
            f" {protocols} protocols, {sources} sources"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
