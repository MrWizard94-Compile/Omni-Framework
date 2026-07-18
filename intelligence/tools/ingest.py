"""Fetch vivisection subjects into the corpus at their pinned commits.

Reads analysis/*.toml for each subject's repo_url + pinned_commit and produces
intelligence/corpus/<slug>/ as a shallow checkout of exactly that commit.
Black-box subjects (no repo) are skipped by design (ADR-0002).

If a subject's pinned_commit is empty, the default branch head is fetched and
its sha printed so the author can pin it in the TOML (the file is the source of
truth; this tool never edits it).

Usage:
    python tools/ingest.py --all
    python tools/ingest.py --subject fabric-loader
"""

from __future__ import annotations

import argparse
import shutil
import stat
import subprocess
import sys
from pathlib import Path

from intel_lib import ANALYSIS_DIR, CORPUS_DIR, Analysis, load_all_analyses, parse_analysis


def _git(args: list[str], cwd: Path) -> str:
    result = subprocess.run(
        ["git", *args], cwd=cwd, capture_output=True, text=True, check=True
    )
    return result.stdout.strip()


def ingest(analysis: Analysis, corpus_dir: Path = CORPUS_DIR) -> str | None:
    """Fetch one subject; returns the checked-out sha, or None for black-box subjects."""
    if analysis.method == "black-box":
        print(f"[skip] {analysis.slug}: black-box subject, no corpus fetch (ADR-0002)")
        return None
    target = corpus_dir / analysis.slug
    if target.exists():
        head = _git(["rev-parse", "HEAD"], cwd=target)
        if analysis.pinned_commit and head == analysis.pinned_commit:
            print(f"[ok]   {analysis.slug}: already at pinned commit {head[:12]}")
            return head
        print(f"[re]   {analysis.slug}: at {head[:12]}, refetching")
        shutil.rmtree(target, onexc=_force_remove)
    target.mkdir(parents=True)
    _git(["init", "-q"], cwd=target)
    _git(["remote", "add", "origin", analysis.repo_url], cwd=target)
    if analysis.pinned_commit:
        _git(["fetch", "-q", "--depth", "1", "origin", analysis.pinned_commit], cwd=target)
    else:
        _git(["fetch", "-q", "--depth", "1", "origin", "HEAD"], cwd=target)
    _git(["checkout", "-q", "FETCH_HEAD"], cwd=target)
    head = _git(["rev-parse", "HEAD"], cwd=target)
    if analysis.pinned_commit:
        print(f"[ok]   {analysis.slug}: fetched pinned commit {head[:12]}")
    else:
        print(
            f"[PIN]  {analysis.slug}: fetched unpinned HEAD {head} — record this sha as"
            f" pinned_commit in analysis/{analysis.slug}.toml"
        )
    return head


def _force_remove(func: object, path: str, exc: BaseException) -> None:
    """shutil.rmtree onexc hook: clear read-only bits git sets on object files."""
    target = Path(path)
    target.chmod(stat.S_IWRITE)
    target.unlink()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--all", action="store_true", help="ingest every subject")
    group.add_argument("--subject", help="ingest one subject by slug")
    args = parser.parse_args(argv)

    if args.all:
        analyses = load_all_analyses()
    else:
        analyses = [parse_analysis(ANALYSIS_DIR / f"{args.subject}.toml")]
    for analysis in analyses:
        ingest(analysis)
    return 0


if __name__ == "__main__":
    sys.exit(main())
