// See the root build for shared configuration.
//
// Mixin integration (LDR-8, ADR-0009): one pinned upstream 0.8.7-line runtime
// behind `MixinServiceOmni`.
//
// Two dependency shapes here, and the difference matters.
//
// Mixin itself is `api`: ADR-0009 bundles it, and compat layers register their
// configs against its types, so it is part of this module's surface.
//
// Guava and Gson are `compileOnly`. Mixin's POM declares no dependencies at
// all, yet its code references both — it is built to run inside Minecraft,
// which already ships them (1.21.1 carries Guava 32.1.2-jre and Gson 2.10.1).
// Declaring them `implementation` would put a second copy on the classpath
// beside the game's, which is how two Guava versions end up loaded and
// something fails with NoSuchMethodError far from the cause. They are pinned
// to the versions the game supplies, so compilation sees the API the runtime
// will actually have.
dependencies {
    api(project(":loader-core"))
    api(project(":loader-transform"))
    api("org.spongepowered:mixin:0.8.7")

    compileOnly("com.google.guava:guava:32.1.2-jre")
    compileOnly("com.google.code.gson:gson:2.10.1")

    // Tests have no game around them, so they must supply what the game would.
    testImplementation("com.google.guava:guava:32.1.2-jre")
    testImplementation("com.google.code.gson:gson:2.10.1")
}
