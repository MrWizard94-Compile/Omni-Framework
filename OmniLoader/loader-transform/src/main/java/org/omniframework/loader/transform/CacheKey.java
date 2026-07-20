package org.omniframework.loader.transform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The identity of a transformed jar (ADR-0010).
 *
 * <p>A transform cache is only as good as its key. Two failure modes sit on either side of it,
 * and they are not symmetric:
 *
 * <ul>
 *   <li><b>Key too broad</b> (something that affects output is not in the key) — the cache
 *       returns a stale jar built with different mappings or a different pipeline. The game
 *       loads and misbehaves in ways that look like a mod bug, and the cache is the last place
 *       anyone will look. This is the dangerous direction.
 *   <li><b>Key too narrow</b> (something irrelevant is in the key) — a spurious miss. The
 *       transform re-runs and the user waits. Merely wasteful.
 * </ul>
 *
 * <p>So every input that can change a byte of output is folded in, and the hash is over a
 * <em>length-prefixed</em> encoding rather than concatenated strings: without prefixes the pairs
 * ("ab", "c") and ("a", "bc") hash identically, which is exactly the collision that produces a
 * stale-jar bug that reproduces on one machine in a thousand.
 *
 * <p>ADR-0010 fixes the inputs: jar bytes, mappings artifact id + hash, pipeline version, compat
 * layer version, and config-relevant flags.
 */
public record CacheKey(String value) implements Comparable<CacheKey> {

    private static final String ALGORITHM = "SHA-256";
    private static final int STREAM_BUFFER = 64 * 1024;

    /** Canonical constructor; validates the shape so a malformed key cannot become a path. */
    public CacheKey {
        Objects.requireNonNull(value, "value");
        if (value.length() != 64 || !value.chars().allMatch(CacheKey::isLowerHex)) {
            throw new IllegalArgumentException(
                    "a cache key must be 64 lowercase hex characters, got: " + value);
        }
    }

    private static boolean isLowerHex(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
    }

    /**
     * The directory shard this key lives under: {@code <key[0:2]>/<key>} per ADR-0010.
     *
     * <p>The two-character fan-out exists because some filesystems degrade badly with tens of
     * thousands of sibling directories, and a large modpack plus a few mapping revisions gets
     * there quickly.
     */
    public String shard() {
        return value.substring(0, 2);
    }

    @Override
    public int compareTo(CacheKey other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }

    /** A short prefix for log lines, where the full 64 characters are noise. */
    public String abbreviated() {
        return value.substring(0, 12);
    }

    /** Starts building a key. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Accumulates the inputs ADR-0010 names, then hashes them.
     *
     * <p>Flags are held in a sorted map rather than a list: two runs that set the same flags in a
     * different order describe the same transform and must not produce different keys. Every
     * other input is order-significant and contributes in the order it was added.
     */
    public static final class Builder {

        private final MessageDigest digest;
        private final TreeMap<String, String> flags = new TreeMap<>();
        private final List<String> described = new ArrayList<>();

        private Builder() {
            try {
                this.digest = MessageDigest.getInstance(ALGORITHM);
            } catch (NoSuchAlgorithmException e) {
                // SHA-256 is mandated by the JLS platform requirements; its absence means a
                // broken JVM, not a condition callers could handle.
                throw new IllegalStateException(ALGORITHM + " is required but unavailable", e);
            }
        }

        /** Folds in the exact bytes of the input jar. */
        public Builder jar(Path jar) throws IOException {
            Objects.requireNonNull(jar, "jar");
            field("jar");
            try (InputStream in = Files.newInputStream(jar)) {
                byte[] buffer = new byte[STREAM_BUFFER];
                long total = 0;
                int read;
                // Streamed rather than read whole: a fat modpack jar can be hundreds of
                // megabytes and the loader may key dozens of them during one boot.
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                    total += read;
                }
                lengthPrefix(total);
            }
            described.add("jar=" + jar.getFileName());
            return this;
        }

        /** Folds in raw content directly — used by tests and by in-memory inputs. */
        public Builder jarBytes(byte[] content) {
            Objects.requireNonNull(content, "content");
            field("jar");
            digest.update(content);
            lengthPrefix(content.length);
            described.add("jar=<" + content.length + " bytes>");
            return this;
        }

        /**
         * Folds in the mappings artifact by identity <em>and</em> content hash.
         *
         * <p>Both, not either. The id alone would not notice a republished artifact under the
         * same name; the hash alone would not distinguish two artifacts that happen to be
         * byte-identical but are meant to be tracked separately.
         */
        public Builder mappings(String artifactId, String contentHash) {
            field("mappings");
            chunk(artifactId);
            chunk(contentHash);
            described.add("mappings=" + artifactId + "@" + shortHash(contentHash));
            return this;
        }

        /** Folds in the pipeline version — bump it and every entry invalidates, by design. */
        public Builder pipelineVersion(int version) {
            field("pipeline");
            chunk(Integer.toString(version));
            described.add("pipeline=v" + version);
            return this;
        }

        /** Folds in the compat layer that will process this jar, and its version. */
        public Builder layer(String layerId, String layerVersion) {
            field("layer");
            chunk(layerId);
            chunk(layerVersion);
            described.add("layer=" + layerId + "@" + layerVersion);
            return this;
        }

        /**
         * Records a config flag that changes output.
         *
         * <p>Only flags that actually affect produced bytes belong here. Adding a cosmetic one
         * (a log level, a UI preference) costs every user a full re-transform for nothing.
         */
        public Builder flag(String name, String value) {
            flags.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /** Convenience for boolean flags. */
        public Builder flag(String name, boolean value) {
            return flag(name, Boolean.toString(value));
        }

        /** Finishes the hash and produces the key. */
        public CacheKey build() {
            field("flags");
            lengthPrefix(flags.size());
            flags.forEach((name, value) -> {
                chunk(name);
                chunk(value);
                described.add("flag:" + name + "=" + value);
            });
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return new CacheKey(hex.toString());
        }

        /**
         * A human-readable account of what went into the key.
         *
         * <p>When a user reports "it keeps re-transforming every launch", the answer is always
         * an input that changes when it should not. This makes that visible instead of leaving
         * a 64-character hash as the only evidence.
         */
        public String describeInputs() {
            return String.join(", ", described);
        }

        /** Marks a named field, so inputs cannot be confused across positions. */
        private void field(String name) {
            chunk(name);
        }

        /** Adds a length-prefixed chunk — see the class note on ambiguity. */
        private void chunk(String text) {
            byte[] bytes = Objects.requireNonNull(text, "text").getBytes(StandardCharsets.UTF_8);
            lengthPrefix(bytes.length);
            digest.update(bytes);
        }

        private void lengthPrefix(long length) {
            for (int shift = 56; shift >= 0; shift -= 8) {
                digest.update((byte) (length >>> shift));
            }
        }

        private static String shortHash(String hash) {
            return hash.length() <= 12 ? hash : hash.substring(0, 12);
        }
    }
}
