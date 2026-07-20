package org.omniframework.loader.transform;

import java.io.Serial;

/**
 * A transformation could not be completed.
 *
 * <p>Unchecked deliberately. Every caller sits inside the boot sequence, where the only honest
 * responses are "report and abort" or "discard the cache entry and retry" — neither of which is
 * expressed usefully by a checked exception threaded through every pass signature.
 *
 * <p>Failures that a user can act on become {@code OmniIssue}s at the pipeline boundary (LDR-10);
 * this type carries the ones that indicate a broken cache or a malformed jar.
 */
public class TransformException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    /** A failure with an explanation. */
    public TransformException(String message) {
        super(message);
    }

    /** A failure with an explanation and an underlying cause. */
    public TransformException(String message, Throwable cause) {
        super(message, cause);
    }
}
