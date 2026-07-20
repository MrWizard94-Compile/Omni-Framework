package org.omniframework.api;

/**
 * The entrypoint for code that must run only on the game client.
 *
 * <p>Declared under the {@code "client"} phase. The loader never constructs this on a dedicated
 * server, so a class referencing rendering types is never loaded there — which is what keeps a
 * single jar working on both sides without the author guarding every call.
 *
 * <p>That guarantee is why a separate entrypoint exists rather than a side check inside
 * {@link ModInitializer#onInitialize()}: branching still resolves the class, and resolving it on
 * a server is exactly the {@code NoClassDefFoundError} the split avoids.
 */
@FunctionalInterface
public interface ClientModInitializer {

    /** Runs once at startup, client only, after {@link ModInitializer#onInitialize()}. */
    void onInitializeClient();
}
