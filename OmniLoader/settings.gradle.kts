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
// Still to come (ARCHITECTURE §2): loader-core, loader-provider-api,
// provider-minecraft, loader-resolve, loader-transform, loader-mixin,
// compat-api, compat-fabric, compat-neoforge, omni-api.

rootProject.name = "omniloader"

include(
    "loader-diag",
    "loader-model",
)
