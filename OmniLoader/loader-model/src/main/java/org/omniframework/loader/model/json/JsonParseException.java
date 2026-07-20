package org.omniframework.loader.model.json;

import java.io.Serial;

/**
 * A malformed JSON document, located precisely.
 *
 * <p>The line and column are not decoration: manifest parse failures become {@code OmniIssue}s
 * whose location must name the exact spot, so "invalid JSON" can never be all the user is told.
 */
public final class JsonParseException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final int line;
    private final int column;

    /** Creates an exception pointing at 1-based {@code line} and {@code column}. */
    public JsonParseException(String message, int line, int column) {
        super(message + " (line " + line + ", column " + column + ")");
        this.line = line;
        this.column = column;
    }

    /** 1-based line where parsing failed. */
    public int line() {
        return line;
    }

    /** 1-based column where parsing failed. */
    public int column() {
        return column;
    }
}
