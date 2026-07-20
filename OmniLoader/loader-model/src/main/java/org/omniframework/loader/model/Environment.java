package org.omniframework.loader.model;

import java.util.Optional;

/**
 * Which physical side a mod loads on (LDR-9).
 *
 * <p>"Side" is the distinction between the client program and the dedicated-server program,
 * not the logical client/server split inside a running game. A mod declared {@link #CLIENT}
 * is simply absent from a dedicated server — it is never discovered, never resolved, never
 * transformed — which is why this is part of the model rather than a runtime check.
 */
public enum Environment {
    /** Loads only in the client. */
    CLIENT("client"),
    /** Loads only on a dedicated server. */
    SERVER("server"),
    /** Loads on both (the default when a manifest says nothing). */
    BOTH("both");

    private final String manifestValue;

    Environment(String manifestValue) {
        this.manifestValue = manifestValue;
    }

    /** The exact string a manifest uses for this value. */
    public String manifestValue() {
        return manifestValue;
    }

    /** Whether a mod with this environment loads on the given side. */
    public boolean loadsOn(Environment side) {
        return this == BOTH || this == side;
    }

    /** Parses a manifest value, or empty when it is not one of the three. */
    public static Optional<Environment> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        for (Environment candidate : values()) {
            if (candidate.manifestValue.equals(text)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
