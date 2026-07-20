package org.omniframework.loader.core.resource;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * A place class and resource bytes come from: a jar, or a directory of loose classes.
 *
 * <p>Both shapes are first-class rather than jar-with-a-directory-hack, because the development
 * loop depends on directories. A mod author running from an IDE has loose classes on disk and no
 * jar at all; a loader that only understands jars forces a build step into every edit-run cycle.
 *
 * <p>Implementations must be safe for concurrent reads — classloading is parallel (LDR-4).
 */
public interface ResourceRoot extends Closeable {

    /** Where this root lives, for diagnostics and code-source attribution. */
    Path location();

    /**
     * Reads a resource by its path inside the root, e.g. {@code "net/example/Init.class"}.
     *
     * @return the bytes, or empty when this root does not contain that path
     */
    Optional<byte[]> read(String path);

    /** Whether this root contains the given path, without reading it. */
    boolean contains(String path);

    /**
     * Every package this root defines classes in, in internal form
     * ({@code "net/example/thing"}). Used for package-claim diagnostics (LDR-4) — which is why
     * it is computed once at open time rather than on demand.
     */
    Set<String> packages();

    /** The class-file path for a binary class name. */
    static String classPath(String binaryName) {
        return binaryName.replace('.', '/') + ".class";
    }

    /** The package (internal form) of a binary class name, or empty for the default package. */
    static String packageOf(String binaryName) {
        int lastDot = binaryName.lastIndexOf('.');
        return lastDot < 0 ? "" : binaryName.substring(0, lastDot).replace('.', '/');
    }
}
