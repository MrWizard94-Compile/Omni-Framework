package org.omniframework.loader.mixin;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.spongepowered.asm.service.IClassTracker;

/**
 * Tells Mixin which classes are already loaded, and which it must not touch.
 *
 * <h2>Why "already loaded" is the important question</h2>
 *
 * <p>A mixin can only modify a class that has not been defined yet. Once the JVM has loaded
 * {@code net.minecraft.Entity}, applying a mixin to it is impossible — but Mixin will happily
 * report success, because from its side the transformation ran. The result is a mod that appears
 * to load and then does nothing, which is among the hardest failures to diagnose because there is
 * no error anywhere.
 *
 * <p>So this tracker exists to let Mixin refuse early and say so. It is fed by the transforming
 * classloader as classes are defined.
 *
 * <p>Thread-safe: classloading is parallel, and this is consulted from inside it.
 */
public final class OmniClassTracker implements IClassTracker {

    /** Classes Mixin has been told are unusable as mixin targets. */
    private final Set<String> invalid = ConcurrentHashMap.newKeySet();

    /** Classes the loader has actually defined. */
    private final Set<String> loaded = ConcurrentHashMap.newKeySet();

    /** Package prefix to the reason it is restricted. */
    private final Map<String, String> restrictions = new ConcurrentHashMap<>();

    /**
     * Marks the loader's own packages off-limits.
     *
     * <p>A mixin targeting the loader would be modifying the machinery that is applying it —
     * which is either a no-op or a crash, depending on timing, and never what the author meant.
     */
    public OmniClassTracker() {
        restrict("org.omniframework.loader.", "the loader itself cannot be a mixin target");
        restrict("org.spongepowered.asm.", "the Mixin runtime cannot be a mixin target");
        restrict("java.", "JDK classes cannot be mixin targets");
        restrict("jdk.", "JDK classes cannot be mixin targets");
        restrict("sun.", "JDK internals cannot be mixin targets");
    }

    /** Declares that classes under {@code packagePrefix} may not be targeted. */
    public void restrict(String packagePrefix, String reason) {
        restrictions.put(
                Objects.requireNonNull(packagePrefix, "packagePrefix"),
                Objects.requireNonNull(reason, "reason"));
    }

    /** Called by the classloader when a class is defined. */
    public void recordLoaded(String className) {
        loaded.add(normalise(className));
    }

    @Override
    public void registerInvalidClass(String className) {
        invalid.add(normalise(className));
    }

    @Override
    public boolean isClassLoaded(String className) {
        String name = normalise(className);
        return loaded.contains(name) || invalid.contains(name);
    }

    @Override
    public String getClassRestrictions(String className) {
        String name = normalise(className);
        for (Map.Entry<String, String> restriction : restrictions.entrySet()) {
            if (name.startsWith(restriction.getKey())) {
                return restriction.getValue();
            }
        }
        // Mixin's contract is an empty string for "no restrictions", not null: it concatenates
        // the result into diagnostics, and a null would surface as the literal text "null".
        return "";
    }

    /** Whether a class was explicitly registered as an invalid target. */
    public boolean isInvalid(String className) {
        return invalid.contains(normalise(className));
    }

    /** How many classes have been defined so far. */
    public int loadedCount() {
        return loaded.size();
    }

    /** Mixin uses dotted binary names throughout its service API. */
    private static String normalise(String className) {
        return Objects.requireNonNull(className, "className").replace('/', '.');
    }
}
