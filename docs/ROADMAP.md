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

## Phase 4 — MVP Implementation ✅ (2026-07-21)

**Status**: bricks 1–16 + B1–B4 complete for the Phase 4 bar. Live meta install verified
(`server_smoke`). Dedicated server spawn no longer incorrectly requires MSA (D8 vs CPL-2
scope clarified: entitlement gates **client play**, not server jar processes). Mixed-pack
fixtures on one `BootSequence`. Full FML event-bus / pack-scale NeoForge AOT remain
**follow-on**, not stubs. **Client window** vanilla 1.21.1: director-confirmed
playable (2026-07-21). Re-run steps in `docs/FIRST_RUN.md` anytime.

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
| 15 | `compat-api` + `compat-fabric` — Fabric claim→import→entrypoint **alongside** native (CMP-2, ADR-0020) | **very high** | ✅ 2026-07-21 — claimer + layer wired into discovery/boot; `MixedPackBootTest` |
| 16 | `compat-neoforge` — NeoForge claim→import→`@Mod` construct **alongside** others (ADR-0020) | **extreme** | ✅ 2026-07-21 **MVP path** (TOML + @Mod scan + construct). Full AOT/event-bus/remap = follow-on |

Brick 16 is Sinytra-Connector-scale: the corpus dossier records that as multi-year work by
a team. Treating it as "one more brick" would be dishonest planning. Bricks 10–14 are
ordinary hard engineering; 15–16 are research-adjacent and should get their own scoping
pass before anyone commits to a date.

### The missing middle: launcher ↔ loader (bricks B1–B4), scoped 2026-07-20

The first live launch of an imported pack exposed a gap no brick had named: the
**launcher and OmniLoader are two separate programs**. `omni-core` launches vanilla
Minecraft from the meta component stack; OmniLoader — everything bricks 10–15 built — is
never invoked, so a 324-mod NeoForge pack launched as bare 1.21.1 with its mods silently
ignored. Bricks 15 and 16 assumed a boot they were never wired into.

Director decision (2026-07-20): pursue the full mixed-loader goal (ADR-0020) rather than a
warn-and-refuse stopgap. That decomposes into:

| # | Brick | What it delivers | Difficulty |
|---|---|---|---|
| B1 | `loader-bootstrap` — a top-level module with a real `main`, orchestrating locate → discover → resolve → partition → entrypoint → launch as ONE flow | ✅ 2026-07-20 — first time bricks 10/11/14 run composed on one shared loader; native mod + game main both fire in dependency order, proven headlessly. Transform/mixin absent by design (native mods need neither; they arrive with B3). | **very high** |

**B1 finding (2026-07-20): the transforming classloader's parent is wrong for a real modded
boot.** `OmniTransformingClassLoader`'s parent is `getPlatformClassLoader()` (JDK only), and
its parent-first list shares `org.omniframework.loader.*` but not `org.omniframework.api.*`.
A native mod's entrypoint implements omni-api's `ModInitializer`; loaded through the
transforming loader it cannot resolve that type — the platform parent has neither omni-api
nor the loader runtime. Never fired yet: brick 10 booted vanilla (no mod links the API) and
brick 14 dispatched via a test-local loader. The first real modded boot would hit a baffling
`ClassNotFoundException`. B1's orchestration is blocked on introducing a proper loader-runtime
parent that exposes `org.omniframework.loader.*` and `org.omniframework.api.*` with shared
identity — a correction to brick 10's core classloader, done deliberately, not rushed.

**B1 delivered so far (2026-07-20): the discovery stage** (`loader-bootstrap` module,
`ModDiscovery` + `DiscoveredMods`). Walks a mods folder, parses native `omni.mod.json` into
resolver candidates, and separates jars awaiting a compat claimer — counted and reported,
never dropped. The orchestration that composes discovery with resolve/transform/mixin/
entrypoint/launch is the next unit, and starts from the classloader finding above.
| B2 | Launcher ↔ loader bridge — `omni-core` launch builds a command that boots through `loader-bootstrap` for loader-managed instances, routed by the imported loader kind and the staged distribution | ✅ 2026-07-21 — loader-side game/library handoff, staged JVM-verified distribution, `build_loader_command`, and `resolve_loader_runtime` routing. A modded instance now launches through OmniLoader; its mods still await B3's claimer. | high |
| B3 | `compat-neoforge` — NeoForge claim/import/@Mod construct through B1 | ✅ 2026-07-21 MVP; full pack translation follow-on | **extreme** |
| B4 | Mixed-pack proof — native + Fabric + NeoForge on one boot | ✅ 2026-07-21 `MixedPackBootTest` (fixtures) | high |

B1 is the shared prerequisite: bricks 15 and 16 both had their exit criteria amended to
"loads alongside others" (ADR-0020), and "alongside" has no meaning until a single boot
flow exists to load them into. So B1 comes before finishing either compat layer.

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

**Exit criterion: CLOSED 2026-07-21 (code + automated paths).** Mojang approval live.
Server install+spawn against live meta: `server_smoke --accept-eula --launch`. Loader
mixed fixtures: `MixedPackBootTest`. Client GUI vanilla 1.21.1: **director-confirmed
playable** (2026-07-21). Walkthrough retained in `FIRST_RUN.md` for re-verify.

The approval arrived exactly as their process promised — silently. No receipt, no status
page, no approval notice; the 403 simply stopped. Two probes were run, several hours
apart, deliberately and by hand. The first still 403'd; the second went straight through.
Nothing in the codebase changed between them.

That first successful sign-in immediately surfaced a defect **no test could have reached
before it**: the credential vault stored the Microsoft refresh token and the Minecraft
services token in one Windows credential, and real JWTs (1–2 KB each) blow past the
2560-character blob limit. Every prior test used fixtures that fit. Fixed by splitting the
account across two entries, with regression tests built from realistic token sizes.

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

## Phase 5 — Stabilization ✅ (2026-07-21)

Test matrix, zero-warning hardening across all three languages, docs completion, release engineering (SOUL §34): signed/checksummed artifacts, changelogs, upgrade paths. **ADR-0014 export writers landed.**

| Brick | Deliverable | Status |
|---|---|---|
| S1 | Instance export: `.mrpack`, CurseForge `manifest.json`+overrides, Omni `omni.pack.json` v0 (`omni-core::export`, IPC `export_instance`, golden tests) | ✅ 2026-07-21 |
| S2 | Test matrix doc (`docs/TEST_MATRIX.md`) + CI workflow (`.github/workflows/ci.yml`) for Rust / Gradle / intelligence | ✅ 2026-07-21 |
| S3 | Release engineering: `CHANGELOG.md`, `docs/RELEASE.md`, `scripts/checksum-release.ps1`, upgrade-path notes | ✅ 2026-07-21 |
| S4 | Docs sync (COMMANDS.md, ROADMAP/CLAUDE, root README status) | ✅ 2026-07-21 |

Phase 4 live-window findings remain tracked as ordinary bugs under the matrix; the formal Phase 5 **exit** is the gates and export surface above, not a second E2E product freeze.

## Phase 6 — Mod Census & Tiering ✅ (2026-07-21)

Index Modrinth + CurseForge public APIs into the DB. Tier every 1.21.1-relevant mod by **completeness / support / quality**. Output: ranked census capped at ≤50,000 candidates. (Scale reality: Modrinth ≈60k projects, CurseForge ≈180k — the cap is a filtering outcome, not a porting quota.)

Governing decision: **ADR-0022**. Tooling lives under `intelligence/` (separate from the vivisection DB):

| Brick | Deliverable | Status |
|---|---|---|
| C1 | Census schema + stdlib HTTP + Modrinth/CurseForge indexers + deterministic tier model + ranked export (`tools/census_*.py`, `census/schema.sql`) | ✅ 2026-07-21 |
| C2 | Full live index for 1.21.1 (both platforms; resume-safe) | ✅ 2026-07-21 — **Modrinth 28 260** + **CurseForge 10 000** (CF Core API search page cap @ gameVersion 1.21.1); key handling hardened (`CF_API_KEY` alias, key-file, `$$`/quote normalize) |
| C3 | Cross-platform identity: `project_files` hashes, link by hash/slug/name_author, Modrinth+CF enrich | ✅ 2026-07-21 — CF enrich **10 000**; MR top **500**; **92 178** files; **6 825** cross-links (hash 531 / slug 5628 / name_author 666) |
| C4 | Frozen ranked snapshot for Phase 7 intake (≤50k, reviewed tier cuts) | ✅ 2026-07-21 — snapshot `3`, **38 260** candidates ≤50k; tiers **S 1057 / A 5009 / B 21 320 / C 10 871 / D 3**; report `intelligence/census/report.md` |

```powershell
cd intelligence
.venv\Scripts\python tools\census_index.py run --target 1.21.1
.venv\Scripts\python tools\census_index.py status
```

## Phase 7 — Autonomous Porting Pipeline ▶ (foundation 2026-07-21)

Per-mod port queue for top tiers: license check → (if restrictive) author outreach with template → port → verify in mod-test-suite → publish. Compat layers carry everything not (yet) ported. Hard "no" from an author permanently excludes native porting of that mod (ADR-0003). Governing: **ADR-0023**.

| Brick | Deliverable | Status |
|---|---|---|
| P1 | License classifier + `omni_port.db` queue + C4 intake + human-gated outreach drafts (`tools/port_queue.py`, `porting/`) | ✅ 2026-07-21 — batch `c4-1.21.1-2026-07-21`: **6066** S+A rows; **641** auto-permitted (permissive); outreach/review for the rest |
| P2 | Bulk outreach send + CRM (mail/Discord) | ⬜ human process; tool only **drafts** by design |
| P3 | Native port execution harness | ⬜ |
| P4 | mod-test-suite verification hook | ⬜ |
| P5 | Publish / attribution packaging | ⬜ |

**SOUL honesty:** Phase 7 is **not** complete. Only the intake/license foundation (P1) is production-ready. Autonomous rewrite/verify/publish remain multi-session bricks and must not be marked done until they exist as complete, tested systems.

### Mod-pack loadability (post–Phase 4 hardening, 2026-07-21)

| Piece | Status |
|---|---|
| Stage jars into `game/mods/` on import + launch | ✅ `import::stage` |
| Route loader-managed launch via OmniLoader (`OMNI_LOADER_DIST`) | ✅ auto-probe monorepo `loader-dist` |
| Fabric claim + entrypoints + `FabricLoader.getInstance()` | ✅ |
| Client/server Fabric entrypoint phases | ✅ |
| Intermediary→official remap on boot | ✅ compose Fabric intermediary Tiny + Mojang proguard; `TransformService.ensure` |
| Mixin on boot dist | ✅ `loader-mixin` on dist; install + `MixinClassTransformer` (best-effort) |
| Access-widener merge on boot | ✅ `BootTransform` + `AccessMergePass` |
| `OmniLoaderApi` installed before entrypoints | ✅ |
| mrpack remote download of mods | ✅ `pendingDownloads` + install phase `mod-files` + overrides extract |
| `omni-platform` (LNC-9) | ✅ crate + Modrinth ResourceQuery; CF search + opt-out types |
| NeoForge per-mod event bus (construct path) | ✅ hosted `IEventBus` + activate-before-create + `forMod` post; `NeoForgePerModBusTest` |
| NeoForge FML setup lifecycle (common/client/server) | ✅ `NeoForgeLifecycle.fireSetup` + hosted FML*SetupEvent types |
| NeoForge pack-scale AOT / AT / full FML parity | ⬜ residual Sinytra-scale (AOT remap still boot-global for hosted jars) |
| LNC-10 resource/shader/data/world folder model | ✅ `resources` module + UI toggle + headless tests |
| Content browse UI over `omni-platform` | ✅ Browse tab + `search_content` / install IPC |
| CF fingerprint bulk + opt-out download typing | ✅ `files_by_fingerprints` POST + `file_download` blocked path |
| SVC-2 multi-provider failover | ✅ `FailoverBlobSource` |
| System JVM discovery | ✅ `java_discover` + Settings scan |
| Server port/config UX | ✅ `server_config` + Instances server form |
| Boot failure tree UI | ✅ `omniloader-issues.json` dump + Instances issue panel |

## Parallel Track — OmniMC (ADR-0017)

An original pure-Rust voxel game (own code/assets/identity, WASM-native mod API,
clean-room guardrails). **Not** part of Phases 4–7 and never their blocker; its own
architecture phase begins after the Phase 4 MVP ships. Charter: `../OmniMC_Rust Edition/README.md`.

## External Dependencies (human action items)

| Item | Owner | Status |
|---|---|---|
| Azure app registration for MSA OAuth | MrWizard94 | ✅ 2026-07-19 — "OmniLauncher", client ID `ff86f866-0ef7-4208-aa66-c17d8124dcc3`, settings verified compliant (ADR-0016) |
| Mojang approval of Azure client ID (form: aka.ms/mce-reviewappid) | MrWizard94 | ✅ **APPROVED 2026-07-20** — verified by live end-to-end sign-in, not by any notice. Their review is unobservable by design (no receipt, no status, no approval mail); it simply started working. Submitted 2026-07-19. |
| Meta CDN hosting (R2 bucket `omni-meta` + `omnimeta.wpaistudio.net`) | MrWizard94 + AI | ✅ **LIVE 2026-07-20** — tree published and hash-chain verified over the public URL |
