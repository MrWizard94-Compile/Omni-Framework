package org.omniframework.loader.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.diag.OmniIssue;
import org.omniframework.loader.model.json.Json;
import org.omniframework.loader.model.json.JsonParseException;
import org.omniframework.loader.model.json.JsonReader;

/**
 * Reads {@code omni.mod.json} schema 0 into {@link ModMetadata} (LDR-6, ARCHITECTURE §5).
 *
 * <p>Four rules from the specification shape this parser, each earned from a corpus finding:
 *
 * <ol>
 *   <li><b>{@code schema} is mandatory and versioned forever.</b> fabric-loader's V0/V1 split
 *       showed what happens when a manifest format has to grow without a declared version:
 *       every reader must guess, and guesses diverge. A manifest without {@code schema} is
 *       rejected outright, not assumed to be v0.
 *   <li><b>Unknown keys warn, never fail.</b> A mod built against a newer loader must still
 *       load on an older one if its actual requirements are met. Failing on unknown keys makes
 *       every additive change a breaking change.
 *   <li><b>{@code ${jar.version}} substitution.</b> Build tooling stamps the version into the
 *       jar manifest; forcing authors to also hand-edit the mod manifest guarantees the two
 *       drift (the fancymodloader lesson).
 *   <li><b>Validation errors are typed issues naming the exact path.</b> Every problem is
 *       reported against a location like {@code depends.somelib.version}, and parsing
 *       <em>collects</em> rather than throwing at the first fault, so one launch reports every
 *       error instead of one per attempt.
 * </ol>
 */
public final class OmniModJson {

    /** The only schema version this build understands. */
    public static final int SCHEMA_VERSION = 0;

    /** The file name, at the jar root. */
    public static final String FILE_NAME = "omni.mod.json";

    /** Keys recognised at the manifest's top level; anything else warns. */
    private static final Set<String> KNOWN_KEYS = Set.of(
            "schema", "id", "group", "version", "name", "description", "authors", "license",
            "contact", "environment", "entrypoints", "depends", "recommends", "suggests",
            "breaks", "conflicts", "provides", "jars", "mixins", "access", "ext");

    /** A mod id: lowercase alphanumerics, underscore and dash, starting with a letter. */
    private static final java.util.regex.Pattern MOD_ID =
            java.util.regex.Pattern.compile("[a-z][a-z0-9_\\-]{1,63}");

    private OmniModJson() {
        // Static entry points only.
    }

    /**
     * Parses a manifest.
     *
     * @param text the manifest source
     * @param substitutions values for {@code ${...}} placeholders, typically
     *     {@code jar.version} from the jar's {@code Implementation-Version}
     * @param issues collects every problem found; check {@link IssueCollector#hasErrors()}
     * @return the metadata, or empty when a blocking issue prevented it
     */
    public static Optional<ModMetadata> parse(
            String text, Map<String, String> substitutions, IssueCollector issues) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(substitutions, "substitutions");
        Objects.requireNonNull(issues, "issues");

        Json root;
        try {
            root = JsonReader.parse(text);
        } catch (JsonParseException malformed) {
            issues.add(OmniIssue.error("manifest.malformed")
                    .at(FILE_NAME)
                    .with(malformed.getMessage())
                    .remedy("Fix the JSON syntax at the reported line and column.")
                    .causedBy(malformed)
                    .build());
            return Optional.empty();
        }

        Optional<Json.Obj> maybeObject = root.asObject();
        if (maybeObject.isEmpty()) {
            issues.add(OmniIssue.error("manifest.not-an-object")
                    .at(FILE_NAME)
                    .with(root.kind())
                    .remedy("The manifest must be a JSON object: { \"schema\": 0, ... }")
                    .build());
            return Optional.empty();
        }
        Json.Obj object = maybeObject.get();

        if (!checkSchema(object, issues)) {
            return Optional.empty();
        }
        warnUnknownKeys(object, issues);

        Optional<String> id = requiredString(object, "id", issues);
        Optional<String> versionText =
                requiredString(object, "version", issues).map(raw -> substitute(raw, substitutions, issues));
        if (id.isEmpty() || versionText.isEmpty()) {
            return Optional.empty();
        }
        if (!MOD_ID.matcher(id.get()).matches()) {
            issues.add(OmniIssue.error("manifest.invalid-id")
                    .at("id")
                    .with(id.get())
                    .remedy("Mod ids are 2–64 characters: a lowercase letter followed by "
                            + "lowercase letters, digits, underscores or dashes.")
                    .build());
            return Optional.empty();
        }
        if (versionText.get().isBlank()) {
            issues.add(OmniIssue.error("manifest.blank-version")
                    .at("version")
                    .remedy("Give the mod a version, e.g. \"1.0.0\".")
                    .build());
            return Optional.empty();
        }

        ModVersion version = ModVersion.parse(versionText.get());
        if (!version.isSemantic()) {
            // Not fatal: the mod loads, but ranges cannot match it, so say so once here
            // rather than leaving the author to discover it through a resolver failure.
            issues.add(OmniIssue.warning("manifest.non-semantic-version")
                    .at("version")
                    .with(version.raw())
                    .remedy("Use Semantic Versioning (1.2.3) so other mods can depend on "
                            + "version ranges of this one.")
                    .build());
        }

        ModMetadata.Builder builder = ModMetadata.builder(id.get(), version);
        optionalString(object, "group", issues).ifPresent(builder::group);
        optionalString(object, "name", issues).ifPresent(builder::name);
        optionalString(object, "description", issues).ifPresent(builder::description);
        optionalString(object, "license", issues).ifPresent(builder::license);
        optionalString(object, "access", issues).ifPresent(builder::accessFile);

        readEnvironment(object, issues).ifPresent(builder::environment);
        readAuthors(object, issues).forEach(builder::author);
        readContact(object, issues).forEach(builder::contact);
        readEntrypoints(object, issues).forEach(builder::entrypoint);
        readDependencies(object, issues).forEach(builder::dependency);
        readProvides(object, substitutions, issues).forEach(builder::provides);
        readStringArray(object, "jars", issues).forEach(builder::nestedJar);
        readStringArray(object, "mixins", issues).forEach(builder::mixinConfig);
        readExtensions(object, issues).forEach(builder::extension);

        return issues.hasErrors() ? Optional.empty() : Optional.of(builder.build());
    }

    /** Parses with no placeholder substitutions. */
    public static Optional<ModMetadata> parse(String text, IssueCollector issues) {
        return parse(text, Map.of(), issues);
    }

    // ------------------------------------------------------------ top level

    private static boolean checkSchema(Json.Obj object, IssueCollector issues) {
        Optional<Json> schema = object.get("schema");
        if (schema.isEmpty()) {
            issues.add(OmniIssue.error("manifest.missing-schema")
                    .at("schema")
                    .remedy("Add \"schema\": " + SCHEMA_VERSION + " as the first key. It is "
                            + "mandatory so this file can evolve without ambiguity.")
                    .build());
            return false;
        }
        if (!(schema.get() instanceof Json.Num number)) {
            issues.add(OmniIssue.error("manifest.invalid-schema")
                    .at("schema")
                    .with(schema.get().kind())
                    .remedy("\"schema\" must be the number " + SCHEMA_VERSION + ".")
                    .build());
            return false;
        }
        int declared = (int) number.value();
        if (declared != SCHEMA_VERSION) {
            issues.add(OmniIssue.error("manifest.unsupported-schema")
                    .at("schema")
                    .with(number.raw(), String.valueOf(SCHEMA_VERSION))
                    .remedy(declared > SCHEMA_VERSION
                            ? "This mod needs a newer OmniLoader. Update the loader."
                            : "This manifest uses a retired schema. Update the mod.")
                    .build());
            return false;
        }
        return true;
    }

    private static void warnUnknownKeys(Json.Obj object, IssueCollector issues) {
        for (String key : object.members().keySet()) {
            if (!KNOWN_KEYS.contains(key)) {
                issues.add(OmniIssue.warning("manifest.unknown-key")
                        .at(key)
                        .with(key)
                        .remedy("This key is ignored. Namespaced custom data belongs under "
                                + "\"ext\", e.g. \"ext\": { \"yourmod:key\": ... }.")
                        .build());
            }
        }
    }

    // ------------------------------------------------------------- sections

    private static Optional<Environment> readEnvironment(Json.Obj object, IssueCollector issues) {
        Optional<String> text = optionalString(object, "environment", issues);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        Optional<Environment> parsed = Environment.parse(text.get());
        if (parsed.isEmpty()) {
            issues.add(OmniIssue.error("manifest.invalid-environment")
                    .at("environment")
                    .with(text.get())
                    .remedy("Use \"client\", \"server\" or \"both\".")
                    .build());
        }
        return parsed;
    }

    private static List<String> readAuthors(Json.Obj object, IssueCollector issues) {
        return readStringArray(object, "authors", issues);
    }

    private static Map<String, String> readContact(Json.Obj object, IssueCollector issues) {
        Map<String, String> contact = new LinkedHashMap<>();
        Optional<Json> value = object.get("contact");
        if (value.isEmpty()) {
            return contact;
        }
        Optional<Json.Obj> asObject = value.get().asObject();
        if (asObject.isEmpty()) {
            issues.add(typeError("contact", "object", value.get()));
            return contact;
        }
        asObject.get().members().forEach((key, entry) -> {
            Optional<String> text = entry.asString();
            if (text.isPresent()) {
                contact.put(key, text.get());
            } else {
                issues.add(typeError("contact." + key, "string", entry));
            }
        });
        return contact;
    }

    private static List<Entrypoint> readEntrypoints(Json.Obj object, IssueCollector issues) {
        List<Entrypoint> entrypoints = new ArrayList<>();
        Optional<Json> value = object.get("entrypoints");
        if (value.isEmpty()) {
            return entrypoints;
        }
        Optional<Json.Obj> asObject = value.get().asObject();
        if (asObject.isEmpty()) {
            issues.add(typeError("entrypoints", "object", value.get()));
            return entrypoints;
        }
        asObject.get().members().forEach((phase, declared) -> {
            Optional<Json.Arr> array = declared.asArray();
            if (array.isEmpty()) {
                issues.add(typeError("entrypoints." + phase, "array", declared));
                return;
            }
            List<Json> elements = array.get().elements();
            for (int i = 0; i < elements.size(); i++) {
                String path = "entrypoints." + phase + "[" + i + "]";
                Json element = elements.get(i);
                Optional<String> plain = element.asString();
                if (plain.isPresent()) {
                    entrypoints.add(Entrypoint.java(phase, plain.get()));
                    continue;
                }
                Optional<Json.Obj> adapted = element.asObject();
                if (adapted.isEmpty()) {
                    issues.add(typeError(path, "string or object", element));
                    continue;
                }
                Optional<String> target = adapted.get().get("value").flatMap(Json::asString);
                if (target.isEmpty()) {
                    issues.add(OmniIssue.error("manifest.entrypoint-missing-value")
                            .at(path)
                            .remedy("An adapted entrypoint needs \"value\": \"the.Class\".")
                            .build());
                    continue;
                }
                Optional<String> adapter = adapted.get().get("adapter").flatMap(Json::asString);
                entrypoints.add(new Entrypoint(phase, target.get(), adapter));
            }
        });
        return entrypoints;
    }

    private static List<Dependency> readDependencies(Json.Obj object, IssueCollector issues) {
        List<Dependency> dependencies = new ArrayList<>();
        for (Dependency.Kind kind : Dependency.Kind.values()) {
            Optional<Json> value = object.get(kind.manifestKey());
            if (value.isEmpty()) {
                continue;
            }
            Optional<Json.Obj> asObject = value.get().asObject();
            if (asObject.isEmpty()) {
                issues.add(typeError(kind.manifestKey(), "object", value.get()));
                continue;
            }
            asObject.get().members().forEach((modId, constraint) ->
                    readDependency(kind, modId, constraint, issues).ifPresent(dependencies::add));
        }
        return dependencies;
    }

    private static Optional<Dependency> readDependency(
            Dependency.Kind kind, String modId, Json constraint, IssueCollector issues) {
        String base = kind.manifestKey() + "." + modId;

        // Shorthand: "somelib": ">=2.0"
        Optional<String> shorthand = constraint.asString();
        if (shorthand.isPresent()) {
            return parsePredicate(shorthand.get(), base, issues)
                    .map(predicate -> new Dependency(
                            kind, modId, predicate, Dependency.LoadOrder.NONE, Optional.empty()));
        }

        // Long form: "somelib": { "version": ">=2.0", "order": "after", "reason": "…" }
        Optional<Json.Obj> asObject = constraint.asObject();
        if (asObject.isEmpty()) {
            issues.add(typeError(base, "string or object", constraint));
            return Optional.empty();
        }
        Json.Obj entry = asObject.get();
        Optional<String> versionText = entry.get("version").flatMap(Json::asString);
        Optional<VersionPredicate> predicate = versionText.isPresent()
                ? parsePredicate(versionText.get(), base + ".version", issues)
                : Optional.of(VersionPredicate.any());
        if (predicate.isEmpty()) {
            return Optional.empty();
        }

        Dependency.LoadOrder order = Dependency.LoadOrder.NONE;
        Optional<String> orderText = entry.get("order").flatMap(Json::asString);
        if (orderText.isPresent()) {
            Optional<Dependency.LoadOrder> parsed = Dependency.LoadOrder.parse(orderText.get());
            if (parsed.isEmpty()) {
                issues.add(OmniIssue.error("manifest.invalid-order")
                        .at(base + ".order")
                        .with(orderText.get())
                        .remedy("Use \"before\", \"after\" or omit the key.")
                        .build());
                return Optional.empty();
            }
            order = parsed.get();
        }
        Optional<String> reason = entry.get("reason").flatMap(Json::asString);
        return Optional.of(new Dependency(kind, modId, predicate.get(), order, reason));
    }

    private static Optional<VersionPredicate> parsePredicate(
            String text, String path, IssueCollector issues) {
        Optional<VersionPredicate> predicate = VersionPredicate.expand(text);
        if (predicate.isEmpty()) {
            issues.add(OmniIssue.error("manifest.invalid-version-range")
                    .at(path)
                    .with(text)
                    .remedy("Use a range like \">=1.2.3\", \"^1.2.3\", an exact \"1.2.3\", "
                            + "or \"*\" for any version.")
                    .build());
        }
        return predicate;
    }

    private static List<ProvidedMod> readProvides(
            Json.Obj object, Map<String, String> substitutions, IssueCollector issues) {
        List<ProvidedMod> provided = new ArrayList<>();
        Optional<Json> value = object.get("provides");
        if (value.isEmpty()) {
            return provided;
        }
        Optional<Json.Arr> array = value.get().asArray();
        if (array.isEmpty()) {
            issues.add(typeError("provides", "array", value.get()));
            return provided;
        }
        List<Json> elements = array.get().elements();
        for (int i = 0; i < elements.size(); i++) {
            String path = "provides[" + i + "]";
            Optional<Json.Obj> entry = elements.get(i).asObject();
            if (entry.isEmpty()) {
                issues.add(typeError(path, "object", elements.get(i)));
                continue;
            }
            Optional<String> id = entry.get().get("id").flatMap(Json::asString);
            Optional<String> versionText = entry.get().get("version").flatMap(Json::asString);
            if (id.isEmpty() || versionText.isEmpty()) {
                issues.add(OmniIssue.error("manifest.invalid-provides")
                        .at(path)
                        .remedy("Each entry needs \"id\" and \"version\".")
                        .build());
                continue;
            }
            provided.add(new ProvidedMod(
                    id.get(), ModVersion.parse(substitute(versionText.get(), substitutions, issues))));
        }
        return provided;
    }

    private static Map<String, Object> readExtensions(Json.Obj object, IssueCollector issues) {
        Map<String, Object> extensions = new LinkedHashMap<>();
        Optional<Json> value = object.get("ext");
        if (value.isEmpty()) {
            return extensions;
        }
        Optional<Json.Obj> asObject = value.get().asObject();
        if (asObject.isEmpty()) {
            issues.add(typeError("ext", "object", value.get()));
            return extensions;
        }
        asObject.get().members().forEach((key, entry) -> {
            if (!key.contains(":")) {
                issues.add(OmniIssue.warning("manifest.unnamespaced-extension")
                        .at("ext." + key)
                        .with(key)
                        .remedy("Namespace custom values to avoid collisions, e.g. "
                                + "\"yourmod:" + key + "\".")
                        .build());
            }
            // Stored as the parsed JSON so no information is lost; consumers that
            // understand a namespace know the shape they wrote.
            extensions.put(key, entry);
        });
        return extensions;
    }

    // --------------------------------------------------------------- helpers

    private static List<String> readStringArray(Json.Obj object, String key, IssueCollector issues) {
        List<String> values = new ArrayList<>();
        Optional<Json> value = object.get(key);
        if (value.isEmpty()) {
            return values;
        }
        Optional<Json.Arr> array = value.get().asArray();
        if (array.isEmpty()) {
            issues.add(typeError(key, "array", value.get()));
            return values;
        }
        List<Json> elements = array.get().elements();
        for (int i = 0; i < elements.size(); i++) {
            Optional<String> text = elements.get(i).asString();
            if (text.isPresent()) {
                values.add(text.get());
            } else {
                issues.add(typeError(key + "[" + i + "]", "string", elements.get(i)));
            }
        }
        return values;
    }

    private static Optional<String> requiredString(
            Json.Obj object, String key, IssueCollector issues) {
        Optional<Json> value = object.get(key);
        if (value.isEmpty()) {
            issues.add(OmniIssue.error("manifest.missing-key")
                    .at(key)
                    .with(key)
                    .remedy("Add \"" + key + "\" to " + FILE_NAME + ".")
                    .build());
            return Optional.empty();
        }
        Optional<String> text = value.get().asString();
        if (text.isEmpty()) {
            issues.add(typeError(key, "string", value.get()));
        }
        return text;
    }

    private static Optional<String> optionalString(
            Json.Obj object, String key, IssueCollector issues) {
        Optional<Json> value = object.get(key);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> text = value.get().asString();
        if (text.isEmpty()) {
            issues.add(typeError(key, "string", value.get()));
        }
        return text;
    }

    private static OmniIssue typeError(String path, String expected, Json actual) {
        return OmniIssue.error("manifest.wrong-type")
                .at(path)
                .with(expected, actual.kind())
                .remedy("Expected " + expected + " here but found " + actual.kind() + ".")
                .build();
    }

    /**
     * Replaces {@code ${key}} placeholders. An unknown placeholder is a warning and is left
     * verbatim — substituting an empty string would produce a mod whose version is silently
     * wrong, which is worse than one that visibly still says {@code ${jar.version}}.
     */
    private static String substitute(
            String text, Map<String, String> substitutions, IssueCollector issues) {
        if (text.indexOf('$') < 0) {
            return text;
        }
        StringBuilder result = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            int open = text.indexOf("${", index);
            if (open < 0) {
                result.append(text, index, text.length());
                break;
            }
            int close = text.indexOf('}', open + 2);
            if (close < 0) {
                result.append(text, index, text.length());
                break;
            }
            result.append(text, index, open);
            String key = text.substring(open + 2, close);
            String replacement = substitutions.get(key);
            if (replacement == null) {
                issues.add(OmniIssue.warning("manifest.unknown-placeholder")
                        .at("version")
                        .with(key)
                        .remedy("No value was supplied for ${" + key + "}. Check the build "
                                + "tooling that stamps it, e.g. the jar's Implementation-Version.")
                        .build());
                result.append(text, open, close + 1);
            } else {
                result.append(replacement);
            }
            index = close + 1;
        }
        return result.toString();
    }
}
