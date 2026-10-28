package net.neoforged.fml.event.lifecycle;

/** Hosted FML common setup event (fires after all @Mod constructors). */
public final class FMLCommonSetupEvent {
    private final String phase = "common";

    public String getPhase() {
        return phase;
    }
}
