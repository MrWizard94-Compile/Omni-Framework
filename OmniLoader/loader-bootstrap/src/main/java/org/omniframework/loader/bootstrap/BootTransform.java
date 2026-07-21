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
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.diag.OmniIssue;
import org.omniframework.loader.provider.GameSide;
import org.omniframework.loader.resolve.ModCandidate;
import org.omniframework.loader.transform.PassContext;
import org.omniframework.loader.transform.TransformCache;
import org.omniframework.loader.transform.TransformPipeline;
import org.omniframework.loader.transform.TransformService;
import org.omniframework.loader.transform.mapping.ClassIndex;
import org.omniframework.loader.transform.mapping.IntermediaryComposer;
import org.omniframework.loader.transform.mapping.MappingSet;
import org.omniframework.loader.transform.mapping.TinyV2Reader;
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

        TransformPipeline pipeline = new TransformPipeline(
                List.of(new RemapPass(), new EnvironmentStripPass(), new RefmapRewritePass()), 1);
        TransformService service = new TransformService(
                new TransformCache(cacheRoot), pipeline, BootSequence.LOADER_VERSION, work);
        ClassIndex hierarchy = new ClassIndex();
        PassContext.GameSideTarget target =
                side == GameSide.SERVER
                        ? PassContext.GameSideTarget.SERVER
                        : PassContext.GameSideTarget.CLIENT;

        String mappingsHash = Integer.toHexString(mappings.describe().hashCode());
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
                        mappingsHash,
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
}
