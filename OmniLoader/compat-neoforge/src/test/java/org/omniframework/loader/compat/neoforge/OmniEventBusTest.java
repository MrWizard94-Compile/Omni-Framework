package org.omniframework.loader.compat.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.omniframework.loader.compat.neoforge.bus.ModConstructedEvent;
import org.omniframework.loader.compat.neoforge.bus.OmniEventBus;
import org.omniframework.loader.model.ModMetadata;
import org.omniframework.loader.model.ModVersion;
import org.omniframework.loader.provider.GameSide;
import org.omniframework.loader.resolve.ModCandidate;
import java.nio.file.Path;

class OmniEventBusTest {

    @AfterEach
    void tearDown() {
        FMLJavaModLoadingContext.clear();
    }

    @Test
    void registerAndPostDeliversToAnnotatedMethods() {
        OmniEventBus bus = new OmniEventBus();
        List<String> seen = new ArrayList<>();
        bus.register(new Object() {
            @SubscribeEvent
            public void onConstructed(ModConstructedEvent event) {
                seen.add(event.getModId());
            }
        });
        bus.post(new ModConstructedEvent("demo"));
        assertEquals(List.of("demo"), seen);
    }

    @Test
    void fmlContextExposesInstalledBus() {
        OmniEventBus bus = new OmniEventBus();
        AtomicInteger hits = new AtomicInteger();
        bus.addListener(ModConstructedEvent.class, e -> hits.incrementAndGet());
        FMLJavaModLoadingContext.install("mymod", bus);
        FMLJavaModLoadingContext.activate("mymod");
        FMLJavaModLoadingContext.get().getModEventBus().post(new ModConstructedEvent("mymod"));
        assertEquals(1, hits.get());
        assertEquals("mymod", FMLJavaModLoadingContext.get().getModId());
        assertTrue(FMLJavaModLoadingContext.get().getModEventBus() == bus);
    }

    @Test
    void forModPostsToOwningBusEvenWhenAnotherModIsActive() {
        OmniEventBus busA = new OmniEventBus();
        OmniEventBus busB = new OmniEventBus();
        List<String> seenA = new ArrayList<>();
        List<String> seenB = new ArrayList<>();
        busA.addListener(ModConstructedEvent.class, e -> seenA.add(e.getModId()));
        busB.addListener(ModConstructedEvent.class, e -> seenB.add(e.getModId()));
        FMLJavaModLoadingContext.install("mod-a", busA);
        FMLJavaModLoadingContext.install("mod-b", busB);
        // Last active is B — posting via forMod(A) must still hit bus A only.
        FMLJavaModLoadingContext.activate("mod-b");
        FMLJavaModLoadingContext.forMod("mod-a")
                .getModEventBus()
                .post(new ModConstructedEvent("mod-a"));
        assertEquals(List.of("mod-a"), seenA);
        assertTrue(seenB.isEmpty(), "active mod B must not receive A's post");
        assertEquals("mod-b", FMLJavaModLoadingContext.get().getModId());
    }

    @Test
    void lifecycleFireSetupPostsCommonOnEveryModBus() {
        OmniEventBus busA = new OmniEventBus();
        OmniEventBus busB = new OmniEventBus();
        AtomicInteger commonA = new AtomicInteger();
        AtomicInteger commonB = new AtomicInteger();
        busA.addListener(FMLCommonSetupEvent.class, e -> commonA.incrementAndGet());
        busB.addListener(FMLCommonSetupEvent.class, e -> commonB.incrementAndGet());
        FMLJavaModLoadingContext.install("a", busA);
        FMLJavaModLoadingContext.install("b", busB);
        Path jar = Path.of("dummy.jar");
        List<ModCandidate> mods = List.of(
                ModCandidate.topLevel(ModMetadata.builder("a", ModVersion.parse("1")).build(), jar),
                ModCandidate.topLevel(ModMetadata.builder("b", ModVersion.parse("1")).build(), jar));
        NeoForgeLifecycle.fireSetup(mods, GameSide.CLIENT);
        assertEquals(1, commonA.get());
        assertEquals(1, commonB.get());
    }
}
