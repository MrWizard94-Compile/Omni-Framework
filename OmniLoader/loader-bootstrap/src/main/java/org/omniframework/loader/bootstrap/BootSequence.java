package org.omniframework.loader.bootstrap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.omniframework.api.ModInitializer;
import org.omniframework.loader.core.ClassTransformer;
import org.omniframework.loader.core.GameLauncher;
import org.omniframework.loader.core.OmniTransformingClassLoader;
import org.omniframework.loader.core.Partition;
import org.omniframework.loader.core.PartitionRegistry;
import org.omniframework.loader.core.entrypoint.EntrypointDispatcher;
import org.omniframework.loader.core.resource.JarResourceRoot;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.model.ModMetadata;
import org.omniframework.loader.model.ModVersion;
import org.omniframework.loader.provider.GameProvider;
import org.omniframework.loader.provider.GameSide;
import org.omniframework.loader.provider.LocatedGame;
import org.omniframework.loader.resolve.ModCandidate;
import org.omniframework.loader.resolve.ResolutionResult;
import org.omniframework.loader.resolve.Resolver;

/**
 * Runs the whole boot: locate the game, discover mods, resolve them, load them, fire their
 * entrypoints, and hand off to the game (ARCHITECTURE §3, B1).
 *
 * <p>This is the first time bricks 10–14 run <em>composed</em> rather than each in its own test.
 * Every stage has been proven in isolation; this proves they fit together, on one shared
 * classloader, in the order the architecture fixed.
 *
 * <h2>Scope held on purpose</h2>
 *
 * <p>Only <b>native</b> mods load here. Jars awaiting a compat layer are counted and reported
 * (see {@link DiscoveredMods}), never silently dropped, but a Fabric or NeoForge jar does not
 * load until its layer's claimer and transform passes exist (B2/B3). And the transform and Mixin
 * stages are absent because native mods need neither — they are already in the runtime namespace
 * and a native test mod declares no mixins. Wiring those stages here as no-ops would be dead code
 * pretending to be composition; they arrive with the ecosystem that exercises them.
 *
 * <h2>The platform provides two "mods" that are not mods</h2>
 *
 * <p>Native mods declare {@code depends: { omniloader, minecraft }}. Neither is a jar in the mods
 * folder — the loader and the game are the environment. So the resolver is given synthetic
 * candidates for both, or every real mod would fail resolution on a missing dependency (exactly
 * the failure the brick-14 integration test caught). Those synthetic candidates satisfy
 * dependencies and take part in ordering, but never become mod partitions or receive entrypoint
 * dispatch — they are already present as the game and the loader.
 */
public final class BootSequence {

    /**
     * The loader version presented to the resolver as the {@code omniloader} dependency target.
     * Matches the Gradle project version; a mod's {@code depends: { omniloader: ">=0.1" }} is
     * satisfied by it.
     */
    public static final String LOADER_VERSION = "0.1.0";

    private final List<GameProvider> providers;

    /** Builds a sequence over the given providers (ServiceLoader-discovered in a real boot). */
    public BootSequence(List<GameProvider> providers) {
        this.providers = List.copyOf(Objects.requireNonNull(providers, "providers"));
    }

    /**
     * Runs the boot.
     *
     * <p>Returns empty when the boot could not <em>begin</em> — no provider claimed the directory,
     * the game could not be located, or resolution failed — with the reasons in {@code issues}.
     * When it returns a value, the game's {@code main} has already run to completion (the call
     * blocks on it, as a launcher process must).
     *
     * @throws org.omniframework.loader.core.GameLaunchException if the game itself fails to start
     *     or throws — a real launch failure, distinct from a boot that could not begin
     */
    public Optional<BootResult> run(BootConfig config, IssueCollector issues) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(issues, "issues");

        Optional<LocatedGame> located = locate(config, issues);
        if (located.isEmpty()) {
            return Optional.empty();
        }
        LocatedGame game = located.get();

        DiscoveredMods discovered = ModDiscovery.discover(config.modsDirectory(), issues);

        Optional<List<ModCandidate>> ordered = resolve(discovered, game, config.side(), issues);
        if (ordered.isEmpty()) {
            return Optional.empty();
        }

        // Only jars that came from discovery become partitions and get entrypoints; the synthetic
        // platform candidates are filtered out by id — they are the game and the loader, already
        // present.
        Set<String> realModIds =
                discovered.nativeMods().stream().map(ModCandidate::id).collect(Collectors.toSet());
        List<ModCandidate> realMods =
                ordered.get().stream().filter(candidate -> realModIds.contains(candidate.id())).toList();

        // The registry holds open jar handles and the loader holds open jars too; both must be
        // released once the game's main returns. In a single-shot real launch the process would
        // exit anyway, but leaving them open leaks a handle per jar across repeated boots and, on
        // Windows, pins the files so the instance directory cannot be cleaned. They stay open
        // through launch — the game loads classes from them the whole time it runs — and are
        // closed only after it returns.
        PartitionRegistry registry = buildPartitions(game, config.libraries(), realMods);
        try {
            GameLauncher launcher = new GameLauncher(registry, ClassTransformer.identity());
            OmniTransformingClassLoader loader = launcher.newClassLoader();
            try {
                dispatchEntrypoints(realMods, loader);
                launcher.launch(loader, game, config.launchArguments());
            } finally {
                closeQuietly(loader);
            }
            return Optional.of(new BootResult(
                    game.version(),
                    realMods.stream().map(ModCandidate::id).toList(),
                    discovered.unclaimedCount()));
        } finally {
            closeQuietly(registry);
        }
    }

    /** Closes a resource, swallowing a close-time failure that must not mask the boot's result. */
    private static void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception ignored) {
            // A failure releasing handles after the game has already run is not the boot's
            // outcome; the process is on its way down or on to the next launch regardless.
        }
    }

    /**
     * The game to boot: the one the launcher handed over if it did, else a provider's discovery.
     *
     * <p>A launcher-provided game skips provider selection entirely — the launcher resolved the
     * stack and knows the jars, so rediscovering them would only add a way to disagree with what
     * the launcher already decided.
     */
    private Optional<LocatedGame> locate(BootConfig config, IssueCollector issues) {
        if (config.providedGame().isPresent()) {
            return config.providedGame();
        }
        Optional<GameProvider> provider =
                GameLauncher.selectProvider(providers, config.gameDirectory(), config.side(), issues);
        return provider.flatMap(chosen -> chosen.locate(config.gameDirectory(), config.side(), issues));
    }

    /** Resolves native mods against the platform, returning the load order or empty on failure. */
    private Optional<List<ModCandidate>> resolve(
            DiscoveredMods discovered, LocatedGame game, GameSide side, IssueCollector issues) {
        List<ModCandidate> candidates = new ArrayList<>(discovered.nativeMods());
        candidates.addAll(platformCandidates(game));

        ResolutionResult resolution = new Resolver().resolve(candidates, side);
        if (!resolution.succeeded()) {
            resolution.errors().forEach(issues::add);
            return Optional.empty();
        }
        return Optional.of(resolution.loadOrder());
    }

    /** The game and the loader, as candidates a mod may depend on. */
    private static List<ModCandidate> platformCandidates(LocatedGame game) {
        Path gameJar = game.gameJars().get(0);
        ModCandidate minecraft = ModCandidate.topLevel(
                ModMetadata.builder("minecraft", ModVersion.parse(game.version()))
                        .name("Minecraft").build(),
                gameJar);
        ModCandidate omniloader = ModCandidate.topLevel(
                ModMetadata.builder("omniloader", ModVersion.parse(LOADER_VERSION))
                        .name("OmniLoader").build(),
                gameJar);
        return List.of(minecraft, omniloader);
    }

    /** Game jars, the game's libraries, then one per-mod partition per resolved native mod (LDR-4). */
    private static PartitionRegistry buildPartitions(
            LocatedGame game, List<Path> libraries, List<ModCandidate> mods) {
        try {
            PartitionRegistry registry = GameLauncher.partitionsFor(game, libraries);
            for (ModCandidate mod : mods) {
                registry.add(Partition.mod(mod.id(), "omni", JarResourceRoot.open(mod.source())));
            }
            return registry;
        } catch (IOException e) {
            // A jar that resolved but will not open now is a real fault, not "no mods"; surfacing
            // it as an empty boot would send the user hunting for a phantom.
            throw new UncheckedIOException("cannot open a resolved mod or game jar", e);
        }
    }

    /**
     * Constructs and runs every mod's {@code main} entrypoint, on the shared loader.
     *
     * <p>Registration follows resolution order, and the dispatcher preserves it: a mod initialises
     * after everything it depends on. A mod that throws is reported and does not stop the others,
     * but the phase as a whole then fails (the dispatcher raises its phase exception), because a
     * half-initialised mod set is not a game worth launching.
     */
    private static void dispatchEntrypoints(List<ModCandidate> mods, ClassLoader loader) {
        EntrypointDispatcher dispatcher = new EntrypointDispatcher();
        for (ModCandidate mod : mods) {
            dispatcher.register(mod.metadata(), loader);
        }
        dispatcher.run("main", ModInitializer.class,
                (metadata, initializer) -> initializer.onInitialize());
    }
}
