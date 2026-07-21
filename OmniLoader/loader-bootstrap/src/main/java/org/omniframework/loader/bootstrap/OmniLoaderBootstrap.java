package org.omniframework.loader.bootstrap;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.omniframework.loader.core.GameLauncher;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.provider.GameSide;
import org.omniframework.loader.provider.LocatedGame;

/**
 * The loader's entry point — the {@code main} the launcher invokes to start a modded game (B1).
 *
 * <p>Deliberately thin. It parses the process arguments into a {@link BootConfig}, discovers the
 * game providers on the classpath, and hands off to {@link BootSequence}, where all the logic
 * (and all the tests) live. The rule that governs the launcher's shell — the entry holds no
 * decisions — governs this too.
 *
 * <h2>Arguments</h2>
 *
 * <pre>{@code
 *   --game-dir <path>     the instance directory (required)
 *   --mods-dir <path>     the mods folder (default: <game-dir>/mods)
 *   --side client|server  which program to start (default: client)
 *   --game-jar <path>     a game jar (repeatable); when given, the launcher has located the
 *                         game and the loader does not discover it
 *   --game-version <v>    the located game's version (required with --game-jar)
 *   --game-main <class>   the game's main class (required with --game-jar)
 *   --library <path>      a game library jar (repeatable), added as a partition
 *   --                    everything after is passed verbatim to the game's main
 * }</pre>
 *
 * <p>The launcher passes {@code --game-jar}/{@code --game-version}/{@code --game-main} because it
 * already resolved the instance's stack; a standalone or dev invocation omits them and lets a
 * {@code GameProvider} discover the game instead.
 *
 * <p>Unknown flags before {@code --} are an error rather than ignored: a launcher that
 * misspelled one should hear about it at once, not launch a subtly wrong game.
 */
public final class OmniLoaderBootstrap {

    private OmniLoaderBootstrap() {}

    /**
     * Starts the loader.
     *
     * <p>Exit codes: {@code 0} once the game's {@code main} returns; {@code 1} if the boot could
     * not begin (arguments, no provider, resolution) — the reasons are printed first; {@code 2}
     * if the game itself failed to launch.
     */
    public static void main(String[] args) {
        Optional<BootConfig> config = parse(args);
        if (config.isEmpty()) {
            System.exit(1);
            return;
        }

        IssueCollector issues = new IssueCollector();
        try {
            Optional<BootResult> result =
                    new BootSequence(GameLauncher.discoverProviders()).run(config.get(), issues);
            if (result.isEmpty()) {
                reportIssues(issues);
                System.exit(1);
                return;
            }
            // Issues can accumulate on a successful boot too (a dropped soft ordering edge, an
            // unclaimed jar); surface them without failing.
            reportIssues(issues);
            System.out.println("[omniloader] " + result.get().describe());
        } catch (org.omniframework.loader.core.GameLaunchException gameFailed) {
            System.err.println("[omniloader] the game failed to launch: " + gameFailed.getMessage());
            System.exit(2);
        }
    }

    /**
     * Parses the arguments, printing a specific error and returning empty on any fault.
     *
     * <p>Returned as an {@link Optional} rather than throwing, because the sole caller's response
     * to a bad argument is to exit — an exception would add a stack trace that tells a user
     * nothing they can act on.
     */
    static Optional<BootConfig> parse(String[] args) {
        Path gameDir = null;
        Path modsDir = null;
        GameSide side = GameSide.CLIENT;
        List<String> launchArgs = new ArrayList<>();
        List<Path> gameJars = new ArrayList<>();
        List<Path> libraries = new ArrayList<>();
        String gameVersion = null;
        String gameMain = null;
        Path intermediaryTiny = null;
        Path mojangProguard = null;
        Path composedTiny = null;

        int i = 0;
        while (i < args.length) {
            String arg = args[i];
            switch (arg) {
                case "--game-dir" -> {
                    Optional<String> value = valueFor(args, i, "--game-dir");
                    if (value.isEmpty()) {
                        return Optional.empty();
                    }
                    gameDir = Path.of(value.get());
                    i += 2;
                }
                case "--mods-dir" -> {
                    Optional<String> value = valueFor(args, i, "--mods-dir");
                    if (value.isEmpty()) {
                        return Optional.empty();
                    }
                    modsDir = Path.of(value.get());
                    i += 2;
                }
                case "--side" -> {
                    Optional<String> value = valueFor(args, i, "--side");
                    if (value.isEmpty()) {
                        return Optional.empty();
                    }
                    Optional<GameSide> parsed = parseSide(value.get());
                    if (parsed.isEmpty()) {
                        System.err.println("[omniloader] --side must be 'client' or 'server', got: "
                                + value.get());
                        return Optional.empty();
                    }
                    side = parsed.get();
                    i += 2;
                }
                case "--game-jar" -> {
                    Optional<String> value = valueFor(args, i, "--game-jar");
                    if (value.isEmpty()) {
                        return Optional.empty();
                    }
                    gameJars.add(Path.of(value.get()));
                    i += 2;
                }
                case "--library" -> {
                    Optional<String> value = valueFor(args, i, "--library");
                    if (value.isEmpty()) {
                        return Optional.empty();
                    }
                    libraries.add(Path.of(value.get()));
                    i += 2;
                }
                case "--game-version" -> {
                    Optional<String> value = valueFor(args, i, "--game-version");
                    if (value.isEmpty()) {
                        return Optional.empty();
                    }
                    gameVersion = value.get();
                    i += 2;
                }
                case "--game-main" -> {
                    Optional<String> value = valueFor(args, i, "--game-main");
                    if (value.isEmpty()) {
                        return Optional.empty();
                    }
                    gameMain = value.get();
                    i += 2;
                }
                case "--intermediary-tiny" -> {
                    Optional<String> value = valueFor(args, i, "--intermediary-tiny");
                    if (value.isEmpty()) {
                        return Optional.empty();
                    }
                    intermediaryTiny = Path.of(value.get());
                    i += 2;
                }
                case "--mojang-mappings" -> {
                    Optional<String> value = valueFor(args, i, "--mojang-mappings");
                    if (value.isEmpty()) {
                        return Optional.empty();
                    }
                    mojangProguard = Path.of(value.get());
                    i += 2;
                }
                case "--mappings" -> {
                    Optional<String> value = valueFor(args, i, "--mappings");
                    if (value.isEmpty()) {
                        return Optional.empty();
                    }
                    composedTiny = Path.of(value.get());
                    i += 2;
                }
                case "--" -> {
                    // Everything after the separator is the game's, verbatim.
                    for (int g = i + 1; g < args.length; g++) {
                        launchArgs.add(args[g]);
                    }
                    i = args.length;
                }
                default -> {
                    System.err.println("[omniloader] unknown argument: " + arg);
                    return Optional.empty();
                }
            }
        }

        if (gameDir == null) {
            System.err.println("[omniloader] --game-dir is required");
            return Optional.empty();
        }
        Optional<Optional<LocatedGame>> provided = providedGame(gameJars, gameVersion, gameMain);
        if (provided.isEmpty()) {
            return Optional.empty();
        }
        Path mods = modsDir != null ? modsDir : gameDir.resolve("mods");
        return Optional.of(new BootConfig(
                gameDir,
                mods,
                side,
                launchArgs,
                List.copyOf(libraries),
                provided.get(),
                Optional.ofNullable(intermediaryTiny),
                Optional.ofNullable(mojangProguard),
                Optional.ofNullable(composedTiny)));
    }

    /**
     * Builds the launcher-provided game, if the game flags were given.
     *
     * <p>Returns the outer {@code Optional} empty only on a <em>malformed</em> combination — game
     * jars without the version or main class, which is a caller error worth refusing. When no
     * game flags are present at all it returns a present-but-empty inner Optional, meaning "no
     * provided game; discover one". The two-level result keeps "the caller got it wrong" distinct
     * from "the caller chose provider discovery".
     */
    private static Optional<Optional<LocatedGame>> providedGame(
            List<Path> gameJars, String version, String mainClass) {
        if (gameJars.isEmpty()) {
            if (version != null || mainClass != null) {
                System.err.println(
                        "[omniloader] --game-version/--game-main require at least one --game-jar");
                return Optional.empty();
            }
            return Optional.of(Optional.empty());
        }
        if (version == null || mainClass == null) {
            System.err.println(
                    "[omniloader] --game-jar requires both --game-version and --game-main");
            return Optional.empty();
        }
        return Optional.of(Optional.of(new LocatedGame(version, gameJars, mainClass, List.of())));
    }

    /** The value following a flag, or empty (with a message) if the flag ends the arguments. */
    private static Optional<String> valueFor(String[] args, int flagIndex, String flag) {
        if (flagIndex + 1 >= args.length) {
            System.err.println("[omniloader] " + flag + " needs a value");
            return Optional.empty();
        }
        return Optional.of(args[flagIndex + 1]);
    }

    private static Optional<GameSide> parseSide(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "client" -> Optional.of(GameSide.CLIENT);
            case "server" -> Optional.of(GameSide.SERVER);
            default -> Optional.empty();
        };
    }

    private static void reportIssues(IssueCollector issues) {
        for (var issue : issues.issues()) {
            System.out.println("[omniloader] " + issue.describe());
        }
    }
}
