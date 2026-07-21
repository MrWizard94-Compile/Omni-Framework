package org.omniframework.loader.bootstrap;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.omniframework.loader.provider.GameSide;
import org.omniframework.loader.provider.LocatedGame;

/**
 * Everything the boot sequence needs to know before it can start (B1, extended for B2).
 *
 * <p>A plain value: the bootstrap fills it from the process arguments, a test fills it directly.
 * It carries no policy — the sequence decides what to do with it.
 *
 * <h2>Two ways the game is found</h2>
 *
 * <p>{@link #providedGame()} is the difference between a standalone boot and a launcher-driven
 * one. When it is empty, the sequence discovers the game with a {@code GameProvider} — the dev
 * and standalone path (LDR-1). When it is present, the launcher has <em>already</em> located the
 * game (it resolved the instance's component stack and knows every jar path), so it hands the
 * result over rather than making the loader rediscover what the launcher already knows. Neither
 * is more correct; they are different callers.
 *
 * <p>{@link #libraries()} are the game's runtime libraries — LWJGL and the rest — as jar paths.
 * They become library partitions in the transforming loader (game and libraries share the
 * loader, as the Fabric-family semantics require). In provider mode this is usually empty because
 * a dev classpath already carries them; the launcher populates it.
 *
 * @param gameDirectory the instance directory, where a provider looks and where the game runs
 * @param modsDirectory the {@code mods} folder walked by {@link ModDiscovery}
 * @param side which program is starting
 * @param launchArguments the arguments handed to the game's {@code main}, in order
 * @param libraries the game's runtime library jars, added as partitions
 * @param providedGame a game the launcher already located, or empty to discover one
 */
public record BootConfig(
        Path gameDirectory,
        Path modsDirectory,
        GameSide side,
        List<String> launchArguments,
        List<Path> libraries,
        Optional<LocatedGame> providedGame) {

    /** Canonical constructor; defensively copies. */
    public BootConfig {
        Objects.requireNonNull(gameDirectory, "gameDirectory");
        Objects.requireNonNull(modsDirectory, "modsDirectory");
        Objects.requireNonNull(side, "side");
        launchArguments = List.copyOf(Objects.requireNonNull(launchArguments, "launchArguments"));
        libraries = List.copyOf(Objects.requireNonNull(libraries, "libraries"));
        Objects.requireNonNull(providedGame, "providedGame");
    }

    /**
     * The standalone shape: discover the game via a provider, no launcher-supplied libraries.
     *
     * <p>Kept so the dev/provider path and its tests stay simple; the launcher uses the canonical
     * constructor with a {@code providedGame}.
     */
    public BootConfig(
            Path gameDirectory, Path modsDirectory, GameSide side, List<String> launchArguments) {
        this(gameDirectory, modsDirectory, side, launchArguments, List.of(), Optional.empty());
    }

    /** The common provider-mode shape: mods live in {@code <gameDirectory>/mods}. */
    public static BootConfig of(Path gameDirectory, GameSide side, List<String> launchArguments) {
        return new BootConfig(gameDirectory, gameDirectory.resolve("mods"), side, launchArguments);
    }
}
