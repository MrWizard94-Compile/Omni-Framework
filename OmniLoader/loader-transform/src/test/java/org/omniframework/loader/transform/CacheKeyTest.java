package org.omniframework.loader.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The cache key decides whether a user gets a correct jar or a stale one, so these tests are
 * mostly about the ways it could quietly fail to notice a change.
 */
class CacheKeyTest {

    private static CacheKey.Builder base() {
        return CacheKey.builder()
                .jarBytes("mod contents".getBytes(StandardCharsets.UTF_8))
                .mappings("intermediary-1.21.1", "abc123")
                .pipelineVersion(1)
                .layer("native", "0.1.0");
    }

    @Test
    @DisplayName("identical inputs produce an identical key")
    void deterministic() {
        assertEquals(base().build(), base().build());
    }

    @Test
    @DisplayName("a key is 64 lowercase hex characters and shards on its first two")
    void shape() {
        CacheKey key = base().build();
        assertEquals(64, key.value().length());
        assertTrue(key.value().matches("[0-9a-f]{64}"), key.value());
        assertEquals(key.value().substring(0, 2), key.shard());
    }

    @Test
    @DisplayName("changing the jar changes the key")
    void jarSensitivity() {
        CacheKey other = CacheKey.builder()
                .jarBytes("mod contents!".getBytes(StandardCharsets.UTF_8))
                .mappings("intermediary-1.21.1", "abc123")
                .pipelineVersion(1)
                .layer("native", "0.1.0")
                .build();
        assertNotEquals(base().build(), other);
    }

    @Test
    @DisplayName("changing the mappings artifact or its hash changes the key")
    void mappingSensitivity() {
        assertNotEquals(base().build(), CacheKey.builder()
                .jarBytes("mod contents".getBytes(StandardCharsets.UTF_8))
                .mappings("intermediary-1.21.2", "abc123")
                .pipelineVersion(1).layer("native", "0.1.0").build(),
                "a different mappings artifact must not reuse the entry");
        assertNotEquals(base().build(), CacheKey.builder()
                .jarBytes("mod contents".getBytes(StandardCharsets.UTF_8))
                .mappings("intermediary-1.21.1", "def456")
                .pipelineVersion(1).layer("native", "0.1.0").build(),
                "a republished artifact under the same name must not reuse the entry");
    }

    @Test
    @DisplayName("bumping the pipeline version changes the key")
    void pipelineSensitivity() {
        // This is the mechanism by which a fixed transform bug actually reaches users.
        CacheKey other = CacheKey.builder()
                .jarBytes("mod contents".getBytes(StandardCharsets.UTF_8))
                .mappings("intermediary-1.21.1", "abc123")
                .pipelineVersion(2)
                .layer("native", "0.1.0")
                .build();
        assertNotEquals(base().build(), other);
    }

    @Test
    @DisplayName("changing the compat layer or its version changes the key")
    void layerSensitivity() {
        CacheKey other = CacheKey.builder()
                .jarBytes("mod contents".getBytes(StandardCharsets.UTF_8))
                .mappings("intermediary-1.21.1", "abc123")
                .pipelineVersion(1)
                .layer("fabric", "0.1.0")
                .build();
        assertNotEquals(base().build(), other);
    }

    @Test
    @DisplayName("a config flag changes the key, and flag order does not")
    void flags() {
        CacheKey withFlags = base().flag("strip", true).flag("debug", "off").build();
        CacheKey reordered = base().flag("debug", "off").flag("strip", true).build();

        assertNotEquals(base().build(), withFlags, "a flag that changes output must change the key");
        assertEquals(withFlags, reordered,
                "the same flags set in a different order describe the same transform");
    }

    @Test
    @DisplayName("adjacent inputs cannot be confused by shifting a boundary")
    void lengthPrefixingPreventsAmbiguity() {
        // The classic concatenation collision: without length prefixes, ("ab","c") and
        // ("a","bc") hash identically, and two genuinely different transforms would share a
        // cache entry. That is a stale-jar bug that would reproduce on almost no machines.
        CacheKey left = CacheKey.builder()
                .jarBytes(new byte[] {1})
                .mappings("ab", "c")
                .pipelineVersion(1).layer("l", "v").build();
        CacheKey right = CacheKey.builder()
                .jarBytes(new byte[] {1})
                .mappings("a", "bc")
                .pipelineVersion(1).layer("l", "v").build();

        assertNotEquals(left, right);
    }

    @Test
    @DisplayName("hashing a file matches hashing its bytes")
    void fileAndBytesAgree(@TempDir Path dir) throws IOException {
        byte[] content = "a jar, more or less".getBytes(StandardCharsets.UTF_8);
        Path jar = dir.resolve("mod.jar");
        Files.write(jar, content);

        CacheKey fromFile = CacheKey.builder().jar(jar)
                .mappings("m", "h").pipelineVersion(1).layer("l", "v").build();
        CacheKey fromBytes = CacheKey.builder().jarBytes(content)
                .mappings("m", "h").pipelineVersion(1).layer("l", "v").build();

        assertEquals(fromBytes, fromFile, "streaming a file must hash the same as its content");
    }

    @Test
    @DisplayName("inputs are described in plain language for diagnosis")
    void describesInputs() {
        // The answer to "why does it re-transform every launch" is always an input that changes
        // when it should not, and a 64-character hash is no help in finding which one.
        CacheKey.Builder builder = base().flag("strip", true);
        builder.build();
        String described = builder.describeInputs();

        assertTrue(described.contains("intermediary-1.21.1"), described);
        assertTrue(described.contains("pipeline=v1"), described);
        assertTrue(described.contains("flag:strip=true"), described);
    }

    @Test
    @DisplayName("a malformed key is rejected rather than becoming a path")
    void rejectsMalformed() {
        assertThrows(IllegalArgumentException.class, () -> new CacheKey("too-short"));
        assertThrows(IllegalArgumentException.class, () -> new CacheKey("../".repeat(21) + "a"));
        assertThrows(IllegalArgumentException.class,
                () -> new CacheKey("A".repeat(64)), "uppercase would shard inconsistently");
    }
}
