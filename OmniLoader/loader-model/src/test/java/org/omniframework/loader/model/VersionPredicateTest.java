package org.omniframework.loader.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The dependency constraint language every manifest writes and the resolver enforces. */
class VersionPredicateTest {

    private static boolean matches(String predicate, String version) {
        return VersionPredicate.expand(predicate).orElseThrow().matches(ModVersion.parse(version));
    }

    @ParameterizedTest
    @CsvSource({
        "*,          1.0.0, true",
        "any,        9.9.9, true",
        "1.21.1,     1.21.1, true",
        "1.21.1,     1.21.2, false",
        "=1.21.1,    1.21.1, true",
        ">=0.1,      0.1.0, true",
        ">=0.1,      0.0.9, false",
        ">=1.2.3,    1.2.3, true",
        ">1.2.3,     1.2.3, false",
        ">1.2.3,     1.2.4, true",
        "<=2.0.0,    2.0.0, true",
        "<2.0.0,     2.0.0, false",
        "<2.0.0,     1.9.9, true",
    })
    @DisplayName("comparison operators behave as written")
    void comparisons(String predicate, String version, boolean expected) {
        assertTrue(matches(predicate, version) == expected,
                () -> predicate + " vs " + version + " should be " + expected);
    }

    @Test
    @DisplayName("caret allows compatible changes, tilde only patch-level ones")
    void caretAndTilde() {
        assertTrue(matches("^1.2.3", "1.2.3"));
        assertTrue(matches("^1.2.3", "1.9.0"), "caret spans the whole major");
        assertFalse(matches("^1.2.3", "2.0.0"), "caret stops at the next major");
        assertFalse(matches("^1.2.3", "1.2.2"), "caret has a lower bound");

        assertTrue(matches("~1.2.3", "1.2.9"), "tilde spans the patch range");
        assertFalse(matches("~1.2.3", "1.3.0"), "tilde stops at the next minor");
    }

    @Test
    @DisplayName("comma-separated terms must all hold")
    void conjunction() {
        assertTrue(matches(">=1.2, <2.0", "1.5.0"));
        assertFalse(matches(">=1.2, <2.0", "2.0.0"));
        assertFalse(matches(">=1.2, <2.0", "1.1.0"));
    }

    @Test
    @DisplayName("partial versions fill in omitted components")
    void partialVersions() {
        assertTrue(matches(">=1.2", "1.2.0"), "\">=1.2\" means \">=1.2.0\"");
        assertTrue(matches(">=1", "1.0.0"));
        // Java platform versions are often written as a single component.
        assertTrue(matches(">=21", "21"), "\">=21\" must match platform java \"21\"");
        assertTrue(matches(">=21", "21.0.0"));
        assertTrue(matches(">=17", "21.0.0"));
    }

    @Test
    @DisplayName("Fabric-style x and * wildcards expand to a bounded range")
    void xWildcards() {
        assertTrue(matches("1.21.x", "1.21.1"));
        assertTrue(matches("1.21.x", "1.21.0"));
        assertFalse(matches("1.21.x", "1.22.0"));
        assertFalse(matches("1.21.x", "1.20.4"));
        assertTrue(matches("21.0.x", "21.0.8"));
        assertTrue(matches("1.21.*", "1.21.1"), "star form used by some Fabric manifests");
    }

    @Test
    @DisplayName("four-part Fabric versions order using the first three components")
    void fourPartFabricVersions() {
        assertTrue(matches(">=0.10.3", "0.12.15.4+1.21"));
        assertTrue(matches(">=0.102.0", "0.116.11+1.21.1"));
        assertTrue(matches(">=1.1.0", "1.26.1.3"), "villagerapi-style four-part");
    }

    @Test
    @DisplayName("Fabric space-separated intervals and trailing dashes parse")
    void fabricIntervals() {
        assertTrue(matches(">=1.21- <1.21.2-", "1.21.1"),
                "fabric-api's minecraft range");
        assertTrue(matches(">=1.21 <1.22", "1.21.1"));
        assertFalse(matches(">=1.21- <1.21.2-", "1.21.2"));
        assertTrue(matches("~1.21-", "1.21.0"));
        assertTrue(matches(">=0.18.6", "0.18.6"));
    }

    @Test
    @DisplayName("a non-semver version matches only an exact textual constraint")
    void nonSemanticVersions() {
        // This is the rule that keeps the resolver honest: ranges cannot silently
        // "succeed" against a version they have no way to order.
        ModVersion odd = ModVersion.parse("nightly-2026-07-19");
        assertFalse(odd.isSemantic());
        assertTrue(VersionPredicate.expand("nightly-2026-07-19").orElseThrow().matches(odd));
        assertFalse(VersionPredicate.expand(">=1.0.0").orElseThrow().matches(odd));
        assertTrue(VersionPredicate.any().matches(odd), "but \"any\" still accepts it");
    }

    @ParameterizedTest
    @ValueSource(strings = {">=", "", "  ", ">=not.a.version", ">>1.0.0", ">=1.0.0,"})
    @DisplayName("unparseable constraints are rejected rather than silently ignored")
    void rejectsGarbage(String text) {
        assertTrue(VersionPredicate.expand(text).isEmpty(), () -> "should reject: " + text);
    }

    @Test
    @DisplayName("expansion keeps the author's original text for diagnostics")
    void expansionPreservesRawText() {
        VersionPredicate predicate = VersionPredicate.expand("^1.2.3").orElseThrow();
        assertTrue(predicate.raw().equals("^1.2.3"),
                "an error message must show what the author wrote, not the expansion");
        assertTrue(predicate.terms().size() == 2, "even though it reasons about two bounds");
    }
}
