package net.fabricmc.loader.api;

import java.util.Map;
import java.util.function.BiConsumer;

/**
 * A process-wide key/value channel mods use to hand each other objects without a compile-time
 * dependency.
 *
 * <p>Keys are namespaced by mod id upstream ({@code "modid:key"}). That convention is not
 * enforced here for the same reason it is not enforced there — a mod that ignores it is
 * unfortunate, not broken — but unnamespaced keys are the obvious collision source.
 */
public interface ObjectShare {

    /** The current value for a key, or null. */
    Object get(String key);

    /** Sets a value, returning the previous one. */
    Object put(String key, Object value);

    /** Sets a value only if absent, returning the existing one or null. */
    Object putIfAbsent(String key, Object value);

    /** Removes a key, returning its value. */
    Object remove(String key);

    /**
     * Requests notification when a key is set, firing immediately if it already is.
     *
     * <p>The immediate-fire behaviour matters: without it every consumer would need its own
     * race-handling for "the producer ran first", and most would get it wrong.
     */
    void whenAvailable(String key, BiConsumer<String, Object> consumer);

    /** A snapshot of everything currently shared. */
    Map<String, Object> getMap();
}
