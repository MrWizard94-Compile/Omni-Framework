package org.omniframework.loader.model;

import java.util.Objects;
import java.util.Optional;

/**
 * A mod's version as the manifest actually declared it.
 *
 * <p>Two shapes exist because reality has two shapes. Most versions are semver and support
 * ordering and range predicates. Some — especially when a Fabric or Forge manifest is imported
 * through a compat layer — are not ({@code "1.21.1-BETA"}, {@code "v3"}, a date stamp). The
 * loader must still load those mods, so this type keeps the raw text and reports honestly
 * whether ordering is available.
 *
 * <p>The rule that falls out, and which the resolver relies on: a version that is not semver
 * can be compared for <em>equality</em> but never for <em>range</em>. A predicate like
 * {@code >=2.0} simply does not match a non-semver version, and the resolver says so in a
 * typed issue rather than guessing.
 */
public record ModVersion(String raw, Optional<SemanticVersion> semantic) {

    /** Canonical constructor. */
    public ModVersion {
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(semantic, "semantic");
        if (raw.isBlank()) {
            throw new IllegalArgumentException("a version must not be blank");
        }
    }

    /** Parses {@code text}, keeping it verbatim whether or not it is valid semver. */
    public static ModVersion parse(String text) {
        return new ModVersion(text, SemanticVersion.parse(text));
    }

    /** Whether this version supports ordering and range matching. */
    public boolean isSemantic() {
        return semantic.isPresent();
    }

    @Override
    public String toString() {
        return raw;
    }
}
