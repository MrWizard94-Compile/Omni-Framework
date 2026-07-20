package org.omniframework.loader.compat.fabric.impl;

import java.util.Objects;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Holds the {@link FabricLoader} instance the compat layer installs.
 *
 * <p>Separate from the interface so the implementation type never becomes part of the surface
 * hosted mods see, and so a test can install a fake without a booting game.
 */
public final class FabricLoaderHolder {

    private static volatile FabricLoader instance;

    private FabricLoaderHolder() {}

    /**
     * The installed loader.
     *
     * @throws IllegalStateException before the layer installs one
     */
    public static FabricLoader get() {
        FabricLoader current = instance;
        if (current == null) {
            throw new IllegalStateException(
                    "FabricLoader.getInstance() was called before the Fabric compat layer "
                            + "installed one. The layer installs it before any hosted entrypoint "
                            + "runs, so this almost certainly came from a static initialiser "
                            + "that executed earlier than that.");
        }
        return current;
    }

    /** Whether an instance is installed. */
    public static boolean isInstalled() {
        return instance != null;
    }

    /** Installs the instance. Called by the layer, and by tests supplying a fake. */
    public static void install(FabricLoader loader) {
        instance = Objects.requireNonNull(loader, "loader");
    }

    /** Clears the instance. For tests, which must not leak state between cases. */
    public static void reset() {
        instance = null;
    }
}
