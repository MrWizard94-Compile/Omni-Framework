package org.omniframework.loader.compat.neoforge.bus;

import java.util.Objects;

/** Fired after a NeoForge {@code @Mod} instance is constructed on the Omni host path. */
public final class ModConstructedEvent {

    private final String modId;

    public ModConstructedEvent(String modId) {
        this.modId = Objects.requireNonNull(modId, "modId");
    }

    public String getModId() {
        return modId;
    }
}
