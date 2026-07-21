package org.omniframework.loader.bootstrap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.EnvType;
import org.omniframework.api.ModInitializer;
import org.omniframework.loader.compat.CompatLayer;
import org.omniframework.loader.compat.fabric.FabricCompatLayer;
import org.omniframework.loader.compat.fabric.impl.FabricLoaderHolder;
import org.omniframework.loader.compat.fabric.impl.OmniFabricLoader;
import org.omniframework.loader.compat.neoforge.NeoForgeCompatLayer;
import org.omniframework.loader.core.ClassTransformer;
import org.omniframework.loader.core.GameLauncher;
import org.omniframework.loader.core.OmniTransformingClassLoader;
import org.omniframework.loader.core.Partition;
import org.omniframework.loader.core.PartitionRegistry;
import org.omniframework.loader.core.entrypoint.EntrypointDispatcher;
import org.omniframework.loader.core.resource.JarResourceRoot;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.mixin.CompatibilityLevels;
import org.omniframework.loader.mixin.MixinClassTransformer;
import org.omniframework.loader.mixin.MixinServiceOmni;
import org.omniframework.loader.mixin.OmniAuditTrail;
import org.omniframework.loader.mixin.OmniBytecodeProvider;
import org.omniframework.loader.mixin.OmniClassProvider;
import org.omniframework.loader.mixin.OmniClassTracker;
import org.omniframework.loader.model.ModMetadata;
import org.omniframework.loader.model.ModVersion;
import org.omniframework.loader.provider.GameProvider;
import org.omniframework.loader.provider.GameSide;
import org.omniframework.loader.provider.LocatedGame;
import org.omniframework.loader.resolve.ModCandidate;
import org.omniframework.loader.resolve.ResolutionResult;
import org.omniframework.loader.resolve.Resolver;
import org.omniframework.loader.transform.mapping.MappingSet;

/**
 * Full boot: locate → discover (native + compat claimers) → resolve → partition → entrypoints →
 * game main (ARCHITECTURE §3, B1 + bricks 15/16 wiring).
 */
public final class BootSequence {

    /**
     * OmniLoader product version (native omniloader id). Kept separate from the
     * <em>hosted Fabric Loader API</em> version we advertise to guest mods.
     */
    public static final String LOADER_VERSION = "0.1.0";

    /**
     * Version advertised for synthetic {@code fabricloader} / provides, so real
     * Fabric packs that declare {@code fabricloader >= 0.15…0.18} resolve. This is
     * the API surface version Omni claims to host — not a claim that stock Fabric
     * Loader 0.18.x is embedded.
     */
    public static final String HOSTED_FABRIC_LOADER_VERSION = "0.18.5";

    private final List<GameProvider> providers;
    private final List<CompatLayer> layers;

    /** Default boot: Minecraft provider layers + Fabric + NeoForge. */
    public BootSequence(List<GameProvider> providers) {
        this(providers, List.of(new FabricCompatLayer(), new NeoForgeCompatLayer()));
    }

    public BootSequence(List<GameProvider> providers, List<CompatLayer> layers) {
        this.providers = List.copyOf(Objects.requireNonNull(providers, "providers"));
        this.layers = List.copyOf(Objects.requireNonNull(layers, "layers"));
    }

    public Optional<BootResult> run(BootConfig config, IssueCollector issues) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(issues, "issues");

        Optional<LocatedGame> located = locate(config, issues);
        if (located.isEmpty()) {
            return Optional.empty();
        }
        LocatedGame game = located.get();

        DiscoveredMods discovered = ModDiscovery.discover(config.modsDirectory(), layers, issues);

        Optional<List<ModCandidate>> ordered = resolve(discovered, game, config.side(), issues);
        if (ordered.isEmpty()) {
            return Optional.empty();
        }

        Map<String, String> ecosystems = ecosystemIndex(discovered);
        Set<String> realModIds = discovered.allLoadable().stream()
                .map(ModCandidate::id)
                .collect(Collectors.toSet());
        List<ModCandidate> realMods = ordered.get().stream()
                .filter(candidate -> realModIds.contains(candidate.id()))
                .toList();

        // AOT remap Fabric/NeoForge jars into official names when mappings are present.
        MappingSet mappings = BootTransform.loadMappings(config, issues);
        Path transformCache = config.gameDirectory().resolve(".omniloader").resolve("transform-cache");
        realMods = BootTransform.ensure(
                realMods, ecosystems, mappings, config.side(), transformCache, issues);

        PartitionRegistry registry = buildPartitions(game, config.libraries(), realMods, ecosystems);
        try {
            ClassTransformer transformer = buildLoadTimeTransformer(registry, realMods, issues);
            GameLauncher launcher = new GameLauncher(registry, transformer);
            OmniTransformingClassLoader loader = launcher.newClassLoader();
            try {
                installFabricLoader(config, game, realMods);
                dispatchEntrypoints(realMods, ecosystems, loader, config.side());
                launcher.launch(loader, game, config.launchArguments());
            } finally {
                FabricLoaderHolder.reset();
                closeQuietly(loader);
            }
            return Optional.of(new BootResult(
                    game.version(),
                    realMods.stream().map(ModCandidate::id).toList(),
                    discovered.unclaimedCount()));
        } finally {
            closeQuietly(registry);
        }
    }

    /**
     * Installs Mixin (when available on the classpath) and returns a load-time transformer
     * chain ending with {@link MixinClassTransformer}. Falls back to identity if Mixin cannot
     * start — fixtures and native-only packs still boot.
     */
    private static ClassTransformer buildLoadTimeTransformer(
            PartitionRegistry registry, List<ModCandidate> realMods, IssueCollector issues) {
        try {
            OmniClassTracker tracker = new OmniClassTracker();
            OmniAuditTrail audit = new OmniAuditTrail();
            OmniClassProvider classProvider =
                    new OmniClassProvider(BootSequence.class.getClassLoader(), List.of());
            OmniBytecodeProvider bytecode =
                    OmniBytecodeProvider.ofClassLoader(BootSequence.class.getClassLoader());
            MixinServiceOmni.install(new MixinServiceOmni.Wiring(
                    classProvider, bytecode, tracker, audit, CompatibilityLevels.forGame(65)));
            org.spongepowered.asm.launch.MixinBootstrap.init();
            for (ModCandidate mod : realMods) {
                for (String config : mod.metadata().mixinConfigs()) {
                    try {
                        org.spongepowered.asm.mixin.Mixins.addConfiguration(config);
                    } catch (RuntimeException e) {
                        issues.add(org.omniframework.loader.diag.OmniIssue.warning("boot.mixin.config")
                                .at(mod.id())
                                .with(config, String.valueOf(e.getMessage()))
                                .remedy("Mixin config failed to register; the mod may misbehave")
                                .build());
                    }
                }
            }
            MixinServiceOmni service = new MixinServiceOmni();
            MixinClassTransformer mixin =
                    new MixinClassTransformer(service.createTransformer(), tracker);
            return mixin;
        } catch (Throwable t) {
            issues.add(org.omniframework.loader.diag.OmniIssue.warning("boot.mixin.unavailable")
                    .at("mixin")
                    .with(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage())
                    .remedy("Continuing without Mixin application; mods that need mixins will fail")
                    .build());
            return ClassTransformer.identity();
        }
    }

    private static void installFabricLoader(
            BootConfig config, LocatedGame game, List<ModCandidate> realMods) {
        EnvType env =
                config.side() == GameSide.SERVER ? EnvType.SERVER : EnvType.CLIENT;
        String[] args = config.launchArguments().toArray(String[]::new);
        OmniFabricLoader fabricLoader = new OmniFabricLoader(
                config.gameDirectory(), env, game.version(), args, realMods);
        FabricLoaderHolder.install(fabricLoader);
    }

    private static Map<String, String> ecosystemIndex(DiscoveredMods discovered) {
        Map<String, String> map = new HashMap<>();
        for (ModCandidate nativeMod : discovered.nativeMods()) {
            map.put(nativeMod.id(), "omni");
        }
        for (DiscoveredMods.HostedMod hosted : discovered.hostedMods()) {
            map.put(hosted.candidate().id(), hosted.ecosystem());
        }
        return map;
    }

    private static void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception ignored) {
            // after-game cleanup must not mask the boot result
        }
    }

    private Optional<LocatedGame> locate(BootConfig config, IssueCollector issues) {
        if (config.providedGame().isPresent()) {
            return config.providedGame();
        }
        Optional<GameProvider> provider =
                GameLauncher.selectProvider(providers, config.gameDirectory(), config.side(), issues);
        return provider.flatMap(chosen -> chosen.locate(config.gameDirectory(), config.side(), issues));
    }

    private Optional<List<ModCandidate>> resolve(
            DiscoveredMods discovered, LocatedGame game, GameSide side, IssueCollector issues) {
        List<ModCandidate> candidates = new ArrayList<>(discovered.allLoadable());
        candidates.addAll(platformCandidates(game));

        ResolutionResult resolution = new Resolver().resolve(candidates, side);
        if (!resolution.succeeded()) {
            resolution.errors().forEach(issues::add);
            return Optional.empty();
        }
        return Optional.of(resolution.loadOrder());
    }

    private static List<ModCandidate> platformCandidates(LocatedGame game) {
        Path gameJar = game.gameJars().get(0);
        List<ModCandidate> platform = new ArrayList<>();
        platform.add(ModCandidate.topLevel(
                ModMetadata.builder("minecraft", ModVersion.parse(game.version()))
                        .name("Minecraft")
                        .build(),
                gameJar));
        platform.add(ModCandidate.topLevel(
                ModMetadata.builder("omniloader", ModVersion.parse(LOADER_VERSION))
                        .name("OmniLoader")
                        .build(),
                gameJar));
        // Fabric mods depend on fabricloader; NeoForge on neoforge/forge.
        // Advertise a modern Fabric Loader API version so dependency resolution
        // does not reject every real pack (they require fabricloader >= 0.15+).
        platform.add(ModCandidate.topLevel(
                ModMetadata.builder("fabricloader", ModVersion.parse(HOSTED_FABRIC_LOADER_VERSION))
                        .name("Fabric Loader (hosted by Omni)")
                        .build(),
                gameJar));
        platform.add(ModCandidate.topLevel(
                ModMetadata.builder("neoforge", ModVersion.parse(LOADER_VERSION))
                        .name("NeoForge (hosted)")
                        .build(),
                gameJar));
        platform.add(ModCandidate.topLevel(
                ModMetadata.builder("forge", ModVersion.parse(LOADER_VERSION))
                        .name("Forge (hosted)")
                        .build(),
                gameJar));
        // Use 21.0.0 so ">=21" range predicates match (semver needs orderable form).
        platform.add(ModCandidate.topLevel(
                ModMetadata.builder("java", ModVersion.parse("21.0.0"))
                        .name("Java")
                        .build(),
                gameJar));
        return platform;
    }

    private static PartitionRegistry buildPartitions(
            LocatedGame game,
            List<Path> libraries,
            List<ModCandidate> mods,
            Map<String, String> ecosystems) {
        try {
            PartitionRegistry registry = GameLauncher.partitionsFor(game, libraries);
            for (ModCandidate mod : mods) {
                String ecosystem = ecosystems.getOrDefault(mod.id(), "omni");
                registry.add(Partition.mod(mod.id(), ecosystem, JarResourceRoot.open(mod.source())));
            }
            return registry;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open a resolved mod or game jar", e);
        }
    }

    /**
     * Runs entrypoints per ecosystem so Omni and Fabric interfaces never collide on phase
     * {@code main}. Side-specific Fabric phases run after {@code main}.
     */
    private static void dispatchEntrypoints(
            List<ModCandidate> mods,
            Map<String, String> ecosystems,
            ClassLoader loader,
            GameSide side) {
        List<ModCandidate> omni = new ArrayList<>();
        List<ModCandidate> fabric = new ArrayList<>();
        List<ModCandidate> neoforge = new ArrayList<>();
        for (ModCandidate mod : mods) {
            switch (ecosystems.getOrDefault(mod.id(), "omni")) {
                case "fabric" -> fabric.add(mod);
                case "neoforge" -> neoforge.add(mod);
                default -> omni.add(mod);
            }
        }

        if (!omni.isEmpty()) {
            EntrypointDispatcher dispatcher = new EntrypointDispatcher();
            for (ModCandidate mod : omni) {
                dispatcher.register(mod.metadata(), loader);
            }
            dispatcher.run(
                    "main",
                    ModInitializer.class,
                    (metadata, initializer) -> initializer.onInitialize());
        }

        if (!fabric.isEmpty()) {
            EntrypointDispatcher dispatcher = new EntrypointDispatcher();
            for (ModCandidate mod : fabric) {
                dispatcher.register(mod.metadata(), loader);
            }
            dispatcher.run(
                    "main",
                    net.fabricmc.api.ModInitializer.class,
                    (metadata, initializer) -> initializer.onInitialize());
            if (side == GameSide.CLIENT) {
                dispatcher.run(
                        "client",
                        ClientModInitializer.class,
                        (metadata, initializer) -> initializer.onInitializeClient());
            } else if (side == GameSide.SERVER) {
                dispatcher.run(
                        "server",
                        DedicatedServerModInitializer.class,
                        (metadata, initializer) -> initializer.onInitializeServer());
            }
        }

        if (!neoforge.isEmpty()) {
            EntrypointDispatcher dispatcher = new EntrypointDispatcher();
            for (ModCandidate mod : neoforge) {
                dispatcher.register(mod.metadata(), loader);
            }
            // Construction is the Phase 4 proof; FML event bus is out of scope.
            dispatcher.run("main", Object.class, (metadata, instance) -> {
                // Touch the instance so a throw in <init> is the failure mode, not a no-op.
                Objects.requireNonNull(instance, metadata.id());
            });
        }
    }
}
