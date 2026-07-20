package org.omniframework.loader.mixin;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.omniframework.loader.core.ClassTransformer;
import org.omniframework.loader.core.Partition;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

/**
 * Applies mixins as classes are loaded — the seam between {@code loader-core} and Mixin.
 *
 * <h2>Why this is the last transformer in the chain</h2>
 *
 * <p>ARCHITECTURE §6 fixes the pipeline order, and Mixin is deliberately at the end. Everything
 * before it is ahead-of-time and cached: remapping, access merging, environment stripping. Mixin
 * cannot join that cache — its targets are not known until the moment a class is requested, which
 * is exactly why §6 excludes it — so it runs here, per class, per launch.
 *
 * <p>The ordering is not a preference. Mixins are written against official names and resolve
 * their injection points by member signature, so they must see bytes the remapper has already
 * finished with. Running Mixin first would have it searching for members that do not exist yet.
 *
 * <h2>Failure is not swallowed</h2>
 *
 * <p>When Mixin throws, this does not fall back to the untransformed class. A class that loads
 * with its mixins silently missing is the worst available outcome: the mod appears to work, and
 * misbehaves only along the paths the mixin was meant to change. Better to fail the class load
 * and say which mixin did it — {@link MixinApplicationException} carries the class name so the
 * report names something the user can act on.
 *
 * <p>Thread-safe, because classloading is parallel and Mixin's own transformer is built to be
 * called concurrently.
 */
public final class MixinClassTransformer implements ClassTransformer {

    private final IMixinTransformer transformer;
    private final OmniClassTracker tracker;
    private final AtomicLong transformed = new AtomicLong();
    private final AtomicLong unchanged = new AtomicLong();

    /**
     * Builds the bridge.
     *
     * @param transformer Mixin's transformer, from {@link MixinServiceOmni#createTransformer()}
     * @param tracker told about every class defined, so Mixin can refuse targets already loaded
     */
    public MixinClassTransformer(IMixinTransformer transformer, OmniClassTracker tracker) {
        this.transformer = Objects.requireNonNull(transformer, "transformer");
        this.tracker = Objects.requireNonNull(tracker, "tracker");
    }

    @Override
    public byte[] transform(String binaryName, Partition partition, byte[] bytes) {
        Objects.requireNonNull(binaryName, "binaryName");
        Objects.requireNonNull(bytes, "bytes");

        // Classes the tracker restricts are the loader's and Mixin's own. Handing those to
        // Mixin would mean modifying the machinery mid-application.
        if (!tracker.getClassRestrictions(binaryName).isEmpty()) {
            unchanged.incrementAndGet();
            return bytes;
        }

        byte[] result;
        try {
            // transformClassBytes takes the name twice: the original and the post-remap name.
            // They are identical here because remapping already happened ahead of time, so by
            // this point there is only one name for the class.
            result = transformer.transformClassBytes(binaryName, binaryName, bytes);
        } catch (RuntimeException | LinkageError e) {
            throw new MixinApplicationException(binaryName, e);
        }

        // Mixin returns the input array when it had nothing to apply. Comparing by identity
        // rather than content is deliberate: it is what Mixin actually guarantees, and hashing
        // every class to learn the same thing would cost real time on every load.
        if (result == null) {
            unchanged.incrementAndGet();
            return bytes;
        }
        if (result == bytes) {
            unchanged.incrementAndGet();
        } else {
            transformed.incrementAndGet();
        }

        // Recorded after the fact: once the bytes are settled, this class can no longer be a
        // mixin target, and Mixin needs to know that to refuse later attempts rather than
        // reporting a success that changed nothing.
        tracker.recordLoaded(binaryName);
        return result;
    }

    /** How many classes Mixin actually modified this run. */
    public long transformedCount() {
        return transformed.get();
    }

    /** How many classes passed through untouched. */
    public long unchangedCount() {
        return unchanged.get();
    }

    /** A line for the boot log. */
    public String describe() {
        long total = transformed.get() + unchanged.get();
        return transformed.get() + " of " + total + " classes had mixins applied";
    }
}
