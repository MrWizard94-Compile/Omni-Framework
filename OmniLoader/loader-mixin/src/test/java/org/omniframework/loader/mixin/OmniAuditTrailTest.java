package org.omniframework.loader.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The load-time half of "who changed this class" (ARCHITECTURE §8). */
class OmniAuditTrailTest {

    @Test
    @DisplayName("mixins are reported in the order they applied")
    void preservesApplicationOrder() {
        // Two mixins on one method produce different bytecode depending on which went first.
        // That ordering is the usual cause of "works alone, breaks together", so it is the
        // payload here — sorting these would destroy the only useful signal.
        OmniAuditTrail trail = new OmniAuditTrail();
        trail.onApply("net/minecraft/Entity", "mod_a.mixins.EntityMixin");
        trail.onApply("net/minecraft/Entity", "mod_b.mixins.EntityMixin");
        trail.onApply("net/minecraft/Entity", "mod_c.mixins.EntityMixin");

        assertEquals(
                List.of("mod_a.mixins.EntityMixin", "mod_b.mixins.EntityMixin",
                        "mod_c.mixins.EntityMixin"),
                trail.mixinsAppliedTo("net/minecraft/Entity"));
    }

    @Test
    @DisplayName("a class is queryable by either dotted or slashed name")
    void acceptsEitherForm() {
        // Mixin reports both forms depending on the call site; requiring the user to know which
        // would return "not found" for a class that is right there.
        OmniAuditTrail trail = new OmniAuditTrail();
        trail.onApply("net/minecraft/Entity", "mod.Mixin");

        assertEquals(1, trail.forClass("net/minecraft/Entity").size());
        assertEquals(1, trail.forClass("net.minecraft.Entity").size());
    }

    @Test
    @DisplayName("all three event kinds are recorded distinctly")
    void recordsEveryKind() {
        OmniAuditTrail trail = new OmniAuditTrail();
        trail.onApply("a/B", "mod.Mixin");
        trail.onPostProcess("a/B");
        trail.onGenerate("a/Synthetic", "mod.Mixin");

        assertEquals(
                List.of(OmniAuditTrail.Kind.APPLY, OmniAuditTrail.Kind.POST_PROCESS),
                trail.forClass("a/B").stream().map(OmniAuditTrail.Entry::kind).toList());
        assertEquals(OmniAuditTrail.Kind.GENERATE, trail.forClass("a/Synthetic").get(0).kind());
        assertEquals(3, trail.eventCount());
    }

    @Test
    @DisplayName("an untouched class reports nothing, distinguishably")
    void untouchedClassIsEmpty() {
        OmniAuditTrail trail = new OmniAuditTrail();

        assertTrue(trail.forClass("never/Touched").isEmpty());
        assertTrue(trail.describeClass("never/Touched").isEmpty(),
                "the overwhelming majority of classes are untouched; that must be tellable "
                        + "apart from 'touched but unprintable'");
    }

    @Test
    @DisplayName("the description names the mixins, in order")
    void describesForAudit() {
        OmniAuditTrail trail = new OmniAuditTrail();
        trail.onApply("net/minecraft/Entity", "create.mixins.EntityMixin");
        trail.onApply("net/minecraft/Entity", "sodium.mixins.EntityMixin");

        String described = trail.describeClass("net.minecraft.Entity").orElseThrow();

        assertTrue(described.contains("net.minecraft.Entity"), described);
        assertTrue(described.indexOf("create.mixins") < described.indexOf("sodium.mixins"),
                () -> "order must survive into the rendering: " + described);
    }

    @Test
    @DisplayName("concurrent application from many threads loses no events")
    void isThreadSafe() throws Exception {
        // Mixin applies during classloading, which is parallel. A trail that dropped or
        // corrupted entries under load would fail exactly when the pack is big enough to care.
        OmniAuditTrail trail = new OmniAuditTrail();
        int threads = 4;
        int perThread = 250;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int id = t;
                tasks.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        trail.onApply("net/minecraft/Class" + (i % 20), "mod" + id + ".Mixin" + i);
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

        assertEquals((long) threads * perThread, trail.eventCount());
        long recorded = trail.touchedClasses().stream()
                .mapToLong(className -> trail.forClass(className).size())
                .sum();
        assertEquals((long) threads * perThread, recorded, "an event was lost under concurrency");
    }
}
