package org.omniframework.loader.model;

import java.util.Objects;

/**
 * An additional identity a mod claims to satisfy (LDR-5's cross-ecosystem dedupe).
 *
 * <p>This is what lets one artifact stand in for another. A mod that bundles a library can
 * declare it {@code provides} that library's id, so a third mod depending on the library
 * resolves against the bundle instead of failing or double-loading. It is also how a compat
 * layer states that its hosted API satisfies the guest ecosystem's identity — the mechanism
 * behind "the Fabric API surface is present" without a second copy on the classpath.
 *
 * <p>Because a provided identity participates in resolution exactly like a real one, ambiguity
 * here is a real hazard: two mods providing the same id at the same version is a conflict the
 * resolver must report, not silently pick a winner for.
 */
public record ProvidedMod(String id, ModVersion version) {

    /** Canonical constructor; validates. */
    public ProvidedMod {
        Objects.requireNonNull(version, "version");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a provided mod must have an id");
        }
    }

    @Override
    public String toString() {
        return id + " " + version;
    }
}
