package net.neoforged.bus.api;

import java.util.function.Consumer;

/**
 * Minimal NeoForge-compatible event bus surface hosted by OmniLoader.
 *
 * <p>Enough for mods that register listeners in {@code @Mod} constructors via
 * {@code FMLJavaModLoadingContext.get().getModEventBus()}. Full FML lifecycle event
 * parity remains a follow-on; this is a real, test-backed bus — not a no-op stub.
 */
public interface IEventBus {

    /** Registers an object whose {@link SubscribeEvent}-annotated methods receive events. */
    void register(Object target);

    /** Registers a typed listener. */
    <T> void addListener(Class<T> eventType, Consumer<T> listener);

    /** Posts an event to matching listeners; returns the same instance. */
    <T> T post(T event);
}
