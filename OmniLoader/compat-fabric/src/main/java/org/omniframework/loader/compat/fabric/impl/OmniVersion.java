package org.omniframework.loader.compat.fabric.impl;

import java.util.Objects;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import org.omniframework.loader.model.ModVersion;

/**
 * {@link Version} backed by OmniLoader's own version model.
 *
 * <p>Deliberately a view rather than a parallel implementation. Two version comparators in one
 * process is how a mod passes resolution and then fails its own runtime check against the same
 * pair of versions — a contradiction with no visible cause, because each half is individually
 * correct.
 */
public record OmniVersion(ModVersion delegate) implements Version {

    /** Canonical constructor. */
    public OmniVersion {
        Objects.requireNonNull(delegate, "delegate");
    }

    /** Parses a version, or explains why it could not be. */
    public static Version parse(String text) throws VersionParsingException {
        if (text == null || text.isBlank()) {
            throw new VersionParsingException("a version string must not be empty");
        }
        try {
            return new OmniVersion(ModVersion.parse(text));
        } catch (RuntimeException e) {
            throw new VersionParsingException("cannot parse version '" + text + "'", e);
        }
    }

    @Override
    public String getFriendlyString() {
        return delegate.raw();
    }

    @Override
    public int compareTo(Version other) {
        if (other instanceof OmniVersion omni) {
            return compareSemantic(omni);
        }
        // A foreign Version implementation cannot be compared semantically without assuming its
        // string format; lexicographic on the friendly string is the honest fallback.
        return getFriendlyString().compareTo(other.getFriendlyString());
    }

    private int compareSemantic(OmniVersion other) {
        if (delegate.semantic().isPresent() && other.delegate.semantic().isPresent()) {
            return delegate.semantic().get().compareTo(other.delegate.semantic().get());
        }
        return delegate.raw().compareTo(other.delegate.raw());
    }

    @Override
    public String toString() {
        return delegate.raw();
    }
}
