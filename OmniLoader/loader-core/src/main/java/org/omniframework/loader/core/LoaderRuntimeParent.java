package org.omniframework.loader.core;

import java.net.URL;
import java.util.List;
import java.util.Objects;

/**
 * The parent of the transforming loader: the JDK, plus exactly the API packages mods link
 * against — and nothing else (ARCHITECTURE §4, refined by ADR-0021).
 *
 * <h2>Why a bare platform loader was wrong</h2>
 *
 * <p>The transforming loader must seal out the application classpath: a mod that could see the
 * launcher's own dependencies would silently link against them, producing "works on my machine"
 * failures that are near-impossible to trace. The original design achieved that by parenting the
 * transforming loader to {@code getPlatformClassLoader()} — the JDK and nothing more.
 *
 * <p>But that sealed out too much. A native mod's entrypoint <em>is</em> an
 * {@code org.omniframework.api.ModInitializer}, and for the loader to cast the mod's instance to
 * that type, the mod's class and the loader must resolve the <em>same</em> {@code ModInitializer}
 * — one class, one defining loader. A platform-loader parent has no {@code org.omniframework.api}
 * at all, so the mod's class could not even be defined, and had it been, its interface would have
 * been a different class object than the loader holds. The failure never surfaced only because
 * nothing had yet booted a mod that links the API.
 *
 * <h2>What this shares, and what it refuses</h2>
 *
 * <p>This sits between the platform loader and the transforming loader. JDK classes resolve
 * through the platform parent as normal. Classes in a {@linkplain #sharedPrefixes shared prefix}
 * are delegated to {@link #apiSource} — the loader that actually holds the API on its classpath,
 * so there is exactly one copy with a shared identity. <b>Everything else throws</b>
 * {@link ClassNotFoundException}, which is what preserves the seal: the application classpath
 * behind {@code apiSource} is reachable only for the allowlisted API packages, never in general.
 *
 * <p>The shared set is deliberately just the mod-facing API ({@code org.omniframework.api.}). The
 * loader's own internals ({@code org.omniframework.loader.}) are <em>not</em> shared — mods link
 * the API, not the implementation, and sharing the implementation would leak the loader's guts
 * into the mod environment. Third-party runtimes that must also be shared once bytecode
 * transformation and Mixin run against real mods (ASM, the Mixin runtime) are added to the shared
 * set when those stages are wired, not before.
 */
final class LoaderRuntimeParent extends ClassLoader {

    static {
        registerAsParallelCapable();
    }

    private final ClassLoader apiSource;
    private final List<String> sharedPrefixes;

    /**
     * Creates the runtime parent.
     *
     * @param apiSource the loader that holds the shared API on its classpath (in production, the
     *     loader that loaded the bootstrap; in tests, whatever provides the API)
     * @param sharedPrefixes dotted package prefixes delegated to {@code apiSource}
     */
    LoaderRuntimeParent(ClassLoader apiSource, List<String> sharedPrefixes) {
        // Platform parent: JDK classes resolve through normal delegation before findClass runs.
        super("omni-runtime", ClassLoader.getPlatformClassLoader());
        this.apiSource = Objects.requireNonNull(apiSource, "apiSource");
        this.sharedPrefixes = List.copyOf(Objects.requireNonNull(sharedPrefixes, "sharedPrefixes"));
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        // Reached only after the platform parent has already declined (so JDK never gets here).
        if (isShared(name)) {
            return apiSource.loadClass(name);
        }
        // The seal: anything not JDK and not an allowlisted API package stays unreachable.
        throw new ClassNotFoundException(name);
    }

    @Override
    protected URL findResource(String name) {
        // Resources under a shared package come from the same source, so a mod reading, say, a
        // service file for an API type sees the loader's copy rather than nothing.
        return isSharedResource(name) ? apiSource.getResource(name) : null;
    }

    private boolean isShared(String binaryName) {
        for (String prefix : sharedPrefixes) {
            if (binaryName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSharedResource(String resourcePath) {
        // Resource paths use '/', package prefixes use '.'.
        String asBinary = resourcePath.replace('/', '.');
        return isShared(asBinary);
    }
}
