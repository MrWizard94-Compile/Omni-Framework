package org.omniframework.loader.bootstrap;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.omniframework.loader.resolve.ModCandidate;

/**
 * The outcome of walking a mods directory (B1, boot sequence step 2).
 *
 * <p>Two lists, deliberately separate:
 *
 * <ul>
 *   <li>{@link #nativeMods()} — jars carrying an {@code omni.mod.json}, already parsed into the
 *       canonical model and ready to hand to the resolver.
 *   <li>{@link #unclaimed()} — jars the native reader did not recognise. These are not errors:
 *       they are almost always Fabric or NeoForge mods, and a compat layer's
 *       {@code EcosystemClaimer} will take them once B2/B3 wire claimers into discovery. Keeping
 *       them as a named list rather than dropping them is what lets the mixed-pack goal (ADR-0020)
 *       be reached incrementally — today they are counted and reported, not silently ignored.
 * </ul>
 *
 * <p>The split is the honest statement of what this loader can load <em>today</em> versus what is
 * present and waiting: a 300-mod NeoForge pack discovered now yields zero native mods and 300
 * unclaimed, which is exactly the truth until a claimer exists.
 */
public record DiscoveredMods(List<ModCandidate> nativeMods, List<Path> unclaimed) {

    /** Canonical constructor; defensively copies. */
    public DiscoveredMods {
        nativeMods = List.copyOf(Objects.requireNonNull(nativeMods, "nativeMods"));
        unclaimed = List.copyOf(Objects.requireNonNull(unclaimed, "unclaimed"));
    }

    /** How many jars were recognised as native Omni mods. */
    public int nativeCount() {
        return nativeMods.size();
    }

    /** How many jars await a compat claimer. */
    public int unclaimedCount() {
        return unclaimed.size();
    }

    /** A line for the boot log — the plain truth about what will and will not load. */
    public String describe() {
        return nativeCount() + " native mod(s) discovered, " + unclaimedCount()
                + " jar(s) awaiting a compat layer";
    }
}
