package org.omniframework.loader.bootstrap;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.diag.OmniIssue;
import org.omniframework.loader.provider.GameSide;
import org.omniframework.loader.provider.LocatedGame;
import org.omniframework.loader.resolve.ModCandidate;
import org.omniframework.loader.transform.PassContext;
import org.omniframework.loader.transform.TransformCache;
import org.omniframework.loader.transform.TransformPipeline;
import org.omniframework.loader.transform.TransformService;
import org.omniframework.loader.transform.mapping.ClassIndex;
import org.omniframework.loader.transform.mapping.IntermediaryComposer;
import org.omniframework.loader.transform.mapping.MappingSet;
import org.omniframework.loader.transform.mapping.ProguardMappingReader;
import org.omniframework.loader.transform.mapping.TinyV2Reader;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import org.omniframework.loader.transform.pass.AccessChange;
import org.omniframework.loader.transform.pass.AccessMergePass;
import org.omniframework.loader.transform.pass.EnvironmentStripPass;
import org.omniframework.loader.transform.pass.RemapPass;
import org.omniframework.loader.transform.pass.RefmapRewritePass;

/**
 * Ensures Fabric/NeoForge jars are AOT-remapped into the official namespace before partitions
 * open them (ARCHITECTURE §6, LDR-2/3).
 */
final class BootTransform {

    private BootTransform() {}

    /**
     * Loads mappings from optional tiny/proguard paths. Returns identity when nothing is
     * configured so native-only boots still work.
     */
    static MappingSet loadMappings(BootConfig config, IssueCollector issues) {
        Path tiny = config.intermediaryTiny().orElse(null);
        Path proguard = config.mojangProguard().orElse(null);
        Path composed = config.composedTiny().orElse(null);
        try {
            if (composed != null && Files.isRegularFile(composed)) {
                try (Reader reader = Files.newBufferedReader(composed, StandardCharsets.UTF_8)) {
                    MappingSet set = TinyV2Reader.read(reader, "intermediary", "official");
                    issues.add(OmniIssue.warning("boot.mappings.composed")
                            .at(composed.toString())
                            .with(set.describe())
                            .remedy("Mappings loaded; no action needed")
                            .build());
                    return set;
                }
            }
            if (tiny != null
                    && proguard != null
                    && Files.isRegularFile(tiny)
                    && Files.isRegularFile(proguard)) {
                try (Reader t = Files.newBufferedReader(tiny, StandardCharsets.UTF_8);
                        Reader p = Files.newBufferedReader(proguard, StandardCharsets.UTF_8)) {
                    MappingSet set = IntermediaryComposer.compose(t, p);
                    issues.add(OmniIssue.warning("boot.mappings.composed-live")
                            .at(tiny.toString())
                            .with(set.describe())
                            .remedy("Mappings composed from intermediary + Mojang proguard")
                            .build());
                    return set;
                }
            }
        } catch (IOException | RuntimeException e) {
            issues.add(OmniIssue.error("boot.mappings.failed")
                    .at("mappings")
                    .with(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                    .remedy("Check --mappings / --intermediary-tiny / --mojang-mappings paths")
                    .build());
            return MappingSet.identity();
        }
        issues.add(OmniIssue.warning("boot.mappings.missing")
                .at("mappings")
                .with("none")
                .remedy(
                        "Pass --mappings <tiny> or --intermediary-tiny + --mojang-mappings so "
                                + "Fabric jars remap to official names")
                .build());
        return MappingSet.identity();
    }

    /**
     * AOT-remaps Mojang game jars from obfuscated names into the official namespace so remapped
     * Fabric/NeoForge mods can resolve {@code net.minecraft.*} types (D2).
     *
     * <p>Without this step the client jar still contains {@code guu.class} while mods reference
     * {@code net.minecraft.resources.ResourceLocation} — every entrypoint then fails with
     * {@code ClassNotFoundException}.
     */
    static LocatedGame ensureGame(
            LocatedGame game,
            MappingSet obfuscatedToOfficial,
            GameSide side,
            Path cacheRoot,
            IssueCollector issues) {
        Objects.requireNonNull(game, "game");
        if (obfuscatedToOfficial == null || obfuscatedToOfficial.isIdentity()) {
            return game;
        }
        Path work = cacheRoot.resolve("work");
        try {
            Files.createDirectories(cacheRoot);
            Files.createDirectories(work);
        } catch (IOException e) {
            issues.add(OmniIssue.error("boot.transform.game-cache")
                    .at(cacheRoot.toString())
                    .with(String.valueOf(e.getMessage()))
                    .remedy("Ensure the instance directory is writable")
                    .build());
            return game;
        }
        TransformPipeline pipeline = new TransformPipeline(List.of(new RemapPass()), 1);
        TransformService service = new TransformService(
                new TransformCache(cacheRoot), pipeline, BootSequence.LOADER_VERSION, work);
        ClassIndex hierarchy = new ClassIndex();
        PassContext.GameSideTarget target =
                side == GameSide.SERVER
                        ? PassContext.GameSideTarget.SERVER
                        : PassContext.GameSideTarget.CLIENT;
        String mappingsHash = Integer.toHexString(obfuscatedToOfficial.describe().hashCode());
        List<Path> remapped = new ArrayList<>(game.gameJars().size());
        int index = 0;
        for (Path jar : game.gameJars()) {
            try {
                TransformService.Request request = new TransformService.Request(
                        "minecraft-game-" + index,
                        jar,
                        obfuscatedToOfficial,
                        "obfuscated-official",
                        mappingsHash,
                        "game",
                        "1",
                        target,
                        Map.of());
                TransformService.Ensured result = service.ensure(request, hierarchy, issues);
                remapped.add(result.jar());
                index++;
            } catch (RuntimeException e) {
                issues.add(OmniIssue.error("boot.transform.game")
                        .at(jar.toString())
                        .with(e.getMessage() == null ? e.toString() : e.getMessage())
                        .remedy("The game jar could not be remapped; check Mojang mappings")
                        .build());
                remapped.add(jar);
            }
        }
        issues.add(OmniIssue.warning("boot.transform.game-done")
                .at("game")
                .with(Integer.toString(remapped.size()))
                .remedy("Game jars remapped into official namespace")
                .build());
        return new LocatedGame(game.version(), remapped, game.mainClass(), game.launchArguments());
    }

    /**
     * Loads obfuscated→official maps from Mojang proguard text for game-jar remap.
     */
    static MappingSet loadObfuscatedToOfficial(BootConfig config, IssueCollector issues) {
        Path proguard = config.mojangProguard().orElse(null);
        if (proguard == null || !Files.isRegularFile(proguard)) {
            return MappingSet.identity();
        }
        try (Reader reader = Files.newBufferedReader(proguard, StandardCharsets.UTF_8)) {
            return ProguardMappingReader.toMappingSet(ProguardMappingReader.read(reader));
        } catch (IOException | RuntimeException e) {
            issues.add(OmniIssue.error("boot.mappings.proguard")
                    .at(proguard.toString())
                    .with(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                    .remedy("Check --mojang-mappings path; game will stay obfuscated")
                    .build());
            return MappingSet.identity();
        }
    }

    /**
     * Transforms non-native mod jars. Returns new candidates pointing at cache jars when
     * mappings are non-identity; otherwise the input list.
     */
    static List<ModCandidate> ensure(
            List<ModCandidate> mods,
            Map<String, String> ecosystems,
            MappingSet mappings,
            GameSide side,
            Path cacheRoot,
            IssueCollector issues) {
        Objects.requireNonNull(mods, "mods");
        if (mappings.isIdentity()) {
            return mods;
        }
        Path work = cacheRoot.resolve("work");
        try {
            Files.createDirectories(cacheRoot);
            Files.createDirectories(work);
        } catch (IOException e) {
            issues.add(OmniIssue.error("boot.transform.cache")
                    .at(cacheRoot.toString())
                    .with(String.valueOf(e.getMessage()))
                    .remedy("Ensure the instance directory is writable")
                    .build());
            return mods;
        }

        List<AccessChange> accessChanges = collectAccessChanges(mods, issues);
        List<org.omniframework.loader.transform.TransformPass> passes = new ArrayList<>();
        passes.add(new RemapPass());
        if (!accessChanges.isEmpty()) {
            passes.add(new AccessMergePass(accessChanges));
        }
        passes.add(new EnvironmentStripPass());
        passes.add(new RefmapRewritePass());
        TransformPipeline pipeline = new TransformPipeline(passes, 1);
        TransformService service = new TransformService(
                new TransformCache(cacheRoot), pipeline, BootSequence.LOADER_VERSION, work);
        ClassIndex hierarchy = new ClassIndex();
        PassContext.GameSideTarget target =
                side == GameSide.SERVER
                        ? PassContext.GameSideTarget.SERVER
                        : PassContext.GameSideTarget.CLIENT;

        String mappingsHash = Integer.toHexString(mappings.describe().hashCode());
        String accessHash = Integer.toHexString(accessChanges.hashCode());
        List<ModCandidate> out = new ArrayList<>(mods.size());
        int ensured = 0;
        for (ModCandidate mod : mods) {
            String ecosystem = ecosystems.getOrDefault(mod.id(), "omni");
            if ("omni".equals(ecosystem)) {
                out.add(mod);
                continue;
            }
            try {
                TransformService.Request request = new TransformService.Request(
                        mod.id(),
                        mod.source(),
                        mappings,
                        "intermediary-official",
                        mappingsHash + ":" + accessHash,
                        ecosystem,
                        "1",
                        target,
                        Map.of());
                TransformService.Ensured result = service.ensure(request, hierarchy, issues);
                out.add(ModCandidate.topLevel(mod.metadata(), result.jar()));
                ensured++;
            } catch (RuntimeException e) {
                issues.add(OmniIssue.error("boot.transform.mod")
                        .at(mod.id())
                        .with(e.getMessage() == null ? e.toString() : e.getMessage())
                        .remedy("The mod jar could not be remapped; check mappings and jar integrity")
                        .build());
                out.add(mod);
            }
        }
        issues.add(OmniIssue.warning("boot.transform.done")
                .at("transform")
                .with(Integer.toString(ensured))
                .remedy("Hosted jars remapped into official namespace")
                .build());
        return out;
    }

    /**
     * Loads access-widener files declared by mods and merges them into one change set
     * (ARCHITECTURE §6 access modification merge).
     */
    private static List<AccessChange> collectAccessChanges(
            List<ModCandidate> mods, IssueCollector issues) {
        List<AccessChange> all = new ArrayList<>();
        for (ModCandidate mod : mods) {
            Optional<String> access = mod.metadata().accessFile();
            if (access.isEmpty()) {
                continue;
            }
            String pathInJar = access.get();
            try (JarFile jar = new JarFile(mod.source().toFile())) {
                ZipEntry entry = jar.getEntry(pathInJar);
                if (entry == null) {
                    issues.add(OmniIssue.warning("boot.access.missing")
                            .at(mod.id())
                            .with(pathInJar)
                            .remedy("Access file declared in metadata was not found in the jar")
                            .build());
                    continue;
                }
                String text = new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                all.addAll(AccessChange.parseWidener(text));
            } catch (IOException | RuntimeException e) {
                issues.add(OmniIssue.warning("boot.access.parse")
                        .at(mod.id())
                        .with(pathInJar, String.valueOf(e.getMessage()))
                        .remedy("Access widener could not be applied; members may stay inaccessible")
                        .build());
            }
        }
        if (all.isEmpty()) {
            return List.of();
        }
        return AccessChange.merge(all);
    }
}
