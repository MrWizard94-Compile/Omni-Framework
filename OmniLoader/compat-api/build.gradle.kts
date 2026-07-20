// See the root build for shared configuration.
//
// The compat-layer SPI (CMP-1, ARCHITECTURE §7): seven contracts a hosted
// ecosystem implements to run on OmniLoader.
//
// Depends on the loader's model and diagnostics but NOT on loader-core: a
// layer describes what it needs, it does not reach into the loader to get it.
// Keeping that boundary is what makes a layer testable without a booting game,
// and what stops the two shipped layers quietly growing dependencies on each
// other's internals.
dependencies {
    api(project(":loader-model"))
    api(project(":loader-diag"))
    api(project(":loader-resolve"))
    api(project(":loader-transform"))
}
