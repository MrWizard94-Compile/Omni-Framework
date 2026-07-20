package org.omniframework.loader.transform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.transform.mapping.ClassIndex;
import org.omniframework.loader.transform.mapping.MappingSet;
import org.omniframework.loader.transform.pass.EnvironmentStripPass;
import org.omniframework.loader.transform.pass.RemapPass;

/** The pipeline's own guarantees: reproducible output, honest audits, and all-or-nothing failure. */
class TransformPipelineTest {

    private static final MappingSet MAPPINGS = new MappingSet.Builder("intermediary", "official")
            .mapClass("game/class_1", "net/minecraft/Entity")
            .build();

    private static PassContext context() {
        return new PassContext("test.jar", "testmod", MAPPINGS, new ClassIndex(),
                PassContext.GameSideTarget.CLIENT, new IssueCollector());
    }

    private static Path inputJar(Path dir) throws IOException {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put("game/class_1", Bytecode.valueClass("game/class_1", 1));
        classes.put("mod/Untouched", Bytecode.valueClass("mod/Untouched", 2));
        return Bytecode.classJar(dir.resolve("in").resolve("mod.jar"), classes);
    }

    @Test
    @DisplayName("the same input produces a byte-identical jar every time")
    void outputIsReproducible(@TempDir Path dir) throws IOException {
        // Without this there is no way to distinguish a corrupt cache entry from ordinary
        // nondeterminism, and no way to verify an entry by rebuilding it.
        TransformPipeline pipeline = new TransformPipeline(List.of(new RemapPass()), 1);
        Path input = inputJar(dir);

        Path first = dir.resolve("out-1.jar");
        Path second = dir.resolve("out-2.jar");
        pipeline.run(input, first, context());
        pipeline.run(input, second, context());

        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second),
                "two runs over identical input must produce identical bytes");
    }

    @Test
    @DisplayName("output does not embed the wall clock")
    void timestampsAreFixed(@TempDir Path dir) throws IOException {
        // Zip entry times default to "now", which would make every rebuild differ from the last
        // in a way nothing notices until someone compares two caches.
        TransformPipeline pipeline = new TransformPipeline(List.of(new RemapPass()), 1);
        Path input = inputJar(dir);
        Path output = dir.resolve("out.jar");
        pipeline.run(input, output, context());

        try (var zip = new java.util.zip.ZipFile(output.toFile())) {
            List<Long> times = zip.stream().map(java.util.zip.ZipEntry::getTime).distinct().toList();
            assertEquals(1, times.size(), "every entry should carry the same fixed timestamp");
            assertNotEquals(0L, times.get(0));
            assertTrue(times.get(0) < System.currentTimeMillis() - 1000L,
                    "the timestamp must be a constant, not the clock");
        }
    }

    @Test
    @DisplayName("only classes the passes changed appear in the audit")
    void auditRecordsOnlyRealChanges(@TempDir Path dir) throws IOException {
        TransformPipeline pipeline = new TransformPipeline(List.of(new RemapPass()), 1);
        TransformPipeline.Result result =
                pipeline.run(inputJar(dir), dir.resolve("out.jar"), context());

        assertEquals(List.of("game/class_1"), result.audit().modifiedClasses(),
                "a class that mentions nothing mapped must not clutter the audit");
        assertEquals(2, result.entryCount());
        assertEquals(1, result.modifiedCount());
        assertTrue(result.changedAnything());
    }

    @Test
    @DisplayName("untouched entries are copied through unchanged")
    void untouchedEntriesSurvive(@TempDir Path dir) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("game/class_1.class", Bytecode.valueClass("game/class_1", 1));
        entries.put("assets/mod/icon.png", new byte[] {1, 2, 3, 4});
        entries.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
        Path input = Bytecode.jarOf(dir.resolve("in").resolve("mod.jar"), entries);

        Path output = dir.resolve("out.jar");
        new TransformPipeline(List.of(new RemapPass()), 1).run(input, output, context());

        try (var zip = new java.util.zip.ZipFile(output.toFile())) {
            assertArrayEquals(new byte[] {1, 2, 3, 4},
                    zip.getInputStream(zip.getEntry("assets/mod/icon.png")).readAllBytes(),
                    "a resource the pipeline has no opinion about must survive intact");
            assertTrue(zip.getEntry("META-INF/MANIFEST.MF") != null);
        }
    }

    @Test
    @DisplayName("a class belonging entirely to the other side is dropped from the jar")
    void wholeClassStripRemovesTheEntry(@TempDir Path dir) throws IOException {
        // Stripping members but leaving the class is the worst of both worlds: the class ships
        // to a dedicated server, and the first thing to touch it fails on rendering types that
        // are not there. If the pass says a class is client-only, the entry has to go.
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("mod/ClientOnly.class", Bytecode.sidedClass(
                "mod/ClientOnly", "Lnet/fabricmc/api/Environment;", "CLIENT", null, null));
        entries.put("mod/Common.class", Bytecode.valueClass("mod/Common", 1));
        Path input = Bytecode.jarOf(dir.resolve("in").resolve("mod.jar"), entries);

        Path output = dir.resolve("out.jar");
        PassContext server = new PassContext("mod.jar", "testmod", MappingSet.identity(),
                new ClassIndex(), PassContext.GameSideTarget.SERVER, new IssueCollector());
        new TransformPipeline(List.of(new EnvironmentStripPass()), 1)
                .run(input, output, server);

        try (var zip = new java.util.zip.ZipFile(output.toFile())) {
            assertTrue(zip.getEntry("mod/Common.class") != null, "common classes must survive");
            assertTrue(zip.getEntry("mod/ClientOnly.class") == null,
                    "a client-only class must not be shipped to a server");
        }
    }

    @Test
    @DisplayName("a failing pass fails the whole jar, naming the entry")
    void failureIsPerJar(@TempDir Path dir) throws IOException {
        // A partially transformed jar loads and then misbehaves only along the paths touching
        // the classes that were skipped. One clear error beats that.
        TransformPass exploding = new TransformPass() {
            @Override
            public String id() {
                return "exploding";
            }

            @Override
            public String version() {
                return "1";
            }

            @Override
            public Optional<Outcome> transform(JarResource resource, PassContext context) {
                throw new IllegalStateException("boom");
            }
        };

        TransformException thrown = assertThrows(TransformException.class,
                () -> new TransformPipeline(List.of(exploding), 1)
                        .run(inputJar(dir), dir.resolve("out.jar"), context()));

        assertTrue(thrown.getMessage().contains("exploding"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("test.jar"),
                "the message must name the jar, or it is a stack trace with no indication "
                        + "which of 300 mods produced it: " + thrown.getMessage());
    }

    @Test
    @DisplayName("passes run in the declared order")
    void passesRunInOrder(@TempDir Path dir) throws IOException {
        TransformPass first = appending("first", (byte) 'A');
        TransformPass second = appending("second", (byte) 'B');
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("data.txt", new byte[0]);
        Path input = Bytecode.jarOf(dir.resolve("in").resolve("mod.jar"), entries);

        Path output = dir.resolve("out.jar");
        new TransformPipeline(List.of(first, second), 1).run(input, output, context());

        try (var zip = new java.util.zip.ZipFile(output.toFile())) {
            byte[] content = zip.getInputStream(zip.getEntry("data.txt")).readAllBytes();
            assertArrayEquals(new byte[] {'A', 'B'}, content,
                    "order matters: remap must run before access merge, which names official types");
        }
    }

    @Test
    @DisplayName("two passes cannot share an id")
    void duplicatePassIdsRejected() {
        // The audit records changes by pass id; two passes sharing one would make it ambiguous
        // which made a change, defeating the point of recording it.
        TransformPass a = appending("same", (byte) 'A');
        TransformPass b = appending("same", (byte) 'B');
        assertThrows(IllegalArgumentException.class, () -> new TransformPipeline(List.of(a, b), 1));
    }

    @Test
    @DisplayName("the jar's own classes are indexed before transformation")
    void indexesOwnClasses(@TempDir Path dir) throws IOException {
        // A jar referencing its own types would otherwise have every one of them treated as
        // unknown, so inherited members declared inside the jar would not be remapped.
        ClassIndex hierarchy = new ClassIndex();
        PassContext ctx = new PassContext("test.jar", "testmod", MAPPINGS, hierarchy,
                PassContext.GameSideTarget.CLIENT, new IssueCollector());

        new TransformPipeline(List.of(new RemapPass()), 1)
                .run(inputJar(dir), dir.resolve("out.jar"), ctx);

        assertEquals(2, hierarchy.size());
        assertTrue(hierarchy.get("game/class_1").isPresent());
    }

    /** A pass that appends one byte to every entry, for order and wiring tests. */
    private static TransformPass appending(String id, byte marker) {
        return new TransformPass() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String version() {
                return "1";
            }

            @Override
            public Optional<Outcome> transform(JarResource resource, PassContext context) {
                byte[] content = resource.content();
                byte[] appended = java.util.Arrays.copyOf(content, content.length + 1);
                appended[content.length] = marker;
                return Optional.of(Outcome.of(appended, "appended " + (char) marker));
            }
        };
    }
}
