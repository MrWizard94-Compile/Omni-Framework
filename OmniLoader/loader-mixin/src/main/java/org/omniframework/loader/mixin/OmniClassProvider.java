package org.omniframework.loader.mixin;

import java.net.URL;
import java.util.List;
import java.util.Objects;
import org.spongepowered.asm.service.IClassProvider;

/**
 * Resolves classes for Mixin through the loader's own classloader.
 *
 * <p>Mixin needs real {@link Class} objects for annotation and hierarchy questions. Everything
 * here routes through the transforming loader rather than the system loader, because the two see
 * different worlds: the system classpath has the untransformed jars, and resolving a game class
 * there would hand Mixin a shape that does not match what is actually running.
 */
public final class OmniClassProvider implements IClassProvider {

    private final ClassLoader loader;
    private final List<URL> classPath;

    /** Builds a provider over {@code loader}, reporting {@code classPath} to Mixin. */
    public OmniClassProvider(ClassLoader loader, List<URL> classPath) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.classPath = List.copyOf(Objects.requireNonNull(classPath, "classPath"));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Deprecated upstream in favour of container handles, but still declared abstract on the
     * interface, so it must be implemented to compile. Mixin 0.8.7 does still consult it on some
     * paths, so returning the real classpath is safer than returning empty — an empty array
     * would make those paths silently find nothing. Removal is tied to the ADR-0009 version bump
     * that drops the method.
     */
    @Deprecated
    @Override
    public URL[] getClassPath() {
        return classPath.toArray(new URL[0]);
    }

    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        return Class.forName(Objects.requireNonNull(name, "name"), false, loader);
    }

    @Override
    public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(Objects.requireNonNull(name, "name"), initialize, loader);
    }

    @Override
    public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
        // An "agent" class is one Mixin needs before the transforming loader exists — its own
        // support types. Resolving those through this class's loader rather than the game's is
        // deliberate: asking the transforming loader for them during its own construction is
        // how a classloading cycle starts.
        return Class.forName(
                Objects.requireNonNull(name, "name"), initialize, getClass().getClassLoader());
    }
}
