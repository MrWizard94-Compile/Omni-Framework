package org.omniframework.loader.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Third-party config plugins run on the boot path; this is what makes them accountable. */
class ObservedConfigPluginTest {

    /** A plugin that can be made slow or made to throw. */
    private static class StubPlugin implements IMixinConfigPlugin {
        Duration delay = Duration.ZERO;
        RuntimeException failure;
        int shouldApplyCalls;

        private void work() {
            if (failure != null) {
                throw failure;
            }
            if (!delay.isZero()) {
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override public void onLoad(String mixinPackage) { work(); }
        @Override public String getRefMapperConfig() { work(); return "refmap.json"; }
        @Override public boolean shouldApplyMixin(String target, String mixin) {
            shouldApplyCalls++;
            work();
            return true;
        }
        @Override public void acceptTargets(Set<String> mine, Set<String> others) { work(); }
        @Override public List<String> getMixins() { work(); return List.of("MyMixin"); }
        @Override public void preApply(String t, ClassNode n, String m, IMixinInfo i) { work(); }
        @Override public void postApply(String t, ClassNode n, String m, IMixinInfo i) { work(); }
    }

    private static ObservedConfigPlugin wrap(
            StubPlugin stub, Duration budget, List<String> log) {
        return new ObservedConfigPlugin(stub, "testmod", budget, log::add);
    }

    @Test
    @DisplayName("calls pass through and return the delegate's values unchanged")
    void delegatesFaithfully() {
        // Observation must not change behaviour: a wrapper that altered results would be a
        // worse bug than the one it is watching for.
        StubPlugin stub = new StubPlugin();
        ObservedConfigPlugin observed = wrap(stub, Duration.ofSeconds(1), new ArrayList<>());

        assertEquals("refmap.json", observed.getRefMapperConfig());
        assertEquals(List.of("MyMixin"), observed.getMixins());
        assertTrue(observed.shouldApplyMixin("net.minecraft.Entity", "mod.EntityMixin"));
        assertEquals(1, stub.shouldApplyCalls);

        observed.onLoad("mod.mixins");
        observed.acceptTargets(Set.of("a"), Set.of("b"));
        observed.preApply("t", new ClassNode(), "m", null);
        observed.postApply("t", new ClassNode(), "m", null);
    }

    @Test
    @DisplayName("a slow call is reported and names the mod responsible")
    void reportsSlowCalls() throws Exception {
        // "Why does my launch take ninety seconds" is answerable only if the cost is attributed
        // to a mod. Timing without attribution would be a number nobody can act on.
        StubPlugin stub = new StubPlugin();
        stub.delay = Duration.ofMillis(60);
        List<String> log = new ArrayList<>();
        ObservedConfigPlugin observed = wrap(stub, Duration.ofMillis(10), log);

        observed.shouldApplyMixin("net.minecraft.Entity", "mod.EntityMixin");

        assertEquals(1, observed.overrunCount());
        assertEquals(1, log.size());
        assertTrue(log.get(0).contains("testmod"), log.get(0));
        assertTrue(log.get(0).contains("shouldApplyMixin"), log.get(0));
    }

    @Test
    @DisplayName("a call within budget is silent")
    void fastCallsAreSilent() {
        // A log that fires for every plugin call is a log nobody reads.
        StubPlugin stub = new StubPlugin();
        List<String> log = new ArrayList<>();
        ObservedConfigPlugin observed = wrap(stub, Duration.ofSeconds(5), log);

        observed.getMixins();

        assertEquals(0, observed.overrunCount());
        assertTrue(log.isEmpty(), log::toString);
    }

    @Test
    @DisplayName("a failing plugin is reported AND rethrown, never swallowed")
    void failuresPropagate() {
        // A plugin that threw has not decided which mixins apply. Continuing past it means
        // applying a set the mod never approved — one broken mod silently changing another's
        // behaviour.
        StubPlugin stub = new StubPlugin();
        stub.failure = new IllegalStateException("bad config");
        List<String> log = new ArrayList<>();
        ObservedConfigPlugin observed = wrap(stub, Duration.ofSeconds(1), log);

        assertThrows(IllegalStateException.class, () -> observed.onLoad("mod.mixins"));

        assertEquals(1, observed.failureCount());
        assertTrue(log.get(0).contains("testmod"), log.get(0));
        assertTrue(log.get(0).contains("cannot continue"), log.get(0));
    }

    @Test
    @DisplayName("time is still accounted when a call throws")
    void timingSurvivesFailure() {
        // Otherwise a plugin that fails slowly would look free, and the slowest thing in the
        // boot would be invisible precisely when something is already wrong.
        StubPlugin stub = new StubPlugin();
        stub.failure = new IllegalStateException("bad config");
        ObservedConfigPlugin observed = wrap(stub, Duration.ofSeconds(1), new ArrayList<>());

        assertThrows(IllegalStateException.class, () -> observed.getMixins());

        assertFalse(observed.timeIn("getMixins").isNegative());
        assertEquals(1, observed.failureCount());
    }

    @Test
    @DisplayName("time is attributed per method, not just in total")
    void attributesPerMethod() {
        StubPlugin stub = new StubPlugin();
        stub.delay = Duration.ofMillis(20);
        ObservedConfigPlugin observed = wrap(stub, Duration.ofSeconds(1), new ArrayList<>());

        observed.getMixins();
        observed.getMixins();

        assertTrue(observed.timeIn("getMixins").toMillis() >= 40,
                () -> "expected accumulated time, got " + observed.timeIn("getMixins"));
        assertEquals(Duration.ZERO, observed.timeIn("preApply"));
        assertTrue(observed.totalTime().toMillis() >= 40);
    }

    @Test
    @DisplayName("the summary names the mod and its cost")
    void describes() {
        StubPlugin stub = new StubPlugin();
        ObservedConfigPlugin observed = wrap(stub, Duration.ofSeconds(1), new ArrayList<>());
        observed.getMixins();

        assertTrue(observed.describe().startsWith("testmod:"), observed.describe());
    }

    @Test
    @DisplayName("a nonsensical budget is refused at construction")
    void rejectsBadBudget() {
        // A zero budget would report every call as an overrun, which is the same as reporting
        // nothing.
        StubPlugin stub = new StubPlugin();
        assertThrows(IllegalArgumentException.class,
                () -> new ObservedConfigPlugin(stub, "m", Duration.ZERO, s -> { }));
        assertThrows(IllegalArgumentException.class,
                () -> new ObservedConfigPlugin(stub, "m", Duration.ofMillis(-1), s -> { }));
    }
}
