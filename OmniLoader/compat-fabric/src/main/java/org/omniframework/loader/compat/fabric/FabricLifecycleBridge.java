package org.omniframework.loader.compat.fabric;

import java.util.List;
import java.util.Optional;
import org.omniframework.loader.compat.LifecycleBridge;

/**
 * Maps Fabric entrypoint phases onto Omni phases (CMP-2 contract 7).
 *
 * <p>{@code main} / {@code client} / {@code server} map 1:1. Fabric's rarer phases
 * ({@code preLaunch}, …) are declared unmapped rather than folded into {@code main}.
 */
public final class FabricLifecycleBridge implements LifecycleBridge {

    @Override
    public List<String> guestPhases() {
        return List.of("main", "client", "server", "preLaunch");
    }

    @Override
    public Optional<String> omniPhaseFor(String guestPhase) {
        return switch (guestPhase) {
            case "main", "client", "server" -> Optional.of(guestPhase);
            default -> Optional.empty();
        };
    }
}
