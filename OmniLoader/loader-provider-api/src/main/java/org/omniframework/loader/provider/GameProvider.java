package org.omniframework.loader.provider;

import java.nio.file.Path;
import java.util.List;
import org.omniframework.loader.diag.IssueCollector;

/**
 * How the loader is told where the game is — without knowing what the game <em>is</em>
 * (LDR-1: "the loader core MUST ship zero gameplay API; game-specific logic MUST sit behind a
 * provider SPI").
 *
 * <p>This interface is the entire reason `loader-core` contains no reference to Minecraft. The
 * core knows it must locate <em>a</em> program, put <em>some</em> jars on a classloader, and
 * call <em>a</em> main class; a provider fills in which. That separation is what makes the
 * version-agnosticism requirement (TGT-2) real rather than aspirational: following the game to
 * a new version is provider data, not core surgery.
 *
 * <p>Implementations are discovered with {@link java.util.ServiceLoader}, so a provider is added
 * by dropping a jar on the launch classpath — the mechanism (fabric-loader's {@code GameProvider})
 * that lets the same loader serve a dedicated server, a client, and a test harness.
 *
 * <p>Implementations must be stateless until {@link #locate} is called, and must not touch the
 * filesystem in {@link #id()} or {@link #displayName()} — the launcher calls those to choose
 * between providers before committing to one.
 */
public interface GameProvider {

    /** Stable identifier, e.g. {@code "minecraft"}. Used to select a provider explicitly. */
    String id();

    /** Human-readable name for diagnostics and the boot UI. */
    String displayName();

    /**
     * Whether this provider believes it can serve the given installation directory.
     *
     * <p>Cheap checks only — a file existing, a name matching. Expensive validation belongs in
     * {@link #locate}, which is allowed to fail with detail.
     */
    boolean canHandle(Path gameDirectory, GameSide side);

    /**
     * Locates everything needed to launch, or reports why it cannot.
     *
     * <p>Failures are recorded as typed issues rather than thrown, because "the game jar is
     * missing" and "the version is unrecognised" are ordinary user-fixable conditions that the
     * boot UI must render as a failure tree (LDR-10) — not stack traces.
     *
     * @param gameDirectory the instance's game directory
     * @param side which program is being launched
     * @param issues collects any problems; an empty return with no issues means "nothing found"
     * @return the located game, or empty when it could not be located
     */
    java.util.Optional<LocatedGame> locate(Path gameDirectory, GameSide side, IssueCollector issues);

    /**
     * Paths this provider needs on the classloader before the game itself — libraries the game
     * links against.
     *
     * <p>Separate from {@link LocatedGame#gameJars()} because the two are treated differently
     * downstream: game jars are transformed and partitioned as the game, libraries are not
     * candidates for mod transformation at all.
     */
    default List<Path> additionalLibraries() {
        return List.of();
    }
}
