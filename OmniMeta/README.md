# OmniMeta — the Omni Metadata Service

**Status**: Phase 3 design — implementation lands with Phase 4 (SVC-1 requires it before OmniLauncher ships).
**Authority chain**: SOUL v2.0.0 → `../docs/REQUIREMENTS.md` (SVC-1/SVC-2, D6) → this spec.
**Pattern provenance**: three-way corpus consensus — meta.multimc.org (multimc), meta.prismlauncher.org (prism-launcher), daedalus (modrinth-app, the Rust reference implementation).

## What it is

A **statically generated, CDN-served** JSON index that normalizes every upstream the launcher needs — so OmniLauncher consumes exactly one metadata dialect and upstream churn is absorbed at generation time, never in the app (LNC-3).

## Components (monorepo)

- `OmniMeta/generator/` (Rust, Phase 4): pulls upstreams → validates → emits the static tree. Deterministic: same inputs (pinned upstream snapshots) → byte-identical output (SOUL §21). Runs on a schedule + on-demand.
- `crates/omni-meta-client` (see OmniLauncher/ARCHITECTURE.md §1): the typed consumer.

## Upstreams normalized

Mojang version manifest v2 + per-version client.json + java-runtime manifests (official-launcher dossier: the authoritative contract); OmniLoader releases (this repo's CI); compat-layer releases + their mapping/translation data artifacts (ADR-0010); recommended-version policy data.

## Index format v1 (all files immutable-cacheable except the roots)

```
/v1/index.json                    { formatVersion, components: [{uid, versionsUrl, sha256}] }
/v1/<uid>/index.json              { uid, versions: [{version, releaseTime, type, recommended, url, sha256}] }
/v1/<uid>/<version>.json          component payload (Omni dialect, superset of OneSix lessons):
                                  { uid, version, requires: [{uid, range}], mainClass?, arguments?,
                                    libraries: [{coord, url, sha1, size, rules}], assetIndex?,
                                    javaVersion?, data: {...component-specific...} }
```

Component uids at launch: `net.minecraft`, `org.omniframework.omniloader`, `org.omniframework.compat-fabric`, `org.omniframework.compat-neoforge`, `org.omniframework.mappings-<gameversion>`, LWJGL variants ((atlauncher) ARM lesson). Roots carry short TTL + content hashes; everything else is hash-addressed and immutable (hmcl-grade cacheability; offline-tolerant per SVC-2).

## Hosting (ADR-0012)

Cloudflare: generator output → R2 bucket → CDN, domain `meta.omniframework.wpaistudio.net` (placeholder — director confirms the final domain at Phase 4 deploy; the URL is a launcher build-config value exactly like (prism-launcher)'s `Launcher_META_URL`, so it is changeable without code edits). No dynamic compute in the serving path — static files only, availability = CDN availability.

## Non-goals

No account data, no telemetry, no mod files — metadata only. Mod content always comes from official platform CDNs (COMPLIANCE §1/§5).
