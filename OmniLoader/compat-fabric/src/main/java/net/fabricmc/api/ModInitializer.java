package net.fabricmc.api;

/** A hosted mod's main entrypoint, run on both sides. */
@FunctionalInterface
public interface ModInitializer {
    /** Runs once at startup, in the load order resolution established. */
    void onInitialize();
}
