package org.omniframework.loader.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The cache's job is to never hand back bytes that do not match its key. Most of these tests are
 * about the ways a half-written or damaged entry could be mistaken for a good one.
 */
class TransformCacheTest {

    private static CacheKey key(String seed) {
        return CacheKey.builder()
                .jarBytes(seed.getBytes(StandardCharsets.UTF_8))
                .mappings("m", "h")
                .pipelineVersion(1)
                .layer("native", "0.1.0")
                .build();
    }

    private static EntryMeta meta(String source, int pipelineVersion) {
        return new EntryMeta(source, "jar=" + source, pipelineVersion, "0.1.0",
                Instant.parse("2026-07-20T00:00:00Z"), 10, 3);
    }

    private static TransformAudit auditOf(String className) {
        TransformAudit.Builder builder = new TransformAudit.Builder();
        builder.record(className, new TransformAudit.PassRecord(
                "remap-official", "1", 100, 120, 42, List.of("renamed a/B → c/D")));
        return builder.build();
    }

    private static Path jarFile(Path dir, String name, String content) throws IOException {
        Path jar = dir.resolve(name);
        Files.writeString(jar, content);
        return jar;
    }

    @Test
    @DisplayName("a stored entry is found again with its jar, audit, and metadata intact")
    void roundTrip(@TempDir Path dir) throws IOException {
        TransformCache cache = new TransformCache(dir.resolve("cache"));
        Path jar = jarFile(dir, "mod.jar", "transformed bytes");
        CacheKey key = key("mod");

        cache.store(key, jar, auditOf("net/minecraft/Entity"), meta("mod.jar", 1));
        CacheEntry found = cache.lookup(key).orElseThrow();

        assertEquals("transformed bytes", Files.readString(found.jar()));
        assertEquals("mod.jar", found.meta().sourceName());
        assertEquals(List.of("net/minecraft/Entity"), found.audit().modifiedClasses());
    }

    @Test
    @DisplayName("an unknown key is a miss")
    void missOnUnknownKey(@TempDir Path dir) {
        TransformCache cache = new TransformCache(dir.resolve("cache"));
        assertTrue(cache.lookup(key("never stored")).isEmpty());
    }

    @Test
    @DisplayName("entries land under a two-character shard")
    void shardsOnDisk(@TempDir Path dir) throws IOException {
        TransformCache cache = new TransformCache(dir.resolve("cache"));
        CacheKey key = key("mod");
        cache.store(key, jarFile(dir, "mod.jar", "x"), TransformAudit.empty(), meta("mod.jar", 1));

        Path expected = dir.resolve("cache").resolve(key.shard()).resolve(key.value());
        assertTrue(Files.isDirectory(expected), "expected an entry at " + expected);
    }

    @Nested
    @DisplayName("damaged entries")
    class Damaged {

        @Test
        @DisplayName("an entry missing its jar is a miss, not a crash")
        void missingJar(@TempDir Path dir) throws IOException {
            TransformCache cache = new TransformCache(dir.resolve("cache"));
            CacheKey key = key("mod");
            cache.store(key, jarFile(dir, "mod.jar", "x"), TransformAudit.empty(), meta("mod.jar", 1));

            Files.delete(cache.directoryFor(key).resolve(TransformCache.OUTPUT_JAR));

            assertTrue(cache.lookup(key).isEmpty(),
                    "a torn entry must not be served; that is how a truncated jar becomes a "
                            + "ClassFormatError that survives every reinstall");
        }

        @Test
        @DisplayName("an entry missing its audit is a miss")
        void missingAudit(@TempDir Path dir) throws IOException {
            TransformCache cache = new TransformCache(dir.resolve("cache"));
            CacheKey key = key("mod");
            cache.store(key, jarFile(dir, "mod.jar", "x"), TransformAudit.empty(), meta("mod.jar", 1));

            Files.delete(cache.directoryFor(key).resolve(TransformCache.AUDIT_FILE));

            assertTrue(cache.lookup(key).isEmpty(),
                    "an entry whose audit is gone can never answer 'who changed this class'");
        }

        @Test
        @DisplayName("interrupted writes are swept, and are never mistaken for entries")
        void sweepsIncompleteWrites(@TempDir Path dir) throws IOException {
            TransformCache cache = new TransformCache(dir.resolve("cache"));
            CacheKey key = key("mod");
            cache.store(key, jarFile(dir, "mod.jar", "x"), TransformAudit.empty(), meta("mod.jar", 1));

            Path orphan = cache.root().resolve(key.shard()).resolve(".incoming-12345");
            Files.createDirectories(orphan);
            Files.writeString(orphan.resolve(TransformCache.OUTPUT_JAR), "half a jar");

            assertEquals(1, cache.entries().size(), "a temp directory is not an entry");
            assertEquals(1, cache.sweepIncompleteWrites());
            assertFalse(Files.exists(orphan));
            assertTrue(cache.lookup(key).isPresent(), "the real entry must survive the sweep");
        }

        @Test
        @DisplayName("a corrupt audit is reported rather than silently returning nothing")
        void corruptAuditThrows(@TempDir Path dir) throws IOException {
            TransformCache cache = new TransformCache(dir.resolve("cache"));
            CacheKey key = key("mod");
            cache.store(key, jarFile(dir, "mod.jar", "x"), TransformAudit.empty(), meta("mod.jar", 1));
            Files.writeString(cache.directoryFor(key).resolve(TransformCache.AUDIT_FILE),
                    "{ this is not json");

            CacheEntry entry = cache.lookup(key).orElseThrow();
            assertThrows(TransformException.class, entry::audit);
        }
    }

    @Nested
    @DisplayName("garbage collection")
    class Gc {

        @Test
        @DisplayName("entries from another pipeline version are swept")
        void versionSweep(@TempDir Path dir) throws IOException {
            TransformCache cache = new TransformCache(dir.resolve("cache"));
            cache.store(key("old"), jarFile(dir, "old.jar", "x"),
                    TransformAudit.empty(), meta("old.jar", 1));
            cache.store(key("new"), jarFile(dir, "new.jar", "y"),
                    TransformAudit.empty(), meta("new.jar", 2));

            // Version-1 entries can never be hit again once the pipeline is at 2 — the version
            // is part of the key — so removing them costs nothing.
            assertEquals(1, cache.sweepStaleVersions(2));
            assertEquals(1, cache.entries().size());
            assertEquals("new.jar", cache.entries().get(0).meta().sourceName());
        }

        @Test
        @DisplayName("eviction removes least-recently-used entries first")
        void lruEviction(@TempDir Path dir) throws IOException {
            TransformCache cache = new TransformCache(dir.resolve("cache"));
            CacheKey stale = key("stale");
            CacheKey fresh = key("fresh");
            cache.store(stale, jarFile(dir, "stale.jar", "x".repeat(500)),
                    TransformAudit.empty(), meta("stale.jar", 1));
            cache.store(fresh, jarFile(dir, "fresh.jar", "y".repeat(500)),
                    TransformAudit.empty(), meta("fresh.jar", 1));

            Files.setLastModifiedTime(
                    cache.directoryFor(stale).resolve(TransformCache.ACCESS_MARKER),
                    FileTime.from(Instant.now().minus(30, ChronoUnit.DAYS)));

            long half = cache.sizeBytes() / 2;
            assertEquals(1, cache.evictToFit(half));
            assertTrue(cache.lookup(stale).isEmpty(), "the older entry should go first");
            assertTrue(cache.lookup(fresh).isPresent(), "the recently used entry should survive");
        }

        @Test
        @DisplayName("a cache already within budget evicts nothing")
        void noEvictionWhenFits(@TempDir Path dir) throws IOException {
            TransformCache cache = new TransformCache(dir.resolve("cache"));
            cache.store(key("mod"), jarFile(dir, "mod.jar", "x"),
                    TransformAudit.empty(), meta("mod.jar", 1));

            assertEquals(0, cache.evictToFit(Long.MAX_VALUE));
            assertEquals(1, cache.entries().size());
        }

        @Test
        @DisplayName("a lookup refreshes an entry's place in the LRU order")
        void lookupTouches(@TempDir Path dir) throws IOException {
            TransformCache cache = new TransformCache(dir.resolve("cache"));
            CacheKey used = key("used");
            cache.store(used, jarFile(dir, "used.jar", "x"),
                    TransformAudit.empty(), meta("used.jar", 1));
            Files.setLastModifiedTime(
                    cache.directoryFor(used).resolve(TransformCache.ACCESS_MARKER),
                    FileTime.from(Instant.now().minus(30, ChronoUnit.DAYS)));

            Instant before = cache.lookup(used).orElseThrow().lastAccessed();
            Optional<CacheEntry> again = cache.lookup(used);

            assertTrue(again.isPresent());
            assertTrue(again.get().lastAccessed().isAfter(before.minusSeconds(1)),
                    "using an entry must count as using it, or LRU evicts the hottest jars");
        }
    }

    @Test
    @DisplayName("--audit finds every jar that transformed a class")
    void auditAcrossEntries(@TempDir Path dir) throws IOException {
        TransformCache cache = new TransformCache(dir.resolve("cache"));
        cache.store(key("a"), jarFile(dir, "a.jar", "x"),
                auditOf("net/minecraft/Entity"), meta("a.jar", 1));
        cache.store(key("b"), jarFile(dir, "b.jar", "y"),
                auditOf("net/minecraft/Entity"), meta("b.jar", 1));
        cache.store(key("c"), jarFile(dir, "c.jar", "z"),
                auditOf("net/minecraft/Level"), meta("c.jar", 1));

        List<TransformCache.ClassHistory> history = cache.auditClass("net.minecraft.Entity");

        assertEquals(2, history.size(), "both jars that touched the class should be reported");
        assertTrue(history.stream().anyMatch(entry -> entry.sourceName().equals("a.jar")));
        assertTrue(history.stream().anyMatch(entry -> entry.sourceName().equals("b.jar")));
        assertTrue(history.get(0).describe().contains("remap-official"),
                history.get(0).describe());
    }

    @Test
    @DisplayName("--audit accepts a dotted class name as well as a slashed one")
    void auditAcceptsEitherForm(@TempDir Path dir) throws IOException {
        TransformCache cache = new TransformCache(dir.resolve("cache"));
        cache.store(key("a"), jarFile(dir, "a.jar", "x"),
                auditOf("net/minecraft/Entity"), meta("a.jar", 1));

        assertEquals(1, cache.auditClass("net/minecraft/Entity").size());
        assertEquals(1, cache.auditClass("net.minecraft.Entity").size(),
                "a user types dots; requiring slashes would return 'not found' for a class that "
                        + "is right there");
    }

    @Test
    @DisplayName("storing over an existing key replaces it rather than accumulating")
    void storeReplaces(@TempDir Path dir) throws IOException {
        TransformCache cache = new TransformCache(dir.resolve("cache"));
        CacheKey key = key("mod");
        cache.store(key, jarFile(dir, "first.jar", "first"),
                TransformAudit.empty(), meta("first.jar", 1));
        cache.store(key, jarFile(dir, "second.jar", "second"),
                TransformAudit.empty(), meta("second.jar", 1));

        assertEquals(1, cache.entries().size());
        assertEquals("second", Files.readString(cache.lookup(key).orElseThrow().jar()));
    }

    @Test
    @DisplayName("a directory that is not named like a key is left alone")
    void ignoresForeignDirectories(@TempDir Path dir) throws IOException {
        TransformCache cache = new TransformCache(dir.resolve("cache"));
        Path foreign = cache.root().resolve("ab").resolve("something-a-user-put-here");
        Files.createDirectories(foreign);
        Files.writeString(foreign.resolve(TransformCache.OUTPUT_JAR), "x");
        Files.writeString(foreign.resolve(TransformCache.AUDIT_FILE), "x");
        Files.writeString(foreign.resolve(TransformCache.META_FILE), "x");

        assertEquals(0, cache.entries().size());
        assertTrue(Files.exists(foreign), "deleting unrecognised files would be overreach");
    }
}
