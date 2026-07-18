"""Shared library for the Omni-Framework intelligence pipeline.

Loads hand-audited analysis files (analysis/*.toml), validates them into typed
records, rebuilds the SQLite database from schema.sql, and renders per-subject
markdown dossiers. The TOML files are the versioned source of truth; the DB and
dossiers are deterministic build artifacts (ADR-0004).

Runtime dependencies: Python 3.11+ standard library only.
"""

from __future__ import annotations

import sqlite3
import tomllib
from dataclasses import dataclass, field
from pathlib import Path

INTEL_DIR = Path(__file__).resolve().parent.parent
SCHEMA_PATH = INTEL_DIR / "schema.sql"
ANALYSIS_DIR = INTEL_DIR / "analysis"
CORPUS_DIR = INTEL_DIR / "corpus"
DOSSIER_DIR = INTEL_DIR / "dossiers"
DB_PATH = INTEL_DIR / "omni_intel.db"

SUBJECT_KINDS = frozenset({"launcher", "loader", "subsystem", "compat-layer"})
METHODS = frozenset({"open-source-deep", "black-box"})
SOURCE_KINDS = frozenset({"code", "doc", "url"})
MATURITIES = frozenset({"core", "stable", "experimental", "legacy"})
RELEVANCES = frozenset({"adopt", "adapt", "observe", "avoid"})


class AnalysisError(ValueError):
    """A hand-authored analysis file violates the schema contract."""


@dataclass(frozen=True)
class Source:
    key: str
    kind: str
    ref: str
    note: str
    retrieved: str


@dataclass(frozen=True)
class Component:
    name: str
    path: str
    role: str
    description: str
    source_keys: tuple[str, ...]


@dataclass(frozen=True)
class Feature:
    name: str
    category: str
    description: str
    maturity: str
    future_proofing: int
    omni_relevance: str
    notes: str
    source_keys: tuple[str, ...]


@dataclass(frozen=True)
class AuthFlow:
    name: str
    description: str
    steps: str
    source_keys: tuple[str, ...]


@dataclass(frozen=True)
class FileFormat:
    name: str
    format_kind: str
    path_pattern: str
    description: str
    source_keys: tuple[str, ...]


@dataclass(frozen=True)
class Protocol:
    name: str
    endpoint: str
    description: str
    source_keys: tuple[str, ...]


@dataclass(frozen=True)
class Note:
    title: str
    body: str
    source_keys: tuple[str, ...]


@dataclass(frozen=True)
class Analysis:
    slug: str
    name: str
    kind: str
    method: str
    repo_url: str
    license: str
    pinned_commit: str
    language: str
    summary: str
    analyzed_at: str
    sources: tuple[Source, ...] = field(default=())
    components: tuple[Component, ...] = field(default=())
    features: tuple[Feature, ...] = field(default=())
    auth_flows: tuple[AuthFlow, ...] = field(default=())
    file_formats: tuple[FileFormat, ...] = field(default=())
    protocols: tuple[Protocol, ...] = field(default=())
    notes: tuple[Note, ...] = field(default=())


def _req_str(raw: dict[str, object], key: str, ctx: str, *, allow_empty: bool = False) -> str:
    value = raw.get(key)
    if not isinstance(value, str):
        raise AnalysisError(f"{ctx}: field '{key}' must be a string, got {type(value).__name__}")
    if not allow_empty and not value.strip():
        raise AnalysisError(f"{ctx}: field '{key}' must not be empty")
    return value


def _opt_str(raw: dict[str, object], key: str, ctx: str) -> str:
    value = raw.get(key, "")
    if not isinstance(value, str):
        raise AnalysisError(f"{ctx}: field '{key}' must be a string, got {type(value).__name__}")
    return value


def _req_enum(raw: dict[str, object], key: str, allowed: frozenset[str], ctx: str) -> str:
    value = _req_str(raw, key, ctx)
    if value not in allowed:
        raise AnalysisError(f"{ctx}: field '{key}' is '{value}', expected one of {sorted(allowed)}")
    return value


def _req_source_keys(raw: dict[str, object], ctx: str) -> tuple[str, ...]:
    value = raw.get("source_keys")
    if (
        not isinstance(value, list)
        or not value
        or not all(isinstance(item, str) and item.strip() for item in value)
    ):
        raise AnalysisError(f"{ctx}: 'source_keys' must be a non-empty list of non-empty strings")
    return tuple(value)


def _tables(raw: dict[str, object], key: str, ctx: str) -> list[dict[str, object]]:
    value = raw.get(key, [])
    if not isinstance(value, list) or not all(isinstance(item, dict) for item in value):
        raise AnalysisError(f"{ctx}: '{key}' must be an array of tables")
    return value


def parse_analysis(path: Path) -> Analysis:
    """Parse and validate one hand-audited analysis TOML file."""
    ctx = path.name
    with path.open("rb") as fh:
        raw = tomllib.load(fh)

    subject_raw = raw.get("subject")
    if not isinstance(subject_raw, dict):
        raise AnalysisError(f"{ctx}: missing [subject] table")
    sctx = f"{ctx} [subject]"
    method = _req_enum(subject_raw, "method", METHODS, sctx)
    black_box = method == "black-box"

    sources = tuple(
        Source(
            key=_req_str(s, "key", f"{ctx} [[sources]] #{i}"),
            kind=_req_enum(s, "kind", SOURCE_KINDS, f"{ctx} [[sources]] #{i}"),
            ref=_req_str(s, "ref", f"{ctx} [[sources]] #{i}"),
            note=_opt_str(s, "note", f"{ctx} [[sources]] #{i}"),
            retrieved=_opt_str(s, "retrieved", f"{ctx} [[sources]] #{i}"),
        )
        for i, s in enumerate(_tables(raw, "sources", ctx), start=1)
    )
    if not sources:
        raise AnalysisError(f"{ctx}: at least one [[sources]] entry is required (provenance)")
    seen: set[str] = set()
    for source in sources:
        if source.key in seen:
            raise AnalysisError(f"{ctx}: duplicate source key '{source.key}'")
        seen.add(source.key)
        if source.kind in {"doc", "url"} and not source.retrieved.strip():
            raise AnalysisError(
                f"{ctx}: source '{source.key}' is kind '{source.kind}' and needs a"
                " 'retrieved' date"
            )
    valid_keys = {source.key for source in sources}

    def checked_keys(entry: dict[str, object], ectx: str) -> tuple[str, ...]:
        keys = _req_source_keys(entry, ectx)
        unknown = [key for key in keys if key not in valid_keys]
        if unknown:
            raise AnalysisError(f"{ectx}: unknown source keys {unknown}")
        return keys

    def feature(entry: dict[str, object], ectx: str) -> Feature:
        score = entry.get("future_proofing")
        if not isinstance(score, int) or isinstance(score, bool) or not 1 <= score <= 5:
            raise AnalysisError(f"{ectx}: 'future_proofing' must be an integer 1..5")
        return Feature(
            name=_req_str(entry, "name", ectx),
            category=_req_str(entry, "category", ectx),
            description=_req_str(entry, "description", ectx),
            maturity=_req_enum(entry, "maturity", MATURITIES, ectx),
            future_proofing=score,
            omni_relevance=_req_enum(entry, "omni_relevance", RELEVANCES, ectx),
            notes=_opt_str(entry, "notes", ectx),
            source_keys=checked_keys(entry, ectx),
        )

    analysis = Analysis(
        slug=_req_str(subject_raw, "slug", sctx),
        name=_req_str(subject_raw, "name", sctx),
        kind=_req_enum(subject_raw, "kind", SUBJECT_KINDS, sctx),
        method=method,
        repo_url=_req_str(subject_raw, "repo_url", sctx, allow_empty=black_box),
        license=_req_str(subject_raw, "license", sctx),
        pinned_commit=_req_str(subject_raw, "pinned_commit", sctx, allow_empty=black_box),
        language=_req_str(subject_raw, "language", sctx),
        summary=_req_str(subject_raw, "summary", sctx),
        analyzed_at=_req_str(subject_raw, "analyzed_at", sctx),
        sources=sources,
        components=tuple(
            Component(
                name=_req_str(c, "name", f"{ctx} [[components]] #{i}"),
                path=_req_str(c, "path", f"{ctx} [[components]] #{i}"),
                role=_req_str(c, "role", f"{ctx} [[components]] #{i}"),
                description=_req_str(c, "description", f"{ctx} [[components]] #{i}"),
                source_keys=checked_keys(c, f"{ctx} [[components]] #{i}"),
            )
            for i, c in enumerate(_tables(raw, "components", ctx), start=1)
        ),
        features=tuple(
            feature(f, f"{ctx} [[features]] #{i}")
            for i, f in enumerate(_tables(raw, "features", ctx), start=1)
        ),
        auth_flows=tuple(
            AuthFlow(
                name=_req_str(a, "name", f"{ctx} [[auth_flows]] #{i}"),
                description=_req_str(a, "description", f"{ctx} [[auth_flows]] #{i}"),
                steps=_req_str(a, "steps", f"{ctx} [[auth_flows]] #{i}"),
                source_keys=checked_keys(a, f"{ctx} [[auth_flows]] #{i}"),
            )
            for i, a in enumerate(_tables(raw, "auth_flows", ctx), start=1)
        ),
        file_formats=tuple(
            FileFormat(
                name=_req_str(f, "name", f"{ctx} [[file_formats]] #{i}"),
                format_kind=_req_str(f, "format_kind", f"{ctx} [[file_formats]] #{i}"),
                path_pattern=_req_str(f, "path_pattern", f"{ctx} [[file_formats]] #{i}"),
                description=_req_str(f, "description", f"{ctx} [[file_formats]] #{i}"),
                source_keys=checked_keys(f, f"{ctx} [[file_formats]] #{i}"),
            )
            for i, f in enumerate(_tables(raw, "file_formats", ctx), start=1)
        ),
        protocols=tuple(
            Protocol(
                name=_req_str(p, "name", f"{ctx} [[protocols]] #{i}"),
                endpoint=_req_str(p, "endpoint", f"{ctx} [[protocols]] #{i}"),
                description=_req_str(p, "description", f"{ctx} [[protocols]] #{i}"),
                source_keys=checked_keys(p, f"{ctx} [[protocols]] #{i}"),
            )
            for i, p in enumerate(_tables(raw, "protocols", ctx), start=1)
        ),
        notes=tuple(
            Note(
                title=_req_str(n, "title", f"{ctx} [[notes]] #{i}"),
                body=_req_str(n, "body", f"{ctx} [[notes]] #{i}"),
                source_keys=checked_keys(n, f"{ctx} [[notes]] #{i}"),
            )
            for i, n in enumerate(_tables(raw, "notes", ctx), start=1)
        ),
    )
    expected = f"{analysis.slug}.toml"
    if path.name != expected:
        raise AnalysisError(f"{ctx}: filename must match slug (expected '{expected}')")
    return analysis


def load_all_analyses(analysis_dir: Path = ANALYSIS_DIR) -> list[Analysis]:
    """Load every analysis file, sorted by slug for deterministic output."""
    paths = sorted(analysis_dir.glob("*.toml"))
    analyses = [parse_analysis(path) for path in paths]
    slugs = [analysis.slug for analysis in analyses]
    if len(slugs) != len(set(slugs)):
        raise AnalysisError(f"duplicate subject slugs across analysis files: {slugs}")
    return analyses


def build_database(
    analyses: list[Analysis],
    db_path: Path = DB_PATH,
    schema_path: Path = SCHEMA_PATH,
) -> None:
    """Rebuild the database from scratch — full determinism, no incremental state."""
    db_path.unlink(missing_ok=True)
    conn = sqlite3.connect(db_path)
    try:
        conn.executescript(schema_path.read_text(encoding="utf-8"))
        for analysis in analyses:
            cur = conn.execute(
                "INSERT INTO subjects (slug, name, kind, method, repo_url, license,"
                " pinned_commit, language, summary, analyzed_at)"
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    analysis.slug,
                    analysis.name,
                    analysis.kind,
                    analysis.method,
                    analysis.repo_url,
                    analysis.license,
                    analysis.pinned_commit,
                    analysis.language,
                    analysis.summary,
                    analysis.analyzed_at,
                ),
            )
            sid = cur.lastrowid
            conn.executemany(
                "INSERT INTO sources (subject_id, key, kind, ref, note, retrieved)"
                " VALUES (?, ?, ?, ?, ?, ?)",
                [(sid, s.key, s.kind, s.ref, s.note, s.retrieved) for s in analysis.sources],
            )
            conn.executemany(
                "INSERT INTO components (subject_id, name, path, role, description, source_keys)"
                " VALUES (?, ?, ?, ?, ?, ?)",
                [
                    (sid, c.name, c.path, c.role, c.description, ",".join(c.source_keys))
                    for c in analysis.components
                ],
            )
            conn.executemany(
                "INSERT INTO features (subject_id, name, category, description, maturity,"
                " future_proofing, omni_relevance, notes, source_keys)"
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                [
                    (
                        sid,
                        f.name,
                        f.category,
                        f.description,
                        f.maturity,
                        f.future_proofing,
                        f.omni_relevance,
                        f.notes,
                        ",".join(f.source_keys),
                    )
                    for f in analysis.features
                ],
            )
            conn.executemany(
                "INSERT INTO auth_flows (subject_id, name, description, steps, source_keys)"
                " VALUES (?, ?, ?, ?, ?)",
                [
                    (sid, a.name, a.description, a.steps, ",".join(a.source_keys))
                    for a in analysis.auth_flows
                ],
            )
            conn.executemany(
                "INSERT INTO file_formats (subject_id, name, format_kind, path_pattern,"
                " description, source_keys) VALUES (?, ?, ?, ?, ?, ?)",
                [
                    (sid, f.name, f.format_kind, f.path_pattern, f.description,
                     ",".join(f.source_keys))
                    for f in analysis.file_formats
                ],
            )
            conn.executemany(
                "INSERT INTO protocols (subject_id, name, endpoint, description, source_keys)"
                " VALUES (?, ?, ?, ?, ?)",
                [
                    (sid, p.name, p.endpoint, p.description, ",".join(p.source_keys))
                    for p in analysis.protocols
                ],
            )
            conn.executemany(
                "INSERT INTO analysis_notes (subject_id, title, body, source_keys)"
                " VALUES (?, ?, ?, ?)",
                [(sid, n.title, n.body, ",".join(n.source_keys)) for n in analysis.notes],
            )
        conn.commit()
    finally:
        conn.close()


def _cite(keys: tuple[str, ...]) -> str:
    return ", ".join(f"`{key}`" for key in keys)


def render_dossier(analysis: Analysis) -> str:
    """Render one subject's dossier as markdown. Pure function — deterministic."""
    lines: list[str] = []
    add = lines.append
    add(f"# Dossier: {analysis.name}")
    add("")
    add("*Generated by `tools/dossier.py` — do not edit by hand; edit"
        f" `analysis/{analysis.slug}.toml` and regenerate.*")
    add("")
    add("| | |")
    add("|---|---|")
    add(f"| Kind | {analysis.kind} |")
    add(f"| Method | {analysis.method} |")
    add(f"| Repository | {analysis.repo_url or 'n/a (black-box subject)'} |")
    add(f"| License | {analysis.license} |")
    add(f"| Pinned commit | `{analysis.pinned_commit or 'n/a'}` |")
    add(f"| Language | {analysis.language} |")
    add(f"| Analyzed | {analysis.analyzed_at} |")
    add("")
    add("## Executive Summary")
    add("")
    add(analysis.summary.strip())
    if analysis.components:
        add("")
        add("## Architecture Components")
        for c in analysis.components:
            add("")
            add(f"### {c.name}")
            add("")
            add(f"- **Location**: `{c.path}`")
            add(f"- **Role**: {c.role}")
            add(f"- **Sources**: {_cite(c.source_keys)}")
            add("")
            add(c.description.strip())
    if analysis.features:
        add("")
        add("## Feature Inventory")
        categories: dict[str, list[Feature]] = {}
        for f in analysis.features:
            categories.setdefault(f.category, []).append(f)
        for category, features in categories.items():
            add("")
            add(f"### {category}")
            add("")
            add("| Feature | Maturity | Future-proofing | Omni relevance | Sources |")
            add("|---|---|---|---|---|")
            for f in features:
                add(
                    f"| {f.name} | {f.maturity} | {f.future_proofing}/5 |"
                    f" {f.omni_relevance} | {_cite(f.source_keys)} |"
                )
            for f in features:
                add("")
                add(f"**{f.name}** — {f.description.strip()}")
                if f.notes.strip():
                    add("")
                    add(f"*Omni notes*: {f.notes.strip()}")
    if analysis.auth_flows:
        add("")
        add("## Authentication Flows")
        for a in analysis.auth_flows:
            add("")
            add(f"### {a.name}")
            add("")
            add(a.description.strip())
            add("")
            add("Steps:")
            add("")
            add(a.steps.strip())
            add("")
            add(f"*Sources*: {_cite(a.source_keys)}")
    if analysis.file_formats:
        add("")
        add("## File Formats")
        for ff in analysis.file_formats:
            add("")
            add(f"### {ff.name} ({ff.format_kind})")
            add("")
            add(f"- **Path pattern**: `{ff.path_pattern}`")
            add(f"- **Sources**: {_cite(ff.source_keys)}")
            add("")
            add(ff.description.strip())
    if analysis.protocols:
        add("")
        add("## Protocols & API Surfaces")
        for p in analysis.protocols:
            add("")
            add(f"### {p.name}")
            add("")
            add(f"- **Endpoint**: `{p.endpoint}`")
            add(f"- **Sources**: {_cite(p.source_keys)}")
            add("")
            add(p.description.strip())
    if analysis.notes:
        add("")
        add("## Analysis Notes")
        for n in analysis.notes:
            add("")
            add(f"### {n.title}")
            add("")
            add(n.body.strip())
            add("")
            add(f"*Sources*: {_cite(n.source_keys)}")
    add("")
    add("## Source Register")
    add("")
    add("| Key | Kind | Reference | Retrieved | Note |")
    add("|---|---|---|---|---|")
    for s in analysis.sources:
        ref = s.ref if s.kind != "code" else f"`{s.ref}` @ `{analysis.pinned_commit[:12]}`"
        add(f"| `{s.key}` | {s.kind} | {ref} | {s.retrieved or '—'} | {s.note} |")
    add("")
    return "\n".join(lines)


def write_dossier(analysis: Analysis, dossier_dir: Path = DOSSIER_DIR) -> Path:
    dossier_dir.mkdir(parents=True, exist_ok=True)
    out = dossier_dir / f"{analysis.slug}.md"
    out.write_text(render_dossier(analysis), encoding="utf-8", newline="\n")
    return out


def render_matrix(analyses: list[Analysis]) -> str:
    """Render the Phase 2 comparative feature matrix. Pure function — deterministic.

    Groups every subject's features by category, then appends the adopt shortlist
    (the cherry-pick session's working set) sorted by future-proofing score.
    """
    lines: list[str] = []
    add = lines.append
    add("# Omni-Framework Feature Matrix (Phase 2)")
    add("")
    add("*Generated by `tools/matrix.py` from `analysis/*.toml` — do not edit by hand.*")
    add("")
    add(f"Subjects: {len(analyses)} · Features: {sum(len(a.features) for a in analyses)}")
    add("")
    by_category: dict[str, list[tuple[str, Feature]]] = {}
    for analysis in sorted(analyses, key=lambda a: a.slug):
        for feature in analysis.features:
            by_category.setdefault(feature.category, []).append((analysis.slug, feature))
    for category in sorted(by_category):
        add(f"## {category}")
        add("")
        add("| Subject | Feature | Maturity | Future-proofing | Omni relevance |")
        add("|---|---|---|---|---|")
        for slug, feature in sorted(by_category[category], key=lambda e: (e[1].name, e[0])):
            add(
                f"| {slug} | {feature.name} | {feature.maturity} |"
                f" {feature.future_proofing}/5 | **{feature.omni_relevance}** |"
            )
        add("")
    add("## Adopt shortlist (by future-proofing)")
    add("")
    add("| Score | Feature | Subject | Category |")
    add("|---|---|---|---|")
    adopts = [
        (analysis.slug, feature)
        for analysis in sorted(analyses, key=lambda a: a.slug)
        for feature in analysis.features
        if feature.omni_relevance == "adopt"
    ]
    for slug, feature in sorted(adopts, key=lambda e: (-e[1].future_proofing, e[1].name, e[0])):
        add(f"| {feature.future_proofing}/5 | {feature.name} | {slug} | {feature.category} |")
    add("")
    return "\n".join(lines)


def write_matrix(analyses: list[Analysis], out_path: Path | None = None) -> Path:
    out = out_path if out_path is not None else INTEL_DIR / "matrix.md"
    out.write_text(render_matrix(analyses), encoding="utf-8", newline="\n")
    return out
