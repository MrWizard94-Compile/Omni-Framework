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

## Phase 2 — Feature Synthesis ⬜

Comparative feature matrix generated from the DB; future-proofing scores; **director cherry-pick session** (user input is a hard requirement here) → `docs/REQUIREMENTS.md` (Omni-Framework Requirements Spec).

## Phase 3 — Architecture Design ⬜

Full ADR set. OmniLoader: bootstrap chain, mod discovery, metadata format, transformation pipeline, compat-layer SPI. OmniLauncher: instance model, MSA auth, version/asset pipeline, Java runtime management, mod-platform clients. Protocol and file-format specs written before code (SOUL §7, §24).

## Phase 4 — MVP Implementation ⬜

- OmniLauncher (Tauri) launches vanilla 1.21.1 end-to-end with real MSA login.
- OmniLoader boots 1.21.1 and loads: one native Omni test mod, one Fabric mod via compat layer, one NeoForge mod via compat layer.

## Phase 5 — Stabilization ⬜

Test matrix, zero-warning hardening across all three languages, docs completion, release engineering (SOUL §34): signed/checksummed artifacts, changelogs, upgrade paths.

## Phase 6 — Mod Census & Tiering ⬜

Index Modrinth + CurseForge public APIs into the DB. Tier every 1.21.1-relevant mod by **completeness / support / quality**. Output: ranked census capped at ≤50,000 candidates. (Scale reality: Modrinth ≈60k projects, CurseForge ≈180k — the cap is a filtering outcome, not a porting quota.)

## Phase 7 — Autonomous Porting Pipeline ⬜

Per-mod port queue for top tiers: license check → (if restrictive) author outreach with template → port → verify in mod-test-suite → publish. Compat layers carry everything not (yet) ported. Hard "no" from an author permanently excludes native porting of that mod (ADR-0003).

## External Dependencies (human action items)

| Item | Owner | Status |
|---|---|---|
| Azure app registration for MSA OAuth | MrWizard94 | ⏸ deferred until Phase 4 nears (director decision, 2026-07-18) |
| Mojang approval of Azure client ID (form) | MrWizard94 | ⬜ blocked on above |
