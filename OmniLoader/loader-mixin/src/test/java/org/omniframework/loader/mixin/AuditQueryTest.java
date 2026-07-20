package org.omniframework.loader.mixin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.omniframework.loader.transform.CacheKey;
import org.omniframework.loader.transform.EntryMeta;
import org.omniframework.loader.transform.TransformAudit;
import org.omniframework.loader.transform.TransformCache;

/** One question, both halves of the pipeline (ARCHITECTURE §8). */
class AuditQueryTest {

    private static TransformCache cacheWith(Path dir, String className) throws IOException {
        TransformCache cache = new TransformCache(dir.resolve("cache"));
        Path jar = dir.resolve("mod.jar");
        Files.writeString(jar, "transformed");

        TransformAudit.Builder audit = new TransformAudit.Builder();
        audit.record(className, new TransformAudit.PassRecord(
                "remap-official", "1", 100, 120, 42,
                List.of("renamed game/class_1 → net/minecraft/Entity")));

        cache.store(
                CacheKey.builder().jarBytes("mod".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .mappings("intermediary", "abc").pipelineVersion(1).layer("fabric", "1")
                        .build(),
                jar, audit.build(),
                new EntryMeta("mod.jar", "inputs", 1, "0.1.0", Instant.now(), 5, 1));
        return cache;
    }

    @Test
    @DisplayName("both halves appear in one answer, ahead-of-time first")
    void joinsBothHalves(@TempDir Path dir) throws IOException {
        // A user asking why a method looks wrong should not need to know that two mechanisms
        // exist, nor which one to consult.
        TransformCache cache = cacheWith(dir, "net/minecraft/Entity");
        OmniAuditTrail trail = new OmniAuditTrail();
        trail.onApply("net/minecraft/Entity", "create.mixins.EntityMixin");

        String described = new AuditQuery(cache, trail).describe("net.minecraft.Entity");

        assertTrue(described.contains("remap-official"), described);
        assertTrue(described.contains("create.mixins.EntityMixin"), described);
        assertTrue(described.indexOf("remap-official") < described.indexOf("create.mixins"),
                () -> "ahead-of-time work happened first and must read first: " + described);
    }

    @Test
    @DisplayName("a class only Mixin touched still reports the AOT half as unmodified")
    void mixinOnly(@TempDir Path dir) throws IOException {
        TransformCache cache = new TransformCache(dir.resolve("cache"));
        OmniAuditTrail trail = new OmniAuditTrail();
        trail.onApply("net/minecraft/Level", "mod.LevelMixin");

        String described = new AuditQuery(cache, trail).describe("net/minecraft/Level");

        assertTrue(described.contains("unmodified"), described);
        assertTrue(described.contains("mod.LevelMixin"), described);
    }

    @Test
    @DisplayName("a class only the pipeline touched reports without a mixin section")
    void transformOnly(@TempDir Path dir) throws IOException {
        TransformCache cache = cacheWith(dir, "net/minecraft/Entity");

        AuditQuery.History history =
                new AuditQuery(cache, new OmniAuditTrail()).forClass("net/minecraft/Entity");

        assertFalse(history.isEmpty());
        assertTrue(history.loadTime().isEmpty());
        assertFalse(history.aheadOfTime().isEmpty());
    }

    @Test
    @DisplayName("an untouched class says so, rather than saying nothing")
    void untouchedIsStated(@TempDir Path dir) {
        // "Nothing modified this" is a fact worth stating. Silence would read as the audit
        // being incomplete, which is a different and more worrying claim.
        TransformCache cache = new TransformCache(dir.resolve("cache"));

        AuditQuery.History history =
                new AuditQuery(cache, new OmniAuditTrail()).forClass("net/minecraft/Untouched");

        assertTrue(history.isEmpty());
        assertTrue(history.describe().contains("nothing modified this class"),
                history.describe());
    }

    @Test
    @DisplayName("either name form works, in both halves")
    void acceptsEitherNameForm(@TempDir Path dir) throws IOException {
        TransformCache cache = cacheWith(dir, "net/minecraft/Entity");
        OmniAuditTrail trail = new OmniAuditTrail();
        trail.onApply("net/minecraft/Entity", "mod.EntityMixin");
        AuditQuery query = new AuditQuery(cache, trail);

        assertFalse(query.forClass("net.minecraft.Entity").isEmpty());
        assertFalse(query.forClass("net/minecraft/Entity").isEmpty());
    }
}
