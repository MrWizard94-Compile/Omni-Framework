package org.omniframework.loader.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.omniframework.loader.core.resource.DirectoryResourceRoot;
import org.omniframework.loader.core.resource.JarResourceRoot;
import org.omniframework.loader.diag.IssueCollector;

/** The transforming class loader: isolation, partitioning, transformation, and concurrency. */
class ClassLoadingTest {

    private static Path jarWith(Path dir, String name, String binaryName, String body)
            throws IOException {
        return TestJars.builder()
                .source(binaryName, """
                        package %s;
                        public class %s {
                            public static String describe() { return "%s"; }
                        }
                        """.formatted(
                                binaryName.substring(0, binaryName.lastIndexOf('.')),
                                binaryName.substring(binaryName.lastIndexOf('.') + 1),
                                body))
                .writeTo(dir.resolve(name));
    }

    private static PartitionRegistry registryOf(Partition... partitions) {
        PartitionRegistry registry = new PartitionRegistry();
        for (Partition partition : partitions) {
            registry.add(partition);
        }
        return registry;
    }

    @Test
    @DisplayName("loads a class from a jar partition and attributes its code source")
    void loadsFromJar(@TempDir Path dir) throws Exception {
        Path jar = jarWith(dir, "mod.jar", "net.example.Thing", "from-jar");
        PartitionRegistry registry =
                registryOf(Partition.mod("examplemod", "omni", JarResourceRoot.open(jar)));

        try (var loader = new OmniTransformingClassLoader(registry, ClassTransformer.identity())) {
            Class<?> loaded = Class.forName("net.example.Thing", true, loader);
            assertSame(loader, loaded.getClassLoader(), "the class belongs to our loader");
            assertEquals("from-jar", loaded.getMethod("describe").invoke(null));

            // The code source is what makes "which mod provided this class?" answerable.
            var codeSource = loaded.getProtectionDomain().getCodeSource();
            assertNotNull(codeSource, "a loaded class must know where it came from");
            assertTrue(codeSource.getLocation().toString().endsWith("mod.jar"),
                    () -> "unexpected code source: " + codeSource.getLocation());
        }
    }

    @Test
    @DisplayName("loads loose classes from a directory — the development loop")
    void loadsFromDirectory(@TempDir Path dir) throws Exception {
        Path classes = dir.resolve("classes");
        TestJars.builder()
                .source("net.example.Loose", """
                        package net.example;
                        public class Loose { public static String describe() { return "loose"; } }
                        """)
                .writeToDirectory(classes);
        PartitionRegistry registry =
                registryOf(Partition.mod("devmod", "omni", DirectoryResourceRoot.open(classes)));

        try (var loader = new OmniTransformingClassLoader(registry, ClassTransformer.identity())) {
            Class<?> loaded = Class.forName("net.example.Loose", true, loader);
            assertEquals("loose", loaded.getMethod("describe").invoke(null));
        }
    }

    @Test
    @DisplayName("the launch classpath does not leak into the game")
    void applicationClasspathIsSealedOut(@TempDir Path dir) throws Exception {
        Path jar = jarWith(dir, "mod.jar", "net.example.Thing", "x");
        PartitionRegistry registry =
                registryOf(Partition.mod("examplemod", "omni", JarResourceRoot.open(jar)));

        try (var loader = new OmniTransformingClassLoader(registry, ClassTransformer.identity())) {
            // This test class is on the application classpath. If the loader delegated there,
            // a mod could link against the launcher's own dependencies — the exact leak
            // ARCHITECTURE §4 seals. It must not be visible.
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName(ClassLoadingTest.class.getName(), false, loader),
                    "application classpath must not be reachable from the game loader");

            // JDK classes must still resolve, or nothing would run at all.
            assertSame(String.class, Class.forName("java.lang.String", false, loader));
            assertSame(List.class, Class.forName("java.util.List", false, loader));
        }
    }

    @Test
    @DisplayName("first partition in search order wins, and the choice is stable")
    void firstPartitionWins(@TempDir Path dir) throws Exception {
        Path first = jarWith(dir, "first.jar", "net.example.Thing", "first");
        Path second = jarWith(dir, "second.jar", "net.example.Thing", "second");
        PartitionRegistry registry = registryOf(
                Partition.mod("first", "omni", JarResourceRoot.open(first)),
                Partition.mod("second", "omni", JarResourceRoot.open(second)));

        assertEquals("first", registry.ownerOf("net.example.Thing").orElseThrow().id());
        // Asked twice, the answer cannot drift — a class must not resolve to different
        // artifacts at different moments.
        assertEquals("first", registry.ownerOf("net.example.Thing").orElseThrow().id());

        try (var loader = new OmniTransformingClassLoader(registry, ClassTransformer.identity())) {
            Class<?> loaded = Class.forName("net.example.Thing", true, loader);
            assertEquals("first", loaded.getMethod("describe").invoke(null));
        }
    }

    @Test
    @DisplayName("the game wins over a library that ships the same class")
    void gameBeatsLibrary(@TempDir Path dir) throws Exception {
        Path game = jarWith(dir, "game.jar", "net.example.Thing", "game");
        Path library = jarWith(dir, "lib.jar", "net.example.Thing", "library");
        PartitionRegistry registry = registryOf(
                Partition.game(JarResourceRoot.open(game)),
                Partition.library(JarResourceRoot.open(library)));

        try (var loader = new OmniTransformingClassLoader(registry, ClassTransformer.identity())) {
            Class<?> loaded = Class.forName("net.example.Thing", true, loader);
            assertEquals("game", loaded.getMethod("describe").invoke(null));
        }
    }

    @Test
    @DisplayName("transformers rewrite bytes, and see which partition a class came from")
    void transformersRun(@TempDir Path dir) throws Exception {
        Path jar = jarWith(dir, "mod.jar", "net.example.Thing", "original");
        PartitionRegistry registry =
                registryOf(Partition.mod("examplemod", "omni", JarResourceRoot.open(jar)));

        List<String> seen = new ArrayList<>();
        ClassTransformer recording = (name, partition, bytes) -> {
            seen.add(name + "@" + partition.id());
            return bytes;
        };

        try (var loader = new OmniTransformingClassLoader(registry, recording)) {
            Class.forName("net.example.Thing", true, loader);
        }
        assertEquals(List.of("net.example.Thing@examplemod"), seen);
    }

    @Test
    @DisplayName("libraries are never handed to transformers")
    void librariesAreNotTransformed(@TempDir Path dir) throws Exception {
        Path library = jarWith(dir, "lib.jar", "net.example.Lib", "lib");
        PartitionRegistry registry = registryOf(Partition.library(JarResourceRoot.open(library)));

        List<String> seen = new ArrayList<>();
        ClassTransformer recording = (name, partition, bytes) -> {
            seen.add(name);
            return bytes;
        };

        try (var loader = new OmniTransformingClassLoader(registry, recording)) {
            Class.forName("net.example.Lib", true, loader);
        }
        assertTrue(seen.isEmpty(), () -> "a library was transformed: " + seen);
    }

    @Test
    @DisplayName("chained transformers run in order, each seeing the previous output")
    void chainedTransformersComposeInOrder() {
        List<String> order = new ArrayList<>();
        ClassTransformer first = (name, partition, bytes) -> {
            order.add("first");
            return bytes;
        };
        ClassTransformer second = (name, partition, bytes) -> {
            order.add("second");
            return bytes;
        };
        ClassTransformer chain = ClassTransformer.chain(List.of(first, second));
        chain.transform("x.Y", Partition.mod("m", "omni", new NoResourceRoot()), new byte[0]);
        assertEquals(List.of("first", "second"), order);

        // An empty chain is the identity, not a failure — a vanilla launch.
        byte[] bytes = {1, 2, 3};
        assertSame(bytes,
                ClassTransformer.chain(List.of())
                        .transform("x.Y", Partition.mod("m", "omni", new NoResourceRoot()), bytes));
    }

    @Test
    @DisplayName("a class is defined once even under concurrent loading")
    void concurrentLoadingDefinesOnce(@TempDir Path dir) throws Exception {
        Path jar = jarWith(dir, "mod.jar", "net.example.Thing", "concurrent");
        PartitionRegistry registry =
                registryOf(Partition.mod("examplemod", "omni", JarResourceRoot.open(jar)));

        java.util.concurrent.atomic.AtomicInteger transformCount =
                new java.util.concurrent.atomic.AtomicInteger();
        ClassTransformer counting = (name, partition, bytes) -> {
            transformCount.incrementAndGet();
            return bytes;
        };

        try (var loader = new OmniTransformingClassLoader(registry, counting)) {
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Callable<Class<?>>> tasks = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    tasks.add(() -> Class.forName("net.example.Thing", true, loader));
                }
                List<Future<Class<?>>> results = pool.invokeAll(tasks);
                Class<?> first = results.get(0).get();
                for (Future<Class<?>> result : results) {
                    assertSame(first, result.get(), "every thread must get the same Class object");
                }
            } finally {
                pool.shutdownNow();
            }
            // Transformers may therefore be written as thread-safe but not idempotent.
            assertEquals(1, transformCount.get(), "the class was transformed more than once");
        }
    }

    @Test
    @DisplayName("resources resolve from partitions before the parent")
    void resourcesComeFromPartitions(@TempDir Path dir) throws Exception {
        Path jar = TestJars.builder()
                .source("net.example.Thing", """
                        package net.example;
                        public class Thing {}
                        """)
                .resource("data/config.txt", "from-mod")
                .writeTo(dir.resolve("mod.jar"));
        PartitionRegistry registry =
                registryOf(Partition.mod("examplemod", "omni", JarResourceRoot.open(jar)));

        try (var loader = new OmniTransformingClassLoader(registry, ClassTransformer.identity());
                var stream = loader.getResourceAsStream("data/config.txt")) {
            assertNotNull(stream, "the resource must be found");
            assertEquals("from-mod", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("two mods claiming a package is reported, not silently resolved")
    void packageClaimsAreDiagnosed(@TempDir Path dir) throws Exception {
        Path first = jarWith(dir, "a.jar", "shared.pkg.A", "a");
        Path second = jarWith(dir, "b.jar", "shared.pkg.B", "b");
        try (PartitionRegistry registry = registryOf(
                Partition.mod("alpha", "omni", JarResourceRoot.open(first)),
                Partition.mod("beta", "omni", JarResourceRoot.open(second)))) {

        IssueCollector issues = new IssueCollector();
        registry.diagnosePackageClaims(issues);

        assertFalse(issues.hasErrors(), "sharing a package is legal — JPMS is not enforced");
        var issue = issues.issues().stream()
                .filter(candidate -> candidate.code().equals("partition.package-claimed-twice"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the overlap must be reported"));
        assertEquals("shared.pkg", issue.location());
        assertTrue(issue.arguments().get(0).contains("alpha"), issue::describe);
        assertTrue(issue.arguments().get(0).contains("beta"), issue::describe);
        }
    }

    @Test
    @DisplayName("the game sharing packages with a library is not reported as a clash")
    void onlyModsAreDiagnosed(@TempDir Path dir) throws Exception {
        Path game = jarWith(dir, "game.jar", "shared.pkg.A", "a");
        Path library = jarWith(dir, "lib.jar", "shared.pkg.B", "b");
        try (PartitionRegistry registry = registryOf(
                Partition.game(JarResourceRoot.open(game)),
                Partition.library(JarResourceRoot.open(library)))) {
            IssueCollector issues = new IssueCollector();
            registry.diagnosePackageClaims(issues);
            assertTrue(issues.isEmpty(), () -> "unexpected: " + issues.issues());
        }
    }

    @Test
    @DisplayName("two loaders give different Class objects for the same bytes")
    void loadersAreIsolatedFromEachOther(@TempDir Path dir) throws Exception {
        Path jar = jarWith(dir, "mod.jar", "net.example.Thing", "x");
        try (var a = new OmniTransformingClassLoader(
                        registryOf(Partition.mod("m", "omni", JarResourceRoot.open(jar))),
                        ClassTransformer.identity());
                var b = new OmniTransformingClassLoader(
                        registryOf(Partition.mod("m", "omni", JarResourceRoot.open(jar))),
                        ClassTransformer.identity())) {
            assertNotSame(
                    Class.forName("net.example.Thing", false, a),
                    Class.forName("net.example.Thing", false, b),
                    "each loader defines its own copy — the basis of instance isolation");
        }
    }

    /** A root with nothing in it, for transformer tests that never read bytes. */
    private static final class NoResourceRoot implements org.omniframework.loader.core.resource.ResourceRoot {
        @Override
        public Path location() {
            return Path.of("none");
        }

        @Override
        public java.util.Optional<byte[]> read(String path) {
            return java.util.Optional.empty();
        }

        @Override
        public boolean contains(String path) {
            return false;
        }

        @Override
        public java.util.Set<String> packages() {
            return java.util.Set.of();
        }

        @Override
        public void close() {
            // Nothing held.
        }
    }
}
