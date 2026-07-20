package net.fabricmc.loader.api;

import java.io.Serial;

/** Thrown when a version string cannot be parsed. */
public class VersionParsingException extends Exception {

    @Serial private static final long serialVersionUID = 1L;

    /** With a message. */
    public VersionParsingException(String message) {
        super(message);
    }

    /** With a message and cause. */
    public VersionParsingException(String message, Throwable cause) {
        super(message, cause);
    }

    /** With a cause. */
    public VersionParsingException(Throwable cause) {
        super(cause);
    }
}
