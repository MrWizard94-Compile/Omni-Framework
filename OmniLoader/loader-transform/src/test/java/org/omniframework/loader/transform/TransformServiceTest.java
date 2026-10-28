package org.omniframework.loader.transform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.transform.mapping.ClassIndex;
import org.omniframework.loader.transform.mapping.MappingSet;
import org.omniframework.loader.transform.pass.RemapPass;

/**
 * The ensure step end to end: key, cache, pipeline, and the parallel path a first launch takes.
 */
class TransformServiceTest {

    private static final MappingSet MAPPINGS = new MappingSet.Builder("intermediary", "official")
            .mapClass("game/class_1", "net/minecraft/Entity")
            .build();

    private static TransformService service(Path dir) {
        return new TransformService(
                new TransformCache(dir.resolve("cache")),
                new TransformPipeline(List.of(new RemapPass()), 1),
                "0.1.0",
                dir.resolve("work"));
    }

    private static Path modJar(Path dir, String name, int value) throws IOException {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put("game/class_1", Bytecode.valueClass("game/class_1", value));
        return Bytecode.classJar(dir.resolve("mods").resolve(name), classes);
    }

    private static TransformService.Request request(String modId, Path jar) {
        return new TransformService.Request(modId, jar, MAPPINGS, "intermediary-1.21.1", "abc",
                "fabric", "0.1.0", PassContext.GameSideTarget.CLIENT, Map.of());
    }

    @Test
    @DisplayName("the first ensure transforms, the second is served from cache")
    void missThenHit(@TempDir Path dir) throws IOException {
        TransformService service = service(dir);
        Path jar = modJar(dir, "mod.jar", 1);

        TransformService.Ensured first =
                service.ensure(request("mod", jar), new ClassIndex(), new IssueCollector());
        TransformService.Ensured second =
                service.ensure(request("mod", jar), new ClassIndex(), new IssueCollector());

        assertFalse(first.wasHit(), "nothing was cached yet");
        assertTrue(second.wasHit(), "the second launch must not re-transform an unchanged jar");
        assertEquals(first.key(), second.key());
        assertArrayEquals(Files.readAllBytes(first.jar()), Files.readAllBytes(second.jar()));
    }

    @Test
    @DisplayName("a changed jar misses and is transformed again")
    void changedJarMisses(@TempDir Path dir) throws IOException {
        TransformService service = service(dir);

        TransformService.Ensured first = service.ensure(
                request("mod", modJar(dir, "mod.jar", 1)), new ClassIndex(), new IssueCollector());
        TransformService.Ensured second = service.ensure(
                request("mod", modJar(dir, "mod2.jar", 2)), new ClassIndex(), new IssueCollector());

        assertNotEquals(first.key(), second.key());
        assertFalse(second.wasHit(), "different bytes must never reuse an entry");
    }

    @Test
    @DisplayName("the transformed jar actually contains the renamed class")
    void producesRemappedJar(@TempDir Path dir) throws IOException {
        TransformService service = service(dir);
        TransformService.Ensured ensured = service.ensure(
                request("mod", modJar(dir, "mod.jar", 7)), new ClassIndex(), new IssueCollector());

        try (var zip = new java.util.zip.ZipFile(ensured.jar().toFile())) {
            assertTrue(
                    zip.getEntry("net/minecraft/Entity.class") != null,
                    "remapped class entry must use the official path so the classloader can find it");
            assertTrue(
                    zip.getEntry("game/class_1.class") == null,
                    "the intermediary path must not remain after class rename");
            byte[] content =
                    zip.getInputStream(zip.getEntry("net/minecraft/Entity.class")).readAllBytes();
            Object instance = new Bytecode.Loader()
                    .with("net/minecraft/Entity", content)
                    .load("net/minecraft/Entity")
                    .getDeclaredConstructor().newInstance();
            assertEquals(7, instance.getClass().getMethod("value").invoke(instance));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("the cached jar should contain a loadable class", e);
        }
    }

    @Test
    @DisplayName("building for the other side produces a different entry")
    void sideIsPartOfTheKey(@TempDir Path dir) throws IOException {
        // A server jar has client members stripped, so serving a client entry to a server
        // would ship rendering classes to a machine with no display.
        TransformService service = service(dir);
        Path jar = modJar(dir, "mod.jar", 1);

        TransformService.Ensured client = service.ensure(
                request("mod", jar), new ClassIndex(), new IssueCollector());
        TransformService.Ensured server = service.ensure(
                new TransformService.Request("mod", jar, MAPPINGS, "intermediary-1.21.1", "abc",
                        "fabric", "0.1.0", PassContext.GameSideTarget.SERVER, Map.of()),
                new ClassIndex(), new IssueCollector());

        assertNotEquals(client.key(), server.key());
    }

    @Test
    @DisplayName("many jars ensure in parallel and come back in request order")
    void parallelEnsurePreservesOrder(@TempDir Path dir) throws IOException {
        // Load order is the resolver's decision; it must not be perturbed by which jar
        // happened to finish transforming first.
        TransformService service = service(dir);
        List<TransformService.Request> requests = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            requests.add(request("mod" + i, modJar(dir, "mod" + i + ".jar", i)));
        }

        List<TransformService.Ensured> results =
                service.ensureAll(requests, new ClassIndex(), new IssueCollector());

        assertEquals(40, results.size());
        for (int i = 0; i < 40; i++) {
            assertEquals("mod" + i, results.get(i).modId(), "results must follow request order");
        }
        assertEquals(40, TransformService.summarise(results).misses());

        List<TransformService.Ensured> second =
                service.ensureAll(requests, new ClassIndex(), new IssueCollector());
        TransformService.Summary summary = TransformService.summarise(second);
        assertEquals(40, summary.hits(), summary::describe);
        assertEquals(0, summary.misses(), "a warm cache should transform nothing");
    }

    @Test
    @DisplayName("a native jar needs no mappings and is still cached")
    void nativeJarIsCheap(@TempDir Path dir) throws IOException {
        TransformService service = service(dir);
        Path jar = modJar(dir, "native.jar", 3);

        TransformService.Ensured ensured = service.ensure(
                TransformService.Request.native_("nativemod", jar,
                        PassContext.GameSideTarget.CLIENT),
                new ClassIndex(), new IssueCollector());

        assertFalse(ensured.wasHit());
        assertEquals(0, ensured.entry().audit().passApplications(),
                "an already-official jar has nothing to remap");
        assertTrue(service.ensure(
                TransformService.Request.native_("nativemod", jar,
                        PassContext.GameSideTarget.CLIENT),
                new ClassIndex(), new IssueCollector()).wasHit());
    }

    @Test
    @DisplayName("maintenance sweeps interrupted writes and stale-pipeline entries")
    void maintenance(@TempDir Path dir) throws IOException {
        TransformService service = service(dir);
        service.ensure(request("mod", modJar(dir, "mod.jar", 1)),
                new ClassIndex(), new IssueCollector());

        // An entry from an older pipeline can never be hit again, so removing it is free.
        TransformCache cache = new TransformCache(dir.resolve("cache"));
        CacheKey stale = CacheKey.builder().jarBytes(new byte[] {9}).mappings("m", "h")
                .pipelineVersion(0).layer("l", "v").build();
        Path old = dir.resolve("old.jar");
        Files.writeString(old, "x");
        cache.store(stale, old, TransformAudit.empty(), new EntryMeta(
                "old.jar", "inputs", 0, "0.0.1", java.time.Instant.now(), 1, 0));

        Map<String, Integer> removed = service.maintain(Long.MAX_VALUE);

        assertEquals(1, removed.get("staleVersion"));
        assertTrue(cache.lookup(stale).isEmpty());
    }

    @Test
    @DisplayName("--audit traces a class back to the jar that changed it")
    void auditQuery(@TempDir Path dir) throws IOException {
        TransformService service = service(dir);
        service.ensure(request("mod", modJar(dir, "mod.jar", 1)),
                new ClassIndex(), new IssueCollector());

        List<TransformCache.ClassHistory> history = service.auditClass("game.class_1");

        assertEquals(1, history.size());
        assertEquals("mod.jar", history.get(0).sourceName());
        assertTrue(history.get(0).describe().contains("remap-official"),
                history.get(0).describe());
    }

    @Test
    @DisplayName("indexing several jars builds one hierarchy across them")
    void indexAcrossJars(@TempDir Path dir) throws IOException {
        // A mod extending a class from another mod only remaps correctly if both are indexed.
        Path base = Bytecode.classJar(dir.resolve("a").resolve("base.jar"),
                Map.of("game/Base", Bytecode.baseClass("game/Base", "method_1", 1)));
        Path sub = Bytecode.classJar(dir.resolve("b").resolve("sub.jar"),
                Map.of("game/Sub", Bytecode.subclass("game/Sub", "game/Base")));

        ClassIndex index = TransformService.indexAll(List.of(base, sub));

        assertEquals(2, index.size());
        assertTrue(index.ancestorsOf("game/Sub").contains("game/Base"));
    }
}
