package net.fabricmc.loader.api.entrypoint;

import net.fabricmc.loader.api.ModContainer;

/**
 * An entrypoint instance together with the mod that declared it.
 *
 * <p>The pairing is the point: code iterating entrypoints usually needs to attribute what it
 * finds, and an instance alone cannot say which mod to blame when it misbehaves.
 *
 * @param <T> the entrypoint type
 */
public interface EntrypointContainer<T> {

    /** The instance, created on first access. */
    T getEntrypoint();

    /** The mod that declared it. */
    ModContainer getProvider();

    /** A description of where it was declared, for diagnostics. */
    default String getDefinition() {
        return getProvider().getMetadata().getId();
    }
}
