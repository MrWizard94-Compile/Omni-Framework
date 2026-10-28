package org.omniframework.loader.compat.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.omniframework.loader.compat.fabric.impl.OmniFabricLoader;
import org.omniframework.loader.model.Entrypoint;
import org.omniframework.loader.model.ModMetadata;
import org.omniframework.loader.model.ModVersion;
import org.omniframework.loader.resolve.ModCandidate;

/**
 * Drives the shipped {@link OmniFabricLoader} entrypoint surface: metadata-declared classes must
 * materialize through {@code getEntrypoints} / {@code getEntrypointContainers} once a loader is set.
 */
class OmniFabricLoaderEntrypointsTest {

    /** Fixture entrypoint class loaded via the test classloader. */
    public static final class SampleMainInit {
        static final AtomicBoolean CONSTRUCTED = new AtomicBoolean(false);

        public SampleMainInit() {
            CONSTRUCTED.set(true);
        }

        public void onInitialize() {
            // no-op
        }
    }

    @Test
    @DisplayName("getEntrypoints materializes declared main entry from shipped OmniFabricLoader")
    void getEntrypoints_materializesDeclaredMain() {
        SampleMainInit.CONSTRUCTED.set(false);
        ModMetadata meta = ModMetadata.builder("sample-mod", ModVersion.parse("1.0.0"))
                .name("Sample")
                .entrypoint(Entrypoint.java("main", SampleMainInit.class.getName()))
                .build();
        ModCandidate candidate = ModCandidate.topLevel(meta, Path.of("sample-mod.jar"));
        OmniFabricLoader loader = new OmniFabricLoader(
                Path.of("game"),
                EnvType.CLIENT,
                "1.21.1",
                new String[] {"--version", "1.21.1"},
                List.of(candidate));
        loader.setEntrypointLoader(SampleMainInit.class.getClassLoader());

        List<SampleMainInit> entries = loader.getEntrypoints("main", SampleMainInit.class);
        assertEquals(1, entries.size(), "one main entrypoint must load");
        assertTrue(SampleMainInit.CONSTRUCTED.get(), "entrypoint constructor must run");

        List<EntrypointContainer<SampleMainInit>> containers =
                loader.getEntrypointContainers("main", SampleMainInit.class);
        assertEquals(1, containers.size());
        assertEquals("sample-mod", containers.get(0).getProvider().getMetadata().getId());
        // Each call constructs a fresh instance (Fabric-style materialization).
        assertTrue(containers.get(0).getEntrypoint() instanceof SampleMainInit);
    }

    @Test
    @DisplayName("getEntrypoints is empty until setEntrypointLoader is configured")
    void getEntrypoints_emptyWithoutLoader() {
        ModMetadata meta = ModMetadata.builder("sample-mod", ModVersion.parse("1.0.0"))
                .entrypoint(Entrypoint.java("main", SampleMainInit.class.getName()))
                .build();
        OmniFabricLoader loader = new OmniFabricLoader(
                Path.of("game"),
                EnvType.CLIENT,
                "1.21.1",
                new String[0],
                List.of(ModCandidate.topLevel(meta, Path.of("sample-mod.jar"))));

        assertTrue(loader.getEntrypoints("main", SampleMainInit.class).isEmpty());
        assertTrue(loader.getEntrypointContainers("main", SampleMainInit.class).isEmpty());
    }

    @Test
    @DisplayName("invokeEntrypoints runs consumer for each materialized entry")
    void invokeEntrypoints_callsConsumer() {
        ModMetadata meta = ModMetadata.builder("sample-mod", ModVersion.parse("1.0.0"))
                .entrypoint(Entrypoint.java("main", SampleMainInit.class.getName()))
                .build();
        OmniFabricLoader loader = new OmniFabricLoader(
                Path.of("game"),
                EnvType.CLIENT,
                "1.21.1",
                new String[0],
                List.of(ModCandidate.topLevel(meta, Path.of("sample-mod.jar"))));
        loader.setEntrypointLoader(SampleMainInit.class.getClassLoader());

        AtomicBoolean invoked = new AtomicBoolean(false);
        loader.invokeEntrypoints("main", SampleMainInit.class, entry -> {
            entry.onInitialize();
            invoked.set(true);
        });
        assertTrue(invoked.get());
    }
}
