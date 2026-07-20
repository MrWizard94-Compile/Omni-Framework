package org.omniframework.loader.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.diag.OmniIssue;

/**
 * Golden and adversarial manifests (ARCHITECTURE §11).
 *
 * <p>The adversarial half matters more than the golden half. A manifest is untrusted input
 * (SOUL §9) written by hand by thousands of authors, so the parser's behaviour on malformed
 * input is a feature with the same standing as its behaviour on valid input.
 */
class OmniModJsonTest {

    /** The specification's own example, from ARCHITECTURE §5. */
    private static final String GOLDEN =
            """
            {
              "schema": 0,
              "id": "examplemod", "group": "net.example", "version": "1.0.0",
              "name": "Example Mod", "license": "MIT",
              "environment": "both",
              "entrypoints": {
                "main": ["net.example.Init"],
                "client": [{"adapter":"kotlin","value":"net.example.ClientInit"}]
              },
              "depends":    { "omniloader": ">=0.1", "minecraft": "1.21.1",
                              "somelib": { "version": ">=2.0", "order": "after" } },
              "recommends": {}, "suggests": {}, "breaks": {}, "conflicts": {},
              "provides":   [ { "id": "examplecore", "version": "1.0.0" } ],
              "jars":       [ "META-INF/jars/bundled-lib.jar" ],
              "mixins":     [ "examplemod.mixins.json" ],
              "access":     "examplemod.accessomni",
              "ext":        { "omni:ported-from": { "ecosystem": "fabric",
                                                    "upstream": "example-fabric-mod" } }
            }
            """;

    private static ModMetadata parseOrFail(String text) {
        IssueCollector issues = new IssueCollector();
        Optional<ModMetadata> parsed = OmniModJson.parse(text, issues);
        assertFalse(issues.hasErrors(), () -> "unexpected errors: " + issues.errors());
        return parsed.orElseThrow(() -> new AssertionError("parser returned nothing"));
    }

    private static List<OmniIssue> errorsFrom(String text) {
        IssueCollector issues = new IssueCollector();
        Optional<ModMetadata> parsed = OmniModJson.parse(text, issues);
        assertTrue(parsed.isEmpty() || issues.hasErrors(), "a rejected manifest must report why");
        return issues.errors();
    }

    @Nested
    @DisplayName("the specification's example manifest")
    class Golden {

        @Test
        @DisplayName("parses every documented field")
        void parsesEveryField() {
            ModMetadata mod = parseOrFail(GOLDEN);

            assertEquals("examplemod", mod.id());
            assertEquals("net.example", mod.group().orElseThrow());
            assertEquals("1.0.0", mod.version().raw());
            assertTrue(mod.version().isSemantic());
            assertEquals("Example Mod", mod.displayName());
            assertEquals("MIT", mod.license().orElseThrow());
            assertEquals(Environment.BOTH, mod.environment());
            assertEquals("examplemod.accessomni", mod.accessFile().orElseThrow());
            assertEquals(List.of("META-INF/jars/bundled-lib.jar"), mod.nestedJars());
            assertEquals(List.of("examplemod.mixins.json"), mod.mixinConfigs());
        }

        @Test
        @DisplayName("keeps entrypoint phases, order, and language adapters")
        void parsesEntrypoints() {
            ModMetadata mod = parseOrFail(GOLDEN);

            List<Entrypoint> main = mod.entrypointsFor("main");
            assertEquals(1, main.size());
            assertEquals("net.example.Init", main.get(0).value());
            assertFalse(main.get(0).usesAdapter());

            List<Entrypoint> client = mod.entrypointsFor("client");
            assertEquals(1, client.size());
            assertEquals("net.example.ClientInit", client.get(0).value());
            assertEquals("kotlin", client.get(0).adapter().orElseThrow());

            assertTrue(mod.entrypointsFor("server").isEmpty(), "absent phases are simply empty");
        }

        @Test
        @DisplayName("parses both dependency shorthands and separates ordering from requirement")
        void parsesDependencies() {
            ModMetadata mod = parseOrFail(GOLDEN);
            List<Dependency> depends = mod.dependenciesOf(Dependency.Kind.DEPENDS);
            assertEquals(3, depends.size());

            Dependency somelib = depends.stream()
                    .filter(dependency -> dependency.modId().equals("somelib"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(Dependency.LoadOrder.AFTER, somelib.order());
            assertTrue(somelib.matches(ModVersion.parse("2.5.0")));
            assertFalse(somelib.matches(ModVersion.parse("1.9.0")));

            Dependency minecraft = depends.stream()
                    .filter(dependency -> dependency.modId().equals("minecraft"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(Dependency.LoadOrder.NONE, minecraft.order(),
                    "requirement without ordering is the common case");
            assertTrue(minecraft.matches(ModVersion.parse("1.21.1")));
            assertFalse(minecraft.matches(ModVersion.parse("1.21.2")));
        }

        @Test
        @DisplayName("keeps provides and namespaced extension data")
        void parsesProvidesAndExtensions() {
            ModMetadata mod = parseOrFail(GOLDEN);
            assertEquals(1, mod.provides().size());
            assertEquals("examplecore", mod.provides().get(0).id());
            assertTrue(mod.extension("omni:ported-from").isPresent(),
                    "extension data survives so importers lose nothing");
        }
    }

    @Nested
    @DisplayName("schema versioning")
    class Schema {

        @Test
        @DisplayName("a missing schema is rejected, never assumed to be 0")
        void missingSchemaRejected() {
            List<OmniIssue> errors = errorsFrom("""
                    { "id": "mod", "version": "1.0.0" }
                    """);
            assertTrue(errors.stream().anyMatch(issue -> issue.code().equals("manifest.missing-schema")),
                    () -> "expected a missing-schema error, got " + errors);
        }

        @Test
        @DisplayName("a newer schema tells the user to update the loader, not the mod")
        void newerSchemaBlamesTheLoader() {
            List<OmniIssue> errors = errorsFrom("""
                    { "schema": 99, "id": "mod", "version": "1.0.0" }
                    """);
            OmniIssue issue = errors.stream()
                    .filter(candidate -> candidate.code().equals("manifest.unsupported-schema"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(issue.remedy().contains("Update the loader"),
                    () -> "remedy should point at the loader: " + issue.remedy());
        }
    }

    @Nested
    @DisplayName("forward compatibility")
    class UnknownKeys {

        @Test
        @DisplayName("unknown top-level keys warn but never block loading")
        void unknownKeysWarnOnly() {
            IssueCollector issues = new IssueCollector();
            Optional<ModMetadata> mod = OmniModJson.parse("""
                    { "schema": 0, "id": "mod", "version": "1.0.0",
                      "somethingFromTheFuture": { "a": 1 } }
                    """, issues);

            assertTrue(mod.isPresent(), "an additive key must not break an older loader");
            assertFalse(issues.hasErrors());
            assertTrue(issues.issues().stream()
                            .anyMatch(issue -> issue.code().equals("manifest.unknown-key")),
                    "but the user is told it was ignored");
        }

        @Test
        @DisplayName("un-namespaced extension keys warn about collisions")
        void unnamespacedExtensionWarns() {
            IssueCollector issues = new IssueCollector();
            OmniModJson.parse("""
                    { "schema": 0, "id": "mod", "version": "1.0.0",
                      "ext": { "colliding": true } }
                    """, issues);
            assertTrue(issues.issues().stream()
                    .anyMatch(issue -> issue.code().equals("manifest.unnamespaced-extension")));
        }
    }

    @Nested
    @DisplayName("placeholder substitution")
    class Substitution {

        @Test
        @DisplayName("${jar.version} is filled from build tooling")
        void substitutesJarVersion() {
            IssueCollector issues = new IssueCollector();
            ModMetadata mod = OmniModJson.parse("""
                    { "schema": 0, "id": "mod", "version": "${jar.version}" }
                    """, Map.of("jar.version", "2.4.1"), issues).orElseThrow();
            assertEquals("2.4.1", mod.version().raw());
            assertFalse(issues.hasErrors());
        }

        @Test
        @DisplayName("an unresolved placeholder warns and is left visible")
        void unresolvedPlaceholderStaysVisible() {
            IssueCollector issues = new IssueCollector();
            ModMetadata mod = OmniModJson.parse("""
                    { "schema": 0, "id": "mod", "version": "${jar.version}" }
                    """, Map.of(), issues).orElseThrow();
            // Substituting nothing would produce a silently wrong version; leaving the
            // placeholder makes the misconfiguration obvious in every log line.
            assertEquals("${jar.version}", mod.version().raw());
            assertTrue(issues.issues().stream()
                    .anyMatch(issue -> issue.code().equals("manifest.unknown-placeholder")));
        }
    }

    @Nested
    @DisplayName("adversarial input")
    class Adversarial {

        @Test
        @DisplayName("malformed JSON is located by line and column")
        void malformedJsonIsLocated() {
            List<OmniIssue> errors = errorsFrom("{ \"schema\": 0, \"id\": }");
            OmniIssue issue = errors.get(0);
            assertEquals("manifest.malformed", issue.code());
            assertTrue(issue.arguments().get(0).contains("line"),
                    () -> "the location must be in the message: " + issue.arguments());
        }

        @Test
        @DisplayName("every error is collected, so one launch reports them all")
        void collectsAllErrors() {
            IssueCollector issues = new IssueCollector();
            OmniModJson.parse("""
                    { "schema": 0, "id": "mod", "version": "1.0.0",
                      "environment": "sideways",
                      "depends": { "a": ">=broken" },
                      "jars": [ 42 ] }
                    """, issues);
            List<String> codes = issues.errors().stream().map(OmniIssue::code).toList();
            assertTrue(codes.contains("manifest.invalid-environment"), () -> "got " + codes);
            assertTrue(codes.contains("manifest.invalid-version-range"), () -> "got " + codes);
            assertTrue(codes.contains("manifest.wrong-type"), () -> "got " + codes);
        }

        @Test
        @DisplayName("wrong types name the exact path and both types")
        void wrongTypesNameTheExactPath() {
            IssueCollector issues = new IssueCollector();
            OmniModJson.parse("""
                    { "schema": 0, "id": "mod", "version": "1.0.0",
                      "entrypoints": { "main": "not-an-array" } }
                    """, issues);
            OmniIssue issue = issues.errors().stream()
                    .filter(candidate -> candidate.code().equals("manifest.wrong-type"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("entrypoints.main", issue.location());
            assertEquals(List.of("array", "string"), issue.arguments());
        }

        @Test
        @DisplayName("invalid mod ids are rejected with the rule stated")
        void invalidModIds() {
            for (String badId : List.of("Uppercase", "has spaces", "x", "1leading-digit")) {
                List<OmniIssue> errors = errorsFrom(
                        "{ \"schema\": 0, \"id\": \"" + badId + "\", \"version\": \"1.0.0\" }");
                assertTrue(errors.stream()
                                .anyMatch(issue -> issue.code().equals("manifest.invalid-id")),
                        () -> "should reject id " + badId + ", got " + errors);
            }
        }

        @Test
        @DisplayName("a non-semver version loads but warns that ranges cannot match it")
        void nonSemanticVersionWarns() {
            IssueCollector issues = new IssueCollector();
            ModMetadata mod = OmniModJson.parse("""
                    { "schema": 0, "id": "mod", "version": "v3-BETA" }
                    """, issues).orElseThrow();
            assertFalse(issues.hasErrors(), "the mod still loads");
            assertFalse(mod.version().isSemantic());
            assertTrue(issues.issues().stream()
                    .anyMatch(issue -> issue.code().equals("manifest.non-semantic-version")));
        }

        @Test
        @DisplayName("a manifest that is not an object is rejected clearly")
        void nonObjectManifest() {
            List<OmniIssue> errors = errorsFrom("[ 1, 2, 3 ]");
            assertEquals("manifest.not-an-object", errors.get(0).code());
        }
    }
}
