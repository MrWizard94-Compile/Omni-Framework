package org.omniframework.loader.bootstrap;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.omniframework.loader.resolve.ModCandidate;

/**
 * The outcome of walking a mods directory (B1 + brick 15/16 claimers).
 *
 * <p>Three lists, deliberately separate:
 *
 * <ul>
 *   <li>{@link #nativeMods()} — {@code omni.mod.json}
 *   <li>{@link #hostedMods()} — claimed by a compat layer and imported to the canonical model
 *   <li>{@link #unclaimed()} — no native manifest and no layer claimed them
 * </ul>
 */
public record DiscoveredMods(
        List<ModCandidate> nativeMods, List<HostedMod> hostedMods, List<Path> unclaimed) {

    /** A jar hosted through a named compat layer. */
    public record HostedMod(String layerId, String ecosystem, ModCandidate candidate) {
        public HostedMod {
            Objects.requireNonNull(layerId, "layerId");
            Objects.requireNonNull(ecosystem, "ecosystem");
            Objects.requireNonNull(candidate, "candidate");
        }
    }

    /** Canonical constructor; defensively copies. */
    public DiscoveredMods {
        nativeMods = List.copyOf(Objects.requireNonNull(nativeMods, "nativeMods"));
        hostedMods = List.copyOf(Objects.requireNonNull(hostedMods, "hostedMods"));
        unclaimed = List.copyOf(Objects.requireNonNull(unclaimed, "unclaimed"));
    }

    /** Convenience when nothing was hosted. */
    public DiscoveredMods(List<ModCandidate> nativeMods, List<Path> unclaimed) {
        this(nativeMods, List.of(), unclaimed);
    }

    public int nativeCount() {
        return nativeMods.size();
    }

    public int hostedCount() {
        return hostedMods.size();
    }

    public int unclaimedCount() {
        return unclaimed.size();
    }

    /** Every candidate that should enter resolution (native + hosted). */
    public List<ModCandidate> allLoadable() {
        List<ModCandidate> all = new ArrayList<>(nativeMods.size() + hostedMods.size());
        all.addAll(nativeMods);
        for (HostedMod hosted : hostedMods) {
            all.add(hosted.candidate());
        }
        return List.copyOf(all);
    }

    public String describe() {
        return nativeCount() + " native, " + hostedCount() + " hosted, " + unclaimedCount()
                + " unclaimed";
    }
}
