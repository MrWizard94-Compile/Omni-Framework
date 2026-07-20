package org.omniframework.loader.mixin;

import org.spongepowered.asm.service.IMixinServiceBootstrap;

/**
 * Tells Mixin that OmniLoader's service exists, before the service class is loaded.
 *
 * <p>Mixin discovers bootstraps first and services second, so that a host can prepare its
 * environment — or decline — without the service class being initialised. OmniLoader needs no
 * preparation here: {@link MixinServiceOmni#install} has already run by the time Mixin
 * bootstraps, and doing work in this class would mean touching the service before Mixin has
 * decided to use it.
 */
public final class MixinServiceOmniBootstrap implements IMixinServiceBootstrap {

    /** Required by {@code ServiceLoader}. */
    public MixinServiceOmniBootstrap() {
        // Nothing to prepare; see the class note.
    }

    @Override
    public String getName() {
        return MixinServiceOmni.NAME;
    }

    @Override
    public String getServiceClassName() {
        return MixinServiceOmni.class.getName();
    }

    @Override
    public void bootstrap() {
        // Deliberately empty. Every host-side decision is made by the loader before Mixin
        // starts, so there is nothing to do at this point that would not be doing it twice.
    }
}
