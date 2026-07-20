package org.omniframework.loader.mixin;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Wraps a mod's Mixin config plugin so its behaviour is visible (ARCHITECTURE §8:
 * "logged, time-budgeted, never silently swallowed").
 *
 * <h2>What a config plugin is, and why it needs watching</h2>
 *
 * <p>A mixin config may name a plugin class, and Mixin then calls that mod's code during config
 * loading and around every mixin application. It is arbitrary third-party code running on the
 * boot path, before the game exists. A plugin that is slow makes the launcher look hung; one that
 * throws can take out an unrelated mod's mixins; and by default neither is attributable to
 * anything a user could name.
 *
 * <p>Every call is therefore timed and attributed to the owning mod, and slow ones are reported.
 *
 * <h2>"Time-budgeted" is a report, not a kill</h2>
 *
 * <p>The budget is a threshold that produces a warning, not a deadline that aborts the call.
 * That is deliberate and worth being plain about: there is no safe way to stop arbitrary Java
 * mid-execution. {@code Thread.stop} is deprecated and unsafe precisely because it can leave
 * shared state half-modified, and a plugin interrupted inside {@code preApply} would leave Mixin
 * partway through transforming a class — trading a slow boot for a corrupt one.
 *
 * <p>So the budget makes slowness <em>attributable</em>: when a launch takes ninety seconds, the
 * log names the mod responsible instead of leaving the user to guess. That is the achievable
 * goal; pretending to enforce a hard deadline would be a worse lie than not having one.
 *
 * <h2>Failures propagate</h2>
 *
 * <p>An exception is recorded and rethrown, never swallowed. A plugin that fails has, by
 * definition, not decided which mixins apply — continuing past it means applying a set the mod
 * never approved, which is how one broken mod silently changes another's behaviour.
 */
public final class ObservedConfigPlugin implements IMixinConfigPlugin {

    /** Default threshold above which a single call is reported as slow. */
    public static final Duration DEFAULT_BUDGET = Duration.ofMillis(250);

    private final IMixinConfigPlugin delegate;
    private final String modId;
    private final Duration budget;
    private final Consumer<String> log;
    private final Map<String, AtomicLong> nanosByMethod = new ConcurrentHashMap<>();
    private final AtomicLong overruns = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    /** Wraps {@code delegate}, attributing its cost to {@code modId}. */
    public ObservedConfigPlugin(
            IMixinConfigPlugin delegate, String modId, Duration budget, Consumer<String> log) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.modId = Objects.requireNonNull(modId, "modId");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.log = Objects.requireNonNull(log, "log");
        if (budget.isNegative() || budget.isZero()) {
            throw new IllegalArgumentException("budget must be positive, got " + budget);
        }
    }

    /** Wraps with the default budget, logging to standard error. */
    public static ObservedConfigPlugin wrap(IMixinConfigPlugin delegate, String modId) {
        return new ObservedConfigPlugin(delegate, modId, DEFAULT_BUDGET, System.err::println);
    }

    @Override
    public void onLoad(String mixinPackage) {
        run("onLoad", () -> delegate.onLoad(mixinPackage));
    }

    @Override
    public String getRefMapperConfig() {
        return call("getRefMapperConfig", delegate::getRefMapperConfig);
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // Called once per (target, mixin) pair, so it is the hottest of these methods and the
        // one where a slow plugin turns into a visibly slow launch.
        return call("shouldApplyMixin",
                () -> delegate.shouldApplyMixin(targetClassName, mixinClassName));
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        run("acceptTargets", () -> delegate.acceptTargets(myTargets, otherTargets));
    }

    @Override
    public List<String> getMixins() {
        return call("getMixins", delegate::getMixins);
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
            IMixinInfo mixinInfo) {
        run("preApply",
                () -> delegate.preApply(targetClassName, targetClass, mixinClassName, mixinInfo));
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
            IMixinInfo mixinInfo) {
        run("postApply",
                () -> delegate.postApply(targetClassName, targetClass, mixinClassName, mixinInfo));
    }

    // ------------------------------------------------------------ accounting

    private void run(String method, Runnable body) {
        call(method, () -> {
            body.run();
            return null;
        });
    }

    private <T> T call(String method, java.util.function.Supplier<T> body) {
        long startedAt = System.nanoTime();
        try {
            return body.get();
        } catch (RuntimeException | LinkageError e) {
            failures.incrementAndGet();
            log.accept("[mixin] config plugin for " + modId + " failed in " + method + "(): "
                    + e + " — the mod has not decided which of its mixins apply, so loading "
                    + "cannot continue past it");
            throw e;
        } finally {
            long elapsed = System.nanoTime() - startedAt;
            nanosByMethod.computeIfAbsent(method, ignored -> new AtomicLong()).addAndGet(elapsed);
            if (elapsed > budget.toNanos()) {
                overruns.incrementAndGet();
                log.accept("[mixin] config plugin for " + modId + " spent "
                        + Duration.ofNanos(elapsed).toMillis() + "ms in " + method
                        + "() (budget " + budget.toMillis() + "ms) — this mod is why the launch "
                        + "is slow");
            }
        }
    }

    /** The mod this plugin belongs to. */
    public String modId() {
        return modId;
    }

    /** Total time spent inside this plugin, across every call. */
    public Duration totalTime() {
        return Duration.ofNanos(
                nanosByMethod.values().stream().mapToLong(AtomicLong::get).sum());
    }

    /** Time spent in one method, for attributing a slow boot precisely. */
    public Duration timeIn(String method) {
        AtomicLong nanos = nanosByMethod.get(method);
        return nanos == null ? Duration.ZERO : Duration.ofNanos(nanos.get());
    }

    /** How many individual calls exceeded the budget. */
    public long overrunCount() {
        return overruns.get();
    }

    /** How many calls threw. */
    public long failureCount() {
        return failures.get();
    }

    /** A line for the boot log. */
    public String describe() {
        return modId + ": " + totalTime().toMillis() + "ms across " + nanosByMethod.size()
                + " plugin methods, " + overruns.get() + " over budget, "
                + failures.get() + " failed";
    }
}
