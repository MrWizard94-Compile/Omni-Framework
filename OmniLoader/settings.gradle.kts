// OmniLoader — Gradle multi-project (OmniLoader/ARCHITECTURE.md §2).
//
// Modules land in dependency order as Phase 4 progresses. Brick 8 delivers the
// two that everything else builds on:
//
//   loader-diag   typed, translatable issues (LDR-10) — every other module
//                 reports through it, so it has no internal dependencies.
//   loader-model  the canonical mod model + `omni.mod.json` v0 (LDR-6, §5) —
//                 the shape every ecosystem imports INTO.
//
//   loader-provider-api  the GameProvider SPI — how the loader is told where the
//                        game is, without knowing anything about Minecraft (LDR-1).
//   loader-core          classloading, partitions, discovery, boot (LDR-3, LDR-4).
//   provider-minecraft   the one provider that does know about Minecraft.
//
//   loader-resolve       which mods load, in what order, and why not (LDR-5).
//   loader-transform     the AOT bytecode pipeline, its cache, and the audit
//                        store that answers "who changed this class" (LDR-2).
//
// Still to come (ARCHITECTURE §2): loader-mixin, compat-api, compat-fabric,
// compat-neoforge, omni-api.

rootProject.name = "omniloader"

include(
    "loader-diag",
    "loader-model",
    "loader-provider-api",
    "loader-core",
    "provider-minecraft",
    "loader-resolve",
    "loader-transform",
    "loader-mixin",
    "omni-api",
    "integration-tests",
)
