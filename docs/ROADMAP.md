# Omni-Framework Roadmap

**Living document** — update status markers every delivery. Governed by `../SOUL.md`.
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

## Phase 4 — MVP Implementation ▶ (started 2026-07-18)

**Honest status**: two of the three scope items below are delivered. The **loader** is
not — bricks 1–9 built the launcher and meta service; the loader has 2 of its 12
modules. "Bricks delivered" is not the same as "phase complete", and the table below
is split so that stays visible.

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

### Launcher + meta: delivered. Loader: remaining bricks

Scope item 2 (`OmniLoader` boots the game and loads three kinds of mod) decomposes into
these. Ordered by dependency; difficulty is relative, and the last two are the highest-risk
work in the entire project.

| # | Brick | Difficulty | Status |
|---|---|---|---|
| 10 | `loader-core` + `loader-provider-api` + `provider-minecraft` — transforming classloader, partitions, boot sequence, vanilla headless boot | high | ✅ 2026-07-20 |
| 11 | `loader-resolve` — backtracking constraint search (ADR-0018), rule SPI, ordering, dedupe, unsat-core error rendering (LDR-5) | high | ✅ 2026-07-20 |
| 12 | `loader-transform` — AOT pipeline, hash-keyed cache, audit store, intermediary→official remapping (LDR-2, ADR-0010, ADR-0019) | **very high** | ✅ 2026-07-20 |
| 13 | `loader-mixin` — pinned Mixin behind `MixinServiceOmni`, refmap translation, compat levels (LDR-8, ADR-0009) | **very high** | ✅ 2026-07-20 |
| 14 | `omni-api` + a native test mod — proves the native path end to end | medium | ✅ 2026-07-20 |
| 15 | `compat-api` + `compat-fabric` — a real Fabric mod loads **alongside native mods** (metadata-level, CMP-2, ADR-0020) | **very high** | ▶ |
| 16 | `compat-neoforge` — a real NeoForge mod loads **in the same pack as Fabric and native mods** (full AOT translation, ADR-0020) | **extreme** | ⬜ |

Brick 16 is Sinytra-Connector-scale: the corpus dossier records that as multi-year work by
a team. Treating it as "one more brick" would be dishonest planning. Bricks 10–14 are
ordinary hard engineering; 15–16 are research-adjacent and should get their own scoping
pass before anyone commits to a date.

**Neither is done alone (ADR-0020).** The product thesis is mixed packs — any mod family
together in one instance — so each layer's exit criterion is its ecosystem loading
*alongside* the others, and a dedicated mixed-pack test gates both.

**Measured 2026-07-20** against Better MC [FABRIC] BMC3 (MC 1.21.1, 347 mods), which
replaced estimation with counts:

| Question | Answer |
|---|---|
| Manifests importing cleanly | 340/347 (98%) |
| Surface **we must implement** (`net.fabricmc.api.*`, `net.fabricmc.loader.api.*`) | **66 types**, used by 304/347 mods |
| Surface **we simply load** (`net.fabricmc.fabric.api.*`) | 385 types — ships as the fabric-api mod |
| Types carrying most of the weight | 6 outweigh the other 60 combined |

The 49% of mods declaring a `fabric-api` dependency do not need that API reimplemented;
they need one ordinary jar to load. That distinction is the difference between a bounded
job and an unbounded one.

### First live validation — 2026-07-20

The app was built, launched, and driven end to end. Everything upstream of the Launch
button is now proven against reality rather than fixtures:

| Capability | Live result |
|---|---|
| Meta service | Published and hash-chain verified over the public URL (20 objects, 0 mismatches) |
| Import | Director's real ATM10 pack: **324 mods**, 0 skipped, loader identified |
| Install | **1.06 GB** in ~27 min — 270 libraries/jars, 3,888 assets, Java 21 auto-provisioned |
| Repair | Full verify of 4,560 files in **34–61 s**, zero re-downloads |
| Resume | Interrupted and failed jobs resumed to completion, nothing fetched twice |
| Auth | MSA → XBL → XSTS all live; stops at Minecraft services with a typed 403 |

Four defects surfaced and were fixed the same session — all of the "only appears when the
system meets reality" kind: a job failing before it started was silently swallowed; a
stale `pending` job had no action; a meta error named no URL; and a retried-then-succeeded
job kept displaying its old failure.

**Exit criterion still open**: the game itself launching, which is gated on Mojang's
approval of the client ID (submitted 2026-07-19). Everything the Launch button depends on
is working. Walkthrough: `FIRST_RUN.md`.

Mojang's review is **unobservable**: no receipt, no status page, no approval notice
(director-confirmed 2026-07-20). Approval manifests only as the Minecraft-services step
ceasing to return 403. Two consequences worth stating plainly, so nobody re-derives them:

- **Silence carries no information.** Neither "no email" nor elapsed time tells us whether
  the submission landed, is queued, or was rejected. Any plan that waits for a signal is
  waiting for something that does not exist.
- **Retrying is the only probe**, so the check must be cheap and infrequent by design — a
  deliberate poll a human runs, never an automated retry loop. Hammering an approval
  endpoint is the fastest way to earn the denial we are trying to avoid.

The install and launch chain either side of that step is verified independently: the server
path needs no Microsoft account at all and is exercised end to end by
`cargo run -p omni-core --example server_smoke` (measured 2026-07-20: cold 17.0s, warm
6.0s, server jar hash-verified against its content-addressed name).

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
| Mojang approval of Azure client ID (form: aka.ms/mce-reviewappid) | MrWizard94 | ▶ **submitted 2026-07-19** (AppID + tenant + justification) — approval gates live end-to-end login only. **The process is opaque by design**: Mojang sends no submission receipt, publishes no status to check, and issues no approval notice (director-confirmed 2026-07-20). It simply starts working. So absence of mail is not evidence either way, and the *only* observable is retrying the auth chain — see the note under Phase 4's exit criterion. |
| Meta CDN hosting (R2 bucket `omni-meta` + `omnimeta.wpaistudio.net`) | MrWizard94 + AI | ✅ **LIVE 2026-07-20** — tree published and hash-chain verified over the public URL |
