# OmniLauncher

**Status: Phase 4/5 product MVP + LNC-9 platform clients + mrpack CDN download.**
Binding design: [`ARCHITECTURE.md`](ARCHITECTURE.md). IPC contract:
[`COMMANDS.md`](COMMANDS.md).

## Stack

- **`crates/omni-core`** — headless core (SQLite, jobs, auth, install, launch,
  import/export, command API)
- **`crates/omni-meta-client`** — OmniMeta `/v1` consumer (LNC-3)
- **`crates/omni-platform`** — CurseForge + Modrinth typed clients + `ResourceQuery` (LNC-9)
- **`apps/launcher-shell`** — Tauri 2 thin command wiring
- **`apps/launcher-ui`** — Vue 3 + TypeScript + Tailwind surfaces

## Build / test

```powershell
# from monorepo root
cargo test --workspace
cargo clippy --workspace --all-targets

cd OmniLauncher\apps\launcher-ui
npm install
npm run build

cd ..\launcher-shell
cargo tauri dev
```

## Recent completeness work

| Item | Status |
|------|--------|
| mrpack `downloads[]` → install job phase `mod-files` | Done |
| mrpack `overrides/` + `client-overrides/` extract on import | Done |
| `omni-platform` (Modrinth search/hash; CF search + key/opt-out types) | Done |
| Export UI (mrpack / CurseForge / Omni) | Done |
| LNC-10 resource/shader/world folders | Done (`resources` + UI) |
| CF fingerprint POST + content browse UI | Done (Browse tab + Core API) |
| System JVM discovery / server.properties UX / boot issue tree | Done |
| SVC-2 FailoverBlobSource | Done |
| NeoForge full pack AOT / AT parity | Open (setup lifecycle shipped; pack AOT residual) |

## Auth / compliance

Official Microsoft OAuth only; tokens in the OS vault. See
`../docs/COMPLIANCE.md` and `../docs/FIRST_RUN.md`.
