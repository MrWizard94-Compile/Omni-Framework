package org.omniframework.loader.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.omniframework.loader.transform.mapping.ClassIndex;
import org.omniframework.loader.transform.mapping.MappingSet;
import org.omniframework.loader.transform.mapping.TinyV2Reader;

/** Mapping data and the hierarchy walk that makes it usable. */
class MappingTest {

    @Nested
    @DisplayName("Tiny v2 parsing")
    class Tiny {

        private static MappingSet read(String document, String from, String to) throws IOException {
            return TinyV2Reader.read(new StringReader(document), from, to);
        }

        @Test
        @DisplayName("classes, fields, and methods are read")
        void readsAllThree() throws IOException {
            MappingSet mappings = read("""
                    tiny\t2\t0\tintermediary\tofficial
                    c\tnet/minecraft/class_1\tnet/minecraft/Entity
                    \tf\tI\tfield_1\thealth
                    \tm\t()V\tmethod_1\ttick
                    \t\tp\t1\targ0\tamount
                    """, "intermediary", "official");

            assertEquals(Optional.of("net/minecraft/Entity"),
                    mappings.mapClass("net/minecraft/class_1"));
            assertEquals(Optional.of("health"),
                    mappings.mapField("net/minecraft/class_1", "field_1", "I"));
            assertEquals(Optional.of("tick"),
                    mappings.mapMethod("net/minecraft/class_1", "method_1", "()V"));
        }

        @Test
        @DisplayName("members are distinguished by descriptor, not by name alone")
        void descriptorsDisambiguate() throws IOException {
            // Overloads are constant in Minecraft; keying on name alone would rename whichever
            // happened to match first.
            MappingSet mappings = read("""
                    tiny\t2\t0\tintermediary\tofficial
                    c\tgame/C\tgame/C
                    \tm\t(I)V\tmethod_1\tsetInt
                    \tm\t(F)V\tmethod_1\tsetFloat
                    """, "intermediary", "official");

            assertEquals(Optional.of("setInt"), mappings.mapMethod("game/C", "method_1", "(I)V"));
            assertEquals(Optional.of("setFloat"), mappings.mapMethod("game/C", "method_1", "(F)V"));
        }

        @Test
        @DisplayName("the requested namespaces are honoured, not assumed positionally")
        void namespacesAreSelected() throws IOException {
            // Reading the columns in the wrong order yields a set that renames confidently in the
            // wrong direction — a jar that verifies and then finds no game method at all.
            String document = """
                    tiny\t2\t0\tofficial\tintermediary\tnamed
                    c\tnet/minecraft/Entity\tnet/minecraft/class_1\tEntity
                    """;

            assertEquals(Optional.of("net/minecraft/class_1"),
                    read(document, "official", "intermediary").mapClass("net/minecraft/Entity"));
            assertEquals(Optional.of("net/minecraft/Entity"),
                    read(document, "intermediary", "official").mapClass("net/minecraft/class_1"));
        }

        @Test
        @DisplayName("a missing namespace is refused with the available ones named")
        void missingNamespace() {
            TransformException thrown = assertThrows(TransformException.class, () -> read("""
                    tiny\t2\t0\tintermediary\tofficial
                    """, "intermediary", "srg"));

            assertTrue(thrown.getMessage().contains("srg"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("official"),
                    "the message should say what IS available: " + thrown.getMessage());
        }

        @Test
        @DisplayName("an entry unmapped in the target namespace keeps its name")
        void unmappedEntriesAreLeftAlone() throws IOException {
            // A trailing empty column is how the format spells "not mapped"; dropping it would
            // turn an unmapped member into an out-of-range read.
            MappingSet mappings = read("""
                    tiny\t2\t0\tintermediary\tofficial
                    c\tgame/Mapped\tgame/Official
                    c\tgame/Unmapped\t
                    \tm\t()V\tmethod_1\t
                    """, "intermediary", "official");

            assertEquals(Optional.of("game/Official"), mappings.mapClass("game/Mapped"));
            assertTrue(mappings.mapClass("game/Unmapped").isEmpty());
            assertTrue(mappings.mapMethod("game/Unmapped", "method_1", "()V").isEmpty());
        }

        @Test
        @DisplayName("a non-Tiny or wrong-version file is refused")
        void rejectsWrongFormat() {
            assertThrows(TransformException.class,
                    () -> read("not a mappings file", "a", "b"));
            assertThrows(TransformException.class,
                    () -> read("tiny\t1\t0\ta\tb", "a", "b"));
            assertThrows(TransformException.class, () -> read("", "a", "b"));
        }

        @Test
        @DisplayName("a member before any class is refused rather than silently dropped")
        void memberWithoutClass() {
            assertThrows(TransformException.class, () -> read("""
                    tiny\t2\t0\tintermediary\tofficial
                    \tm\t()V\tmethod_1\ttick
                    """, "intermediary", "official"));
        }
    }

    @Nested
    @DisplayName("class hierarchy")
    class Hierarchy {

        @Test
        @DisplayName("ancestors are returned superclass-first, then interfaces")
        void resolutionOrder() {
            ClassIndex index = new ClassIndex();
            index.add("game/Base", "java/lang/Object", List.of("game/Marker"));
            index.add("game/Middle", "game/Base", List.of());
            index.add("game/Leaf", "game/Middle", List.of("game/Extra"));

            List<String> ancestors = index.ancestorsOf("game/Leaf");

            // Superclass before interfaces mirrors how the JVM resolves a member, so a name
            // present in both picks the declaration the JVM would.
            assertTrue(ancestors.indexOf("game/Middle") < ancestors.indexOf("game/Base"),
                    ancestors::toString);
            assertTrue(ancestors.contains("game/Extra"));
            assertTrue(ancestors.contains("game/Marker"), "interfaces are inherited transitively");
            assertFalse(ancestors.contains("game/Leaf"), "a class is not its own ancestor");
        }

        @Test
        @DisplayName("an unknown class yields no ancestors rather than an error")
        void unknownClassIsEmpty() {
            // Mod jars routinely reference the JDK and other mods; those are not ours to rewrite.
            assertTrue(new ClassIndex().ancestorsOf("java/lang/String").isEmpty());
        }

        @Test
        @DisplayName("a cycle in the hierarchy terminates instead of hanging")
        void cyclesTerminate() {
            // Malformed or adversarial jars exist; a loader that hangs on one is a worse
            // failure than one that reports it.
            ClassIndex index = new ClassIndex();
            index.add("game/A", "game/B", List.of());
            index.add("game/B", "game/A", List.of());

            List<String> ancestors = index.ancestorsOf("game/A");

            assertTrue(ancestors.contains("game/B"));
            assertTrue(ancestors.size() <= 2, ancestors::toString);
        }

        @Test
        @DisplayName("assignability follows the ancestor chain")
        void assignability() {
            ClassIndex index = new ClassIndex();
            index.add("game/Base", "java/lang/Object", List.of());
            index.add("game/Sub", "game/Base", List.of());

            assertTrue(index.isAssignableFrom("game/Base", "game/Sub"));
            assertTrue(index.isAssignableFrom("game/Sub", "game/Sub"));
            assertFalse(index.isAssignableFrom("game/Sub", "game/Base"));
        }

        @Test
        @DisplayName("the index settles correctly when registration races lookups")
        void concurrentRegistrationDoesNotPoisonTheMemo() throws Exception {
            // The pipeline transforms jars in parallel through one shared index, each jar
            // registering its classes as it goes, so lookups genuinely overlap registrations.
            //
            // This is a stress test, not a regression test: it does not fail against a plain
            // computeIfAbsent, because ConcurrentHashMap.clear() cannot skip a concurrent
            // insertion. It exists to hold the settled invariant under real interleaving, which
            // is the property callers actually depend on.
            // The condition that makes a stale memo observably wrong: Leaf's ancestor chain is
            // INCOMPLETE until Middle is registered. A walk cached before that registration is
            // missing Base forever. Readers race ahead of writers on purpose.
            int width = 400;
            ClassIndex index = new ClassIndex();
            index.add("game/Base", "java/lang/Object", List.of());
            for (int i = 0; i < width; i++) {
                index.add("game/Leaf" + i, "game/Middle" + i, List.of());
            }

            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> tasks = new ArrayList<>();
                tasks.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < width; i++) {
                        index.add("game/Middle" + i, "game/Base", List.of());
                    }
                    return null;
                }));
                for (int t = 0; t < 3; t++) {
                    tasks.add(pool.submit(() -> {
                        start.await();
                        for (int round = 0; round < 40; round++) {
                            for (int i = 0; i < width; i++) {
                                index.ancestorsOf("game/Leaf" + i);
                            }
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> task : tasks) {
                    task.get();
                }
            } finally {
                pool.shutdownNow();
            }

            // Once every Middle is registered, every Leaf must reach Base. A memo cached during
            // the race would have frozen the incomplete answer.
            for (int i = 0; i < width; i++) {
                assertTrue(index.ancestorsOf("game/Leaf" + i).contains("game/Base"),
                        "Leaf" + i + " lost its transitive ancestor to a stale memo: "
                                + index.ancestorsOf("game/Leaf" + i));
            }
        }

        @Test
        @DisplayName("the identity mapping set renames nothing")
        void identity() {
            assertTrue(MappingSet.identity().isIdentity());
            assertTrue(MappingSet.identity().mapClass("anything").isEmpty());
            assertFalse(new MappingSet.Builder("a", "b").mapClass("x", "y").build().isIdentity());
        }
    }
}
