# Release engineering — Phase 5

How Omni-Framework versions are cut, checksummed, and upgraded. Complements
`docs/TEST_MATRIX.md` and SOUL’s packaging rules.

## Versioning

| Component | Scheme | Source of truth |
|-----------|--------|-----------------|
| Rust workspace crates | `0.y.z` (workspace.package) | root `Cargo.toml` |
| OmniLoader | Gradle `version` | `OmniLoader/build.gradle.kts` / settings |
| Omni pack format | `schema: 0` in `omni.pack.json` | `omni-core::export` |
| Omni meta index | format v1 | `OmniMeta/format` |
| Census schema | SQL `CREATE` + migrate | `intelligence/census/schema.sql` |

Bump the workspace version in one commit when cutting a launcher/meta release.
Loader may lag or lead only with an ADR if the public SPI breaks.

## Release checklist

1. **Green matrix** — every Always-gate in `TEST_MATRIX.md` on a clean tree.
2. **CHANGELOG** — `CHANGELOG.md` entry under `[Unreleased]` moved to the version.
3. **Artifacts**
   - Launcher: `cargo build --release -p launcher-shell` (or `cargo tauri build` from
     `OmniLauncher/apps/launcher-shell` when packaging the desktop app).
   - Meta tools: `cargo build --release -p omni-meta-gen -p omni-meta-deploy`.
   - Loader: `cd OmniLoader; .\gradlew build` → `build/libs/omniloader-*.jar` and
     module distributions as produced by the bootstrap dist task.
4. **Checksums** — run `scripts/checksum-release.ps1 -Path <artifact-dir>` and
   publish the resulting `SHA256SUMS.txt` beside the binaries.
5. **Signatures** (when director keys exist) — sign the same file list; never
   commit private keys. Until a signing key is provisioned, checksums alone are
   the integrity surface (document that fact in the release notes).
6. **Meta CDN** — if the release includes a meta tree bump:
   `cargo run -p omni-meta-gen -- …` then `cargo run -p omni-meta-deploy -- …`
   with env-only credentials (ADR-0012/0016).
7. **Tag** — `git tag -a v0.y.z -m "…"`.

## Checksums

```powershell
# Example after a release build:
.\scripts\checksum-release.ps1 -Path .\dist
# Writes dist\SHA256SUMS.txt (sorted paths, lowercase hex)
```

Consumers verify with any SHA-256 tool that matches the file line format:

```
<hex>  <relative-path>
```

## Upgrade paths

| Change | Path |
|--------|------|
| Launcher SQLite schema | Forward-only embedded migrations (`schema_migrations`). Older DBs open and upgrade on first run. Never edit applied SQL files. |
| Omni pack `schema` | Bump integer; readers must reject unknown schemas with a typed error (export currently writes `0`). |
| Meta format v1 | New format version → new URL prefix or ADR; clients already hash-chain verify. |
| OmniLoader | Side-by-side install of the staged `loader-dist`; instances keep pointing at the staged runtime the launcher resolves. |
| Census DB | Network-sourced; wipe or re-fetch is safe. Migrations in `open_census_db`. |

## Export formats (ADR-0014)

Bidirectional migration is complete when import (Phase 4) and export (Phase 5)
both exist:

| Format | Export | Import |
|--------|--------|--------|
| Modrinth `.mrpack` | `export_instance` / `mrpack` | `MrpackImporter` |
| CurseForge pack | `export_instance` / `curseforge` | `CurseForgeImporter` |
| Omni native | `export_instance` / `omni` | (re-import via future reader; schema 0 documented) |

Foreign formats are never the store — only interchange.

## What is not automated yet

- Code signing certificates (director-held secrets).
- GitHub Releases upload (manual or future workflow job).
- Full dual-platform census freeze (Phase 6 C4; needs CF third-party key).
