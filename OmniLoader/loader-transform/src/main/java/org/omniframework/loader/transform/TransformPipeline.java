package org.omniframework.loader.transform;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Runs the ordered passes over a jar and writes the transformed result (ARCHITECTURE §6).
 *
 * <h2>Output is byte-for-byte reproducible</h2>
 *
 * <p>Two runs over the same input produce identical jars. This is not tidiness: the cache key is
 * computed from inputs, so if output varied between runs there would be no way to tell a genuine
 * cache corruption from ordinary nondeterminism, and no way to verify a cache entry by rebuilding
 * it. Three things are pinned to get there — entries are written in sorted order, timestamps are
 * fixed rather than taken from the clock, and no run-specific metadata is emitted.
 *
 * <p>Zip timestamps are the usual culprit. Left alone they record when the file was written, which
 * makes every rebuild differ from the last in a way nothing else notices until someone tries to
 * compare two caches.
 *
 * <h2>Failure is per-jar, not per-class</h2>
 *
 * <p>If any class in a jar fails to transform, the whole jar fails. A partially transformed jar is
 * the worst outcome available: it loads, and then misbehaves only along the paths that touch the
 * classes that were skipped. Refusing the jar gives the user one clear error instead of a
 * mystery.
 */
public final class TransformPipeline {

    /**
     * A fixed timestamp for every entry: 2010-01-01T00:00:00Z, the value the reproducible-builds
     * convention uses. Any constant would do; what matters is that it is not the clock.
     */
    private static final long FIXED_TIME = 1_262_304_000_000L;

    private final List<TransformPass> passes;
    private final int pipelineVersion;

    /**
     * Builds a pipeline running {@code passes} in order.
     *
     * @param pipelineVersion folded into cache keys; bump when the pass set or its order changes
     */
    public TransformPipeline(List<TransformPass> passes, int pipelineVersion) {
        this.passes = List.copyOf(Objects.requireNonNull(passes, "passes"));
        this.pipelineVersion = pipelineVersion;
        Map<String, TransformPass> seen = new LinkedHashMap<>();
        for (TransformPass pass : this.passes) {
            TransformPass clash = seen.put(pass.id(), pass);
            if (clash != null) {
                // Two passes sharing an id would make the audit ambiguous about which one made
                // a change, defeating the point of recording it.
                throw new IllegalArgumentException("duplicate pass id: " + pass.id());
            }
        }
    }

    /** The version folded into cache keys. */
    public int pipelineVersion() {
        return pipelineVersion;
    }

    /** The passes, in application order. */
    public List<TransformPass> passes() {
        return passes;
    }

    /**
     * Transforms {@code input} into {@code output}.
     *
     * <p>The jar's own classes are added to the context's hierarchy index first. A jar routinely
     * references its own types, and without this the remapper would treat every one of them as
     * unknown and skip inherited members declared inside the jar being transformed.
     *
     * @return the audit of what changed
     */
    public Result run(Path input, Path output, PassContext context) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(context, "context");

        Map<String, JarResource> entries = readJar(input);
        indexClasses(entries, context);
        List<JarResource> view = List.copyOf(entries.values());
        for (TransformPass pass : passes) {
            pass.beginJar(view, context);
        }

        TransformAudit.Builder audit = new TransformAudit.Builder();
        Map<String, JarResource> produced = new TreeMap<>();
        int modified = 0;
        int removed = 0;
        for (Map.Entry<String, JarResource> entry : entries.entrySet()) {
            JarResource current = entry.getValue();
            boolean changed = false;
            boolean dropped = false;
            for (TransformPass pass : passes) {
                int before = current.size();
                long startedAt = System.nanoTime();
                Optional<TransformPass.Outcome> result = applyPass(pass, current, context);
                long micros = (System.nanoTime() - startedAt) / 1_000L;
                if (result.isEmpty()) {
                    continue;
                }
                TransformPass.Outcome outcome = result.get();
                if (outcome.removed()) {
                    // Recorded before breaking out: an entry that vanishes without explanation
                    // is exactly the kind of thing the audit exists to answer.
                    if (current.isClass()) {
                        audit.record(current.className(), new TransformAudit.PassRecord(
                                pass.id(), pass.version(), before, 0, micros, outcome.notes()));
                    }
                    dropped = true;
                    break;
                }
                JarResource next = current.withContent(outcome.contentDirect());
                if (current.isClass()) {
                    audit.record(current.className(), new TransformAudit.PassRecord(
                            pass.id(), pass.version(), before, next.size(), micros,
                            outcome.notes()));
                }
                current = next;
                changed = true;
            }
            if (dropped) {
                removed++;
                continue;
            }
            if (changed) {
                modified++;
            }
            produced.put(entry.getKey(), current);
        }

        writeJar(output, produced);
        return new Result(audit.build(), entries.size(), modified, removed);
    }

    private static Optional<TransformPass.Outcome> applyPass(
            TransformPass pass, JarResource resource, PassContext context) {
        try {
            return pass.transform(resource, context);
        } catch (TransformException e) {
            throw e;
        } catch (RuntimeException e) {
            // A pass throwing something unexpected is a bug in the pass, but the message must
            // still name the jar and entry — otherwise the report is a stack trace with no
            // indication of which of 300 mods produced it.
            throw new TransformException(
                    "pass '" + pass.id() + "' failed on " + resource.name()
                            + " in " + context.jarName(), e);
        }
    }

    private static void indexClasses(Map<String, JarResource> entries, PassContext context) {
        for (JarResource resource : entries.values()) {
            if (resource.isClass()) {
                try {
                    context.hierarchy().add(resource.contentDirect());
                } catch (RuntimeException e) {
                    throw new TransformException(
                            "cannot read class " + resource.name() + " in " + context.jarName(), e);
                }
            }
        }
    }

    /** What a run produced. */
    public record Result(
            TransformAudit audit, int entryCount, int modifiedCount, int removedCount) {

        /** Canonical constructor. */
        public Result {
            Objects.requireNonNull(audit, "audit");
        }

        /** Whether the pipeline changed anything at all. */
        public boolean changedAnything() {
            return modifiedCount > 0 || removedCount > 0;
        }

        /** A line for the boot log. */
        public String describe() {
            return modifiedCount + " of " + entryCount + " entries modified"
                    + (removedCount > 0 ? ", " + removedCount + " removed" : "");
        }
    }

    // -------------------------------------------------------------------- io

    private static Map<String, JarResource> readJar(Path jar) {
        Map<String, JarResource> entries = new TreeMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            List<? extends ZipEntry> ordered = zip.stream()
                    .filter(entry -> !entry.isDirectory())
                    .sorted(java.util.Comparator.comparing(ZipEntry::getName))
                    .toList();
            for (ZipEntry entry : ordered) {
                try (InputStream in = zip.getInputStream(entry)) {
                    entries.put(entry.getName(), new JarResource(entry.getName(), in.readAllBytes()));
                }
            }
        } catch (IOException e) {
            throw new TransformException("cannot read jar " + jar, e);
        }
        return entries;
    }

    private static void writeJar(Path jar, Map<String, JarResource> entries) {
        try {
            Path parent = jar.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(jar);
                    ZipOutputStream zip = new ZipOutputStream(out)) {
                for (Map.Entry<String, JarResource> entry : entries.entrySet()) {
                    byte[] content = entry.getValue().contentDirect();
                    ZipEntry zipEntry = new ZipEntry(entry.getKey());
                    // Every time field is set explicitly. Leaving any of them unset lets the
                    // zip implementation fall back to the clock, which breaks reproducibility.
                    zipEntry.setTime(FIXED_TIME);
                    zipEntry.setLastModifiedTime(java.nio.file.attribute.FileTime
                            .fromMillis(FIXED_TIME));
                    zipEntry.setCreationTime(java.nio.file.attribute.FileTime
                            .fromMillis(FIXED_TIME));
                    zipEntry.setLastAccessTime(java.nio.file.attribute.FileTime
                            .fromMillis(FIXED_TIME));
                    zipEntry.setSize(content.length);
                    CRC32 crc = new CRC32();
                    crc.update(content);
                    zipEntry.setCrc(crc.getValue());
                    zip.putNextEntry(zipEntry);
                    zip.write(content);
                    zip.closeEntry();
                }
            }
        } catch (IOException e) {
            throw new TransformException("cannot write jar " + jar, e);
        }
    }

    /** Reads a jar's entries — exposed for callers that need to inspect without transforming. */
    public static List<JarResource> entriesOf(Path jar) {
        return new ArrayList<>(readJar(jar).values());
    }
}
