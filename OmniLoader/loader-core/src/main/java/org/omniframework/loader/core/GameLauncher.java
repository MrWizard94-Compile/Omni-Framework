package org.omniframework.loader.core;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import org.omniframework.loader.core.resource.JarResourceRoot;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.diag.OmniIssue;
import org.omniframework.loader.provider.GameProvider;
import org.omniframework.loader.provider.GameSide;
import org.omniframework.loader.provider.LocatedGame;

/**
 * Boots a located game on the transforming class loader (ARCHITECTURE §3).
 *
 * <p>This is the last few steps of the boot sequence: partitions assembled, loader built, main
 * class invoked. Discovery, resolution, and transformation slot in ahead of it as their modules
 * land; what is here already runs a game, which means the classloading path can be exercised and
 * trusted before anything rewrites a byte.
 *
 * <p>The game's {@code main} is invoked on a thread whose context class loader is the
 * transforming loader. That detail is not incidental: libraries the game uses resolve resources
 * and service providers through the context loader, and getting it wrong produces failures far
 * from their cause — a config file "missing" that is plainly present on disk.
 */
public final class GameLauncher {

    private final PartitionRegistry partitions;
    private final ClassTransformer transformer;

    /** Creates a launcher over prepared partitions. */
    public GameLauncher(PartitionRegistry partitions, ClassTransformer transformer) {
        this.partitions = Objects.requireNonNull(partitions, "partitions");
        this.transformer = Objects.requireNonNull(transformer, "transformer");
    }

    /** Every {@link GameProvider} on the launch classpath, in service-loader order. */
    public static List<GameProvider> discoverProviders() {
        return ServiceLoader.load(GameProvider.class).stream().map(ServiceLoader.Provider::get).toList();
    }

    /**
     * Picks the first provider that claims it can handle the directory.
     *
     * @param issues records a typed issue when nothing claims it
     */
    public static Optional<GameProvider> selectProvider(
            List<GameProvider> providers, Path gameDirectory, GameSide side, IssueCollector issues) {
        for (GameProvider provider : providers) {
            if (provider.canHandle(gameDirectory, side)) {
                return Optional.of(provider);
            }
        }
        issues.add(OmniIssue.error("boot.no-provider")
                .at(gameDirectory.toString())
                .with(side.name().toLowerCase(java.util.Locale.ROOT))
                .remedy("No game provider recognised this directory. Check that the instance "
                        + "has been installed and that the game files are present.")
                .build());
        return Optional.empty();
    }

    /**
     * Builds partitions for a located game and its libraries.
     *
     * <p>Order is game first, then libraries, which is what makes a library shipping a class the
     * game also defines lose rather than shadow it.
     *
     * @throws IOException if a jar cannot be opened
     */
    public static PartitionRegistry partitionsFor(LocatedGame game, List<Path> libraries)
            throws IOException {
        PartitionRegistry registry = new PartitionRegistry();
        int index = 0;
        for (Path jar : game.gameJars()) {
            JarResourceRoot root = JarResourceRoot.open(jar);
            // Multi-jar games need distinct partition ids; the first keeps the plain name so
            // diagnostics read naturally in the overwhelmingly common single-jar case.
            Partition partition = index == 0
                    ? Partition.game(root)
                    : new Partition("minecraft:" + index, Partition.Kind.GAME, "game", root);
            registry.add(partition);
            index++;
        }
        for (Path library : libraries) {
            registry.add(Partition.library(JarResourceRoot.open(library)));
        }
        return registry;
    }

    /**
     * Invokes the game's main class, blocking until it returns.
     *
     * @throws GameLaunchException if the main class or method is missing, or the game throws
     */
    public void launch(LocatedGame game, List<String> arguments) {
        Objects.requireNonNull(game, "game");
        Objects.requireNonNull(arguments, "arguments");
        OmniTransformingClassLoader loader = new OmniTransformingClassLoader(partitions, transformer);

        Thread current = Thread.currentThread();
        ClassLoader previous = current.getContextClassLoader();
        current.setContextClassLoader(loader);
        try {
            Class<?> mainClass = Class.forName(game.mainClass(), false, loader);
            Method main = mainClass.getMethod("main", String[].class);
            if (!Modifier.isStatic(main.getModifiers())) {
                throw new GameLaunchException(
                        game.mainClass() + ".main is not static, so it cannot be an entry point", null);
            }
            main.invoke(null, (Object) arguments.toArray(new String[0]));
        } catch (ClassNotFoundException missing) {
            throw new GameLaunchException(
                    "the game's main class " + game.mainClass() + " was not found in any game jar",
                    missing);
        } catch (NoSuchMethodException missing) {
            throw new GameLaunchException(
                    game.mainClass() + " has no main(String[]) method", missing);
        } catch (IllegalAccessException inaccessible) {
            throw new GameLaunchException(
                    game.mainClass() + ".main is not accessible", inaccessible);
        } catch (InvocationTargetException thrown) {
            // The game itself failed. Unwrap so the user sees the game's exception, not a
            // reflection wrapper around it.
            Throwable cause = thrown.getCause() == null ? thrown : thrown.getCause();
            throw new GameLaunchException("the game exited with an error", cause);
        } finally {
            current.setContextClassLoader(previous);
        }
    }

    /** The loader this launcher would use, for callers that need it before launching. */
    public OmniTransformingClassLoader newClassLoader() {
        return new OmniTransformingClassLoader(partitions, transformer);
    }
}
