# CLAUDE.md — Omni-Framework Contributor Onboarding

## Law

**`SOULv2.0.0.md` is the constitution of this project. SOUL IS LAW.** Every delivery must pass the SOUL §0 fifteen-point pre-delivery checklist. Complete, production-ready, zero-warning bricks only. Document obsessively. When SOUL and convenience conflict, SOUL wins.

## Identity & Branding

- Project: **Omni-Framework** (components: **OmniLauncher**, **OmniLoader**). Parallel track: **OmniMC** — an original pure-Rust voxel game (ADR-0017; NOT a Minecraft rebuild — original content only, clean-room, Java-mod interop out of scope; charter in `OmniMC_Rust Edition/README.md`).
- Company: **Wizard Productions AI Studio** — wpaistudio.net. Director: **MrWizard94** (GitHub: MrWizard94-Compile).
- Spelling: it is always "Framework". The historical "Framwork" typo was eradicated 2026-07-18 (repo renamed; see docs/DECISIONS.md ADR-0005). Never reintroduce it.

## Current State (update this section every delivery)

- **Phase 0 (Foundation): complete** — repo, governance, intelligence pipeline.
- **Phase 1 (Vivisection Corpus): complete (14/14, 2026-07-18)** — all subjects in the DB: 4 loader-family, 8 launchers (incl. 2 black-box per ADR-0002), Mixin, Sinytra Connector. 71 components, 103 scored features, 213 provenance sources.
- **Phase 2 (Feature Synthesis): complete (2026-07-18)** — matrix generated (`intelligence/matrix.md`); director cherry-pick session held; **`docs/REQUIREMENTS.md` is now binding** (12 decisions D1–D12; ADRs 0006–0008). Director override: full server support in the Phase 4 MVP.
- **Phase 3 (Architecture Design): complete (2026-07-18)** — binding specs: `OmniLoader/ARCHITECTURE.md`, `OmniLauncher/ARCHITECTURE.md`, `OmniMeta/README.md`; ADRs 0009–0014. Changes to these require a superseding ADR.
- **Phase 4 (MVP Implementation): in progress (started 2026-07-18)** — brick order in ROADMAP Phase 4 table. **All 9 bricks delivered (2026-07-20)**: (1) OmniMeta generator + `omni-meta-format` crate (root Cargo workspace; snapshot/generate/verify pipeline; deterministic golden-tested output; live-validated against real Mojang 1.21.1 + java-runtime data); (2) `omni-meta-client` (hash-chain verification, content-addressed self-healing cache, offline root fallback); (3) `omni-core` foundations (2026-07-19, ADR-0015: rusqlite bundled, schema v1 + embedded migrations, typed store, resumable job runner proven for every interrupt point, event-stream foundation); (4) `omni-core::auth` (2026-07-19: full COMPLIANCE §2 chain with XErr taxonomy, keyring vault, ADR-0011 window + CPL-2 launch gate, fixture-tested; live device-code endpoint probe green via `examples/auth_probe.rs`); (5) `omni-core::{install,launch,platform}` (2026-07-19: content-addressed blob cache, meta-resolved resumable install jobs incl. assets + auto-Java, argument/classpath assembly, gated spawn + process recording, server variant with explicit EULA acceptance); (6) `omni-core::import` (2026-07-19: all four importers + hash recovery; real-ATM10 validated — 324 mods, 319/319 murmur2 fingerprints exact); (7) `omni-core::app` + `apps/launcher-shell` (Tauri 2) + `apps/launcher-ui` (Vue 3/TS/Tailwind, six §7 surfaces) — IPC contract in `OmniLauncher/COMMANDS.md`, pinned by wire-shape tests; shell holds no logic, so all 20 contract tests run headlessly; (8) OmniLoader Gradle multi-project — `loader-diag` (typed issues) + `loader-model` (canonical model, semver, version predicates, `omni.mod.json` v0 parser, zero-dependency strict JSON reader), Java 21 toolchain with -Werror, 85 tests; (9) `OmniMeta/deploy` (R2 publish: deepest-first ordering, per-object cache policy, env-only credentials, SigV4 against published vectors, public-URL verification). QA gates: `cargo test --workspace`, `cargo clippy --workspace --all-targets` — both must be silent/green.
- External items resolved 2026-07-19 (ADR-0016): Azure app "OmniLauncher" registered + verified compliant (client ID `ff86f866-0ef7-4208-aa66-c17d8124dcc3` — public config, used in brick 4); meta hosting provisioned (R2 `omni-meta` bucket, `omnimeta.wpaistudio.net`, min TLS 1.2). Still pending: Mojang third-party-launcher approval of the client ID (gates live end-to-end login only; fixtures-first dev proceeds).
- **Phase 4 exit criterion open**: the end-to-end run (vanilla 1.21.1 launching from the built app). Walkthrough in `docs/FIRST_RUN.md`; needs the director at the keyboard, and live login additionally needs Mojang's pending client-ID approval.
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
# Rust workspace (OmniMeta + OmniLauncher) — the Phase 4 QA gate:
cargo test --workspace
cargo clippy --workspace --all-targets     # all + pedantic; must be silent

# OmniLoader (from OmniLoader/) — Java 21, -Werror:
./gradlew build

# OmniLauncher UI (from OmniLauncher/apps/launcher-ui):
npm install; npm run build                 # strict vue-tsc typecheck + bundle
# Full desktop app (needs `cargo install tauri-cli --version "^2"`):
cargo tauri dev                            # from OmniLauncher/apps/launcher-shell

# From intelligence/ — full deterministic rebuild:
.venv\Scripts\python tools\ingest.py --all      # fetch corpus at pinned commits
.venv\Scripts\python tools\analyze.py --all     # TOML analyses -> omni_intel.db
.venv\Scripts\python tools\dossier.py --all     # DB -> dossiers/*.md

# QA gates (all must be silent/green before any delivery):
.venv\Scripts\python -m pytest
.venv\Scripts\python -m ruff check .
.venv\Scripts\python -m mypy tools
```
