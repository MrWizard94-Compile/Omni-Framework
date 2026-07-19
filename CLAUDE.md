# CLAUDE.md — Omni-Framework Contributor Onboarding

## Law

**`SOULv2.0.0.md` is the constitution of this project. SOUL IS LAW.** Every delivery must pass the SOUL §0 fifteen-point pre-delivery checklist. Complete, production-ready, zero-warning bricks only. Document obsessively. When SOUL and convenience conflict, SOUL wins.

## Identity & Branding

- Project: **Omni-Framework** (components: **OmniLauncher**, **OmniLoader**).
- Company: **Wizard Productions AI Studio** — wpaistudio.net. Director: **MrWizard94** (GitHub: MrWizard94-Compile).
- Spelling: it is always "Framework". The historical "Framwork" typo was eradicated 2026-07-18 (repo renamed; see docs/DECISIONS.md ADR-0005). Never reintroduce it.

## Current State (update this section every delivery)

- **Phase 0 (Foundation): complete** — repo, governance, intelligence pipeline.
- **Phase 1 (Vivisection Corpus): complete (14/14, 2026-07-18)** — all subjects in the DB: 4 loader-family, 8 launchers (incl. 2 black-box per ADR-0002), Mixin, Sinytra Connector. 71 components, 103 scored features, 213 provenance sources.
- **Phase 2 (Feature Synthesis): complete (2026-07-18)** — matrix generated (`intelligence/matrix.md`); director cherry-pick session held; **`docs/REQUIREMENTS.md` is now binding** (12 decisions D1–D12; ADRs 0006–0008). Director override: full server support in the Phase 4 MVP.
- **Phase 3 (Architecture Design): complete (2026-07-18)** — binding specs: `OmniLoader/ARCHITECTURE.md`, `OmniLauncher/ARCHITECTURE.md`, `OmniMeta/README.md`; ADRs 0009–0014. Changes to these require a superseding ADR.
- **Phase 4 (MVP Implementation): in progress (started 2026-07-18)** — brick order in ROADMAP Phase 4 table. **Bricks 1–3 delivered**: (1) OmniMeta generator + `omni-meta-format` crate (root Cargo workspace; snapshot/generate/verify pipeline; deterministic golden-tested output; live-validated against real Mojang 1.21.1 + java-runtime data); (2) `omni-meta-client` (hash-chain verification, content-addressed self-healing cache, offline root fallback); (3) `omni-core` foundations (2026-07-19, ADR-0015: rusqlite bundled, schema v1 + embedded migrations, typed store, resumable job runner proven for every interrupt point, event-stream foundation). Rust QA gates: `cargo test --workspace`, `cargo clippy --workspace --all-targets` — both must be silent/green.
- External items resolved 2026-07-19 (ADR-0016): Azure app "OmniLauncher" registered + verified compliant (client ID `ff86f866-0ef7-4208-aa66-c17d8124dcc3` — public config, used in brick 4); meta hosting provisioned (R2 `omni-meta` bucket, `omnimeta.wpaistudio.net`, min TLS 1.2). Still pending: Mojang third-party-launcher approval of the client ID (gates live end-to-end login only; fixtures-first dev proceeds).
- Flag for director (carried from Phase 3): corpus HEAD is at MC 26.x — 1.21.1 launch target reconfirmed as TGT-1; version-agnostic core (TGT-2) covers the follow.
- OmniLauncher/OmniLoader: design targets in their ARCHITECTURE.md files (binding); launcher crates land in the root Cargo workspace as bricks 2–7; loader is a separate Gradle multi-project (brick 8).

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
