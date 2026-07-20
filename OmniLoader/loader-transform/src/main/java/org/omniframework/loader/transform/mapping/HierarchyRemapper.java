package org.omniframework.loader.transform.mapping;

import java.util.Objects;
import java.util.Optional;
import org.objectweb.asm.commons.Remapper;

/**
 * ASM's {@link Remapper}, taught to resolve members through the class hierarchy.
 *
 * <p>ASM's own remapper asks "is {@code owner.name:descriptor} mapped?" and stops. That is
 * correct only when every call site names the class that declares the member, which real bytecode
 * does not do — see {@link ClassIndex} for why the difference is invisible until it throws
 * {@code NoSuchMethodError} at runtime.
 *
 * <p>This subclass retries the lookup against each ancestor in resolution order, so a call
 * compiled against a subclass still finds the mapping recorded on the declaring class.
 *
 * <p>Left open for extension so a caller can observe what was renamed — the audit needs to say
 * "12 methods remapped", which is only knowable from inside the lookup. Subclasses must delegate
 * to {@code super} and confine themselves to observing.
 *
 * <h2>Constructors and initialisers stay untouched</h2>
 *
 * <p>{@code <init>} and {@code <clinit>} are named by the JVM specification, not by the mapping
 * author. Renaming one produces a class file that fails verification, so they are returned
 * unchanged before any lookup happens — cheaper and safer than trusting no mapping set ever
 * contains a bogus entry for them.
 */
public class HierarchyRemapper extends Remapper {

    private final MappingSet mappings;
    private final ClassIndex hierarchy;

    /** Builds a remapper applying {@code mappings}, resolving members through {@code hierarchy}. */
    public HierarchyRemapper(MappingSet mappings, ClassIndex hierarchy) {
        this.mappings = Objects.requireNonNull(mappings, "mappings");
        this.hierarchy = Objects.requireNonNull(hierarchy, "hierarchy");
    }

    @Override
    public String map(String internalName) {
        if (internalName == null) {
            return null;
        }
        return mappings.mapClass(internalName).orElse(internalName);
    }

    @Override
    public String mapFieldName(String owner, String name, String descriptor) {
        return resolve(owner, name, descriptor, true).orElse(name);
    }

    @Override
    public String mapMethodName(String owner, String name, String descriptor) {
        if (isSpecialMethod(name)) {
            return name;
        }
        return resolve(owner, name, descriptor, false).orElse(name);
    }

    @Override
    public String mapRecordComponentName(String owner, String name, String descriptor) {
        // A record component is backed by a field and an accessor of the same name; mapping it
        // as a field keeps all three consistent. Letting them diverge would produce a record
        // whose accessor no longer matches its component, which fails verification.
        return mapFieldName(owner, name, descriptor);
    }

    /** Whether a method name is fixed by the JVM specification. */
    private static boolean isSpecialMethod(String name) {
        return "<init>".equals(name) || "<clinit>".equals(name);
    }

    /**
     * Looks the member up on {@code owner}, then on each ancestor in resolution order.
     *
     * <p>The first hit wins, which mirrors how the JVM itself resolves a member: the nearest
     * declaration shadows anything further up.
     */
    private Optional<String> resolve(String owner, String name, String descriptor, boolean field) {
        if (owner == null || owner.startsWith("[")) {
            // Array types declare no members of their own; their calls resolve against Object.
            return Optional.empty();
        }
        for (String candidate : hierarchy.resolutionOrder(owner)) {
            Optional<String> mapped = field
                    ? mappings.mapField(candidate, name, descriptor)
                    : mappings.mapMethod(candidate, name, descriptor);
            if (mapped.isPresent()) {
                return mapped;
            }
        }
        return Optional.empty();
    }
}
