package net.fabricmc.loader.api.metadata;

import net.fabricmc.api.EnvType;

/** Which sides a mod loads on. */
public enum ModEnvironment {
    /** Client only. */
    CLIENT,
    /** Dedicated server only. */
    SERVER,
    /** Both. */
    UNIVERSAL;

    /** Whether this environment includes the given side. */
    public boolean matches(EnvType type) {
        return switch (this) {
            case UNIVERSAL -> true;
            case CLIENT -> type == EnvType.CLIENT;
            case SERVER -> type == EnvType.SERVER;
        };
    }
}
