// NeoForge-family compat layer (CMP-2 order: after Fabric).
// Metadata claim + TOML import + @Mod discovery for the Phase 4 exit path.
// Full AOT translation (Sinytra-scale) is deliberately out of this brick's
// scope: fixture mods prove claim→import→construct; real packs need remap.
dependencies {
    api(project(":compat-api"))
    // @Mod annotation scan uses the same ASM line as loader-transform.
    implementation("org.ow2.asm:asm:9.8")
}
