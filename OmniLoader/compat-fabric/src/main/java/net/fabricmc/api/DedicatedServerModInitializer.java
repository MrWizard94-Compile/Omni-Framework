package net.fabricmc.api;

/** A hosted mod's dedicated-server entrypoint. Does not fire for a single-player integrated server. */
@FunctionalInterface
public interface DedicatedServerModInitializer {
    /** Runs once at startup, dedicated server only. */
    void onInitializeServer();
}
