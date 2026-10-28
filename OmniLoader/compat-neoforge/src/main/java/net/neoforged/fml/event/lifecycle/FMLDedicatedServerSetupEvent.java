package net.neoforged.fml.event.lifecycle;

/** Hosted FML dedicated server setup event. */
public final class FMLDedicatedServerSetupEvent {
    private final String phase = "server";

    public String getPhase() {
        return phase;
    }
}
