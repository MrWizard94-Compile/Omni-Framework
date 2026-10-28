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
import org.omniframework.api.OmniLoaderApi;
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
import org.omniframework.loader.model.ProvidedMod;
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
    public static final String HOSTED_FABRIC_LOADER_VERSION = "0.18.6";

    /**
     * Fallback hosted NeoForge/Forge line when the game version cannot be mapped.
     * NeoForge 1.21.1 packs declare ranges like {@code [21.1.72,)}; product
     * {@link #LOADER_VERSION} ({@code 0.1.0}) must never be advertised here.
     */
    public static final String HOSTED_NEOFORGE_FALLBACK_VERSION = "21.1.999";

    /**
     * Hosted Fabric API surface version advertised for Connector-style guests.
     * High enough for modern 1.21.x packs; not a claim that stock Fabric API is embedded.
     */
    public static final String HOSTED_FABRIC_API_VERSION = "0.115.6+1.21.1";

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

    /**
     * Discover + dependency resolve only (no transform, mixin, or game launch).
     *
     * <p>Used by residual honesty ITs against real pack mods folders so a clean resolve does not
     * spend minutes AOT-remapping hundreds of jars.
     *
     * @return load order ids when resolve succeeded; empty when resolve failed (errors in
     *     {@code issues})
     */
    public Optional<List<String>> resolveOnly(BootConfig config, IssueCollector issues) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(issues, "issues");

        Optional<LocatedGame> located = locate(config, issues);
        if (located.isEmpty()) {
            writeIssueDump(config, issues);
            return Optional.empty();
        }
        LocatedGame game = located.get();
        Path progress = Path.of(System.getProperty("java.io.tmpdir"), "omni-residual-progress.txt");
        long t0 = System.nanoTime();
        DiscoveredMods discovered = ModDiscovery.discover(config.modsDirectory(), layers, issues);
        long discoverMs = (System.nanoTime() - t0) / 1_000_000L;
        try {
            java.nio.file.Files.writeString(
                    progress,
                    "discoverMs=" + discoverMs + " loadable=" + discovered.allLoadable().size()
                            + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // diagnostics only
        }
        t0 = System.nanoTime();
        Optional<List<ModCandidate>> ordered = resolve(discovered, game, config.side(), issues);
        long resolveMs = (System.nanoTime() - t0) / 1_000_000L;
        try {
            java.nio.file.Files.writeString(
                    progress,
                    "resolveMs=" + resolveMs + " ok=" + ordered.isPresent() + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // diagnostics only
        }
        System.out.println("[resolveOnly] loadable=" + discovered.allLoadable().size()
                + " discoverMs=" + discoverMs
                + " resolveMs=" + resolveMs
                + " ok=" + ordered.isPresent());
        System.out.flush();
        if (ordered.isEmpty()) {
            writeIssueDump(config, issues);
            return Optional.empty();
        }
        writeIssueDump(config, issues);
        return Optional.of(ordered.get().stream().map(ModCandidate::id).toList());
    }

    public Optional<BootResult> run(BootConfig config, IssueCollector issues) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(issues, "issues");

        Optional<LocatedGame> located = locate(config, issues);
        if (located.isEmpty()) {
            // Hard failures must still hand off the typed tree to the launcher UI (LDR-10).
            writeIssueDump(config, issues);
            return Optional.empty();
        }
        LocatedGame game = located.get();

        DiscoveredMods discovered = ModDiscovery.discover(config.modsDirectory(), layers, issues);

        Optional<List<ModCandidate>> ordered = resolve(discovered, game, config.side(), issues);
        if (ordered.isEmpty()) {
            writeIssueDump(config, issues);
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
        // Game jar is Mojang-obfuscated; mods are remapped to named. Remap the game too or
        // every net.minecraft.* reference from mods fails ClassNotFoundException.
        MappingSet gameMaps = BootTransform.loadObfuscatedToOfficial(config, issues);
        game = BootTransform.ensureGame(game, gameMaps, config.side(), transformCache, issues);
        realMods = BootTransform.ensure(
                realMods, ecosystems, mappings, config.side(), transformCache, issues);

        PartitionRegistry registry = buildPartitions(game, config.libraries(), realMods, ecosystems);
        try {
            // Deferred transformer: create the game ClassLoader first so Mixin can resolve
            // mod/game classes through it, then swap in MixinClassTransformer before any
            // entrypoint loads a class. Without this, OmniClassProvider used the bootstrap
            // loader and never saw naturespirit mixins → AssertionError invoker stubs.
            java.util.concurrent.atomic.AtomicReference<ClassTransformer> active =
                    new java.util.concurrent.atomic.AtomicReference<>(ClassTransformer.identity());
            ClassTransformer deferred =
                    (binaryName, partition, bytes) -> active.get().transform(binaryName, partition, bytes);
            GameLauncher launcher = new GameLauncher(registry, deferred);
            OmniTransformingClassLoader loader = launcher.newClassLoader();
            // Install Mixin against this loader; buildLoadTimeTransformer activates `active`
            // before registering configs so mixin classes are never sealed as identity.
            buildLoadTimeTransformer(registry, realMods, issues, loader, active);
            try {
                installOmniApi(config, realMods);
                OmniFabricLoader fabricLoader = installFabricLoader(config, game, realMods);
                fabricLoader.setEntrypointLoader(loader);
                // Fabric mods (and Fabric API itself) assume Minecraft Bootstrap has run so
                // BuiltInRegistries is usable. Dispatching main entrypoints before game.main
                // without this produced IllegalArgumentException: Not bootstrapped cascades.
                bootstrapMinecraft(loader, issues);
                writeIssueDump(config, issues);
                try {
                    dispatchEntrypoints(realMods, ecosystems, loader, config.side());
                } catch (org.omniframework.loader.core.entrypoint.EntrypointPhaseException phase) {
                    // Resolve already succeeded. Collect entrypoint failures as typed issues and
                    // still hand off to game main so packs can reach post-entrypoint activity
                    // (title / early client init) instead of dying before Minecraft starts.
                    issues.add(org.omniframework.loader.diag.OmniIssue.error("boot.entrypoint.phase")
                            .at(phase.phase())
                            .with(Integer.toString(phase.failures().size()), phase.getMessage())
                            .remedy("Some mods failed during entrypoint init; game will still start. "
                                    + "Fix or disable the failing mods listed in the console.")
                            .build());
                    for (var failure : phase.failures()) {
                        issues.add(org.omniframework.loader.diag.OmniIssue.error("boot.entrypoint")
                                .at(failure.entrypoint().value())
                                .with(failure.getMessage())
                                .remedy("Disable this mod or fix its dependency / mixin error")
                                .build());
                    }
                    writeIssueDump(config, issues);
                    System.err.println("[omniloader] " + phase.getMessage());
                }
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
     *
     * @param gameLoader the transforming loader that will define game/mod classes; Mixin must
     *     resolve Class objects through this same loader for identity
     */
    private static void buildLoadTimeTransformer(
            PartitionRegistry registry,
            List<ModCandidate> realMods,
            IssueCollector issues,
            ClassLoader gameLoader,
            java.util.concurrent.atomic.AtomicReference<ClassTransformer> active) {
        try {
            OmniClassTracker tracker = new OmniClassTracker();
            OmniAuditTrail audit = new OmniAuditTrail();
            OmniClassProvider classProvider = new OmniClassProvider(gameLoader, List.of());
            // Post-AOT partition bytes for targets; configs live in the same jars.
            java.util.function.Function<String, java.util.Optional<byte[]>> fromPartitions =
                    internalName -> registry.readResource(internalName + ".class");
            OmniBytecodeProvider bytecode =
                    new OmniBytecodeProvider(fromPartitions, fromPartitions);
            java.util.function.Function<String, java.io.InputStream> resources = name -> {
                java.util.Optional<byte[]> bytes = registry.readResource(name);
                return bytes.map(java.io.ByteArrayInputStream::new).orElse(null);
            };
            MixinServiceOmni.install(new MixinServiceOmni.Wiring(
                    classProvider,
                    bytecode,
                    tracker,
                    audit,
                    CompatibilityLevels.forGame(65),
                    resources));
            org.spongepowered.asm.launch.MixinBootstrap.init();
            // Activate Mixin on the deferred classloader BEFORE Mixins.addConfiguration may
            // load mixin classes through gameLoader.
            MixinServiceOmni service = new MixinServiceOmni();
            MixinClassTransformer mixin =
                    new MixinClassTransformer(service.createTransformer(), tracker);
            active.set(mixin);
            int configs = 0;
            for (ModCandidate mod : realMods) {
                for (String config : mod.metadata().mixinConfigs()) {
                    try {
                        org.spongepowered.asm.mixin.Mixins.addConfiguration(config);
                        configs++;
                    } catch (RuntimeException e) {
                        issues.add(org.omniframework.loader.diag.OmniIssue.warning("boot.mixin.config")
                                .at(mod.id())
                                .with(config, String.valueOf(e.getMessage()))
                                .remedy("Mixin config failed to register; the mod may misbehave")
                                .build());
                    }
                }
            }
            issues.add(org.omniframework.loader.diag.OmniIssue.warning("boot.mixin.configs")
                    .at("mixin")
                    .with(Integer.toString(configs))
                    .remedy("Mixin configs registered from partitions")
                    .build());
        } catch (Throwable t) {
            issues.add(org.omniframework.loader.diag.OmniIssue.warning("boot.mixin.unavailable")
                    .at("mixin")
                    .with(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage())
                    .remedy("Continuing without Mixin application; mods that need mixins will fail")
                    .build());
            active.set(ClassTransformer.identity());
        }
    }

    private static void installOmniApi(BootConfig config, List<ModCandidate> realMods) {
        OmniLoaderApi.Holder.install(new OmniLoaderApiImpl(
                LOADER_VERSION,
                realMods,
                config.side(),
                config.gameDirectory(),
                false));
    }

    /**
     * Runs {@code net.minecraft.server.Bootstrap#bootStrap()} on the game classloader when
     * present. Safe no-op if the class is missing (fixture packs without a real game jar).
     */
    private static void bootstrapMinecraft(ClassLoader loader, IssueCollector issues) {
        try {
            Class<?> bootstrap = Class.forName("net.minecraft.server.Bootstrap", true, loader);
            bootstrap.getMethod("bootStrap").invoke(null);
            issues.add(org.omniframework.loader.diag.OmniIssue.warning("boot.game.bootstrap")
                    .at("Bootstrap")
                    .with("ok")
                    .remedy("Minecraft Bootstrap completed before entrypoints")
                    .build());
        } catch (ClassNotFoundException missing) {
            // Fixture / non-Minecraft providers — entrypoints must not require registries.
        } catch (ReflectiveOperationException | RuntimeException e) {
            issues.add(org.omniframework.loader.diag.OmniIssue.warning("boot.game.bootstrap-failed")
                    .at("Bootstrap")
                    .with(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                    .remedy("Entrypoints will run without Minecraft Bootstrap; registry access may fail")
                    .build());
        }
    }

    /**
     * Writes typed issues under {@code gameDir/logs/omniloader-issues.json} for the launcher
     * boot-failure tree UI (LDR-10 handoff). Safe to call on failure and success paths; never
     * throws.
     */
    public static void writeIssueDump(BootConfig config, IssueCollector issues) {
        try {
            Path dir = config.gameDirectory().resolve("logs");
            java.nio.file.Files.createDirectories(dir);
            Path out = dir.resolve("omniloader-issues.json");
            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (var issue : issues.issues()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append('{')
                        .append("\"level\":\"")
                        .append(issue.level().name().toLowerCase())
                        .append("\",")
                        .append("\"code\":\"")
                        .append(escapeJson(issue.code()))
                        .append("\",")
                        .append("\"message\":\"")
                        .append(escapeJson(issue.describe()))
                        .append("\",")
                        .append("\"at\":\"")
                        .append(escapeJson(issue.location()))
                        .append("\",")
                        .append("\"remedy\":\"")
                        .append(escapeJson(issue.remedy()))
                        .append('"')
                        .append('}');
            }
            json.append(']');
            java.nio.file.Files.writeString(out, json.toString());
        } catch (Exception ignored) {
            // Diagnostics must never break boot.
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static OmniFabricLoader installFabricLoader(
            BootConfig config, LocatedGame game, List<ModCandidate> realMods) {
        EnvType env =
                config.side() == GameSide.SERVER ? EnvType.SERVER : EnvType.CLIENT;
        String[] args = config.launchArguments().toArray(String[]::new);
        OmniFabricLoader fabricLoader = new OmniFabricLoader(
                config.gameDirectory(), env, game.version(), args, realMods);
        FabricLoaderHolder.install(fabricLoader);
        return fabricLoader;
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
        List<ModCandidate> platform = platformCandidates(game);
        List<ModCandidate> candidates = new ArrayList<>(discovered.allLoadable());
        // Optional performance modules (e.g. c2me-opts-natives-math needing Java 22) must not
        // fail the whole pack on Java 21 — drop them with a warning instead.
        candidates = dropUnsatisfiedJavaModules(candidates, platform, issues);
        candidates.addAll(platform);

        ResolutionResult resolution = new Resolver().resolve(candidates, side);
        if (!resolution.succeeded()) {
            resolution.errors().forEach(issues::add);
            return Optional.empty();
        }
        return Optional.of(resolution.loadOrder());
    }

    /**
     * Removes non-platform candidates whose hard {@code java} dependency cannot be satisfied
     * by the hosted Java identity. Emits a warning so the pack still boots without that module
     * (e.g. c2me natives-math needing Java 22 on a Java 21 host).
     */
    static List<ModCandidate> dropUnsatisfiedJavaModules(
            List<ModCandidate> mods,
            List<ModCandidate> platform,
            org.omniframework.loader.diag.IssueCollector issues) {
        ModCandidate javaHost = platform.stream()
                .filter(c -> "java".equals(c.id()))
                .findFirst()
                .orElse(null);
        if (javaHost == null || javaHost.version().semantic().isEmpty()) {
            return mods;
        }
        var hostSem = javaHost.version().semantic().get();
        List<ModCandidate> kept = new ArrayList<>(mods.size());
        for (ModCandidate candidate : mods) {
            boolean drop = false;
            for (org.omniframework.loader.model.Dependency dep :
                    candidate.metadata().dependenciesOf(
                            org.omniframework.loader.model.Dependency.Kind.DEPENDS)) {
                if (!"java".equals(dep.modId())) {
                    continue;
                }
                if (javaSatisfies(dep, hostSem)) {
                    continue;
                }
                issues.add(org.omniframework.loader.diag.OmniIssue.warning("resolve.java-skip")
                        .at(candidate.id())
                        .with(
                                candidate.metadata().displayName(),
                                dep.versions().toString(),
                                javaHost.version().raw())
                        .remedy(candidate.metadata().displayName()
                                + " needs Java "
                                + dep.versions()
                                + " but the host provides "
                                + javaHost.version().raw()
                                + "; skipping this module so the pack can still boot.")
                        .build());
                drop = true;
                break;
            }
            if (!drop) {
                kept.add(candidate);
            }
        }
        return kept;
    }

    /** Whether hosted Java satisfies a dependency predicate (EQUAL means ≥ for capability). */
    private static boolean javaSatisfies(
            org.omniframework.loader.model.Dependency dep,
            org.omniframework.loader.model.SemanticVersion hostSem) {
        if (dep.versions().isAny()) {
            return true;
        }
        if (dep.matches(org.omniframework.loader.model.ModVersion.parse(hostSem.toString()))) {
            return true;
        }
        for (var term : dep.versions().terms()) {
            if (term.operator() == org.omniframework.loader.model.VersionPredicate.Operator.ANY) {
                continue;
            }
            if (term.bound().isEmpty()) {
                return false;
            }
            int cmp = hostSem.compareTo(term.bound().get());
            boolean ok =
                    switch (term.operator()) {
                        case EQUAL, GREATER_EQUAL -> cmp >= 0;
                        case GREATER -> cmp > 0;
                        case LESS -> cmp < 0;
                        case LESS_EQUAL -> cmp <= 0;
                        case ANY -> true;
                    };
            if (!ok) {
                return false;
            }
        }
        return true;
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
        // Advertise modern hosted API versions so dependency resolution does not
        // reject every real pack (fabricloader >= 0.15+, neoforge [21.1.x,)).
        // These are capability claims — not binary-identical stock loaders.
        platform.add(ModCandidate.topLevel(
                ModMetadata.builder("fabricloader", ModVersion.parse(HOSTED_FABRIC_LOADER_VERSION))
                        .name("Fabric Loader (hosted by Omni)")
                        .build(),
                gameJar));
        // Connector-style Fabric guest ids: packs declare fabric / fabric-api / module
        // ids even on NeoForge. Host them as provides so resolve does not fail solely
        // for missing platform identities (not full Fabric API runtime parity).
        platform.add(ModCandidate.topLevel(hostedFabricApiMetadata(), gameJar));
        String hostedNeo = hostedNeoForgeVersion(game.version());
        String hostedForge = hostedMinecraftForgeVersion(game.version());
        platform.add(ModCandidate.topLevel(
                ModMetadata.builder("neoforge", ModVersion.parse(hostedNeo))
                        .name("NeoForge (hosted by Omni)")
                        .build(),
                gameJar));
        // Classic Minecraft Forge uses major lines like 51.x/52.x for 1.21.x — NOT the
        // NeoForge 21.1.x scheme. Advertising NeoForge's version as "forge" breaks Forge packs
        // (e.g. Slightly Flavoured) that require forge [51,) / [52.0.8,).
        platform.add(ModCandidate.topLevel(
                ModMetadata.builder("forge", ModVersion.parse(hostedForge))
                        .name("Minecraft Forge (hosted by Omni)")
                        .build(),
                gameJar));
        // Advertise the running JVM major so ">=21" works; patch 0 keeps ranges simple.
        Runtime.Version jvm = Runtime.version();
        String javaVersion = jvm.feature() + ".0.0";
        platform.add(ModCandidate.topLevel(
                ModMetadata.builder("java", ModVersion.parse(javaVersion))
                        .name("Java")
                        .build(),
                gameJar));
        return platform;
    }

    /**
     * Synthetic {@code fabric-api} identity plus common module provides used by
     * Connector / mixed packs (resolve-time only).
     */
    static ModMetadata hostedFabricApiMetadata() {
        ModVersion ver = ModVersion.parse(HOSTED_FABRIC_API_VERSION);
        ModMetadata.Builder b = ModMetadata.builder("fabric-api", ver)
                .name("Fabric API (hosted resolve identity)")
                .description("Omni hosted identity for Fabric guest dependency resolution");
        // Common ids that appear as resolve.dependency-missing on NeoForge packs with
        // Fabric-shaped jars (Sinytra/Connector guests). Keep the list focused on what
        // packs actually declare as hard depends, not every Fabric module ever published.
        // Own id is fabric-api; provides are the other ids packs depend on.
        for (String id : new String[] {
            "fabric",
            "fabric-api-base",
            "fabric-resource-loader-v0",
            "fabric-lifecycle-events-v1",
            "fabric-key-binding-api-v1",
            "fabric-rendering-v1",
            "fabric-renderer-api-v1",
            "fabric-rendering-fluids-v1",
            "fabric-textures-v0",
            "fabric-screen-api-v1",
            "fabric-networking-api-v1",
            "fabric-command-api-v2",
            "fabric-events-interaction-v0",
            "fabric-object-builder-api-v1",
            "fabric-registry-sync-v0",
            "fabric-transitive-access-wideners-v1",
            "fabric-convention-tags-v2",
            "fabric-block-view-api-v2",
            "fabric-data-attachment-api-v1",
        }) {
            b.provides(new ProvidedMod(id, ver));
        }
        return b.build();
    }

    /**
     * Maps a Minecraft version to the NeoForge version line real packs depend on.
     *
     * <p>NeoForge versions for modern MC are {@code {mcMinor}.{mcPatch}.{build}} —
     * e.g. Minecraft {@code 1.21.1} → NeoForge {@code 21.1.x}. The patch is set high
     * ({@code 999}) so open lower bounds like {@code [21.1.72,)} and {@code [21,)}
     * succeed against the hosted platform candidate.
     *
     * @param minecraftVersion raw game version from {@link LocatedGame#version()}
     * @return a semver-orderable hosted NeoForge version string
     */
    public static String hostedNeoForgeVersion(String minecraftVersion) {
        if (minecraftVersion == null || minecraftVersion.isBlank()) {
            return HOSTED_NEOFORGE_FALLBACK_VERSION;
        }
        String raw = stripVersionSuffixes(minecraftVersion);
        String[] parts = raw.split("\\.");
        try {
            if (parts.length >= 3 && "1".equals(parts[0])) {
                int minor = Integer.parseInt(parts[1]);
                int patch = Integer.parseInt(parts[2]);
                return minor + "." + patch + ".999";
            }
            if (parts.length == 2 && "1".equals(parts[0])) {
                int minor = Integer.parseInt(parts[1]);
                return minor + ".0.999";
            }
            // Already looks like a NeoForge line (21.1.72) — host at least that high.
            if (parts.length >= 2) {
                int major = Integer.parseInt(parts[0]);
                int minor = Integer.parseInt(parts[1]);
                if (major >= 20) {
                    return major + "." + minor + ".999";
                }
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return HOSTED_NEOFORGE_FALLBACK_VERSION;
    }

    /**
     * Classic Minecraft Forge version line for the given game version.
     *
     * <p>Forge uses a different scheme from NeoForge: MC 1.21 → Forge 51.x, MC 1.21.1 → 52.x.
     * Fallback {@code 52.0.999} covers modern 1.21.1 packs that declare {@code forge [51,)} /
     * {@code [52.0.8,)}.
     */
    public static String hostedMinecraftForgeVersion(String minecraftVersion) {
        if (minecraftVersion == null || minecraftVersion.isBlank()) {
            return "52.0.999";
        }
        String raw = stripVersionSuffixes(minecraftVersion);
        String[] parts = raw.split("\\.");
        try {
            if (parts.length >= 2 && "1".equals(parts[0])) {
                int minor = Integer.parseInt(parts[1]);
                int patch = parts.length >= 3 ? Integer.parseInt(parts[2]) : 0;
                // Approximate official Forge major lines used by recent packs.
                if (minor == 21 && patch >= 1) {
                    return "52.0.999";
                }
                if (minor == 21) {
                    return "51.0.999";
                }
                if (minor == 20 && patch >= 1) {
                    return "49.0.999";
                }
                if (minor == 20) {
                    return "46.0.999";
                }
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return "52.0.999";
    }

    private static String stripVersionSuffixes(String minecraftVersion) {
        String raw = minecraftVersion.trim();
        int cut = raw.indexOf('-');
        if (cut > 0) {
            raw = raw.substring(0, cut);
        }
        cut = raw.indexOf('+');
        if (cut > 0) {
            raw = raw.substring(0, cut);
        }
        return raw;
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
            // One bus per mod id. activate(modId) runs immediately before THAT mod's
            // construction so FMLJavaModLoadingContext.get() is never the previous mod.
            for (ModCandidate mod : neoforge) {
                net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext.install(
                        mod.id(), new org.omniframework.loader.compat.neoforge.bus.OmniEventBus());
            }
            EntrypointDispatcher dispatcher = new EntrypointDispatcher();
            for (ModCandidate mod : neoforge) {
                dispatcher.register(mod.metadata(), loader);
            }
            dispatcher.run(
                    "main",
                    Object.class,
                    metadata -> net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext.activate(
                            metadata.id()),
                    (metadata, instance) -> {
                        Objects.requireNonNull(instance, metadata.id());
                        org.omniframework.loader.compat.neoforge.NeoForgeLifecycle.fireConstructed(
                                metadata.id());
                    });
            // Full hosted FML lifecycle after all constructors: common then client/server setup.
            org.omniframework.loader.compat.neoforge.NeoForgeLifecycle.fireSetup(neoforge, side);
            net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext.clear();
        }
    }
}
