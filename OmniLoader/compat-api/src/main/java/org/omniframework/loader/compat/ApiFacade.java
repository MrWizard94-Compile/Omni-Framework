package org.omniframework.loader.compat;

import java.util.List;

/**
 * The guest loader's own API, reimplemented over OmniLoader (contract 5, ARCHITECTURE §7).
 *
 * <h2>This is where a compat layer's cost actually lives</h2>
 *
 * <p>A guest mod calls its own loader's API and expects its own loader's answers. Every method
 * the guest API exposes is one this layer must answer correctly; every method it does not expose
 * is a {@code NoSuchMethodError} inside a mod that appeared to load fine. The gap is not
 * discovered at load — it is discovered the first time a player walks into the code path that
 * uses it, which may be weeks later.
 *
 * <p>That asymmetry is why {@link #surfaceReport()} exists. A layer that cannot say what it does
 * not implement cannot be scoped, and "we support Fabric" is a claim nobody can check. The report
 * is the honest form of that claim: a count, and the names.
 */
public interface ApiFacade {

    /** Fully-qualified class names this facade provides, e.g. {@code net.fabricmc.loader.api.FabricLoader}. */
    List<String> providedClasses();

    /**
     * Known-missing surfaces, as {@code owner#method} descriptors.
     *
     * <p>Deliberately part of the contract rather than a TODO list in a comment. A layer is
     * allowed to be incomplete; it is not allowed to be incomplete quietly.
     */
    default List<String> knownGaps() {
        return List.of();
    }

    /** What this facade covers and what it does not — the checkable form of a support claim. */
    default SurfaceReport surfaceReport() {
        return new SurfaceReport(providedClasses().size(), knownGaps().size(), knownGaps());
    }

    /** A layer's API coverage, in numbers rather than adjectives. */
    record SurfaceReport(int providedClassCount, int knownGapCount, List<String> gaps) {

        /** Canonical constructor; defensively copies. */
        public SurfaceReport {
            gaps = List.copyOf(java.util.Objects.requireNonNull(gaps, "gaps"));
        }

        /** A line for the boot log and for scoping conversations. */
        public String describe() {
            return providedClassCount + " API classes provided, " + knownGapCount
                    + " known gaps" + (gaps.isEmpty() ? "" : ": " + String.join(", ", gaps));
        }
    }
}
