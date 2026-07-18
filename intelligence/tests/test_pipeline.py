"""Tests for the intelligence pipeline: validation, DB build, dossier rendering.

Intended behavior under test (SOUL §3): analysis TOML files are strictly
validated (provenance is mandatory), the database rebuild is deterministic, and
dossier rendering is a pure function of the analysis model.
"""

from __future__ import annotations

import sqlite3
from pathlib import Path

import pytest
from intel_lib import (
    ANALYSIS_DIR,
    AnalysisError,
    build_database,
    load_all_analyses,
    parse_analysis,
    render_dossier,
    write_dossier,
)

GOOD_TOML = '''
[subject]
slug = "example-loader"
name = "Example Loader"
kind = "loader"
method = "open-source-deep"
repo_url = "https://example.invalid/example-loader"
license = "Apache-2.0"
pinned_commit = "0123456789abcdef0123456789abcdef01234567"
language = "Java"
summary = "A minimal but complete fixture subject."
analyzed_at = "2026-07-18"

[[sources]]
key = "src-main"
kind = "code"
ref = "src/Main.java"
note = "entry point"

[[sources]]
key = "wiki"
kind = "url"
ref = "https://example.invalid/wiki"
retrieved = "2026-07-18"

[[components]]
name = "Bootstrap"
path = "src/Main.java"
role = "starts the loader"
description = "Boots everything."
source_keys = ["src-main"]

[[features]]
name = "Mod discovery"
category = "mod-discovery"
description = "Finds mods in the mods folder."
maturity = "core"
future_proofing = 4
omni_relevance = "adopt"
source_keys = ["src-main", "wiki"]

[[auth_flows]]
name = "None"
description = "Loaders do not authenticate."
steps = "1. n/a"
source_keys = ["wiki"]

[[file_formats]]
name = "example.mod.json"
format_kind = "json"
path_pattern = "META-INF/example.mod.json"
description = "Mod metadata."
source_keys = ["src-main"]

[[protocols]]
name = "None"
endpoint = "n/a"
description = "No network surface."
source_keys = ["wiki"]

[[notes]]
title = "Fixture note"
body = "Body text."
source_keys = ["wiki"]
'''


def write_fixture(tmp_path: Path, text: str = GOOD_TOML, slug: str = "example-loader") -> Path:
    path = tmp_path / f"{slug}.toml"
    path.write_text(text, encoding="utf-8")
    return path


def test_parse_good_analysis(tmp_path: Path) -> None:
    analysis = parse_analysis(write_fixture(tmp_path))
    assert analysis.slug == "example-loader"
    assert analysis.features[0].future_proofing == 4
    assert analysis.features[0].source_keys == ("src-main", "wiki")
    assert len(analysis.sources) == 2


def test_filename_must_match_slug(tmp_path: Path) -> None:
    path = write_fixture(tmp_path, slug="wrong-name")
    with pytest.raises(AnalysisError, match="filename must match slug"):
        parse_analysis(path)


def test_unknown_source_key_rejected(tmp_path: Path) -> None:
    bad = GOOD_TOML.replace('source_keys = ["src-main"]', 'source_keys = ["nope"]', 1)
    with pytest.raises(AnalysisError, match="unknown source keys"):
        parse_analysis(write_fixture(tmp_path, bad))


def test_missing_provenance_rejected(tmp_path: Path) -> None:
    bad = GOOD_TOML.replace('source_keys = ["src-main"]\n', "", 1)
    with pytest.raises(AnalysisError, match="source_keys"):
        parse_analysis(write_fixture(tmp_path, bad))


def test_bad_enum_rejected(tmp_path: Path) -> None:
    bad = GOOD_TOML.replace('maturity = "core"', 'maturity = "shiny"')
    with pytest.raises(AnalysisError, match="expected one of"):
        parse_analysis(write_fixture(tmp_path, bad))


def test_bad_score_rejected(tmp_path: Path) -> None:
    bad = GOOD_TOML.replace("future_proofing = 4", "future_proofing = 9")
    with pytest.raises(AnalysisError, match=r"integer 1\.\.5"):
        parse_analysis(write_fixture(tmp_path, bad))


def test_duplicate_source_key_rejected(tmp_path: Path) -> None:
    bad = GOOD_TOML.replace('key = "wiki"', 'key = "src-main"')
    with pytest.raises(AnalysisError, match="duplicate source key"):
        parse_analysis(write_fixture(tmp_path, bad))


def test_url_source_requires_retrieved_date(tmp_path: Path) -> None:
    bad = GOOD_TOML.replace('retrieved = "2026-07-18"\n', "", 1)
    with pytest.raises(AnalysisError, match="'retrieved' date"):
        parse_analysis(write_fixture(tmp_path, bad))


def _dump(db_path: Path) -> str:
    conn = sqlite3.connect(db_path)
    try:
        return "\n".join(conn.iterdump())
    finally:
        conn.close()


def test_build_database_counts_and_determinism(tmp_path: Path) -> None:
    analysis = parse_analysis(write_fixture(tmp_path))
    db_a = tmp_path / "a.db"
    db_b = tmp_path / "b.db"
    build_database([analysis], db_path=db_a)
    build_database([analysis], db_path=db_b)
    assert _dump(db_a) == _dump(db_b), "rebuilds must be byte-identical (SOUL §21)"

    conn = sqlite3.connect(db_a)
    try:
        stats = conn.execute("SELECT * FROM subject_stats").fetchall()
        assert stats == [("example-loader", 1, 1, 1, 1, 1, 2)]
        matrix = conn.execute("SELECT slug, name, omni_relevance FROM feature_matrix").fetchall()
        assert matrix == [("example-loader", "Mod discovery", "adopt")]
    finally:
        conn.close()


def test_dossier_rendering_is_pure_and_complete(tmp_path: Path) -> None:
    analysis = parse_analysis(write_fixture(tmp_path))
    first = render_dossier(analysis)
    assert first == render_dossier(analysis), "rendering must be deterministic"
    for expected in (
        "# Dossier: Example Loader",
        "## Executive Summary",
        "### Bootstrap",
        "| Mod discovery | core | 4/5 | adopt |",
        "### example.mod.json (json)",
        "## Source Register",
        "`0123456789ab`",
    ):
        assert expected in first, f"dossier missing: {expected}"
    out = write_dossier(analysis, dossier_dir=tmp_path / "dossiers")
    assert out.read_text(encoding="utf-8") == first


def test_repo_analyses_are_valid_and_complete() -> None:
    """Integration gate: the real analysis corpus must always validate."""
    analyses = load_all_analyses(ANALYSIS_DIR)
    slugs = {analysis.slug for analysis in analyses}
    assert {"fabric-loader", "prism-launcher"} <= slugs, (
        "Milestone-1 subjects must be present"
    )
    for analysis in analyses:
        assert analysis.sources, f"{analysis.slug}: provenance register empty"
        assert analysis.features, f"{analysis.slug}: feature inventory empty"
        if analysis.method == "open-source-deep":
            assert analysis.pinned_commit, f"{analysis.slug}: open-source subject must be pinned"
            assert analysis.components, f"{analysis.slug}: component map empty"
