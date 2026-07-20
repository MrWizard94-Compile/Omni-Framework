package org.omniframework.loader.compat;

import java.util.List;

/**
 * The layer's behavioural patches (contract 6, ARCHITECTURE §7).
 *
 * <p>A shim is a mixin or patch that makes the host behave the way the guest ecosystem expects.
 * They are unavoidable and they are also the thing that rots: each one encodes an assumption
 * about two moving targets at once.
 *
 * <p>CMP-5 therefore makes shim count a tracked health metric, and every shim must land with a
 * reproducing test. A layer whose shim count only grows is losing, and the number is the earliest
 * available signal of that — long before anyone can feel it in the maintenance load.
 */
public interface ShimSet {

    /** Mixin config resources this layer registers, e.g. {@code compat-fabric.mixins.json}. */
    List<String> mixinConfigs();

    /**
     * Every shim, with why it exists.
     *
     * <p>The reason is required. A shim whose justification was never written down cannot be
     * removed later, because nobody can prove the condition it worked around is gone.
     */
    List<Shim> shims();

    /** How many shims this layer carries — the CMP-5 metric. */
    default int shimCount() {
        return shims().size();
    }

    /** One behavioural patch, and the condition that forced it. */
    record Shim(String id, String targets, String reason, String reproducingTest) {

        /** Canonical constructor. */
        public Shim {
            java.util.Objects.requireNonNull(id, "id");
            java.util.Objects.requireNonNull(targets, "targets");
            if (java.util.Objects.requireNonNull(reason, "reason").isBlank()) {
                // A shim with no stated reason is permanent by accident: nobody can later
                // establish whether the thing it worked around still exists.
                throw new IllegalArgumentException("shim '" + id + "' must state why it exists");
            }
            java.util.Objects.requireNonNull(reproducingTest, "reproducingTest");
        }

        /** A line for the shim inventory. */
        public String describe() {
            return id + " → " + targets + " (" + reason + "; test: " + reproducingTest + ")";
        }
    }
}
