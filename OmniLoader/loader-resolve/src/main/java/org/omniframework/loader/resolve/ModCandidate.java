package org.omniframework.loader.resolve;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import org.omniframework.loader.model.ModMetadata;
import org.omniframework.loader.model.ModVersion;

/**
 * One mod the loader <em>could</em> load — a metadata document plus where it came from.
 *
 * <p>"Could" is the operative word. Discovery produces candidates; resolution decides which of
 * them actually load. Keeping the two apart is what lets several versions of the same mod be on
 * disk at once — which is not a rare edge case but the normal consequence of nested jars, where
 * two mods each bundle a different version of the same library.
 *
 * <p>{@link #parent()} records which candidate contained this one, so a failure involving a
 * bundled library can say <em>whose</em> bundled library it was. "somelib 1.2 conflicts with
 * somelib 2.0" is a puzzle; "the copy of somelib inside modA conflicts with the copy inside
 * modB" is an instruction.
 */
public record ModCandidate(
        ModMetadata metadata, Path source, Optional<ModCandidate> parent) {

    /** Canonical constructor; validates. */
    public ModCandidate {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(parent, "parent");
    }

    /** A candidate discovered directly in the mods folder. */
    public static ModCandidate topLevel(ModMetadata metadata, Path source) {
        return new ModCandidate(metadata, source, Optional.empty());
    }

    /** A candidate found nested inside another mod's jar. */
    public static ModCandidate nested(ModMetadata metadata, Path source, ModCandidate parent) {
        return new ModCandidate(metadata, source, Optional.of(Objects.requireNonNull(parent, "parent")));
    }

    /** The mod id this candidate would occupy. */
    public String id() {
        return metadata.id();
    }

    /** The version this candidate offers. */
    public ModVersion version() {
        return metadata.version();
    }

    /** Whether this candidate came from inside another mod. */
    public boolean isNested() {
        return parent.isPresent();
    }

    /**
     * How to describe this candidate to a human: its name and version, and — when it is bundled
     * inside another mod — whose bundle it is.
     */
    public String describe() {
        String base = metadata.displayName() + " " + version();
        return parent.map(owner -> base + " (bundled in " + owner.metadata().displayName() + ")")
                .orElse(base);
    }

    @Override
    public String toString() {
        return id() + " " + version();
    }
}
