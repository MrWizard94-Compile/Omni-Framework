package org.omniframework.loader.bootstrap;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.omniframework.loader.provider.GameSide;

/**
 * Everything the boot sequence needs to know before it can start (B1).
 *
 * <p>A plain value: the bootstrap fills it from the process arguments, a test fills it directly.
 * It carries no policy — the sequence decides what to do with it — so it stays trivial to
 * construct and impossible to get into an inconsistent state.
 *
 * @param gameDirectory the instance's game directory, where a {@code GameProvider} looks for the
 *     installed game
 * @param modsDirectory the instance's {@code mods} folder, walked by {@link ModDiscovery}
 * @param side which program is starting; candidates for the other side are filtered by the
 *     resolver
 * @param launchArguments the arguments handed to the game's {@code main}, in order
 */
public record BootConfig(
        Path gameDirectory,
        Path modsDirectory,
        GameSide side,
        List<String> launchArguments) {

    /** Canonical constructor; defensively copies. */
    public BootConfig {
        Objects.requireNonNull(gameDirectory, "gameDirectory");
        Objects.requireNonNull(modsDirectory, "modsDirectory");
        Objects.requireNonNull(side, "side");
        launchArguments = List.copyOf(Objects.requireNonNull(launchArguments, "launchArguments"));
    }

    /** The common shape: mods live in {@code <gameDirectory>/mods}. */
    public static BootConfig of(Path gameDirectory, GameSide side, List<String> launchArguments) {
        return new BootConfig(
                gameDirectory, gameDirectory.resolve("mods"), side, launchArguments);
    }
}
