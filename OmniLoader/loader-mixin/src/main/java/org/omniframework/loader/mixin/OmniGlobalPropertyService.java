package org.omniframework.loader.mixin;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IPropertyKey;

/**
 * The blackboard Mixin uses to pass state between its own subsystems.
 *
 * <p>Mixin stores things like the active environment and its own initialisation flags here. It is
 * not configuration and mods never see it; the loader's job is only to provide somewhere
 * consistent to put it.
 *
 * <p>Discovered by {@code ServiceLoader}, so it must have a public no-argument constructor.
 *
 * <h2>Keys compare by name, deliberately</h2>
 *
 * <p>Mixin resolves a key by string and then hands the resulting object back later. If keys
 * compared by identity, two {@code resolveKey("x")} calls would produce entries that never find
 * each other, and Mixin would silently re-initialise state it believed it had already stored —
 * a bug that looks like a phase running twice for no reason.
 */
public final class OmniGlobalPropertyService implements IGlobalPropertyService {

    private final Map<IPropertyKey, Object> properties = new ConcurrentHashMap<>();

    /** Required by {@code ServiceLoader}. */
    public OmniGlobalPropertyService() {
        // No state to prepare.
    }

    @Override
    public IPropertyKey resolveKey(String name) {
        return new Key(Objects.requireNonNull(name, "name"));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Unchecked by necessity, not by choice. Mixin's SPI declares {@code <T> T
     * getProperty(IPropertyKey)} with no bound relating {@code T} to the key, so the stored
     * {@code Object} cannot be checked against it — there is nothing at runtime that says what
     * type a given key holds. Storing a typed map instead would not help: the interface hands us
     * an untyped key and expects an inferred return.
     *
     * <p>Not removable while ADR-0009 pins this Mixin line; a version bump that gives the SPI a
     * typed key is the only thing that would change it, and that bump requires its own ADR.
     */
    @SuppressWarnings("unchecked")
    @Override
    public <T> T getProperty(IPropertyKey key) {
        return (T) properties.get(key);
    }

    @Override
    public void setProperty(IPropertyKey key, Object value) {
        if (value == null) {
            // ConcurrentHashMap forbids null values; removing is the honest equivalent and
            // keeps a later get() returning null as the caller expects.
            properties.remove(key);
            return;
        }
        properties.put(key, value);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Unchecked for the same reason as {@link #getProperty(IPropertyKey)}, and removable on
     * the same condition. The default value does not help: it constrains what the caller wanted,
     * not what the map actually holds.
     */
    @SuppressWarnings("unchecked")
    @Override
    public <T> T getProperty(IPropertyKey key, T defaultValue) {
        Object value = properties.get(key);
        return value == null ? defaultValue : (T) value;
    }

    @Override
    public String getPropertyString(IPropertyKey key, String defaultValue) {
        Object value = properties.get(key);
        return value == null ? defaultValue : value.toString();
    }

    /** A name-comparable property key — see the class note on why identity would break. */
    record Key(String name) implements IPropertyKey {

        Key {
            Objects.requireNonNull(name, "name");
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
