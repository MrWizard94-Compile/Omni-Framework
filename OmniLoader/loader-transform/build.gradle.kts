// See the root build for shared configuration.
//
// This is the first OmniLoader module with an external runtime dependency, so
// the reasoning is recorded here rather than left implicit.
//
// ASM is not a convenience. Rewriting references across a jar means decoding
// and re-encoding the constant pool, and a hand-rolled class reader would be a
// permanent correctness liability against a format that gains a version every
// six months. ARCHITECTURE §6 already anticipates external class tooling, and
// ADR-0009's pinned Mixin is itself built on ASM — so ASM enters the graph at
// brick 13 regardless. Taking it here keeps one copy on the classpath instead
// of two.
//
// `asm-commons` supplies ClassRemapper; `asm-tree` supplies the node model the
// access-merge and environment-strip passes edit in place.
dependencies {
    api(project(":loader-model"))
    api(project(":loader-diag"))

    implementation("org.ow2.asm:asm:9.8")
    implementation("org.ow2.asm:asm-commons:9.8")
    implementation("org.ow2.asm:asm-tree:9.8")
}
