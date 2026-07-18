# OmniLauncher

**Status: design target — implementation begins Phase 4** (see `../docs/ROADMAP.md`). This directory intentionally contains no code yet; per SOUL §1, code lands only as complete, tested, zero-warning bricks, and the launcher's requirements are an *output* of Phases 1–3 (vivisection → synthesis → architecture). This README records the locked design constraints so far.

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
