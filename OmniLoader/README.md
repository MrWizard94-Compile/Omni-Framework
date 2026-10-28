# OmniLoader

**Status: implemented (Phase 4 MVP + boot-hardening).** Binding design:
[`ARCHITECTURE.md`](ARCHITECTURE.md). Java 21 multi-project under this directory.

## What it is

The Omni-Framework mod loader for Minecraft 1.21.1: bootstrap, discovery,
resolution, AOT transform cache, Mixin, and runtime compatibility layers that
host Fabric and NeoForge jars alongside native Omni mods.

## Modules

| Module | Role |
|--------|------|
| `loader-bootstrap` | JVM `main`, full boot sequence, `loader-dist` for the launcher |
| `loader-core` | Transforming classloader, partitions, entrypoints |
| `loader-model` | Canonical model + `omni.mod.json` v0 |
| `loader-resolve` | Backtracking resolver (ADR-0018) |
| `loader-transform` | AOT pipeline, cache, remap, access merge, env strip |
| `loader-mixin` | Pinned Mixin 0.8.7 + `MixinServiceOmni` |
| `loader-diag` | Typed `OmniIssue` tree |
| `compat-fabric` / `compat-neoforge` | Hosted ecosystems (CMP-*) |
| `omni-api` | Native mod contract (`ModInitializer`, `OmniLoaderApi`) |

## Build

```powershell
cd OmniLoader
.\gradlew build
.\gradlew :loader-bootstrap:loaderDist
```

Distribution lands at `loader-bootstrap/build/loader-dist/` (`lib/` + `loader.json`).

## Honest scope notes

- **Fabric path:** claim, import, entrypoints (`main`/`client`/`server`), hosted
  `FabricLoader`, intermediary→official remap when mappings are supplied.
- **NeoForge path:** claim, TOML+deps import, `@Mod` construct, **per-mod event
  bus** + **hosted FML setup lifecycle** (`FMLCommonSetupEvent` then
  client/server setup via `NeoForgeLifecycle`). Language providers, access
  transformers, and full pack-scale AOT translation remain residual Sinytra-scale
  work (boot still runs intermediary→official AOT for hosted jars when mappings
  are supplied).
- **Access wideners** are collected on boot and fed into `AccessMergePass`.
- **`OmniLoaderApi`** is installed before entrypoints run.

## Commands

See root `docs/TEST_MATRIX.md` and `docs/FIRST_RUN.md` for launcher handoff.
