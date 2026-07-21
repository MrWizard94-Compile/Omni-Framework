package org.omniframework.loader.compat.fabric;

import java.util.Optional;
import org.omniframework.loader.compat.CompatLayer;
import org.omniframework.loader.compat.EcosystemClaimer;
import org.omniframework.loader.compat.LifecycleBridge;
import org.omniframework.loader.compat.MetadataImporter;

/**
 * The Fabric-family hosting layer (CMP-2): metadata-level, no jar rewrite.
 *
 * <p>Real Fabric mods still need intermediary→official remapping for game classes; that is the
 * transform pipeline's job when mappings are present. Fixture mods that only implement
 * {@code net.fabricmc.api.ModInitializer} and never touch Minecraft classes exercise the claim →
 * import → entrypoint path without mappings — the Phase 4 exit proof for this layer.
 */
public final class FabricCompatLayer implements CompatLayer {

    public static final String ID = "compat-fabric";
    public static final String VERSION = "0.1.0";

    private final EcosystemClaimer claimer = new FabricEcosystemClaimer();
    private final MetadataImporter importer = new FabricMetadataImporter();
    private final LifecycleBridge lifecycle = new FabricLifecycleBridge();

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

    @Override
    public Optional<LifecycleBridge> lifecycleBridge() {
        return Optional.of(lifecycle);
    }
}
