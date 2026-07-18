# CLAUDE.md — Omni-Framework Contributor Onboarding

## Law

**`SOULv2.0.0.md` is the constitution of this project. SOUL IS LAW.** Every delivery must pass the SOUL §0 fifteen-point pre-delivery checklist. Complete, production-ready, zero-warning bricks only. Document obsessively. When SOUL and convenience conflict, SOUL wins.

## Identity & Branding

- Project: **Omni-Framework** (components: **OmniLauncher**, **OmniLoader**).
- Company: **Wizard Productions AI Studio** — wpaistudio.net. Director: **MrWizard94** (GitHub: MrWizard94-Compile).
- Spelling: it is always "Framework". The historical "Framwork" typo was eradicated 2026-07-18 (repo renamed; see docs/DECISIONS.md ADR-0005). Never reintroduce it.

## Current State (update this section every delivery)

- **Phase 0 (Foundation): complete** — repo, governance, intelligence pipeline.
- **Phase 1 (Vivisection Corpus): in progress (8/14)** — subjects done: `fabric-loader`, `prism-launcher`, `quilt-loader`, `fancymodloader`, `sinytra-connector`, `spongepowered-mixin`, `modrinth-app`, `multimc`. Next: MinecraftForge (legacy baseline), ATLauncher, HMCL, GDLauncher, plus black-box dossiers (CurseForge app, official launcher).
- Key Phase 3 input already banked: the compat-layer "bill of materials" (sinytra-connector dossier) and the two-strategy compat comparison (quilt-loader vs sinytra-connector notes). Flag for director: corpus HEAD is at MC 26.x — re-confirm the 1.21.1 launch target in Phase 3.
- OmniLauncher/OmniLoader: design targets documented in their READMEs; **no code until Phase 4** by design (see docs/ROADMAP.md).

## Hard Rules (project-specific, additive to SOUL)

1. **EULA compliance is absolute.** Only the official Microsoft OAuth flow for login. Never handle Mojang/Microsoft passwords. Never enable piracy or offline-auth bypasses. See docs/COMPLIANCE.md.
2. **Reverse-engineering policy** (ADR-0002): open-source subjects get full source vivisection; proprietary software (CurseForge app, official launcher) gets black-box study of documented/public behavior only. Never decompile proprietary binaries.
3. **Provenance or it didn't happen**: every fact in the intelligence DB must have a `sources` row (file path + pinned commit, or URL + retrieval date).
4. **Corpus is never committed.** `intelligence/corpus/` is git-ignored; subjects are re-fetched at pinned commits by `ingest.py`.
5. **Mod licensing**: compat layers load third-party jars unmodified (always legal); native ports only where the license permits or the author grants written permission; a hard "no" is final (ADR-0003).

## Conventions

- Intelligence tooling: Python 3.14, stdlib-only at runtime (`sqlite3`, `tomllib`); dev tools pytest/ruff/mypy via `intelligence/.venv`. Strict typing, ruff clean, mypy clean.
- Analysis files (`intelligence/analysis/*.toml`) are the **versioned source of truth**; the SQLite DB and dossiers are deterministic build artifacts (`omni_intel.db` ignored, `dossiers/*.md` committed for review).
- Commits: atomic, imperative, each leaves the tree buildable (SOUL §15).
- Docs live next to what they describe; `docs/` holds the cross-cutting ones.

## Commands

```powershell
# From intelligence/ — full deterministic rebuild:
.venv\Scripts\python tools\ingest.py --all      # fetch corpus at pinned commits
.venv\Scripts\python tools\analyze.py --all     # TOML analyses -> omni_intel.db
.venv\Scripts\python tools\dossier.py --all     # DB -> dossiers/*.md

# QA gates (all must be silent/green before any delivery):
.venv\Scripts\python -m pytest
.venv\Scripts\python -m ruff check .
.venv\Scripts\python -m mypy tools
```
