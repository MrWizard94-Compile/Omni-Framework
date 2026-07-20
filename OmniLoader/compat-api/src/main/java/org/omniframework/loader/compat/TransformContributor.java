package org.omniframework.loader.compat;

import java.util.List;
import org.omniframework.loader.transform.TransformPass;

/**
 * Extra AOT passes this ecosystem's jars need (contract 4, ARCHITECTURE §7).
 *
 * <p>Ordering is the pipeline's, not the layer's: §6 fixes the sequence so that Mixin sees
 * official names and access widening applies to already-remapped members. A layer says what it
 * needs done, not when.
 *
 * <p>A metadata-level layer supplies none. Fabric-family mods still get remapped, but by the
 * pipeline's own pass configured with the layer's mapping artifact — the layer contributes the
 * data, not the machinery.
 */
@FunctionalInterface
public interface TransformContributor {

    /** Passes to insert for this ecosystem's jars, in the order the layer wants them applied. */
    List<TransformPass> passes();

    /**
     * The mapping artifact this ecosystem's jars are compiled against.
     *
     * <p>Folded into the transform cache key (ADR-0010), so a layer that changes mappings
     * without changing this id will serve stale jars.
     */
    default String mappingArtifactId() {
        return "none";
    }
}
