package net.neoforged.fml.event.lifecycle;

/** Hosted FML client setup event. */
public final class FMLClientSetupEvent {
    private final String phase = "client";

    public String getPhase() {
        return phase;
    }
}
