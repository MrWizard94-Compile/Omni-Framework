package net.fabricmc.loader.api;

/**
 * A mod version.
 *
 * <p>Backed by OmniLoader's own version model rather than a parallel implementation: two version
 * comparators in one process is how a mod passes resolution and then fails its own runtime check.
 */
public interface Version extends Comparable<Version> {

    /** The version as an author would write it. */
    String getFriendlyString();

    /**
     * Parses a version string.
     *
     * @throws VersionParsingException if the string is not a version this loader understands
     */
    static Version parse(String string) throws VersionParsingException {
        return org.omniframework.loader.compat.fabric.impl.OmniVersion.parse(string);
    }
}
