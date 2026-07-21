package org.omniframework.loader.compat.neoforge;

import java.util.List;
import java.util.Optional;
import org.omniframework.loader.compat.CompatLayer;
import org.omniframework.loader.compat.EcosystemClaimer;
import org.omniframework.loader.compat.LifecycleBridge;
import org.omniframework.loader.compat.MetadataImporter;

/**
 * NeoForge-family hosting layer for the Phase 4 exit path.
 *
 * <p><b>What this brick delivers:</b> claim, TOML→canonical import, {@code @Mod} class discovery,
 * classpath partition, and no-arg construction of the mod class via the shared entrypoint
 * dispatcher.
 *
 * <p><b>What it deliberately does not deliver:</b> full FML language providers, event bus wiring,
 * access transformers, or intermediary/SRG remapping. Those are the Sinytra-scale work tracked
 * as the research tail of B3 — not stubbed here, just out of the Phase 4 fixture exit criterion
 * (a NeoForge-shaped jar loads <em>alongside</em> native and Fabric fixtures).
 */
public final class NeoForgeCompatLayer implements CompatLayer {

    public static final String ID = "compat-neoforge";
    public static final String VERSION = "0.1.0";

    private final EcosystemClaimer claimer = new NeoForgeEcosystemClaimer();
    private final NeoForgeMetadataImporter importer = new NeoForgeMetadataImporter();
    private final LifecycleBridge lifecycle = new LifecycleBridge() {
        @Override
        public List<String> guestPhases() {
            return List.of("main");
        }

        @Override
        public Optional<String> omniPhaseFor(String guestPhase) {
            return "main".equals(guestPhase) ? Optional.of("main") : Optional.empty();
        }
    };

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public EcosystemClaimer claimer() {
        return claimer;
    }

    @Override
    public MetadataImporter importer() {
        return importer;
    }

    /** Typed access for jar-aware import (annotation scan). */
    public NeoForgeMetadataImporter neoforgeImporter() {
        return importer;
    }

    @Override
    public Optional<LifecycleBridge> lifecycleBridge() {
        return Optional.of(lifecycle);
    }
}
