package org.omniframework.loader.bootstrap;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.omniframework.loader.core.GameLauncher;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.provider.GameSide;

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
 *   --                    everything after is passed verbatim to the game's main
 * }</pre>
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
        Path mods = modsDir != null ? modsDir : gameDir.resolve("mods");
        return Optional.of(new BootConfig(gameDir, mods, side, launchArgs));
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
