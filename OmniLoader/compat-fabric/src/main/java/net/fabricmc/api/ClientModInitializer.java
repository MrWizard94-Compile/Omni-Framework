package net.fabricmc.api;

/** A hosted mod's client-only entrypoint. Never constructed on a dedicated server. */
@FunctionalInterface
public interface ClientModInitializer {
    /** Runs once at startup, client only. */
    void onInitializeClient();
}
