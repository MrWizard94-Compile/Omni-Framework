package org.omniframework.loader.model;

import java.util.Objects;
import java.util.Optional;

/**
 * One declared entrypoint: a class (or member) the loader instantiates at a named phase
 * (LDR-7).
 *
 * <p>Entrypoints are <em>declared</em> rather than discovered by scanning, and instantiated
 * lazily. Both choices come straight from the corpus: annotation scanning forces every class of
 * every mod to be read at startup, and eager instantiation means a mod whose entrypoint is
 * never reached still pays for its construction. Declaration also means the failure "your
 * entrypoint class is missing" is reported with the manifest path that named it.
 *
 * <p>The optional {@code adapter} selects a language adapter — {@code "kotlin"}, {@code "scala"}
 * — so a mod written in another JVM language is a first-class citizen rather than a wrapper
 * exercise. Absent adapter means the default: a plain Java class.
 */
public record Entrypoint(String phase, String value, Optional<String> adapter) {

    /** Canonical constructor; validates. */
    public Entrypoint {
        Objects.requireNonNull(adapter, "adapter");
        if (phase == null || phase.isBlank()) {
            throw new IllegalArgumentException("an entrypoint must name its phase");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("an entrypoint must name a class or member");
        }
    }

    /** A plain Java entrypoint with no language adapter. */
    public static Entrypoint java(String phase, String className) {
        return new Entrypoint(phase, className, Optional.empty());
    }

    /** Whether a non-default language adapter handles this entrypoint. */
    public boolean usesAdapter() {
        return adapter.isPresent();
    }

    @Override
    public String toString() {
        return adapter.map(name -> phase + ":" + name + ":" + value).orElse(phase + ":" + value);
    }
}
