package org.omniframework.api;

/**
 * The entrypoint a native Omni mod implements to run code at startup.
 *
 * <p>Declared in {@code omni.mod.json} under the {@code "main"} phase:
 *
 * <pre>{@code
 * "entrypoints": { "main": ["net.example.ExampleMod"] }
 * }</pre>
 *
 * <p>The loader constructs the class and calls {@link #onInitialize()} once, in the load order
 * resolution established — a mod initialises after everything it depends on.
 *
 * <h2>What this method is for, and what it is not</h2>
 *
 * <p>It runs on the main thread during startup, before the game is playable. Registration belongs
 * here. Long or blocking work does not: every millisecond spent here is a millisecond the user
 * watches a loading screen, and an exception thrown here fails the whole phase and names your mod
 * as the cause.
 *
 * <p>Implementations need a public no-argument constructor. That constructor should do nothing
 * but exist — the loader may construct an entrypoint before deciding to run it, so side effects
 * there happen at a moment the loader does not promise anything about.
 */
@FunctionalInterface
public interface ModInitializer {

    /**
     * Runs once at startup, on both client and dedicated server.
     *
     * <p>For code that must only run on one side, declare a separate entrypoint under the
     * {@code "client"} or {@code "server"} phase rather than branching here. Those are
     * instantiated lazily, so a client-only class is never even loaded on a server — branching
     * inside this method would still resolve the class and defeat that.
     */
    void onInitialize();
}
