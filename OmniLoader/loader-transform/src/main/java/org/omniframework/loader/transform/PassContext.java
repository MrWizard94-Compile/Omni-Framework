package org.omniframework.loader.transform;

import java.util.Objects;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.transform.mapping.ClassIndex;
import org.omniframework.loader.transform.mapping.MappingSet;

/**
 * What a pass is given about the jar it is transforming.
 *
 * <p>Passes receive this rather than reaching for globals so that two jars can be transformed
 * concurrently with different mappings — which is exactly what happens when a Fabric-family mod
 * (needing intermediary→official) and a native mod (needing nothing) are in the same pack.
 */
public record PassContext(
        String jarName,
        String modId,
        MappingSet mappings,
        ClassIndex hierarchy,
        GameSideTarget side,
        IssueCollector issues) {

    /** Canonical constructor. */
    public PassContext {
        Objects.requireNonNull(jarName, "jarName");
        Objects.requireNonNull(modId, "modId");
        Objects.requireNonNull(mappings, "mappings");
        Objects.requireNonNull(hierarchy, "hierarchy");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(issues, "issues");
    }

    /**
     * Which program the transformed jar is being built for.
     *
     * <p>Declared here rather than reusing the resolver's type because the transform pipeline is
     * a separate module that must not depend on the provider API to answer a two-valued question.
     */
    public enum GameSideTarget {
        /** The game client. */
        CLIENT,
        /** A dedicated server. */
        SERVER;

        /** The lowercase name used in annotations and manifests. */
        public String lowerName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        /** The side that is stripped when building for this one. */
        public GameSideTarget opposite() {
            return this == CLIENT ? SERVER : CLIENT;
        }
    }
}
