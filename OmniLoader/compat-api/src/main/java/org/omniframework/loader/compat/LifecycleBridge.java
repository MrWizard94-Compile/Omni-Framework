package org.omniframework.loader.compat;

import java.util.List;
import java.util.Optional;

/**
 * Maps the guest's entrypoint and event timing onto Omni's phases (contract 7, ARCHITECTURE §7).
 *
 * <h2>Timing is the part that looks like it works</h2>
 *
 * <p>A guest mod's entrypoints assume its own loader's ordering: that one event has already
 * fired, that a registry is still open, that another mod has not yet initialised. Import the
 * metadata perfectly and remap the bytecode perfectly, and a mod whose entrypoint fires one
 * phase too early still fails — usually as a registry error or a null field, far from anything
 * that names the loader.
 *
 * <p>So the mapping is explicit and declared, rather than "call them all at startup and hope".
 * A phase the layer cannot map is reported, not silently folded into the nearest one.
 */
public interface LifecycleBridge {

    /** Guest phase names this bridge understands, e.g. {@code main}, {@code client}. */
    List<String> guestPhases();

    /**
     * The Omni phase a guest phase maps onto, or empty when there is no honest equivalent.
     *
     * <p>Returning empty is a real answer. Folding an unmappable phase into the nearest one
     * produces exactly the too-early initialisation this contract exists to prevent.
     */
    Optional<String> omniPhaseFor(String guestPhase);

    /**
     * Guest phases with no Omni equivalent — declared, so the gap is visible before it bites.
     */
    default List<String> unmappedPhases() {
        return guestPhases().stream().filter(phase -> omniPhaseFor(phase).isEmpty()).toList();
    }
}
