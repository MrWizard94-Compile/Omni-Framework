"""Generate per-subject markdown dossiers from the analysis files.

Dossiers are deterministic renderings of analysis/*.toml (via the same
validated model the DB is built from) and are committed for review; any drift
between TOML and dossier shows up as a git diff (ADR-0004).

Usage:
    python tools/dossier.py --all
    python tools/dossier.py --subject fabric-loader
"""

from __future__ import annotations

import argparse
import sys

from intel_lib import ANALYSIS_DIR, load_all_analyses, parse_analysis, write_dossier


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--all", action="store_true", help="render every subject")
    group.add_argument("--subject", help="render one subject by slug")
    args = parser.parse_args(argv)

    if args.all:
        analyses = load_all_analyses()
    else:
        analyses = [parse_analysis(ANALYSIS_DIR / f"{args.subject}.toml")]
    for analysis in analyses:
        out = write_dossier(analysis)
        print(f"wrote {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
