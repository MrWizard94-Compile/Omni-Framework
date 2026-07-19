# OmniLoader Architecture Specification

**Status**: Phase 3 design — binding for Phase 4 implementation (changes require ADR).
**Authority chain**: SOUL v2.0.0 → `../docs/REQUIREMENTS.md` (LDR/CMP/TGT/CPL) → this spec.
**Corpus evidence**: cited as `(slug)` referring to `../intelligence/dossiers/<slug>.md`.

## 1. Identity & Constraints

OmniLoader is a JVM mod loader for Minecraft: Java Edition, first target MC 1.21.1 / **Java 21** (TGT-1), version-parameterized throughout (TGT-2). Native runtime namespace: **Mojang official names** (D2). It hosts three mod populations: native Omni mods, Fabric-family mods (metadata-level layer), and NeoForge-family mods (translation layer) — per ADR-0003 and CMP-1..5.

## 2. Module Map (Gradle multi-project, `OmniLoader/`)

| Module | Responsibility | Corpus template |
|---|---|---|
| `loader-core` | Bootstrap, classloading, partitions, service wiring. Zero gameplay API. | (fabric-loader) Knot |
| `loader-provider-api` + `provider-minecraft` | GameProvider SPI; MC-specific location/patching isolated | (fabric-loader) |
| `loader-model` | Canonical mod model + `omni.mod.json` parsing (§5) | (fabric-loader) metadata |
| `loader-resolve` | SAT resolver, rule SPI, ordering, dedupe, error rendering | (quilt-loader)+(fancymodloader) |
| `loader-transform` | AOT pipeline, cache, audit store, remapping | (sinytra-connector)+(fancymodloader) |
| `loader-mixin` | Pinned Mixin + `MixinServiceOmni` + refmap translation | (spongepowered-mixin) |
| `loader-diag` | Typed issues, boot UI hooks, failure tree | (fancymodloader) earlydisplay |
| `compat-api` | The compat-layer SPI (§7) | (quilt-loader) plugin SPI |
| `compat-fabric` / `compat-neoforge` | The two shipped layers (build order per D10) | (quilt-loader)/(sinytra-connector) |
| `omni-api` | The versioned gameplay API modules native mods compile against — separate release cadence | (fabric-loader) Fabric API split |

## 3. Boot Sequence

```
JVM entry (client/server thin mains)
 → loader-core init: config, paths, diag channel up FIRST (LDR-10)
 → GameProvider located (ServiceLoader) → game jars identified, version verified vs manifest
 → Discovery: mods/ walker + dev-classpath + args finders → candidate set (nested jars recursed)
 → Per-candidate metadata import → canonical model (native parse | compat-layer import, §7)
 → Resolution: rules from core + each layer → SAT solve → ordered winning set | typed failure
 → Transform ensure: per-jar cache lookup (§6); misses run the AOT pipeline now (parallel)
 → Partitioned transforming loader assembles: game + cached artifacts
 → Mixin bootstrap: config registration (native + translated refmaps), compat levels
 → Entrypoint phase: ordered init hooks per ecosystem semantics
 → hand-off to game main()
```

## 4. Classloading & Partitions (D3)

One `OmniTransformingClassLoader` (KnotClassDelegate pattern, (fabric-loader)): every class routes through it; per-mod **logical partitions** track code source, claimed packages, and ecosystem tags. Package-claim conflicts are diagnosed (fancymodloader's IncompatibleModReason quality) but not JPMS-enforced. Parent loader is a sealed empty loader (no system-classpath leakage). Game classes and mod classes share the namespace — required for Fabric-family semantics and the single Mixin pipeline.

## 5. Canonical Mod Model & `omni.mod.json` v0 (LDR-6)

The internal model every ecosystem imports INTO (sinytra's metadata-translation lesson generalized): identity (id, group, version semver), provides[], entrypoints{key→[impl|adapter]}, dependencies (five kinds + BEFORE/AFTER ordering), environment (client/server/both), mixin configs, access modifications, nested jars, people/license/contact, `ext.{namespace}` custom values.

`omni.mod.json` v0 (native manifest, jar root):

```json
{
  "schema": 0,
  "id": "examplemod", "group": "net.example", "version": "1.0.0",
  "name": "Example Mod", "license": "MIT",
  "environment": "both",
  "entrypoints": { "main": ["net.example.Init"], "client": [{"adapter":"kotlin","value":"net.example.ClientInit"}] },
  "depends":    { "omniloader": ">=0.1", "minecraft": "1.21.1", "somelib": { "version": ">=2.0", "order": "after" } },
  "recommends": {}, "suggests": {}, "breaks": {}, "conflicts": {},
  "provides":   [ { "id": "examplecore", "version": "1.0.0" } ],
  "jars":       [ "META-INF/jars/bundled-lib.jar" ],
  "mixins":     [ "examplemod.mixins.json" ],
  "access":     "examplemod.accessomni",
  "ext":        { "omni:ported-from": { "ecosystem": "fabric", "upstream": "example-fabric-mod" } }
}
```

Rules: `schema` is mandatory and versioned forever (fabric-loader V0/V1 lesson); unknown keys warn, never fail; `${jar.version}` substitution supported (fancymodloader lesson); validation errors are typed issues naming the exact path. Access format v0 = access-widener-compatible dialect (translation from AW and AT both supported by the pipeline).

## 6. Transformation Pipeline & Cache (D1, LDR-2 — details per ADR-0010)

**Cache key** = SHA-256 over (jar bytes, mappings artifact id+hash, pipeline version, layer version, config-relevant flags). **Layout**: `<data>/transform-cache/<key[0:2]>/<key>/` holding output jar + `audit.json` (per-class: processor id, pass version, byte-size delta, timing) + `meta.json` (inputs, produced-by). **Invalidation**: key mismatch = miss; GC by LRU + version sweep. **Audit query**: `omniloader --audit <class>` prints the full transformation history (fancymodloader audit-log adoption; SOUL §16).

Pipeline passes (ordered, per-jar): ecosystem translation (compat layers, §7) → remap to official (mappingio/tinyremapper-class tooling; intermediary→official data per ADR-0010) → access modification merge (AW/AT/native → one applied set) → environment stripping → refmap rewrite (spongepowered-mixin refmap lesson). Native Omni jars skip translation/remap (already official-named) but still cache stripped/access-merged output. Mixin application remains load-time by necessity (target classes unknown until load) — Mixin is NOT part of the AOT cache; everything else is.

## 7. Compat-Layer SPI (CMP-1, `compat-api`)

Seven contracts, one interface family (the sinytra bill of materials, made first-class):

1. `EcosystemClaimer` — inspect candidate jar, claim by ecosystem fingerprint (manifest present).
2. `MetadataImporter` — guest manifest → canonical model (lossless where possible; lossy imports emit typed warnings).
3. `RuleContributor` — resolver semantics for the guest's dependency dialect (quilt rule-SPI pattern).
4. `TransformContributor` — AOT passes for the guest (e.g. NeoForge layer: full translation; Fabric layer: none/light).
5. `ApiFacade` — the guest loader API reimplemented over loader-core (FabricLoader.getInstance() answers).
6. `ShimSet` — the layer's behavioral mixins/patches, versioned, test-backed, size-tracked (CMP-5 metric).
7. `LifecycleBridge` — maps guest entrypoint/event timing onto Omni's phases.

`compat-fabric`: claims `fabric.mod.json`/`quilt.mod.json`; no jar rewrite (metadata-level, (quilt-loader) proof); intermediary→official remap is still required (runtime names differ) and runs as its TransformContributor; provides the Fabric API surface via CMP-4 (ported API project, FFAPI strategy).
`compat-neoforge`: claims `neoforge.mods.toml`; full translation TransformContributor (metadata TOML→model, AT→access merge, language-provider semantics via LifecycleBridge, JPMS expectations flattened into partitions).

## 8. Mixin Integration (LDR-8 — pin per ADR-0009)

Bundled pinned Mixin 0.8.7-line build; `MixinServiceOmni` implements the service SPI over the transforming loader (bytecode via cache-or-live, ClassInfo-style hierarchy metadata from `loader-transform`'s shared class-metadata index); `IMixinAuditTrail` writes into the same audit store as §6. Per-config compat levels derived from each mod's declared loader/ecosystem version. Config plugins run sandboxed-in-observability (logged, time-budgeted, never silently swallowed).

## 9. Diagnostics (LDR-10)

All failures are `OmniIssue` (typed, translatable, with actionable remedy text — ResultAnalyzer quality bar). Boot UI: early GL window with real progress phases + failure tree rendering (fancymodloader earlydisplay + quilt gui, unified). Every subsystem logs structured (SOUL §16); the audit store is the single "who changed this class" answer.

## 10. Version Agnosticism (TGT-2)

No hardcoded MC version anywhere: the provider verifies against meta-service data; mappings/translation data are versioned artifacts fetched per game version; compat shims declare version ranges. Moving 1.21.1→26.x = new data artifacts + shim review, zero architectural change.

## 11. Testing Strategy (SOUL §3)

Unit: model parsing (golden + adversarial manifests), resolver (property-based: solutions satisfy all rules; unsat cores minimal), cache keys (collision/invalidation), access-merge and refmap rewrites (golden jars). Integration: boot vanilla headless; load fixture mods per ecosystem; the mod-test-suite assets (`C:\WPAI\Gaming\Minecraft\mod-test-suite`) become compat regression fixtures. Every shim lands with a reproducing test (CMP-5).
