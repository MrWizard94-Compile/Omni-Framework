package org.omniframework.loader.core.entrypoint;

import java.io.Serial;
import java.util.Objects;
import org.omniframework.loader.model.Entrypoint;

/**
 * An entrypoint could not be created or run.
 *
 * <p>Always carries the declaration that failed. "Entrypoint failed" with no name is unactionable
 * in a pack with three hundred mods; naming the mod, the phase, and the declared value turns it
 * into something a user can look up or report.
 */
public final class EntrypointException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final transient Entrypoint entrypoint;

    /** A failure with an explanation. */
    public EntrypointException(Entrypoint entrypoint, String reason) {
        this(entrypoint, reason, null);
    }

    /** A failure with an explanation and an underlying cause. */
    public EntrypointException(Entrypoint entrypoint, String reason, Throwable cause) {
        super(describe(entrypoint, reason), cause);
        this.entrypoint = entrypoint;
    }

    private static String describe(Entrypoint entrypoint, String reason) {
        Objects.requireNonNull(entrypoint, "entrypoint");
        return "entrypoint '" + entrypoint.value() + "' (phase '" + entrypoint.phase() + "'"
                + entrypoint.adapter().map(name -> ", adapter '" + name + "'").orElse("")
                + "): " + Objects.requireNonNull(reason, "reason");
    }

    /** The declaration that failed. */
    public Entrypoint entrypoint() {
        return entrypoint;
    }
}
