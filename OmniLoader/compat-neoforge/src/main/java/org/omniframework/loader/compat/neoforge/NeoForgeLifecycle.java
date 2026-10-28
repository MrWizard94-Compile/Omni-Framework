package org.omniframework.loader.compat.neoforge;

import java.util.List;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext;
import org.omniframework.loader.compat.neoforge.bus.ModConstructedEvent;
import org.omniframework.loader.provider.GameSide;
import org.omniframework.loader.resolve.ModCandidate;

/**
 * Hosted FML lifecycle beyond {@code @Mod} construction: common setup then side-specific setup,
 * posted on each mod's own bus (pack-scale multi-mod safe).
 */
public final class NeoForgeLifecycle {

    private NeoForgeLifecycle() {}

    /**
     * Runs construct markers (already posted per-mod) then FML setup phases for every NeoForge
     * mod id that has a context installed.
     */
    public static void fireSetup(List<ModCandidate> neoforgeMods, GameSide side) {
        // Common setup for every mod bus.
        for (ModCandidate mod : neoforgeMods) {
            FMLJavaModLoadingContext.forMod(mod.id())
                    .getModEventBus()
                    .post(new FMLCommonSetupEvent());
        }
        if (side == GameSide.CLIENT) {
            for (ModCandidate mod : neoforgeMods) {
                FMLJavaModLoadingContext.forMod(mod.id())
                        .getModEventBus()
                        .post(new FMLClientSetupEvent());
            }
        } else {
            for (ModCandidate mod : neoforgeMods) {
                FMLJavaModLoadingContext.forMod(mod.id())
                        .getModEventBus()
                        .post(new FMLDedicatedServerSetupEvent());
            }
        }
    }

    /** Posts construct event on a single mod bus (used after that mod's construction). */
    public static void fireConstructed(String modId) {
        FMLJavaModLoadingContext.forMod(modId)
                .getModEventBus()
                .post(new ModConstructedEvent(modId));
    }
}
