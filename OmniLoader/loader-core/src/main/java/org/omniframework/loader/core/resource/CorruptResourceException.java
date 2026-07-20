package org.omniframework.loader.core.resource;

import java.io.Serial;
import java.nio.file.Path;

/**
 * A root advertised a resource it then could not read — a damaged jar or a file that vanished
 * mid-load.
 *
 * <p>This is deliberately <em>not</em> reported as "resource absent". A corrupt download and a
 * missing dependency need opposite responses from the user, and a loader that conflates them
 * sends people hunting for a mod they already have.
 */
public final class CorruptResourceException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final transient Path root;
    private final String path;

    /** Creates an exception naming the root, the entry, and the underlying cause. */
    public CorruptResourceException(Path root, String path, Throwable cause) {
        super("cannot read " + path + " from " + root + " — the file may be corrupt", cause);
        this.root = root;
        this.path = path;
    }

    /** The root that failed. */
    public Path root() {
        return root;
    }

    /** The entry that could not be read. */
    public String path() {
        return path;
    }
}
