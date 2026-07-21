# Phase 6 — Mod Census & Tiering

Index every **Minecraft 1.21.1–relevant** mod on Modrinth and CurseForge, score
it on **completeness / support / quality**, and export a ranked candidate list
capped at **≤ 50,000** projects. Governing decision: **ADR-0022**. Compliance
envelope: `docs/COMPLIANCE.md` §5 (API keys, rate limits, distribution opt-outs).

This is **not** the vivisection pipeline (`analysis/*.toml` → `omni_intel.db`).
The census is network-sourced and resumable; its DB is a separate artifact.

## Data flow

```
platform APIs (Modrinth public + CurseForge Core)
        │
        ├── tools/census_index.py fetch   → omni_census.db  (projects + fetch_state)
        ├── tools/census_index.py enrich  → license/links + project_files hashes (C3)
        ├── tools/census_index.py score   → scores table
        ├── tools/census_index.py link    → project_links (hash → slug → name_author)
        └── tools/census_index.py export  → ranked snapshot + census/report.md
```

## Setup

Same venv as the rest of `intelligence/` (stdlib runtime; pytest/ruff/mypy for QA):

```powershell
cd intelligence
.venv\Scripts\python -m pip install -r requirements-dev.txt
```

**CurseForge** requires a **Core API key** (COMPLIANCE §5). Our HTTP transport is
fine (known-good keys get HTTP 200); almost every historical 403 here was a
**mangled key string** before it left the shell:

| Damage | Symptom | Fix |
|---|---|---|
| PowerShell `"$2a$10$..."` | `$2a`/`$10` eaten → `$.…` | **single quotes** or key file |
| Docker Compose `.env` | `$` doubled → `$$2a$$10$$…` | we collapse `$$`→`$` on read |
| Quotes left in the value | `'$2a$…'` including the quotes | stripped automatically |
| Wrong env name | only `CF_API_KEY` set | aliases: `CURSEFORGE_API_KEY`, `CF_API_KEY` |

Studios-only keys that are not approved for Minecraft still 403 with a
*well-shaped* key; apply at the third-party form if that is your case:
https://forms.monday.com/forms/dce5ccb7afda9a1c21dab1a1aa1d84eb?r=use1  
Guide: https://support.curseforge.com/en/support/solutions/articles/9000208346-about-the-curseforge-api-and-how-to-apply-for-a-key

```powershell
# Preferred: key file (shell never sees $)
Set-Content -NoNewline -Path $env:TEMP\omni-cf.key -Value '$2a$10$...'
$env:CURSEFORGE_API_KEY_FILE = "$env:TEMP\omni-cf.key"
# Or env with SINGLE quotes (double quotes corrupt $2a$10$...):
$env:CURSEFORGE_API_KEY = '$2a$10$...'   # never commit; never log
# Sanity (expect Length ~60 and Substring(0,7) = $2a$10$):
$env:CURSEFORGE_API_KEY.Length
$env:CURSEFORGE_API_KEY.Substring(0, 7)
# CLI:
.venv\Scripts\python tools\census_index.py fetch --platforms curseforge --cf-api-key-file $env:TEMP\omni-cf.key --max-pages 1
```

Modrinth needs no key. Both clients send a descriptive `User-Agent` and back off
on HTTP 429.
## Commands

```powershell
cd intelligence

# Full pipeline for 1.21.1 (resume-safe). --max-pages limits a smoke run.
.venv\Scripts\python tools\census_index.py run --target 1.21.1
.venv\Scripts\python tools\census_index.py run --target 1.21.1 --platforms modrinth --max-pages 2

# Individual stages
.venv\Scripts\python tools\census_index.py fetch  --target 1.21.1 --platforms modrinth,curseforge
.venv\Scripts\python tools\census_index.py enrich --target 1.21.1 --max-enrich 500
.venv\Scripts\python tools\census_index.py score  --target 1.21.1
.venv\Scripts\python tools\census_index.py link
.venv\Scripts\python tools\census_index.py export --target 1.21.1 --cap 50000

# Fetch without enrich (long crawls); enrich later in budgeted batches:
.venv\Scripts\python tools\census_index.py run --target 1.21.1 --platforms modrinth --skip-enrich

# Stats
.venv\Scripts\python tools\census_index.py status
```

Outputs:

| Artifact | Path | Committed? |
|----------|------|------------|
| Census DB | `omni_census.db` | no (git-ignored) |
| Ranked report | `census/report.md` | yes (reviewable snapshot of last export) |
| JSON ranks | `census/ranked.json` | no (git-ignored; regenerate) |

## C4 freeze (2026-07-21)

Dual-platform live index closed for Phase 7 intake:

| Metric | Value |
|---|---:|
| Modrinth projects | 28 260 |
| CurseForge projects | 10 000 (Core API search page ceiling @ 1.21.1) |
| Scored (target) | 38 260 |
| Enriched | 10 500 (CF all + MR top 500) |
| File rows | 92 178 |
| Cross-links | 6 825 (hash / slug / name_author) |
| Tiers | S 1057 · A 5009 · B 21 320 · C 10 871 · D 3 |
| Export snapshot | `3` → `census/report.md` (≤50 000 cap) |

Reviewed cut for Phase 7 intake: **S + A** only (6066 queue rows via `port_queue.py intake`).
B/C/D remain in the ranked export for ranking audit but are not auto-queued for native ports.

## Tier model (ADR-0022)

Every project with at least one file for the target game version is scored on
three 0–100 axes; the composite is a fixed weighted sum:

| Axis | Weight | What it measures |
|------|--------|------------------|
| Completeness | 0.35 | Target-version files, loader coverage, side, license, deps signal |
| Support | 0.35 | Recency of update, source/issues/discord/wiki presence |
| Quality | 0.30 | Downloads, followers, description substance, icon, categories |

Tiers (absolute thresholds on the composite):

| Tier | Composite |
|------|-----------|
| S | ≥ 80 |
| A | ≥ 65 |
| B | ≥ 50 |
| C | ≥ 35 |
| D | < 35 |

Ranking is deterministic: `composite DESC`, then `downloads DESC`, then
`(platform, platform_project_id) ASC`. The export keeps at most `--cap`
(default 50_000) rows of the target-version set.

CurseForge projects with `allow_distribution = 0` **remain in the census**
(Phase 7 outreach still needs to see them) but the report flags them so no
download path ever routes around the official app.

## Cross-platform identity (C3)

| Method | Confidence | Evidence |
|--------|------------|----------|
| `hash` | 0.99 | shared sha512 / sha1 / murmur2 on `project_files` |
| `slug` | 0.85 | case-insensitive slug |
| `name_author` | 0.70 | normalized title + author (both length ≥ 3) |
| `manual` | variable | future operator override |

A stronger method upgrades an existing link; a weaker one never downgrades it.
Hashes need an enrich pass (or CF search `latestFiles` during fetch) first.

## Tests

```powershell
.venv\Scripts\python -m pytest tests\test_census.py
.venv\Scripts\python -m ruff check tools\census_lib.py tools\census_modrinth.py tools\census_curseforge.py tools\census_enrich.py tools\census_index.py tests\test_census.py
.venv\Scripts\python -m mypy tools\census_lib.py tools\census_modrinth.py tools\census_curseforge.py tools\census_enrich.py tools\census_index.py
```

All network I/O is behind an injectable transport; CI never hits live APIs.
