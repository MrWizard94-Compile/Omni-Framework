# Intelligence — the Vivisection Database

This directory is the Omni-Framework's institutional memory about every launcher and loader that came before it (Phase 1 of `../docs/ROADMAP.md`). Governing decisions: ADR-0002 (reverse-engineering policy) and ADR-0004 (pipeline design) in `../docs/DECISIONS.md`.

## Data flow

```
analysis/<slug>.toml     hand-audited facts w/ provenance   ← SOURCE OF TRUTH (committed)
        │
        ├── tools/ingest.py   → corpus/<slug>/       shallow checkout @ pinned commit (ignored)
        ├── tools/analyze.py  → omni_intel.db        SQLite, rebuilt from scratch     (ignored)
        └── tools/dossier.py  → dossiers/<slug>.md   readable report                  (committed)
```

- **Every fact carries provenance**: `source_keys` on each row must resolve to the subject's source register (`[[sources]]`), which points at exact repo paths at the pinned commit, or dated URLs. `analyze.py` enforces this — an analysis without provenance does not load.
- **Determinism** (SOUL §21): `analyze.py` always rebuilds the whole DB from the TOML files; two runs produce byte-identical dumps (tested). Dossiers are pure renderings, committed so drift shows as a git diff.
- **The corpus is never committed** — subjects remain upstream's property under upstream's licenses; `ingest.py` re-fetches them at pinned commits on demand.

## Setup (once)

```powershell
py -3.14 -m venv .venv
.venv\Scripts\python -m pip install -r requirements-dev.txt
```

Runtime code is stdlib-only; the venv exists solely for the QA toolchain (pytest / ruff / mypy).

## Commands

```powershell
.venv\Scripts\python tools\ingest.py --all       # fetch corpus at pinned commits
.venv\Scripts\python tools\analyze.py --all      # rebuild omni_intel.db + print stats
.venv\Scripts\python tools\dossier.py --all      # regenerate dossiers/*.md

.venv\Scripts\python -m pytest                   # must be green
.venv\Scripts\python -m ruff check .             # must be silent
.venv\Scripts\python -m mypy                     # must be silent
```

## Adding a subject (the vivisection workflow)

1. Create `analysis/<slug>.toml` with the `[subject]` block (`repo_url`, `pinned_commit = ""` initially).
2. `tools\ingest.py --subject <slug>` — fetches HEAD, prints the sha; pin it in the TOML.
3. Study the corpus. Record components, features (with `future_proofing` 1–5 and `omni_relevance` adopt/adapt/observe/avoid), file formats, auth flows, protocols, notes — each with `source_keys`.
4. `analyze.py --all` + `dossier.py --subject <slug>`; review the dossier like a PR.
5. Update the Phase 1 table in `../docs/ROADMAP.md` and the Current State block in `../CLAUDE.md`.

Black-box subjects (ADR-0002) skip steps 2–3's corpus fetch and cite only `url`/`doc` sources — public documentation, published APIs, and on-disk layouts of our own legitimate installs.

## Comparative queries (Phase 2 preview)

```sql
-- Who does what, per capability:
SELECT * FROM feature_matrix WHERE category = 'auth';
-- What should Omni adopt outright?
SELECT slug, name FROM feature_matrix WHERE omni_relevance = 'adopt' ORDER BY future_proofing DESC;
-- Corpus health:
SELECT * FROM subject_stats;
```
