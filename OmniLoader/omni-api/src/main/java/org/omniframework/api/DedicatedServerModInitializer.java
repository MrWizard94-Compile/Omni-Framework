package org.omniframework.api;

/**
 * The entrypoint for code that must run only on a dedicated server.
 *
 * <p>Declared under the {@code "server"} phase. Never constructed on a client, for the same
 * reason {@link ClientModInitializer} is never constructed on a server.
 *
 * <p>Note "dedicated": a single-player world runs an integrated server inside the client, and
 * this entrypoint does <em>not</em> fire there. Code that must run for any server, integrated or
 * not, belongs in {@link ModInitializer} — the distinction catches people out, so the name is
 * explicit rather than convenient.
 */
@FunctionalInterface
public interface DedicatedServerModInitializer {

    /** Runs once at startup, dedicated server only, after {@link ModInitializer#onInitialize()}. */
    void onInitializeServer();
}
