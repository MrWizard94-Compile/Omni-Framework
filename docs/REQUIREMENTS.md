# Omni-Framework Requirements Specification

**Status**: Binding — output of the Phase 2 director cherry-pick session (2026-07-18, MrWizard94).
**Evidence base**: 14-subject vivisection corpus (`../intelligence/dossiers/`, `matrix.md` — 103 scored features, 213 provenance sources). Every requirement cites its corpus evidence by subject slug.
**Language**: MUST / MUST NOT are binding; SHOULD is binding unless a Phase 3 ADR documents why not. Changes to this document require director approval (SOUL §18).

## 1. Director Decisions (session record)

| # | Decision | Choice |
|---|---|---|
| D1 | Transformation model | Cached AOT + per-class audit log |
| D2 | Native mapping namespace | Mojang official names |
| D3 | JVM isolation model | Single transforming loader + logical partitions |
| D4 | Dependency resolution | Extensible SAT + ordering constraints |
| D5 | Instance model & state | Component stacks in SQLite (mods as entities) |
| D6 | Meta service | Omni-owned, static gen + CDN, from day one |
| D7 | MVP importers | All four: CurseForge App, Prism/MultiMC, Modrinth/.mrpack, official launcher |
| D8 | Server instances | **Full server support in MVP** (director override of phased recommendation) |
| D9 | Launch target | Minecraft 1.21.1 first; version-agnostic core |
| D10 | Compat build order | Fabric-family first, then NeoForge-family |
| D11 | Corpus avoid list | All six confirmed as MUST NOTs (§6) |
| D12 | Offline semantics | Cached entitlement window |

## 2. OmniLoader (LDR)

- **LDR-1 (minimal core)**: The loader core MUST ship zero gameplay API; convenience APIs live in separately versioned modules. Game-specific logic MUST sit behind a provider SPI. *(fabric-loader: minimal-core, GameProvider)*
- **LDR-2 (transformation)**: All bytecode transformation MUST run ahead-of-time into a hash-keyed persistent cache (inputs: mod hash, mappings version, pipeline version) with per-class audit records queryable at runtime. *(quilt-loader: transform cache; sinytra-connector: hash-cached translation; fancymodloader: ClassProcessor audit)* — D1.
- **LDR-3 (mappings)**: The native runtime namespace MUST be Mojang official names. The pipeline MUST include production remapping per hosted ecosystem (intermediary→official for Fabric-family; SRG translation deferred until census demand is shown). *(fancymodloader; sinytra-connector: ART/Adapter; minecraftforge: SRG note)* — D2.
- **LDR-4 (isolation)**: One transforming class loader with logical per-mod partitions, code-source tracking, and package-claim diagnostics. JPMS module layers MUST NOT be required of mods. *(fabric-loader: KnotClassDelegate; fancymodloader: module friction; sinytra-connector: ModuleLayerMigrator cost)* — D3.
- **LDR-5 (resolution)**: Pseudo-boolean SAT resolution with per-ecosystem rule plugins, BEFORE/AFTER ordering constraints, cross-ecosystem identity/provides dedupe, and unsat-core → human-quality error rendering. *(quilt-loader: plugin rules; fabric-loader: ModSolver/ResultAnalyzer; fancymodloader: ordering; sinytra-connector: dedupe filter)* — D4.
- **LDR-6 (metadata)**: Native manifest `omni.mod.json` MUST be schema-versioned from v0, a semantic superset of the hosted ecosystems' manifests, with nested-jar support and namespaced custom values. *(fabric-loader/quilt-loader: schema evolution; fancymodloader: multi-mod jars, jar-manifest version substitution)*
- **LDR-7 (entrypoints)**: Declared, lazily instantiated entrypoints with language adapters AND provider-style discovery support for compat. *(fabric-loader: entrypoints; fancymodloader: language providers)*
- **LDR-8 (Mixin)**: Ship one pinned Mixin runtime behind `MixinServiceOmni`; per-config compat levels; refmap translation per hosted ecosystem; audit-trail wired into LDR-2's store; config plugins executed faithfully. *(spongepowered-mixin; fabric-loader: pinned fork + compat levels)*
- **LDR-9 (environment)**: Client/server member stripping semantics compatible with `@Environment`/side annotations; the loader MUST be dist-agnostic (client and server) — supports D8. *(fabric-loader: env stripping; atlauncher: server demand)*
- **LDR-10 (diagnostics)**: Load failures MUST produce typed, translatable issues rendered in UI (early-display-class boot UX + interactive failure tree), never log-only. *(fancymodloader: early display + ModLoadingIssue; quilt-loader: error GUI)*

## 3. Compatibility Layers (CMP)

- **CMP-1 (SPI)**: Ecosystem adapters are loader plugins implementing: discovery claiming, metadata import to the canonical model, resolver rule contribution, transformation passes, guest-API facade, and shim registration. *(quilt-loader: plugin architecture; sinytra-connector: 7-item bill of materials)*
- **CMP-2 (order)**: Fabric-family layer first (metadata-level hosting — no jar rewriting), then NeoForge-family (AOT jar translation). *(quilt-loader vs sinytra-connector strategy comparison)* — D10.
- **CMP-3 (unmodified jars)**: Guest jars load unmodified from the user's machine; translation artifacts are local cache only, never redistributed (ADR-0003 legality).
- **CMP-4 (API-surface-first)**: For each hosted ecosystem, port/provide the standard API surface once (FFAPI strategy) before porting individual mods. *(sinytra-connector)*
- **CMP-5 (shims + guards)**: Each layer maintains a permanent, test-backed behavioral shim suite; its growth rate is a tracked health metric. Transformation seams MUST fail fast with context. *(sinytra-connector: compat/mixin suite, safeguards)*

## 4. OmniLauncher (LNC)

- **LNC-1 (architecture)**: `omni-core` Rust crate (UI-free) → Tauri shell (commands/capabilities) → web UI; typed event bus for all progress/state. *(modrinth-app; gdlauncher: Electron avoid; ADR-0001)*
- **LNC-2 (state)**: All launcher state in one SQLite DB (sqlx-style migrations, backup). Instances are component stacks; contained mods are entities carrying platform identity, version, enable-state, port/compat status. *(prism-launcher/multimc: components; modrinth-app: SQLite; atlauncher: mod entities)* — D5.
- **LNC-3 (meta)**: Consume only the Omni meta service (§5 SVC-1); MUST NOT parse upstream formats in-app beyond the importer surfaces. — D6.
- **LNC-4 (auth)**: Exactly the COMPLIANCE.md §2 Microsoft chain, interactive + device-code, full XSTS error taxonomy, multi-account, OS-vault token storage. Game identity and any future platform identity MUST be separate modules with separate lifecycles. *(prism-launcher: endpoints/taxonomy; modrinth-app: dual auth domains)*
- **LNC-5 (install/launch)**: Resumable, self-diagnosing, self-repairing installs (store-backed runner); hash-verified delta downloads through provider-abstracted sources with mirror failover; pre-launch task chain (account claim, auto-Java via Mojang runtime manifests, natives, memory check, quick-play). *(modrinth-app: install recovery; hmcl: providers/cache; prism-launcher: task chain, auto-Java)*
- **LNC-6 (java)**: Discover and validate installed JVMs; auto-install exact required runtimes; LWJGL version substitution for ARM/exotic platforms. *(prism-launcher; atlauncher: LWJGL)*
- **LNC-7 (migration)**: MVP importers: CurseForge App instances, Prism/MultiMC family, Modrinth App + .mrpack, official launcher profiles — plus foreign-install fingerprinting and hash-based mod identification via both platforms' APIs. Export toward foreign formats SHOULD follow in Phase 5. *(prism: importers+hash recovery; hmcl: LibraryAnalyzer; curseforge-app/official-launcher: formats; atlauncher: export)* — D7.
- **LNC-8 (servers)**: **MVP ships full server-instance support**: creation, mod management (same entity model), server EULA acceptance flow, process management/console, port/config UX. *(atlauncher: server instances)* — D8 (director override; MVP scope expanded accordingly).
- **LNC-9 (content)**: One ResourceAPI-style abstraction over CurseForge + Modrinth (typed clients; census reuses them): honoring API keys, rate limits, per-project distribution opt-outs, and author monetization routing. *(prism-launcher: ResourceAPI; atlauncher: typed models; curseforge-app: constraints)*
- **LNC-10 (resources)**: Folder models for mods, resource/texture/shader/data packs, worlds — watched, toggleable, batch-operable. *(prism-launcher)*

## 5. Omni Services (SVC)

- **SVC-1 (meta service)**: Statically generated, CDN-served, normalized metadata: Mojang versions (client.json ingestion), OmniLoader + compat layer releases, component version lists with recommendations. Generator is a Rust tool in this monorepo (daedalus reference); output is cacheable and offline-tolerant. *(multimc/prism/modrinth-app: three-way consensus; official-launcher: upstream contract)* — D6.
- **SVC-2 (resilience)**: All remote surfaces (meta, downloads) MUST treat degraded networks as an operating condition: provider failover, health-based selection, cross-instance content-addressed cache. *(hmcl)*

## 6. Binding MUST NOTs (D11 — corpus avoid list)

1. MUST NOT maintain source patches to the game (transformation-based hooks only). *(minecraftforge)*
2. MUST NOT implement offline/unverified accounts or pluggable third-party auth (authlib-injector class). *(hmcl; COMPLIANCE.md)*
3. MUST NOT use Electron for any Omni desktop product. *(gdlauncher)*
4. MUST NOT share mutable game directories between instances. *(official-launcher)*
5. MUST NOT build desktop UI on JVM toolkits. *(atlauncher)*
6. MUST NOT adopt restrictive distribution/branding posture toward users (proprietary core notwithstanding: launcher free, formats open, migration bidirectional). *(multimc; minecraftforge/gdlauncher governance notes)*

## 7. Compliance & Offline (CPL)

- **CPL-1**: COMPLIANCE.md is incorporated by reference; every phase gate re-audits against it.
- **CPL-2 (offline window, D12)**: Verified owners MAY play offline within an explicit cached-entitlement window from last successful verification; expiry requires re-auth; no launch path exists without a verified entitlement record. Window length and storage are a Phase 3 ADR (encrypted, per-account).
- **CPL-3**: Game content only from official Mojang CDNs; platform content only through official APIs with opt-outs and monetization honored.

## 8. Target & Versioning (D9)

- **TGT-1**: First supported game version: **Minecraft 1.21.1** (Java 21).
- **TGT-2**: Loader core, mapping pipeline, meta service, and compat layers MUST be version-parameterized (no hardcoded version assumptions); following the ecosystem to 26.x MUST be achievable as data + mappings updates plus shim work, not architectural change.

## 9. Deferred to Phase 3 ADRs

Mixin fork/pin choice and update policy; transform-cache invalidation details; entitlement-window length/storage; SRG/legacy-Forge support (await census data); meta service hosting specifics (Cloudflare assumed); native-mod SDK shape (in-monorepo per minecraftforge MDK finding); .mrpack/manifest export scope.
