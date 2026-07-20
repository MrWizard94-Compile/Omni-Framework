package org.omniframework.loader.transform;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * One stored transformation: the produced jar plus the record of how it was produced.
 *
 * <p>The two JSON files are read lazily and not cached in memory. A boot touching hundreds of
 * entries needs the jar path from every one of them and the audit from none — parsing audits
 * eagerly would be work done on the critical path for a question nobody asked.
 */
public record CacheEntry(CacheKey key, Path directory) {

    /** Canonical constructor. */
    public CacheEntry {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(directory, "directory");
    }

    /** The transformed jar. This is what goes on the classloader's search path. */
    public Path jar() {
        return directory.resolve(TransformCache.OUTPUT_JAR);
    }

    /** The per-class record of what the pipeline did. Parsed on each call. */
    public TransformAudit audit() {
        return TransformAudit.fromJson(read(directory.resolve(TransformCache.AUDIT_FILE)));
    }

    /** The human-facing description of this entry's inputs. Parsed on each call. */
    public EntryMeta meta() {
        return EntryMeta.fromJson(read(directory.resolve(TransformCache.META_FILE)));
    }

    /** When this entry was last looked up, for LRU eviction. */
    public Instant lastAccessed() {
        try {
            return Files.getLastModifiedTime(directory.resolve(TransformCache.ACCESS_MARKER))
                    .toInstant();
        } catch (IOException e) {
            // An entry whose marker cannot be read is treated as maximally stale, so a damaged
            // entry is evicted first rather than pinned forever.
            return Instant.EPOCH;
        }
    }

    /** Total bytes this entry occupies. */
    public long sizeBytes() {
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile).mapToLong(CacheEntry::sizeOf).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }

    /** Orders entries oldest-access-first, which is the order eviction consumes them in. */
    public static Comparator<CacheEntry> byLeastRecentlyUsed() {
        return Comparator.comparing(CacheEntry::lastAccessed).thenComparing(CacheEntry::key);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new TransformException("cannot read " + file.getFileName() + " from cache entry", e);
        } catch (UncheckedIOException e) {
            throw new TransformException("cannot read " + file.getFileName() + " from cache entry",
                    e.getCause());
        }
    }
}
