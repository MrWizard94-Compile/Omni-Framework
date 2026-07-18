"""Generate the Phase 2 comparative feature matrix from the analysis files.

The matrix (intelligence/matrix.md) is a deterministic rendering of every
subject's scored features grouped by category, plus the adopt shortlist that
drives the director cherry-pick session. Committed like the dossiers so drift
shows as a git diff (ADR-0004).

Usage:
    python tools/matrix.py
"""

from __future__ import annotations

import argparse
import sys

from intel_lib import load_all_analyses, write_matrix


def main(argv: list[str] | None = None) -> int:
    argparse.ArgumentParser(description=__doc__).parse_args(argv)
    out = write_matrix(load_all_analyses())
    print(f"wrote {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
