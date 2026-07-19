# OmniLauncher

**Status: Phase 4 implementation in progress.** The binding design is **[`ARCHITECTURE.md`](ARCHITECTURE.md)** (Phase 3 output, 2026-07-18): crate layering, SQLite schema draft, auth module, job-runner install pipeline, importer set, server instances. This README records the high-level constraints; the architecture doc governs.

Landed so far (brick order in `../docs/ROADMAP.md` Phase 4):

- **`crates/omni-meta-client`** (2026-07-18) — the LNC-3 meta consumer: hash-chain-verified
  fetch of the OmniMeta `/v1` tree, content-addressed self-healing cache, offline root
  fallback with an explicit `Freshness` marker (SVC-2). Transport-abstracted
  (`HttpSource` for the CDN, `DirSource` for local trees); synchronous by design —
  `omni-core` wraps it in its async runtime's blocking facility. Tested against the
  generator's committed golden tree, so both sides of the wire share one fixture.
- **`crates/omni-core`** (2026-07-19, foundations) — the headless core (LNC-1/LNC-2/D5,
  ADR-0015): bundled-SQLite database with WAL + forward-only embedded migrations
  (schema v1: instances as component stacks, mods as entities, accounts holding vault
  refs only, java runtimes, process sessions, settings, jobs); typed repositories whose
  enums mirror every CHECK constraint; the resumable job runner with a guarded state
  machine and a persisted resume point after every step — the "any prefix resumes to
  completion" property from ARCHITECTURE §8 is a test that runs for every interrupt
  point; the typed event stream foundation (`CoreEvent`/`EventSink`). Auth, installs,
  and importers (bricks 4–6) build on these primitives.
- **`omni-core::auth`** (2026-07-19, brick 4) — the COMPLIANCE §2 chain exactly:
  device-code + PKCE-loopback Microsoft OAuth (consumers authority), XBL → XSTS with
  the full `XErr` taxonomy as typed, remedy-bearing errors, Minecraft-services login /
  entitlement / profile. Tokens live only in the OS credential vault (`keyring`,
  trait-abstracted; in-memory vault for tests); the ADR-0011 14-day entitlement window
  is stamped in the vault record AND the accounts row on every verification and slides
  on silent renewal; `AuthService::launch_gate` is the single CPL-2 authority (online
  verify → offline-within-window → hard stop; auth refusals never open the offline
  path). Fixture-driven tests cover the whole chain; `examples/auth_probe.rs` is the
  live-endpoint probe / dev sign-in CLI (validated against the real device-code
  endpoint with the ADR-0016 client ID).
- **`omni-core::{install, launch, platform}`** (2026-07-19, brick 5) — the LNC-5/6
  pipeline: shared content-addressed sha1 blob cache with self-healing verification
  (hmcl cross-instance lesson), meta-resolved install plans (client.json rule
  evaluation, LWJGL requires-closure, official asset layout, auto-Java from the
  `net.minecraft.java` component with runtime registration), and installs as resumable
  jobs whose batched phases make a re-run a free repair pass (modrinth-app lesson).
  Launch: argument assembly for both argument eras with feature flags, classpath over
  the immutable cache (per-instance game dirs — MUST NOT #4), gated spawn with session
  logs + process rows, and the D8 server variant behind an explicit, recorded EULA
  acceptance. Version-scope note: extract-era natives are post-MVP (TGT-1 is 1.21.1,
  where natives ride the classpath), documented in `launch`.

## What it will be

The Omni-Framework's desktop launcher: instance management, Microsoft-account login, version/asset pipeline, Java runtime management, and mod-platform integration — synthesized from the best of Prism, MultiMC, ATLauncher, HMCL, Modrinth App, GDLauncher (vivisection evidence in `../intelligence/`).

## Locked constraints (ADRs)

- **Stack**: Tauri — Rust core, TypeScript/web UI (ADR-0001). Rationale: smallest footprint beside a game JVM, memory-safe core, best future-proofing signal in the launcher ecosystem.
- **Auth**: official Microsoft OAuth chain only, tokens in the OS credential vault, no passwords ever (`../docs/COMPLIANCE.md` §2).
- **Game files**: fetched from Mojang's official CDNs per instance; never redistributed.
- **Mod downloads**: through official CurseForge/Modrinth APIs so authors keep credit and rewards.

## Inputs it is waiting on

1. Phase 1 complete: launcher feature inventory across all subjects.
2. Phase 2: director cherry-pick → `../docs/REQUIREMENTS.md`.
3. Phase 3: ADRs for instance model, meta pipeline, runtime management, UI architecture.
4. External: Azure client ID + Mojang approval (tracked in `../docs/ROADMAP.md`).
