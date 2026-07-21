package org.omniframework.loader.bootstrap;

import java.util.List;
import java.util.Objects;

/**
 * What a completed boot loaded (B1).
 *
 * <p>Returned once the game's {@code main} has run, so it is a record of what actually took part,
 * not a plan. The counts are the honest boot-log line: which mods initialised, and how many jars
 * are still waiting on a compat layer.
 *
 * @param gameVersion the located game's version
 * @param loadedMods native mod ids, in the order they initialised
 * @param unclaimedJars jars present but awaiting a compat claimer (Fabric/NeoForge)
 */
public record BootResult(String gameVersion, List<String> loadedMods, int unclaimedJars) {

    /** Canonical constructor; defensively copies. */
    public BootResult {
        Objects.requireNonNull(gameVersion, "gameVersion");
        loadedMods = List.copyOf(Objects.requireNonNull(loadedMods, "loadedMods"));
    }

    /** A line for the boot log. */
    public String describe() {
        String mods = loadedMods.isEmpty() ? "no native mods" : loadedMods.size() + " native mod(s)";
        String waiting = unclaimedJars == 0 ? "" : ", " + unclaimedJars + " jar(s) awaiting a compat layer";
        return "booted Minecraft " + gameVersion + " with " + mods + waiting;
    }
}
