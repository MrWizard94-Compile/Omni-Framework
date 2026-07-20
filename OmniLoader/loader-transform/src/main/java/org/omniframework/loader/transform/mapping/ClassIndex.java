package org.omniframework.loader.transform.mapping;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;

/**
 * Who extends what — the class hierarchy the remapper needs to be correct.
 *
 * <h2>Why remapping cannot work without this</h2>
 *
 * <p>Mappings name a member by the class that <em>declares</em> it. Bytecode names a member by the
 * class the call site happened to reference, which is very often a subclass. Given
 * {@code Entity.setPos} mapped and a call compiled as {@code Player.setPos}, a remapper that only
 * looks up {@code Player} finds nothing and leaves the call untouched.
 *
 * <p>Nothing detects this. The jar still verifies, still loads, and then throws
 * {@code NoSuchMethodError} the first time that path executes — which for a mod might be a rare
 * interaction discovered by a player weeks later. So the remapper walks ancestors, and this index
 * is what makes that walk possible.
 *
 * <p>Ancestors are returned in Java's own resolution order: the superclass chain first, then
 * interfaces. Where a name exists in both, that ordering picks the same declaration the JVM
 * would.
 *
 * <p>Instances are safe to share across the threads transforming different jars.
 */
public final class ClassIndex {

    private final Map<String, Entry> byName = new ConcurrentHashMap<>();
    private final Map<String, List<String>> ancestorCache = new ConcurrentHashMap<>();

    /**
     * Bumped on every registration, so a memoised ancestor walk can tell whether the hierarchy
     * changed underneath it. See {@link #ancestorsOf}.
     */
    private final java.util.concurrent.atomic.AtomicLong generation =
            new java.util.concurrent.atomic.AtomicLong();

    /** What the index knows about one class. */
    public record Entry(String name, String superName, List<String> interfaces, int access) {

        /** Canonical constructor; defensively copies. */
        public Entry {
            Objects.requireNonNull(name, "name");
            interfaces = List.copyOf(Objects.requireNonNull(interfaces, "interfaces"));
        }

        /** Whether this is an interface rather than a class. */
        public boolean isInterface() {
            return (access & Opcodes.ACC_INTERFACE) != 0;
        }
    }

    /** Registers a class from its bytecode. Later registrations of the same name replace earlier. */
    public void add(byte[] classBytes) {
        ClassReader reader = new ClassReader(Objects.requireNonNull(classBytes, "classBytes"));
        byName.put(reader.getClassName(), new Entry(
                reader.getClassName(),
                reader.getSuperName(),
                List.of(reader.getInterfaces()),
                reader.getAccess()));
        invalidate();
    }

    /** Registers a class directly, for callers that already have the shape (and for tests). */
    public void add(String name, String superName, List<String> interfaces) {
        byName.put(Objects.requireNonNull(name, "name"),
                new Entry(name, superName, interfaces, 0));
        invalidate();
    }

    /** What is known about a class, if it was indexed. */
    public Optional<Entry> get(String internalName) {
        return Optional.ofNullable(byName.get(internalName));
    }

    /** How many classes are indexed. */
    public int size() {
        return byName.size();
    }

    /**
     * Every ancestor of {@code internalName}, nearest first, excluding the class itself.
     *
     * <p>Unknown classes yield an empty list rather than an error. A mod jar routinely references
     * types the index has never seen — the JDK, another mod not being transformed — and those
     * references are not the loader's to rewrite anyway.
     *
     * <p>The result is memoised, and the memo is discarded whenever a class is registered.
     *
     * <p>The generation check makes that invalidation correct <em>locally</em>: a walk that
     * overlapped a registration is returned to its caller but never stored, so a list computed
     * against a half-built hierarchy cannot outlive the moment it was computed. Storing one would
     * mean a member silently not being remapped — the {@code NoSuchMethodError} this index exists
     * to prevent, appearing only under load.
     *
     * <p>A plain {@code computeIfAbsent} would also be safe today, because
     * {@code ConcurrentHashMap.clear()} takes the same per-bin monitor the mapping function runs
     * under and therefore cannot skip a concurrent insertion. That argument depends on reading the
     * implementation of the map rather than its contract, which is a fragile thing for a
     * correctness property to rest on. The counter costs one atomic read per miss and lets the
     * invariant be checked here, where it is stated.
     */
    public List<String> ancestorsOf(String internalName) {
        Objects.requireNonNull(internalName, "internalName");
        List<String> memoised = ancestorCache.get(internalName);
        if (memoised != null) {
            return memoised;
        }
        long before = generation.get();
        List<String> computed = computeAncestors(internalName);
        if (generation.get() == before) {
            ancestorCache.put(internalName, computed);
        }
        return computed;
    }

    private List<String> computeAncestors(String internalName) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        // Superclass before interfaces at each level: this is the order the JVM resolves a
        // member in, so following it picks the declaration the JVM would.
        get(internalName).ifPresent(entry -> enqueue(entry, queue));
        while (!queue.isEmpty()) {
            String next = queue.removeFirst();
            if (!seen.add(next)) {
                continue;
            }
            get(next).ifPresent(entry -> enqueue(entry, queue));
        }
        return List.copyOf(seen);
    }

    private static void enqueue(Entry entry, Deque<String> queue) {
        if (entry.superName() != null) {
            queue.addLast(entry.superName());
        }
        queue.addAll(entry.interfaces());
    }

    /** Whether {@code candidate} is {@code internalName} or one of its ancestors. */
    public boolean isAssignableFrom(String candidate, String internalName) {
        Objects.requireNonNull(candidate, "candidate");
        return candidate.equals(internalName) || ancestorsOf(internalName).contains(candidate);
    }

    /** Marks the memoised walks stale. Called on every registration. */
    private void invalidate() {
        generation.incrementAndGet();
        ancestorCache.clear();
    }

    /** The class and then its ancestors — the order a member lookup should try. */
    public List<String> resolutionOrder(String internalName) {
        List<String> order = new ArrayList<>();
        order.add(internalName);
        order.addAll(ancestorsOf(internalName));
        return order;
    }
}
