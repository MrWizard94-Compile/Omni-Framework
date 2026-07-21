package org.omniframework.loader.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.omniframework.loader.model.json.Json;
import org.omniframework.loader.model.json.JsonReader;

/**
 * Verifies the loader distribution — the thing the launcher actually invokes — as a built
 * artifact, not as source (SOUL gate 2).
 *
 * <p>The distinction matters here more than anywhere. The launcher runs
 * {@code java -cp <staged jars> <mainClass from the manifest> ...} in a fresh JVM with none of
 * this project's build classpath. "It compiles" and "the bootstrap class exists in the source
 * tree" say nothing about whether that command works: a jar left out of the staging, a wrong main
 * class in the manifest, or a class the bootstrap needs before it can even print a usage error
 * would all pass every other test and fail only at the launcher's process boundary. So this test
 * reads the staged {@code loader.json}, then spawns a real JVM against the staged {@code lib/} and
 * confirms the entry point runs.
 *
 * <p>The Gradle build wires {@code omni.loader.dist} to the staged directory and depends the test
 * on the staging task; run outside that wiring, the test skips rather than pretending to pass.
 */
class LoaderDistributionTest {

    private static Path distDir() {
        String configured = System.getProperty("omni.loader.dist");
        assumeTrue(configured != null && !configured.isBlank(),
                "omni.loader.dist is unset — run via Gradle, which stages the dist first");
        Path dir = Path.of(configured);
        assumeTrue(Files.isDirectory(dir), configured + " is not a directory (dist not staged)");
        return dir;
    }

    @Test
    @DisplayName("the manifest names a real entry point and version")
    void manifestIsWellFormed() throws IOException {
        Path manifest = distDir().resolve("loader.json");
        assertTrue(Files.isRegularFile(manifest), "loader.json is missing from the dist");

        Json parsed = JsonReader.parse(Files.readString(manifest, StandardCharsets.UTF_8));
        Json.Obj root = parsed.asObject().orElseThrow();
        String mainClass = root.get("mainClass").flatMap(Json::asString).orElseThrow();
        String version = root.get("version").flatMap(Json::asString).orElseThrow();

        assertEquals(System.getProperty("omni.loader.mainClass"), mainClass,
                "the manifest's main class must match what the build declares");
        assertTrue(!version.isBlank(), "the manifest must carry a version");
    }

    @Test
    @DisplayName("the staged lib folder carries the bootstrap and its runtime dependencies")
    void libContainsTheRuntimeClasspath() throws IOException {
        Path lib = distDir().resolve("lib");
        assertTrue(Files.isDirectory(lib), "the dist has no lib/ folder");

        List<String> jars;
        try (Stream<Path> entries = Files.list(lib)) {
            jars = entries.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".jar")).toList();
        }
        // The bootstrap itself and the modules its orchestration cannot run without. Matched by
        // prefix because versions are in the names.
        for (String required : List.of(
                "loader-bootstrap", "loader-core", "loader-resolve", "loader-model",
                "loader-diag", "loader-provider-api", "provider-minecraft", "omni-api")) {
            assertTrue(jars.stream().anyMatch(name -> name.startsWith(required)),
                    () -> required + " is missing from the staged classpath: " + jars);
        }
    }

    @Test
    @DisplayName("a real JVM can run the staged entry point")
    void stagedEntryPointRuns() throws IOException, InterruptedException {
        Path dir = distDir();
        String mainClass = System.getProperty("omni.loader.mainClass");
        assumeTrue(mainClass != null, "omni.loader.mainClass is unset");

        Path lib = dir.resolve("lib");
        String classpath;
        try (Stream<Path> entries = Files.list(lib)) {
            classpath = entries
                    .filter(p -> p.toString().endsWith(".jar"))
                    .map(p -> p.toAbsolutePath().toString())
                    .reduce((a, b) -> a + java.io.File.pathSeparator + b)
                    .orElseThrow();
        }

        // No arguments: the bootstrap must reach its own usage error and exit 1. Reaching that at
        // all proves the main class named in the manifest is present on the staged classpath and
        // loads — the exact thing the launcher relies on and nothing else here checks.
        Process process = new ProcessBuilder(javaExecutable(), "-cp", classpath, mainClass)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean exited = process.waitFor(60, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            throw new AssertionError("the staged entry point did not exit within 60s");
        }

        assertEquals(1, process.exitValue(),
                () -> "expected exit 1 (missing --game-dir); output was:\n" + output);
        assertTrue(output.contains("--game-dir is required"),
                () -> "the entry point ran but did not print its usage error; output was:\n" + output);
    }

    /** The JVM running these tests, reused to launch the staged one. */
    private static String javaExecutable() {
        String home = System.getProperty("java.home");
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return Path.of(home, "bin", windows ? "java.exe" : "java").toString();
    }
}
