package org.omniframework.loader.core;

import java.util.Objects;
import org.omniframework.loader.core.resource.ResourceRoot;

/**
 * One logical partition of the class namespace: a single code source, tagged with who owns it
 * and which ecosystem it came from (LDR-4, D3).
 *
 * <p>Partitions are the answer to a question the design deliberately took on. ARCHITECTURE §4
 * chose <em>one</em> transforming classloader with logical partitions, rather than a classloader
 * per mod. One loader is required — Fabric-family semantics and a single Mixin pipeline both
 * assume game and mod classes share a namespace — but it costs the isolation that separate
 * loaders would have given for free. Partitions buy back the part that actually mattered:
 * knowing, for any loaded class, exactly which artifact it came from.
 *
 * <p>That knowledge is what makes the diagnostics possible. "Two mods claim the same package"
 * and "this class was transformed by that mod's Mixin" are both unanswerable without it, and
 * both are among the most common real-world failures in the corpus.
 */
public record Partition(String id, Kind kind, String ecosystem, ResourceRoot root) {

    /** Canonical constructor; validates. */
    public Partition {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(root, "root");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a partition must have an id");
        }
        if (ecosystem == null || ecosystem.isBlank()) {
            throw new IllegalArgumentException("a partition must name its ecosystem");
        }
    }

    /** What a partition holds. */
    public enum Kind {
        /** The game itself. */
        GAME,
        /** A library the game or a mod links against; never a transformation candidate. */
        LIBRARY,
        /** A mod. */
        MOD
    }

    /** A game partition. */
    public static Partition game(ResourceRoot root) {
        return new Partition("minecraft", Kind.GAME, "game", root);
    }

    /** A library partition, named after its file. */
    public static Partition library(ResourceRoot root) {
        return new Partition(root.location().getFileName().toString(), Kind.LIBRARY, "library", root);
    }

    /** A mod partition. */
    public static Partition mod(String modId, String ecosystem, ResourceRoot root) {
        return new Partition(modId, Kind.MOD, ecosystem, root);
    }

    /** Whether classes from this partition are eligible for mod transformation. */
    public boolean isTransformable() {
        return kind != Kind.LIBRARY;
    }

    @Override
    public String toString() {
        return kind + " " + id + " (" + root.location() + ")";
    }
}
