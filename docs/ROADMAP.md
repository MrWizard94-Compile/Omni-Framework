# Omni-Framework Roadmap

**Living document** — update status markers every delivery. Governed by `../SOULv2.0.0.md`.
Director checkpoints (SOUL §29) close every phase.

Legend: ✅ complete · ▶ in progress · ⬜ not started

## Phase 0 — Foundation ✅ (2026-07-18)

Repo + remote (`MrWizard94-Compile/Omni-Framework`, private), governance docs wired to SOUL, monorepo layout, intelligence database schema + deterministic ingest/analyze/dossier pipeline.

## Phase 1 — Vivisection Corpus ✅ (2026-07-18)

Every subject gets: pinned-commit corpus fetch, structured analysis (components, features, file formats, auth flows, protocols — all with provenance), DB rows, and a generated dossier.

| Subject | Kind | Method | Status |
|---|---|---|---|
| Fabric Loader | loader | open-source deep | ✅ 2026-07-18 |
| Prism Launcher | launcher | open-source deep | ✅ 2026-07-18 |
| Quilt Loader | loader | open-source deep | ✅ 2026-07-18 |
| NeoForge (FancyModLoader) | loader | open-source deep | ✅ 2026-07-18 |
| MinecraftForge (legacy baseline) | loader | open-source deep | ✅ 2026-07-18 (comparative baseline) |
| SpongePowered Mixin | subsystem | open-source deep | ✅ 2026-07-18 |
| Sinytra Connector | compat layer | open-source deep | ✅ 2026-07-18 |
| MultiMC | launcher | open-source deep | ✅ 2026-07-18 (comparative baseline) |
| ATLauncher | launcher | open-source deep | ✅ 2026-07-18 |
| HMCL | launcher | open-source deep | ✅ 2026-07-18 |
| Modrinth App | launcher | open-source deep | ✅ 2026-07-18 (incl. daedalus meta + labrinth API notes) |
| GDLauncher | launcher | open-source deep | ✅ 2026-07-18 |
| CurseForge App | launcher | **black-box only** (ADR-0002) | ✅ 2026-07-18 |
| Official Minecraft Launcher | launcher | **black-box only** (ADR-0002) | ✅ 2026-07-18 |

Exit criteria: all subjects ingested; dossiers complete; comparative queries answer "who does X best?" for every major capability.

## Phase 2 — Feature Synthesis ✅ (2026-07-18)

Comparative feature matrix generated (`intelligence/matrix.md`, `tools/matrix.py`); director cherry-pick session held 2026-07-18 — 12 decisions (D1–D12) recorded in **`docs/REQUIREMENTS.md`** (the binding Omni-Framework Requirements Spec) and ADRs 0006–0008. Notable director override: **full server-instance support moved into the Phase 4 MVP**.

## Phase 3 — Architecture Design ✅ (2026-07-18)

Delivered: **`OmniLoader/ARCHITECTURE.md`** (module map, boot sequence, `omni.mod.json` v0, transform cache, compat SPI, Mixin integration), **`OmniLauncher/ARCHITECTURE.md`** (crate layering, SQLite schema v1 draft, auth, job runner, importers, servers), **`OmniMeta/README.md`** (meta service + index format v1), and ADRs 0009–0014 resolving the REQUIREMENTS §9 deferred list. Remaining §9 item deferred by design: SRG/legacy-Forge support awaits Phase 6 census data.

## Phase 4 — MVP Implementation ▶ (started 2026-07-18; all 9 bricks delivered 2026-07-20 — end-to-end run pending)

Scope per REQUIREMENTS.md (D7/D8 expanded it):
- OmniLauncher (Tauri) launches vanilla 1.21.1 end-to-end with real MSA login; **client and server instances**; all four migration importers (CurseForge App, Prism/MultiMC, Modrinth/.mrpack, official profiles).
- OmniLoader boots 1.21.1 and loads: one native Omni test mod, one Fabric mod via compat layer, one NeoForge mod via compat layer.
- Omni meta service generator + CDN deployment (SVC-1).

Delivery bricks (dependency-first; each lands complete, tested, zero-warning):

| # | Brick | Status |
|---|---|---|
| 1 | OmniMeta generator + `omni-meta-format` crate (snapshot/generate/verify, golden-tested, live-validated on real 1.21.1) | ✅ 2026-07-18 |
| 2 | `omni-meta-client` crate (launcher-side typed consumer, hash-verified, offline-tolerant) | ✅ 2026-07-18 |
| 3 | `omni-core` foundations: SQLite schema v1 + migrations, job runner (ADR-0015) | ✅ 2026-07-19 |
| 4 | Auth module (MSA chain per COMPLIANCE §2, device-code dev flow) + vault storage | ✅ 2026-07-19 |
| 5 | Install/launch pipeline (client, then server variant + EULA flow) | ✅ 2026-07-19 |
| 6 | Importers ×4 + fingerprinting/hash recovery | ✅ 2026-07-19 |
| 7 | Tauri shell + UI surfaces (contract: `OmniLauncher/COMMANDS.md`) | ✅ 2026-07-19 |
| 8 | OmniLoader Gradle multi-project: `loader-diag` + `loader-model` (`omni.mod.json` v0) per ARCHITECTURE §2 | ✅ 2026-07-20 |
| 9 | Meta CDN deployment tooling (`OmniMeta/deploy`; target `omnimeta.wpaistudio.net`, ADR-0016) | ✅ 2026-07-20 |

**Phase 4 exit criterion still open**: the end-to-end run — vanilla 1.21.1 actually
launching from the built app, with a real account and real Mojang data. Every brick is
tested in isolation and against fixtures; this is the first time they meet reality.
Walkthrough: `FIRST_RUN.md`. Requires the director at the keyboard (a window, a Microsoft
sign-in, a ~600 MB download) and, for live login, Mojang's pending approval.

## Phase 5 — Stabilization ⬜

Test matrix, zero-warning hardening across all three languages, docs completion, release engineering (SOUL §34): signed/checksummed artifacts, changelogs, upgrade paths.

## Phase 6 — Mod Census & Tiering ⬜

Index Modrinth + CurseForge public APIs into the DB. Tier every 1.21.1-relevant mod by **completeness / support / quality**. Output: ranked census capped at ≤50,000 candidates. (Scale reality: Modrinth ≈60k projects, CurseForge ≈180k — the cap is a filtering outcome, not a porting quota.)

## Phase 7 — Autonomous Porting Pipeline ⬜

Per-mod port queue for top tiers: license check → (if restrictive) author outreach with template → port → verify in mod-test-suite → publish. Compat layers carry everything not (yet) ported. Hard "no" from an author permanently excludes native porting of that mod (ADR-0003).

## Parallel Track — OmniMC (ADR-0017)

An original pure-Rust voxel game (own code/assets/identity, WASM-native mod API,
clean-room guardrails). **Not** part of Phases 4–7 and never their blocker; its own
architecture phase begins after the Phase 4 MVP ships. Charter: `../OmniMC_Rust Edition/README.md`.

## External Dependencies (human action items)

| Item | Owner | Status |
|---|---|---|
| Azure app registration for MSA OAuth | MrWizard94 | ✅ 2026-07-19 — "OmniLauncher", client ID `ff86f866-0ef7-4208-aa66-c17d8124dcc3`, settings verified compliant (ADR-0016) |
| Mojang approval of Azure client ID (form: aka.ms/mce-reviewappid) | MrWizard94 | ▶ **submitted 2026-07-19** (AppID + tenant + justification; reviews processed weekly) — approval gates live end-to-end login only |
| Meta CDN hosting (R2 bucket `omni-meta` + `omnimeta.wpaistudio.net`) | MrWizard94 + AI | ✅ **LIVE 2026-07-20** — tree published and hash-chain verified over the public URL |
