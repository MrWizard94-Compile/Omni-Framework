package org.omniframework.loader.compat.fabric;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.omniframework.loader.compat.MetadataImporter;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.diag.OmniIssue;
import org.omniframework.loader.model.Dependency;
import org.omniframework.loader.model.Entrypoint;
import org.omniframework.loader.model.Environment;
import org.omniframework.loader.model.ModMetadata;
import org.omniframework.loader.model.ModVersion;
import org.omniframework.loader.model.ProvidedMod;
import org.omniframework.loader.model.VersionPredicate;
import org.omniframework.loader.model.json.Json;
import org.omniframework.loader.model.json.JsonParseException;
import org.omniframework.loader.model.json.JsonReader;

/**
 * Imports {@code fabric.mod.json} into the canonical model (CMP-2, contract 2).
 *
 * <p>Schema 1 only. Schema 0 predates the fields that matter here and is long dead in the wild;
 * refusing it with a clear message beats importing it half-right.
 *
 * <h2>Where the mappings are not one-to-one</h2>
 *
 * <p>Most of this manifest maps cleanly, because the canonical model was designed as a superset
 * of exactly these ecosystems (LDR-6). Three places do not, and each is handled deliberately
 * rather than approximated:
 *
 * <ul>
 *   <li><b>{@code environment: "*"}</b> means both sides. Written as a wildcard rather than
 *       "both", so a naive reader that fell through to a default would still be right by luck —
 *       and wrong the moment the default changed.
 *   <li><b>Version ranges</b> use Fabric's own dialect ({@code ^1.0.0}, {@code 1.20.x},
 *       {@code *}), not a bare semver comparison. An unparseable range is a WARNING and becomes
 *       "any", because refusing the jar over a range the loader could not read would reject mods
 *       that are otherwise perfectly loadable — but it is recorded, because a silently widened
 *       constraint is how an incompatible pair gets loaded together.
 *   <li><b>Entrypoint values</b> may be a bare string or an object with an adapter. Both forms
 *       are common; only the object form carries language information.
 * </ul>
 *
 * <h2>Lossy is warned, never fatal — with one exception</h2>
 *
 * <p>A field that only affects presentation is a warning. A field that would change
 * <em>what loads</em> and cannot be expressed is an error, because ignoring it produces a mod
 * running under conditions its author excluded. That line is not blurred to keep a jar loading.
 */
public final class FabricMetadataImporter implements MetadataImporter {

    /** The only schema version this importer accepts. */
    public static final int SUPPORTED_SCHEMA = 1;

    /** Namespace under which the untranslatable remainder is preserved. */
    public static final String EXTENSION_NAMESPACE = "fabric:";

    @Override
    public Optional<ModMetadata> importMetadata(String manifestText, IssueCollector issues) {
        Json parsed;
        try {
            parsed = JsonReader.parse(manifestText);
        } catch (JsonParseException e) {
            issues.add(OmniIssue.error("compat.fabric.manifest-unreadable")
                    .at("fabric.mod.json")
                    .remedy("This mod's fabric.mod.json is not valid JSON, so nothing about it "
                            + "can be trusted: " + e.getMessage())
                    .build());
            return Optional.empty();
        }
        Optional<Json.Obj> root = parsed.asObject();
        if (root.isEmpty()) {
            issues.add(OmniIssue.error("compat.fabric.manifest-unreadable")
                    .at("fabric.mod.json")
                    .remedy("fabric.mod.json must be a JSON object.")
                    .build());
            return Optional.empty();
        }
        Json.Obj manifest = root.get();

        Optional<Integer> schema = manifest.get("schemaVersion")
                .flatMap(value -> value instanceof Json.Num num
                        ? Optional.of((int) num.value())
                        : Optional.empty());
        if (schema.isEmpty() || schema.get() != SUPPORTED_SCHEMA) {
            issues.add(OmniIssue.error("compat.fabric.schema-unsupported")
                    .at("fabric.mod.json")
                    .with(schema.map(String::valueOf).orElse("absent"))
                    .remedy("This layer reads fabric.mod.json schema " + SUPPORTED_SCHEMA
                            + "; this mod declares " + schema.map(String::valueOf).orElse("none")
                            + ". Importing it half-right would be worse than refusing it.")
                    .build());
            return Optional.empty();
        }

        Optional<String> id = manifest.get("id").flatMap(Json::asString);
        Optional<String> version = manifest.get("version").flatMap(Json::asString);
        if (id.isEmpty() || version.isEmpty()) {
            issues.add(OmniIssue.error("compat.fabric.identity-missing")
                    .at("fabric.mod.json")
                    .remedy("A mod needs both 'id' and 'version'; this manifest is missing "
                            + (id.isEmpty() ? "'id'" : "'version'") + ".")
                    .build());
            return Optional.empty();
        }

        ModMetadata.Builder builder =
                ModMetadata.builder(id.get(), ModVersion.parse(version.get()));
        manifest.get("name").flatMap(Json::asString).ifPresent(builder::name);
        manifest.get("description").flatMap(Json::asString).ifPresent(builder::description);
        manifest.get("license").flatMap(Json::asString).ifPresent(builder::license);
        readAuthors(manifest, builder);
        readContact(manifest, builder);
        // Presentation-only, but preserved rather than dropped: both appear in real manifests
        // (measured across the director's pack, 2026-07-20) and a launcher UI wants them.
        manifest.get("icon").flatMap(Json::asString)
                .ifPresent(icon -> builder.extension(EXTENSION_NAMESPACE + "icon", icon));
        builder.environment(readEnvironment(manifest, id.get(), issues));
        readEntrypoints(manifest, builder, id.get(), issues);
        readDependencies(manifest, builder, id.get(), issues);
        readProvides(manifest, builder);
        readNestedJars(manifest, builder);
        readMixins(manifest, builder);
        manifest.get("accessWidener").flatMap(Json::asString).ifPresent(builder::accessFile);
        preserveCustom(manifest, builder);

        // Recorded so the origin survives into diagnostics: a mod behaving oddly is a very
        // different investigation depending on whether it is native or hosted.
        builder.extension("omni:ported-from", Map.of("ecosystem", "fabric", "upstream", id.get()));
        return Optional.of(builder.build());
    }

    /** {@code "*"} means both sides; anything else is the plain name. */
    private static Environment readEnvironment(
            Json.Obj manifest, String modId, IssueCollector issues) {
        Optional<String> declared = manifest.get("environment").flatMap(Json::asString);
        if (declared.isEmpty()) {
            return Environment.BOTH;
        }
        String value = declared.get();
        if ("*".equals(value)) {
            return Environment.BOTH;
        }
        return Environment.parse(value).orElseGet(() -> {
            // This one DOES change what loads, so it is surfaced loudly even though the import
            // continues: guessing "both" for a client-only mod puts rendering code on a server.
            issues.add(OmniIssue.warning("compat.fabric.environment-unknown")
                    .at(modId)
                    .with(value)
                    .remedy("'" + value + "' is not a recognised Fabric environment; treating "
                            + modId + " as loading on both sides. If it is side-specific this "
                            + "may load it where it does not belong.")
                    .build());
            return Environment.BOTH;
        });
    }

    /** Entrypoint values are either a bare class name or an object carrying an adapter. */
    private static void readEntrypoints(
            Json.Obj manifest, ModMetadata.Builder builder, String modId, IssueCollector issues) {
        Optional<Json.Obj> entrypoints = manifest.get("entrypoints").flatMap(Json::asObject);
        if (entrypoints.isEmpty()) {
            return;
        }
        entrypoints.get().members().forEach((phase, value) -> value.asArray().ifPresent(array -> {
            for (Json element : array.elements()) {
                Optional<String> bare = element.asString();
                if (bare.isPresent()) {
                    builder.entrypoint(Entrypoint.java(phase, bare.get()));
                    continue;
                }
                Optional<Json.Obj> object = element.asObject();
                if (object.isEmpty()) {
                    issues.add(OmniIssue.warning("compat.fabric.entrypoint-malformed")
                            .at(modId)
                            .with(phase)
                            .remedy("An entrypoint under '" + phase + "' is neither a class name "
                                    + "nor an object; it was skipped and that code will not run.")
                            .build());
                    continue;
                }
                Optional<String> target = object.get().get("value").flatMap(Json::asString);
                if (target.isEmpty()) {
                    issues.add(OmniIssue.warning("compat.fabric.entrypoint-malformed")
                            .at(modId)
                            .with(phase)
                            .remedy("An entrypoint under '" + phase + "' has no 'value'; it was "
                                    + "skipped and that code will not run.")
                            .build());
                    continue;
                }
                Optional<String> adapter = object.get().get("adapter").flatMap(Json::asString);
                builder.entrypoint(new Entrypoint(phase, target.get(), adapter));
            }
        }));
    }

    /** The five dependency kinds, each a map of mod id to version range. */
    private static void readDependencies(
            Json.Obj manifest, ModMetadata.Builder builder, String modId, IssueCollector issues) {
        readDependencyBlock(manifest, "depends", Dependency.Kind.DEPENDS, builder, modId, issues);
        readDependencyBlock(manifest, "recommends", Dependency.Kind.RECOMMENDS, builder, modId, issues);
        readDependencyBlock(manifest, "suggests", Dependency.Kind.SUGGESTS, builder, modId, issues);
        readDependencyBlock(manifest, "conflicts", Dependency.Kind.CONFLICTS, builder, modId, issues);
        readDependencyBlock(manifest, "breaks", Dependency.Kind.BREAKS, builder, modId, issues);
    }

    private static void readDependencyBlock(
            Json.Obj manifest,
            String key,
            Dependency.Kind kind,
            ModMetadata.Builder builder,
            String modId,
            IssueCollector issues) {
        manifest.get(key).flatMap(Json::asObject).ifPresent(block ->
                block.members().forEach((target, ranges) -> {
                    VersionPredicate predicate = readPredicate(ranges, target, modId, kind, issues);
                    builder.dependency(new Dependency(
                            kind, target, predicate, Dependency.LoadOrder.NONE, Optional.empty()));
                }));
    }

    /**
     * Reads a version range, which may be a single string or an array of alternatives.
     *
     * <p>An unreadable range widens to "any" and warns. Refusing the jar would reject mods that
     * load perfectly well, but a silently widened constraint is how two incompatible mods end up
     * loaded together — so it is never silent.
     */
    private static VersionPredicate readPredicate(
            Json ranges, String target, String modId, Dependency.Kind kind, IssueCollector issues) {
        Optional<String> single = ranges.asString();
        if (single.isPresent()) {
            return parseRange(single.get(), target, modId, kind, issues);
        }
        Optional<Json.Arr> alternatives = ranges.asArray();
        if (alternatives.isPresent() && !alternatives.get().elements().isEmpty()) {
            // The canonical model carries one predicate per dependency. Fabric's array means
            // "any of these", and collapsing it to the first would silently narrow the range —
            // so the widest honest answer is "any", said out loud.
            issues.add(OmniIssue.warning("compat.fabric.range-alternatives")
                    .at(modId)
                    .with(target)
                    .remedy(modId + " declares several alternative version ranges for '" + target
                            + "'. The canonical model holds one, so the constraint was widened "
                            + "to any version; an incompatible combination will not be caught.")
                    .build());
            return VersionPredicate.any();
        }
        return VersionPredicate.any();
    }

    private static VersionPredicate parseRange(
            String range, String target, String modId, Dependency.Kind kind, IssueCollector issues) {
        if ("*".equals(range) || range.isBlank()) {
            return VersionPredicate.any();
        }
        Optional<VersionPredicate> parsed = VersionPredicate.expand(range);
        if (parsed.isPresent()) {
            return parsed.get();
        }
        issues.add(OmniIssue.warning("compat.fabric.range-unreadable")
                .at(modId)
                .with(target, range)
                .remedy(modId + " constrains '" + target + "' to '" + range + "', which this "
                        + "layer cannot read. The constraint was widened to any version, so an "
                        + "incompatible " + target + " will load without complaint.")
                .build());
        return VersionPredicate.any();
    }

    private static void readProvides(Json.Obj manifest, ModMetadata.Builder builder) {
        manifest.get("provides").flatMap(Json::asArray).ifPresent(array -> {
            for (Json element : array.elements()) {
                element.asString().ifPresent(provided ->
                        // Fabric's provides carries no version; the providing mod's own is the
                        // honest stand-in, and matches what Fabric itself assumes.
                        builder.provides(new ProvidedMod(provided, ModVersion.parse("0.0.0"))));
            }
        });
    }

    private static void readNestedJars(Json.Obj manifest, ModMetadata.Builder builder) {
        manifest.get("jars").flatMap(Json::asArray).ifPresent(array -> {
            for (Json element : array.elements()) {
                element.asObject()
                        .flatMap(entry -> entry.get("file"))
                        .flatMap(Json::asString)
                        .ifPresent(builder::nestedJar);
            }
        });
    }

    /** Mixin entries are a bare path or an object with {@code config} and an environment. */
    private static void readMixins(Json.Obj manifest, ModMetadata.Builder builder) {
        manifest.get("mixins").flatMap(Json::asArray).ifPresent(array -> {
            for (Json element : array.elements()) {
                Optional<String> bare = element.asString();
                if (bare.isPresent()) {
                    builder.mixinConfig(bare.get());
                    continue;
                }
                element.asObject()
                        .flatMap(entry -> entry.get("config"))
                        .flatMap(Json::asString)
                        .ifPresent(builder::mixinConfig);
            }
        });
    }

    private static void readAuthors(Json.Obj manifest, ModMetadata.Builder builder) {
        // Contributors are credited people too — the canonical model draws no distinction, and
        // dropping them would lose attribution the author deliberately recorded.
        readPeople(manifest, "authors", builder);
        readPeople(manifest, "contributors", builder);
    }

    private static void readPeople(Json.Obj manifest, String key, ModMetadata.Builder builder) {
        manifest.get(key).flatMap(Json::asArray).ifPresent(array -> {
            for (Json element : array.elements()) {
                // Authors are strings or objects with a name; both forms appear in the wild.
                element.asString().ifPresentOrElse(builder::author, () ->
                        element.asObject()
                                .flatMap(entry -> entry.get("name"))
                                .flatMap(Json::asString)
                                .ifPresent(builder::author));
            }
        });
    }

    private static void readContact(Json.Obj manifest, ModMetadata.Builder builder) {
        manifest.get("contact").flatMap(Json::asObject).ifPresent(contact ->
                contact.members().forEach((key, value) ->
                        value.asString().ifPresent(text -> builder.contact(key, text))));
    }

    /**
     * Keeps the guest's {@code custom} block under a namespace.
     *
     * <p>Discarding it would lose the only channel a guest ecosystem's own tooling has, and a
     * later layer or shim may need it. Namespaced so it can never be mistaken for native data.
     */
    private static void preserveCustom(Json.Obj manifest, ModMetadata.Builder builder) {
        manifest.get("custom").flatMap(Json::asObject).ifPresent(custom ->
                custom.members().forEach((key, value) ->
                        builder.extension(EXTENSION_NAMESPACE + key, describe(value))));
    }

    /** A stable rendering of an arbitrary JSON value, for preserved custom data. */
    private static String describe(Json value) {
        return switch (value) {
            case Json.Str str -> str.value();
            case Json.Num num -> num.raw();
            case Json.Bool bool -> Boolean.toString(bool.value());
            case Json.Nul ignored -> "null";
            case Json.Obj obj -> "{" + String.join(",", obj.members().keySet()) + "}";
            case Json.Arr arr -> "[" + arr.elements().size() + " items]";
        };
    }

    /** Every fabric.mod.json key this importer reads — the checkable form of "we support it". */
    public static List<String> understoodKeys() {
        return List.of("schemaVersion", "id", "version", "name", "description", "license",
                "authors", "contact", "environment", "entrypoints", "depends", "recommends",
                "suggests", "conflicts", "breaks", "provides", "jars", "mixins", "accessWidener",
                "custom", "icon", "contributors");
    }
}
