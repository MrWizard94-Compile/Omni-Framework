package org.omniframework.loader.compat.fabric;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The Fabric Loader API surface this layer must provide, measured rather than estimated.
 *
 * <h2>The distinction this class exists to make</h2>
 *
 * <p>"Supporting Fabric mods" sounds like one problem and is two, with wildly different costs:
 *
 * <ul>
 *   <li><b>{@code net.fabricmc.api.*} and {@code net.fabricmc.loader.api.*}</b> ship inside
 *       <i>Fabric Loader</i>. Nothing can supply them but us — a hosted mod cannot provide the
 *       loader it runs on. <b>66 distinct types</b>, referenced by 304 of 347 mods.
 *   <li><b>{@code net.fabricmc.fabric.api.*}</b> ships inside the <i>Fabric API mod</i>
 *       ({@code fabric-api-0.116.11+1.21.1.jar}, 48 nested modules), which declares dependencies
 *       only on {@code fabricloader}, {@code java} and {@code minecraft}. <b>385 distinct
 *       types</b> — and every one of them arrives for free the moment that jar loads as an
 *       ordinary hosted mod.
 * </ul>
 *
 * <p>Conflating the two turns a bounded job into an unbounded one. The 171 mods that declare a
 * {@code fabric-api} dependency do not need us to reimplement Fabric API; they need one jar to
 * load, and that jar needs the 66 types below.
 *
 * <h2>Provenance</h2>
 *
 * <p>Measured 2026-07-20 by scanning the constant pools of all 347 mod jars in Better MC
 * [FABRIC] BMC3 (Minecraft 1.21.1, fabric-loader 0.19.2) — the director's own pack, on the
 * project's target version. Counts are reference occurrences, so they rank by how much of the
 * ecosystem breaks if a type is missing, which is the order to implement in.
 *
 * <p>Re-measure with {@code ExternalCorpusImportTest} against a newer pack before treating these
 * numbers as current. They describe one pack at one version, which is evidence, not law.
 */
public final class FabricLoaderSurface {

    private FabricLoaderSurface() {}

    /** Total distinct loader-API types observed across the measured corpus. */
    public static final int MEASURED_TYPE_COUNT = 66;

    /** Mods in the measured corpus that reference the loader API at all. */
    public static final int MEASURED_DEPENDENT_MODS = 304;

    /** Size of the measured corpus. */
    public static final int MEASURED_CORPUS_SIZE = 347;

    /**
     * The measured surface, ordered by reference count — implement top-down.
     *
     * <p>The distribution is steep: the first six types account for more references than the
     * remaining sixty combined. A layer that provides only those is already useful, which is why
     * the order is recorded rather than the set alone.
     */
    public static final List<Entry> BY_IMPACT = List.of(
            new Entry("net.fabricmc.api.EnvType", 1389, Tier.CORE),
            new Entry("net.fabricmc.api.Environment", 1157, Tier.CORE),
            new Entry("net.fabricmc.loader.api.FabricLoader", 868, Tier.CORE),
            new Entry("net.fabricmc.loader.api.ModContainer", 393, Tier.CORE),
            new Entry("net.fabricmc.loader.api.metadata.ModMetadata", 253, Tier.CORE),
            new Entry("net.fabricmc.api.ModInitializer", 240, Tier.CORE),
            new Entry("net.fabricmc.api.ClientModInitializer", 206, Tier.CORE),
            new Entry("net.fabricmc.loader.api.Version", 161, Tier.CORE),
            new Entry("net.fabricmc.loader.api.metadata.CustomValue", 60, Tier.METADATA),
            new Entry("net.fabricmc.loader.api.metadata.CustomValue$CvObject", 42, Tier.METADATA),
            new Entry("net.fabricmc.loader.api.entrypoint.EntrypointContainer", 41, Tier.CORE),
            new Entry("net.fabricmc.loader.api.metadata.CustomValue$CvType", 33, Tier.METADATA),
            new Entry("net.fabricmc.loader.api.MappingResolver", 27, Tier.MAPPING),
            new Entry("net.fabricmc.loader.api.metadata.CustomValue$CvArray", 22, Tier.METADATA),
            new Entry("net.fabricmc.loader.api.metadata.ModOrigin", 22, Tier.METADATA),
            new Entry("net.fabricmc.loader.api.metadata.ModOrigin$Kind", 20, Tier.METADATA),
            new Entry("net.fabricmc.loader.api.metadata.ContactInformation", 19, Tier.METADATA),
            new Entry("net.fabricmc.loader.api.metadata.Person", 18, Tier.METADATA),
            new Entry("net.fabricmc.api.DedicatedServerModInitializer", 16, Tier.CORE),
            new Entry("net.fabricmc.loader.api.VersionParsingException", 16, Tier.CORE));

    /** How urgent a type is, and roughly what implementing it involves. */
    public enum Tier {
        /** Needed before any hosted mod initialises at all. */
        CORE,
        /** Needed for mods that introspect their own or others' manifests. */
        METADATA,
        /** Needed for mods that resolve names across mapping namespaces at runtime. */
        MAPPING
    }

    /** One type on the surface, with how often the corpus referenced it. */
    public record Entry(String className, int references, Tier tier) {

        /** Canonical constructor. */
        public Entry {
            Objects.requireNonNull(className, "className");
            Objects.requireNonNull(tier, "tier");
            if (references < 0) {
                throw new IllegalArgumentException("references must not be negative");
            }
        }
    }

    /** The types needed before anything can load — the honest minimum for a first hosted mod. */
    public static List<String> coreTypes() {
        return BY_IMPACT.stream().filter(entry -> entry.tier() == Tier.CORE)
                .map(Entry::className).toList();
    }

    /** Reference counts grouped by tier, for planning order. */
    public static Map<Tier, Integer> referencesByTier() {
        Map<Tier, Integer> totals = new java.util.EnumMap<>(Tier.class);
        for (Entry entry : BY_IMPACT) {
            totals.merge(entry.tier(), entry.references(), Integer::sum);
        }
        return totals;
    }

    /** A sentence for scoping conversations, in numbers rather than adjectives. */
    public static String describe() {
        return MEASURED_TYPE_COUNT + " loader-API types referenced by " + MEASURED_DEPENDENT_MODS
                + " of " + MEASURED_CORPUS_SIZE + " mods; the "
                + coreTypes().size() + " CORE types must exist before any hosted mod initialises. "
                + "Fabric API's own 385 types are NOT in this count — that jar loads as a mod.";
    }
}
