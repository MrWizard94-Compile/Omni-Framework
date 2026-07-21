// See the root build for shared configuration.
//
// The assembly module: the top of the dependency graph, where the stages that
// have only ever run in isolation (bricks 10-14) are composed into one boot
// flow, and where the loader gains a `main` the launcher can invoke (B1).
//
// It depends on what it USES. Discovery — the stage delivered first — needs
// the canonical model and the resolver's candidate type, which loader-resolve
// re-exports. The remaining stages (locate, transform, mixin, entrypoint,
// launch) add loader-core / provider-minecraft / omni-api as the orchestration
// lands; they are not declared before the code that uses them exists.
dependencies {
    api(project(":loader-resolve"))
}
