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
