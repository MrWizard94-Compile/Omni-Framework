# OmniLauncher Architecture Specification

**Status**: Phase 3 design — binding for Phase 4 implementation (changes require ADR).
**Authority chain**: SOUL v2.0.0 → `../docs/REQUIREMENTS.md` (LNC/SVC/CPL) → this spec.
**Corpus evidence**: cited as `(slug)` per `../intelligence/dossiers/`.

## 1. Layering (LNC-1, ADR-0001)

```
apps/launcher-ui        TypeScript + Vue + Tailwind (web app; Tauri commands/events only)
apps/launcher-shell     Tauri 2.x: command registration, capabilities, windows, updater
crates/omni-core        ALL logic (UI-free, headless-testable)   ← the product
crates/omni-meta-client Typed client of the Omni meta service (shared w/ tools)
crates/omni-platform    CurseForge + Modrinth typed API clients (shared w/ Phase 6 census)
```

(modrinth-app) three-layer pattern exactly; (gdlauncher) is the MUST-NOT-Electron evidence. `omni-core` exposes an async command API + typed event stream (progress, state deltas); the UI holds no truth.

## 2. State: SQLite Schema Draft (LNC-2, D5 — sqlx migrations, schema v1)

```sql
instances(id PK, name, icon, kind CHECK(kind IN('client','server')), game_version,
          created_at, last_played_at, settings_json, notes)
components(instance_id FK, position, uid, version, pinned, source CHECK(source IN('meta','custom')),
           custom_json, UNIQUE(instance_id, uid))          -- component stack (prism/multimc)
mods(id PK, instance_id FK, file_name, sha1, sha512, murmur2, display_name, version,
     ecosystem CHECK(ecosystem IN('omni','fabric','neoforge','forge','unknown')),
     enabled, platform, platform_project_id, platform_file_id,
     port_status CHECK(port_status IN('native','compat','ported','excluded','unknown')))
     -- mods as entities (atlauncher); hashes feed census + hash-recovery (prism, curseforge-app)
accounts(id PK, uuid, username, kind CHECK(kind IN('microsoft')), vault_ref,  -- tokens live in OS vault ONLY
         entitlement_verified_at, entitlement_window_expires_at)              -- CPL-2 / ADR-0011
java_runtimes(id PK, path, vendor, version, arch, source CHECK(source IN('detected','mojang-manifest')), valid_at)
processes(id PK, instance_id FK, pid, started_at, ended_at, exit_code, session_json) -- (modrinth-app)
settings(key PK, value_json)
jobs(id PK, kind, instance_id, state CHECK(state IN('pending','running','paused','failed','done')),
     progress_json, resume_json, error_json, created_at, updated_at)  -- resumable installs (modrinth-app)
```

Foreign formats (`mmc-pack.json`, `instance.cfg`, `minecraftinstance.json`, `launcher_profiles.json`, `.mrpack`) are importer/exporter surfaces only — never the store (official-launcher shared-dir MUST NOT). Server instances are the same schema (`kind='server'`) — dist-agnostic by construction (D8).

## 3. Auth Module (LNC-4, CPL — exact endpoints per prism-launcher dossier)

`omni-core::auth`: step-chain (interactive PKCE + device-code) → XBL → XSTS (full XErr taxonomy → typed, user-actionable errors) → `api.minecraftservices.com` token → entitlement + profile. Tokens in the OS credential vault via `vault_ref` (never in DB/logs, SOUL §36). Silent refresh scheduler; per-instance account pinning; multi-account. Entitlement success stamps the CPL-2 window (`entitlement_window_expires_at`); launch requires an unexpired window; expiry triggers re-auth, online-only. Platform identity (future Omni services) is a separate module with zero shared state (modrinth-app dual-domain lesson).

## 4. Install & Launch Pipeline (LNC-5/6)

Store-backed **job runner** (jobs table): every install/repair/update is a resumable job with diagnostics (`verify` pass computes what's missing/corrupt) and recovery (crash → resume from `resume_json`) — (modrinth-app install/). Downloads: provider-abstracted sources (SVC-2; (hmcl) mirror-failover architecture) + global content-addressed cache keyed by hash, shared across instances. Launch task chain (ordered, each step a typed unit): account claim → entitlement window check → auto-Java (Mojang runtime manifests; LWJGL substitution per (atlauncher) for ARM) → folders/natives → memory check → quick-play target → process spawn + supervision (logs captured, session recorded). Server launch variant: server jar + loader server entry, EULA-acceptance step (explicit user action recorded), console attach, port/config surface (D8).

## 5. Importers (LNC-7, D7 — all four in MVP)

One `Importer` trait: `detect(path) -> Confidence`, `enumerate() -> [Candidate]`, `import(candidate) -> instance`. Implementations: CurseForge App (`minecraftinstance.json` + `manifest.json`), Prism/MultiMC (`mmc-pack.json` + `instance.cfg` + OneSix patches), Modrinth (app SQLite + `.mrpack`), Official (`launcher_profiles.json` + shared `.minecraft` adoption). Shared helpers: instance fingerprinting ((hmcl) LibraryAnalyzer port) and hash-recovery against both platform APIs ((prism), (curseforge-app) murmur2). Every import produces a report (what mapped, what didn't, why — typed issues).

## 6. Content Platform Clients (LNC-9)

`crates/omni-platform`: typed CurseForge Core API + Modrinth API clients ((atlauncher) models as field reference): API-key management, rate limiting, distribution opt-out honoring (opted-out CF projects → "open in official app" deep-link, never circumvention), download routing through official endpoints (author monetization preserved, COMPLIANCE §5). One `ResourceQuery` abstraction over both ((prism) ResourceAPI) for UI search/install/update; the same crate is the Phase 6 census client.

## 7. UI Surfaces (Phase 4 scope)

Instance grid + detail (components, mods-as-entities with port_status badges, worlds/packs folders (prism resource models)); account manager; job center (live progress from the event stream); import wizard; server console; settings. Boot-failure rendering consumes OmniLoader's typed issue tree end-to-end — one continuous error story from launcher click to loader failure (fancymodloader earlydisplay handoff).

## 8. Testing (SOUL §3)

`omni-core` headless: auth chain against recorded fixtures (never live secrets); schema migration round-trips; job resume from every interrupt point (property: any prefix of a job can resume to completion); importer golden sets (real-world instance fixtures incl. the director's own CurseForge instances, with permission, as private fixtures); format-seam unit tests per (multimc) lesson. UI: command/event contract tests; no logic to test in the shell by design.
