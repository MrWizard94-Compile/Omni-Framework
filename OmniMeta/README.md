# OmniMeta — the Omni Metadata Service

**Status**: Phase 3 design; **generator implemented 2026-07-18** (Phase 4 brick 1 — see §Implementation below). Hosting deploy (ADR-0012) pending director domain confirmation.
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

Cloudflare: generator output → R2 bucket → CDN, domain **`omnimeta.wpaistudio.net`** (confirmed by director 2026-07-19, ADR-0016 — single-level subdomain so Universal SSL covers it; provisioned: bucket `omni-meta`, custom domain bound, min TLS 1.2). The URL is a launcher build-config value exactly like (prism-launcher)'s `Launcher_META_URL`, so it is changeable without code edits. No dynamic compute in the serving path — static files only, availability = CDN availability.

## Implementation (Phase 4 brick 1, 2026-07-18)

Two Rust crates in the root Cargo workspace:

- **`OmniMeta/format`** (`omni-meta-format`) — the typed index-format-v1 model, canonical
  JSON serialization, sha256 hashing, and tree-path rules. Shared with OmniLauncher's
  `omni-meta-client` so generator and consumer agree on one dialect by construction.
- **`OmniMeta/generator`** (`omni-meta-gen`) — the three-stage pipeline:

```powershell
# 1. Capture upstreams into a pinned snapshot (the only networked stage; sha1-verified):
cargo run -p omni-meta-gen -- snapshot --out <dir> [--versions 1.21.1,...]
# 2. Deterministically emit the /v1 tree (pure function of the snapshot; self-verifies):
cargo run -p omni-meta-gen -- generate --snapshot <dir> --out <dir> `
    [--definitions <dir>] [--policy <file>] [--versions IDS] [--force]
# 3. Audit any emitted tree (the publish gate):
cargo run -p omni-meta-gen -- verify --tree <dir>
```

Omni components (OmniLoader, compat layers) enter the tree as **definition files**
(`<dir>/<uid>/<version>.json`, full component payloads) passed via `--definitions`;
loader CI will emit these per release. Recommended versions come from a **policy file**
(`{"recommended": {"<uid>": ["<version>"]}}`); with no policy, the manifest's
`latest.release` is recommended.

Format details v1 pinned by the implementation (documented in `format/src/lib.rs`):

- `requires[].range` dialect: absent = any version, present = exact version string.
- Payloads also carry `releaseTime`/`type` (self-describing when cached standalone).
- Version strings are sanitized into file stems (Mojang has shipped ids with spaces);
  the explicit `url` fields in the indexes are the only sanctioned file locator.
- `net.minecraft` extras ride in `data`: `downloads` (client/server jars + official
  mappings, upstream key dialect) and `logging` (log4j config passthrough).
- LWJGL is split into `org.lwjgl3`/`org.lwjgl2` components with exact-version
  `requires` edges from each game version (enables ARM/platform substitution).

Determinism is enforced by tests (`generator/tests/pipeline.rs`): a committed golden
tree (`generator/tests/golden/`, regeneration command in the test header) must be
reproduced byte-for-byte, and double-runs must be identical. QA gates:
`cargo test --workspace` and `cargo clippy --workspace --all-targets` (zero warnings).

## Non-goals

No account data, no telemetry, no mod files — metadata only. Mod content always comes from official platform CDNs (COMPLIANCE §1/§5).
