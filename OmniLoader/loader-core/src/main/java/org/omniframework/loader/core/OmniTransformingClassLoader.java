package org.omniframework.loader.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Objects;
import java.util.Optional;
import org.omniframework.loader.core.resource.ResourceRoot;

/**
 * The single transforming class loader (LDR-4, D3, ARCHITECTURE §4).
 *
 * <p>Every game and mod class routes through here, which is what makes one Mixin pipeline and
 * Fabric-family semantics possible at all. Three properties are load-bearing and each is
 * enforced rather than assumed:
 *
 * <ol>
 *   <li><b>The parent is the platform loader, not the application loader.</b> This is the
 *       important one. The application loader holds whatever was on the launch classpath — the
 *       launcher's own dependencies, the loader's own libraries. If the game could see those,
 *       a mod would silently link against the launcher's copy of a library, and the resulting
 *       "works on my machine" failures are nearly impossible to diagnose. Delegating only to the
 *       JDK seals that leak (ARCHITECTURE §4: "a sealed empty loader (no system-classpath
 *       leakage)").
 *   <li><b>Parent-last for everything else.</b> Game and mod classes are found here first, so a
 *       transformed class is never shadowed by an untransformed copy.
 *   <li><b>Transformation happens exactly once per class.</b> The JVM's per-name locking plus
 *       {@code findLoadedClass} guarantees a class is defined once; transformers therefore do
 *       not need to be idempotent, only thread-safe.
 * </ol>
 *
 * <p>Registered as parallel-capable so two threads loading different classes do not serialise —
 * discovery and transformation both run concurrently.
 */
public final class OmniTransformingClassLoader extends ClassLoader implements java.io.Closeable {

    static {
        // Must happen in a static initialiser of this exact class, before any instance exists.
        registerAsParallelCapable();
    }

    /**
     * Packages always delegated to the parent. Deliberately short: these are the packages the
     * JVM itself requires come from the platform, plus the loader's own API, which mods link
     * against and must therefore share an identity with.
     */
    private static final String[] PARENT_FIRST_PREFIXES = {
        "java.", "javax.", "jdk.", "sun.", "com.sun.", "org.w3c.dom.", "org.xml.sax.",
        "org.omniframework.loader.",
    };

    private final PartitionRegistry partitions;
    private final ClassTransformer transformer;

    /**
     * Creates a loader over the given partitions.
     *
     * @param partitions the search order; see {@link PartitionRegistry}
     * @param transformer applied to every transformable class; use
     *     {@link ClassTransformer#identity()} for an unmodified game
     */
    public OmniTransformingClassLoader(PartitionRegistry partitions, ClassTransformer transformer) {
        // The platform loader sees JDK classes and nothing else — no application classpath.
        super("omni", ClassLoader.getPlatformClassLoader());
        this.partitions = Objects.requireNonNull(partitions, "partitions");
        this.transformer = Objects.requireNonNull(transformer, "transformer");
    }

    /** The partitions this loader searches. */
    public PartitionRegistry partitions() {
        return partitions;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                loaded = isParentFirst(name) ? loadFromParent(name) : loadLocallyOrParent(name);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    private Class<?> loadFromParent(String name) throws ClassNotFoundException {
        return getParent().loadClass(name);
    }

    private Class<?> loadLocallyOrParent(String name) throws ClassNotFoundException {
        Optional<Partition> owner = partitions.ownerOf(name);
        if (owner.isPresent()) {
            return defineFrom(name, owner.get());
        }
        // Not ours: the JDK may still have it (a mod referencing java.sql, say). If the parent
        // does not either, this throws, which is the correct answer.
        return getParent().loadClass(name);
    }

    private Class<?> defineFrom(String binaryName, Partition partition) throws ClassNotFoundException {
        String path = ResourceRoot.classPath(binaryName);
        byte[] bytes = partition
                .root()
                .read(path)
                .orElseThrow(() -> new ClassNotFoundException(
                        binaryName + " was indexed in " + partition.id() + " but could not be read"));

        if (partition.isTransformable()) {
            bytes = transformer.transform(binaryName, partition, bytes);
            if (bytes == null) {
                throw new ClassNotFoundException(
                        "a transformer returned no bytes for " + binaryName
                                + " (partition " + partition.id() + ")");
            }
        }

        definePackageIfAbsent(binaryName);
        // The code source ties every loaded class back to the artifact it came from — the data
        // that makes "which mod provided this class?" answerable at runtime.
        return defineClass(binaryName, bytes, 0, bytes.length, protectionDomainFor(partition));
    }

    private void definePackageIfAbsent(String binaryName) {
        int lastDot = binaryName.lastIndexOf('.');
        if (lastDot < 0) {
            return;
        }
        String packageName = binaryName.substring(0, lastDot);
        if (getDefinedPackage(packageName) == null) {
            try {
                definePackage(packageName, null, null, null, null, null, null, null);
            } catch (IllegalArgumentException alreadyDefined) {
                // Another thread won the race between the check and the call. Harmless.
            }
        }
    }

    private ProtectionDomain protectionDomainFor(Partition partition) {
        try {
            URL location = partition.root().location().toUri().toURL();
            return new ProtectionDomain(new CodeSource(location, (Certificate[]) null), null, this, null);
        } catch (MalformedURLException impossible) {
            // A path that cannot be expressed as a URL would mean the partition could not have
            // been opened in the first place.
            return new ProtectionDomain(null, null, this, null);
        }
    }

    private static boolean isParentFirst(String name) {
        for (String prefix : PARENT_FIRST_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public URL getResource(String name) {
        // Resources follow the same rule as classes: ours first, parent as fallback.
        return partitions.readResource(name).isPresent()
                ? resourceUrl(name)
                : getParent().getResource(name);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        Optional<byte[]> bytes = partitions.readResource(Objects.requireNonNull(name, "name"));
        return bytes.<InputStream>map(java.io.ByteArrayInputStream::new)
                .orElseGet(() -> getParent().getResourceAsStream(name));
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        // Only the first match is exposed. Mods that need every copy of a resource are a
        // future need; inventing an API for it now would be guessing at its shape.
        URL found = getResource(name);
        return found == null ? Collections.emptyEnumeration() : Collections.enumeration(java.util.List.of(found));
    }

    /**
     * A URL for a resource this loader provides.
     *
     * <p>Points at the owning root rather than a synthetic scheme, so anything that prints it
     * shows a real path a human can go and look at.
     */
    private URL resourceUrl(String name) {
        for (Partition partition : partitions.partitions()) {
            if (partition.root().contains(name)) {
                try {
                    return partition.root().location().toUri().resolve(name).toURL();
                } catch (MalformedURLException | IllegalArgumentException unusable) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Releases every partition's underlying jar handle.
     *
     * <p>The loader owns those handles for its whole life (see {@code JarResourceRoot}), so
     * something has to let them go. Closing a loader whose classes are still in use is a
     * programming error, not a supported operation — after this, any class not already defined
     * will fail to load.
     */
    @Override
    public void close() throws IOException {
        partitions.close();
    }

    @Override
    public String toString() {
        return "OmniTransformingClassLoader(" + partitions.partitions().size() + " partitions)";
    }
}
