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
    api(project(":loader-transform"))
    api(project(":loader-mixin"))
    api(project(":provider-minecraft"))
    api(project(":omni-api"))
    // Compat layers are part of the staged runtime so discovery can claim Fabric/NeoForge jars.
    api(project(":compat-api"))
    api(project(":compat-fabric"))
    api(project(":compat-neoforge"))
    // Mixin expects Guava/Gson from the game at runtime; the bootstrap classpath
    // must supply them before the game partitions exist.
    implementation("com.google.guava:guava:32.1.2-jre")
    implementation("com.google.code.gson:gson:2.10.1")
}

// ---------------------------------------------------------------------------
// Loader distribution (B2): the runtime classpath the launcher hands to `java`.
//
// The launcher (a separate Rust process) cannot depend on a Gradle project; it
// invokes `java -cp <these jars> OmniLoaderBootstrap ...`. So the build stages
// the bootstrap jar plus every runtime dependency into one folder, and writes
// a small manifest naming the entry point — the launcher reads the main class
// from the manifest rather than hardcoding a name that could drift out from
// under it.
//
// A plain Sync rather than the `application` plugin: the launcher builds its
// own JVM command (its own Java runtime, its own -Xmx), so the generated start
// scripts would be dead weight, and Sync keeps the moving parts to exactly the
// two things the launcher needs — a lib folder and a manifest.
// ---------------------------------------------------------------------------

val loaderMainClass = "org.omniframework.loader.bootstrap.OmniLoaderBootstrap"
val loaderDistDir = layout.buildDirectory.dir("loader-dist")

val stageLoaderDist by tasks.registering(Sync::class) {
    description = "Stages the loader runtime classpath (bootstrap jar + all runtime deps)."
    group = "distribution"
    from(tasks.named("jar"))
    from(configurations.named("runtimeClasspath"))
    into(loaderDistDir.map { it.dir("lib") })
}

val writeLoaderManifest by tasks.registering {
    description = "Writes loader.json (entry point + version) beside the staged lib folder."
    group = "distribution"
    val manifest = loaderDistDir.map { it.file("loader.json") }
    val mainClass = loaderMainClass
    val distVersion = version.toString()
    outputs.file(manifest)
    doLast {
        manifest.get().asFile.writeText(
            "{\"mainClass\":\"$mainClass\",\"version\":\"$distVersion\"}\n",
        )
    }
}

val loaderDist by tasks.registering {
    description = "The full loader distribution the launcher invokes: lib/ + loader.json."
    group = "distribution"
    dependsOn(stageLoaderDist, writeLoaderManifest)
}

// The distribution is an artifact of this module; build it whenever the module builds, so a
// broken dist is caught by the ordinary gate rather than only when the launcher tries to use it.
tasks.named("assemble") {
    dependsOn(loaderDist)
}

// The distribution test verifies the STAGED artifact, not the source tree (SOUL gate 2), so it
// needs the dist built first and the path to find it.
tasks.named<Test>("test") {
    dependsOn(loaderDist)
    systemProperty("omni.loader.dist", loaderDistDir.get().asFile.absolutePath)
    systemProperty("omni.loader.mainClass", loaderMainClass)
}
