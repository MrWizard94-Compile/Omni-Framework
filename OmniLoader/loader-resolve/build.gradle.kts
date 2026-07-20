// See the root build for shared configuration.
dependencies {
    api(project(":loader-model"))
    api(project(":loader-diag"))
    // The resolver is a launch-time component, so it speaks in launch-time terms:
    // GameSide ("which program is starting") rather than Environment ("what a mod
    // declared about itself").
    api(project(":loader-provider-api"))
}
