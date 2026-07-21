# Changelog

All notable changes to Omni-Framework are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions follow the workspace package version in `Cargo.toml` unless noted.

## [Unreleased]

### Added

- **Phase 4 — MVP close-out (2026-07-21)**
  - Fabric + NeoForge compat layers wired into discovery/boot (`FabricCompatLayer`,
    `NeoForgeCompatLayer`, claimers, TOML/@Mod scan).
  - `MixedPackBootTest`: native + Fabric + NeoForge fixtures initialise on one
    `BootSequence` (ADR-0020 fixture exit).
  - Shared classloader prefixes for `net.fabricmc.api.*` / `net.fabricmc.loader.api.*`.

- **Phase 5 — Stabilization**
  - Instance **export** (ADR-0014): `mrpack`, CurseForge `manifest.json` + overrides,
    and Omni `omni.pack.json` schema 0 (`omni-core::export`, `export_instance` command).
  - Golden tests in `omni-core/tests/export.rs`; headless app contract coverage.
  - `docs/TEST_MATRIX.md` — three-language QA gate list.
  - `docs/RELEASE.md` — versioning, checksums, upgrade paths.
  - `scripts/checksum-release.ps1` — deterministic SHA-256 manifests for release artifacts.
  - CI workflow `.github/workflows/ci.yml` (Rust, OmniLoader, intelligence).

- **Phase 6 — Mod census (in progress)**
  - Census pipeline C1/C3; Modrinth 1.21.1 live index (see `intelligence/census/`).
  - CurseForge half blocked on third-party API key approval.

### Changed

- Root / CLAUDE / ROADMAP status for Phase 5 completion and Phase 6 progress.

## [0.1.0] — 2026-07-18..2026-07-21 (founding window)

### Added

- Phase 0–3: governance (SOUL), intelligence vivisection (14 subjects), REQUIREMENTS,
  architecture specs, ADRs through 0021.
- Phase 4 MVP bricks: OmniMeta generator/client/deploy; `omni-core` (store, auth, install,
  launch, import, app); Tauri shell + Vue UI; OmniLoader modules through bootstrap +
  launcher↔loader bridge (B1–B2); live MSA approval and meta CDN.

### Notes

- Phase 4 exit (vanilla 1.21.1 window launch) remains a director keyboard walkthrough
  (`docs/FIRST_RUN.md`).
- Mixed-pack thesis (ADR-0020) still needs Fabric finish + NeoForge (B3–B4).
