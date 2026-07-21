// See the root build for shared configuration.
//
// Holds no production code: this module exists so the native path can be
// exercised END TO END, across every module that participates in it. Each
// brick is tested in isolation in its own module; nothing there proves they
// fit together, and "it compiles" is not that proof.
//
// It depends on everything a real boot touches, which is exactly why the tests
// cannot live in any one of those modules.
dependencies {
    testImplementation(project(":loader-model"))
    testImplementation(project(":loader-diag"))
    testImplementation(project(":loader-core"))
    testImplementation(project(":loader-resolve"))
    testImplementation(project(":loader-transform"))
    testImplementation(project(":omni-api"))
    testImplementation(project(":provider-minecraft"))
    testImplementation(project(":loader-bootstrap"))
    testImplementation("org.ow2.asm:asm:9.8")
}
