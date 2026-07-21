// See the root build for shared configuration.
//
// The assembly module: the top of the dependency graph, where the stages that
// have only ever run in isolation (bricks 10-14) are composed into one boot
// flow, and where the loader gains a `main` the launcher can invoke (B1).
//
// It depends on what it USES, and the orchestration uses all of it:
//   loader-core        the transforming loader, partitions, GameLauncher,
//                      and the entrypoint dispatcher.
//   loader-resolve     discovery's candidate type and the resolver.
//   provider-minecraft the one GameProvider that locates a real 1.21.1 game.
//   omni-api           the ModInitializer type the dispatcher casts against.
dependencies {
    api(project(":loader-core"))
    api(project(":loader-resolve"))
    api(project(":provider-minecraft"))
    api(project(":omni-api"))
}
