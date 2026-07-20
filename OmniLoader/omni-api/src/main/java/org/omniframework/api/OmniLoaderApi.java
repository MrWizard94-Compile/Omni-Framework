package org.omniframework.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * What a mod can ask the loader at runtime.
 *
 * <p>The other half of {@code omni-api}: {@link ModInitializer} is how the loader calls a mod,
 * and this is how a mod calls back. Deliberately small — every method here is a promise the
 * loader must keep across versions, and the corpus is full of loader APIs that grew until they
 * could not be changed.
 *
 * <h2>Why this is an interface with a holder rather than a static singleton</h2>
 *
 * <p>A static {@code getInstance()} returning a concrete class is what most loaders ship, and it
 * makes mods untestable: there is no way to run a mod's initialiser against a fake loader without
 * a live game underneath it. An interface the loader installs means a mod's tests can supply
 * their own, which is the difference between mod authors writing tests and not.
 *
 * <p>The implementation lives in the loader, never here. This module has no dependencies by
 * design (see the build file) — it is the contract, not the machinery.
 */
public interface OmniLoaderApi {

    /** The loader's own version, for a mod that needs to vary behaviour across releases. */
    String loaderVersion();

    /** Whether a mod with this id is loaded. The common question, so it gets the short name. */
    boolean isModLoaded(String modId);

    /** The version of a loaded mod, if it is loaded. */
    Optional<String> modVersion(String modId);

    /** Every loaded mod id, in load order. */
    List<String> loadedMods();

    /**
     * Which side this process is.
     *
     * <p>Prefer side-specific entrypoints over branching on this: an entrypoint the loader never
     * constructs is a class the JVM never loads, whereas a branch still resolves both paths.
     * This exists for the cases where the decision genuinely is runtime data.
     */
    Side side();

    /** Whether the game is running from a development environment rather than a packaged install. */
    boolean isDevelopment();

    /**
     * The directory a mod should write its own files into.
     *
     * <p>Created on demand and unique per mod, so two mods cannot collide by both choosing
     * {@code config/settings.json}.
     */
    Path configDirectory(String modId);

    /** Which program is running. */
    enum Side {
        /** The game client. */
        CLIENT,
        /** A dedicated server. */
        SERVER
    }

    /**
     * Holds the instance the loader installs.
     *
     * <p>Separated from the interface so that a test can install a fake, and so the loader's
     * implementation type never becomes part of the public surface.
     */
    final class Holder {

        private static volatile OmniLoaderApi instance;

        private Holder() {}

        /**
         * The installed loader API.
         *
         * @throws IllegalStateException if called before the loader installed one — which for a
         *     mod means calling it from a static initialiser that ran too early, and saying so
         *     is more useful than returning null for the caller to trip over later
         */
        public static OmniLoaderApi get() {
            OmniLoaderApi current = instance;
            if (current == null) {
                throw new IllegalStateException(
                        "the Omni loader API is not available yet — it is installed before mod "
                                + "entrypoints run, so this was almost certainly called from a "
                                + "static initialiser that executed earlier than that");
            }
            return current;
        }

        /** Whether an implementation has been installed. */
        public static boolean isAvailable() {
            return instance != null;
        }

        /** Installs the implementation. Called by the loader, and by tests supplying a fake. */
        public static void install(OmniLoaderApi api) {
            instance = api;
        }
    }
}
