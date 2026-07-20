package net.fabricmc.loader.api;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;

/**
 * The hosted-mod view of the loader — the single most referenced type in the ecosystem
 * (868 references across 347 mods, measured 2026-07-20).
 *
 * <h2>It reports the WHOLE pack, not the Fabric share</h2>
 *
 * <p>{@link #getAllMods()} and {@link #isModLoaded(String)} answer about every mod OmniLoader
 * resolved — native, Fabric-family, NeoForge-family alike. That is required by ADR-0020 and it is
 * not a detail: a Fabric mod checking {@code isModLoaded("jei")} in a mixed pack must get
 * {@code true} when JEI is present as a NeoForge mod, or it will disable its own integration and
 * the user will see a feature silently missing with nothing in the log.
 *
 * <p>The instance is installed by the compat layer before any hosted entrypoint runs.
 */
public interface FabricLoader {

    /**
     * The loader.
     *
     * @throws IllegalStateException if called before the compat layer installed one — which for
     *     a mod means a static initialiser ran earlier than the loader promises, and saying so
     *     beats returning null for the caller to trip over later
     */
    static FabricLoader getInstance() {
        return org.omniframework.loader.compat.fabric.impl.FabricLoaderHolder.get();
    }

    /** Instantiates every entrypoint declared for a phase. */
    <T> List<T> getEntrypoints(String key, Class<T> type);

    /** As {@link #getEntrypoints}, but each paired with the mod that declared it. */
    <T> List<EntrypointContainer<T>> getEntrypointContainers(String key, Class<T> type);

    /**
     * Runs every entrypoint for a phase, reporting failures per mod.
     *
     * <p>One mod throwing does not stop the rest — the same contract as the native dispatcher,
     * because a pack with three broken mods should report three, not send the user round a
     * fix-and-relaunch loop.
     */
    <T> void invokeEntrypoints(String key, Class<T> type, Consumer<? super T> invoker);

    /** The cross-mod object channel. */
    ObjectShare getObjectShare();

    /** Runtime name translation. */
    MappingResolver getMappingResolver();

    /** A mod by id, from the whole pack. */
    Optional<ModContainer> getModContainer(String id);

    /** Every loaded mod, from every ecosystem. */
    Collection<ModContainer> getAllMods();

    /** Whether a mod is loaded, from any ecosystem. */
    boolean isModLoaded(String id);

    /** Whether this is a development environment. */
    boolean isDevelopmentEnvironment();

    /** Which side is running. */
    EnvType getEnvironmentType();

    /** The game version as the game reports it. */
    String getRawGameVersion();

    /** The running game object, once it exists. */
    Object getGameInstance();

    /** The instance directory. */
    Path getGameDir();

    /** The config directory. */
    Path getConfigDir();

    /** The launch arguments. */
    String[] getLaunchArguments(boolean sanitize);

    /**
     * The instance directory as a {@link File}.
     *
     * @deprecated {@link #getGameDir()} is correct; kept because hosted bytecode calls it.
     */
    @Deprecated
    default File getGameDirectory() {
        return getGameDir().toFile();
    }

    /**
     * The config directory as a {@link File}.
     *
     * @deprecated {@link #getConfigDir()} is correct; kept because hosted bytecode calls it.
     */
    @Deprecated
    default File getConfigDirectory() {
        return getConfigDir().toFile();
    }
}
