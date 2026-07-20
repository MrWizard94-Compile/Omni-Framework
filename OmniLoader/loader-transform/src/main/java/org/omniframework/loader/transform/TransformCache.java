package org.omniframework.loader.transform;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The on-disk store of transformed jars (LDR-2, layout per ADR-0010).
 *
 * <p>Layout is {@code <root>/<key[0:2]>/<key>/} holding {@code output.jar}, {@code audit.json},
 * {@code meta.json}, and an {@code accessed} marker.
 *
 * <h2>Why writes are staged and moved</h2>
 *
 * <p>The failure this guards against is not rare: a user force-quits a launch, or the machine
 * loses power, while a jar is half-written. If entries were written in place, the next boot would
 * find a directory at the right key containing a truncated jar and load it — producing
 * {@code ClassFormatError}s that look like a corrupt mod download and survive every reinstall,
 * because the cache keeps serving the same broken bytes.
 *
 * <p>So an entry is built in a temporary directory and moved into its final name only once it is
 * complete. A move is atomic on every filesystem the loader targets, which makes the entry's
 * existence and its validity the same event. An interrupted write leaves a stray temp directory
 * and nothing else, and {@link #sweepIncompleteWrites()} clears those.
 *
 * <p>A jar is only ever <em>read</em> from an entry, never modified, so concurrent readers of a
 * complete entry need no locking.
 */
public final class TransformCache {

    static final String OUTPUT_JAR = "output.jar";
    static final String AUDIT_FILE = "audit.json";
    static final String META_FILE = "meta.json";
    static final String ACCESS_MARKER = "accessed";
    private static final String TEMP_PREFIX = ".incoming-";

    private final Path root;

    /** Opens (creating if needed) a cache rooted at {@code root}. */
    public TransformCache(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new TransformException("cannot create transform cache at " + this.root, e);
        }
    }

    /** The cache root. */
    public Path root() {
        return root;
    }

    /** The directory an entry for {@code key} occupies, whether or not it exists. */
    Path directoryFor(CacheKey key) {
        return root.resolve(key.shard()).resolve(key.value());
    }

    /**
     * Looks up a completed entry.
     *
     * <p>A directory that exists but is missing any of its three files is treated as a miss and
     * not returned — see the class note. Being strict here is what keeps a torn write from
     * becoming a permanent, reinstall-proof failure.
     */
    public Optional<CacheEntry> lookup(CacheKey key) {
        Objects.requireNonNull(key, "key");
        Path directory = directoryFor(key);
        if (!isComplete(directory)) {
            return Optional.empty();
        }
        touch(directory);
        return Optional.of(new CacheEntry(key, directory));
    }

    /** Whether a complete entry exists, without recording an access. */
    public boolean contains(CacheKey key) {
        return isComplete(directoryFor(Objects.requireNonNull(key, "key")));
    }

    private static boolean isComplete(Path directory) {
        return Files.isDirectory(directory)
                && Files.isRegularFile(directory.resolve(OUTPUT_JAR))
                && Files.isRegularFile(directory.resolve(AUDIT_FILE))
                && Files.isRegularFile(directory.resolve(META_FILE));
    }

    /**
     * Stores a transformed jar under {@code key}, replacing any existing entry.
     *
     * @param transformedJar the produced jar; it is copied in, and the caller keeps ownership
     */
    public CacheEntry store(
            CacheKey key, Path transformedJar, TransformAudit audit, EntryMeta meta) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(transformedJar, "transformedJar");
        Objects.requireNonNull(audit, "audit");
        Objects.requireNonNull(meta, "meta");

        Path shard = root.resolve(key.shard());
        Path destination = shard.resolve(key.value());
        Path staging = null;
        try {
            Files.createDirectories(shard);
            staging = Files.createTempDirectory(shard, TEMP_PREFIX);
            Files.copy(transformedJar, staging.resolve(OUTPUT_JAR),
                    StandardCopyOption.REPLACE_EXISTING);
            write(staging.resolve(AUDIT_FILE), audit.toJson());
            write(staging.resolve(META_FILE), meta.toJson());
            write(staging.resolve(ACCESS_MARKER), Instant.now().toString());

            // A pre-existing entry at this key holds bytes that are, by definition of the key,
            // equivalent. Replacing rather than merging keeps exactly one copy.
            deleteRecursively(destination);
            moveIntoPlace(staging, destination);
            staging = null;
            return new CacheEntry(key, destination);
        } catch (IOException e) {
            throw new TransformException(
                    "cannot store transform cache entry " + key.abbreviated(), e);
        } finally {
            if (staging != null) {
                deleteQuietly(staging);
            }
        }
    }

    private static void moveIntoPlace(Path staging, Path destination) throws IOException {
        try {
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Some network filesystems refuse atomic directory moves. The non-atomic move is
            // still a single operation per entry and still leaves either the old entry or the
            // new one, never a mixture of both.
            Files.move(staging, destination);
        }
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** Records that an entry was used, for LRU. Best-effort: a failure here must not fail a boot. */
    private static void touch(Path directory) {
        try {
            Files.setLastModifiedTime(directory.resolve(ACCESS_MARKER), FileTime.from(Instant.now()));
        } catch (IOException ignored) {
            // A read-only or full disk should degrade GC accuracy, not prevent the game starting.
        }
    }

    // ------------------------------------------------------------- maintenance

    /** Every complete entry currently stored. */
    public List<CacheEntry> entries() {
        List<CacheEntry> found = new ArrayList<>();
        try (Stream<Path> shards = Files.list(root)) {
            for (Path shard : shards.filter(Files::isDirectory).toList()) {
                try (Stream<Path> candidates = Files.list(shard)) {
                    for (Path candidate : candidates.filter(TransformCache::isComplete).toList()) {
                        keyOf(candidate).ifPresent(key -> found.add(new CacheEntry(key, candidate)));
                    }
                }
            }
        } catch (NoSuchFileException e) {
            return List.of();
        } catch (IOException e) {
            throw new TransformException("cannot list transform cache at " + root, e);
        }
        found.sort(Comparator.comparing(CacheEntry::key));
        return found;
    }

    private static Optional<CacheKey> keyOf(Path directory) {
        try {
            return Optional.of(new CacheKey(directory.getFileName().toString()));
        } catch (IllegalArgumentException e) {
            // A directory that is not named like a key was not written by us. Leaving it alone
            // is safer than deleting something a user may have put there.
            return Optional.empty();
        }
    }

    /**
     * Removes temporary directories left by interrupted writes.
     *
     * <p>Called at startup. These are inert — nothing looks them up — but they accumulate, and
     * an unexplained pile of directories in a cache is the kind of thing that gets a cache
     * deleted wholesale by a frustrated user.
     */
    public int sweepIncompleteWrites() {
        int removed = 0;
        try (Stream<Path> shards = Files.list(root)) {
            for (Path shard : shards.filter(Files::isDirectory).toList()) {
                try (Stream<Path> candidates = Files.list(shard)) {
                    for (Path candidate : candidates.toList()) {
                        if (candidate.getFileName().toString().startsWith(TEMP_PREFIX)) {
                            deleteQuietly(candidate);
                            removed++;
                        }
                    }
                }
            }
        } catch (NoSuchFileException e) {
            return 0;
        } catch (IOException e) {
            throw new TransformException("cannot sweep transform cache at " + root, e);
        }
        return removed;
    }

    /**
     * Drops entries built by a pipeline version other than {@code currentPipelineVersion}.
     *
     * <p>The version sweep of ADR-0010. These entries can never be hit again — the pipeline
     * version is part of the key — so they are pure waste, and unlike LRU eviction, removing
     * them costs nothing in future work.
     *
     * @return how many entries were removed
     */
    public int sweepStaleVersions(int currentPipelineVersion) {
        int removed = 0;
        for (CacheEntry entry : entries()) {
            int version;
            try {
                version = entry.meta().pipelineVersion();
            } catch (TransformException e) {
                // Unreadable metadata means the entry cannot be reasoned about at all; it is
                // exactly what GC is for.
                deleteQuietly(entry.directory());
                removed++;
                continue;
            }
            if (version != currentPipelineVersion) {
                deleteQuietly(entry.directory());
                removed++;
            }
        }
        return removed;
    }

    /**
     * Evicts least-recently-used entries until the cache fits in {@code maxBytes}.
     *
     * <p>LRU rather than size-or-age alone: a large jar that is loaded every session is worth
     * more than a small one from a modpack the user tried once. Eviction is only ever a
     * performance decision — every evicted entry can be rebuilt from its inputs — which is why
     * it is safe to be approximate here.
     *
     * @return how many entries were removed
     */
    public int evictToFit(long maxBytes) {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must not be negative");
        }
        List<CacheEntry> entries = new ArrayList<>(entries());
        long total = entries.stream().mapToLong(CacheEntry::sizeBytes).sum();
        if (total <= maxBytes) {
            return 0;
        }
        entries.sort(Comparator.comparing(CacheEntry::lastAccessed));
        int removed = 0;
        for (CacheEntry entry : entries) {
            if (total <= maxBytes) {
                break;
            }
            total -= entry.sizeBytes();
            deleteQuietly(entry.directory());
            removed++;
        }
        return removed;
    }

    /** Total size of all entries. */
    public long sizeBytes() {
        return entries().stream().mapToLong(CacheEntry::sizeBytes).sum();
    }

    /**
     * Finds every recorded transformation of a class, across all entries.
     *
     * <p>This is what {@code omniloader --audit <class>} calls. It scans rather than indexes:
     * the query is interactive and rare, and a persistent index would be one more thing that
     * can disagree with the truth on disk.
     */
    public List<ClassHistory> auditClass(String className) {
        String normalised = TransformAudit.normalise(className);
        List<ClassHistory> history = new ArrayList<>();
        for (CacheEntry entry : entries()) {
            try {
                entry.audit().forClass(normalised).ifPresent(record ->
                        history.add(new ClassHistory(entry.key(), entry.meta().sourceName(), record)));
            } catch (TransformException ignored) {
                // One corrupt entry must not blind the query to every other entry.
            }
        }
        return history;
    }

    /** One jar's record of transforming one class. */
    public record ClassHistory(CacheKey key, String sourceName, TransformAudit.ClassAudit audit) {

        /** The rendering {@code --audit} prints per jar. */
        public String describe() {
            return sourceName + " [" + key.abbreviated() + "]\n" + audit.describe();
        }
    }

    // ------------------------------------------------------------------ files

    private static void deleteQuietly(Path path) {
        try {
            deleteRecursively(path);
        } catch (IOException ignored) {
            // On Windows a file can be briefly locked by a virus scanner. The entry stays and
            // is retried next sweep; failing a boot over a stale directory would be worse.
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            List<Path> contents = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path entry : contents) {
                Files.deleteIfExists(entry);
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
