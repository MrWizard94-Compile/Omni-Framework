package org.omniframework.loader.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Ordering and parsing rules from the Semantic Versioning 2.0.0 specification. */
class SemanticVersionTest {

    @Test
    @DisplayName("parses the full grammar: core, pre-release, build metadata")
    void parsesFullGrammar() {
        SemanticVersion version = SemanticVersion.parse("1.21.1-rc.2+build.7").orElseThrow();
        assertEquals(1, version.major());
        assertEquals(21, version.minor());
        assertEquals(1, version.patch());
        assertEquals(List.of("rc", "2"), version.preRelease());
        assertEquals("build.7", version.buildMetadata().orElseThrow());
        assertTrue(version.isPreRelease());
        assertEquals("1.21.1-rc.2+build.7", version.toString(), "round-trips verbatim");
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1.2.3", "01.2.3", "1.2.3-", "1.2.3+", "", "  ",
            "1.2.x", "-1.2.3", "1.2.3-01"})
    @DisplayName("rejects text that is not valid semver, without throwing")
    void rejectsInvalid(String text) {
        assertTrue(SemanticVersion.parse(text).isEmpty(), () -> "should reject: " + text);
    }

    @Test
    @DisplayName("one- and two-component versions pad to major.minor.patch")
    void padsPartialCore() {
        // Manifests (and platform java versions) write "21" / "1.21" constantly.
        SemanticVersion java = SemanticVersion.parse("21").orElseThrow();
        assertEquals(21, java.major());
        assertEquals(0, java.minor());
        assertEquals(0, java.patch());
        SemanticVersion mc = SemanticVersion.parse("1.21").orElseThrow();
        assertEquals(1, mc.major());
        assertEquals(21, mc.minor());
        assertEquals(0, mc.patch());
    }

    @Test
    @DisplayName("numeric components compare numerically, not as text")
    void comparesNumerically() {
        SemanticVersion nine = SemanticVersion.parse("1.0.9").orElseThrow();
        SemanticVersion ten = SemanticVersion.parse("1.0.10").orElseThrow();
        assertTrue(nine.compareTo(ten) < 0, "1.0.9 must sort below 1.0.10");
        // The failure this guards against: "1.0.10".compareTo("1.0.9") is positive as text.
        assertTrue("1.0.10".compareTo("1.0.9") < 0, "text comparison is wrong, hence this type");
    }

    @Test
    @DisplayName("a pre-release sorts below its own release")
    void preReleaseSortsBelowRelease() {
        SemanticVersion candidate = SemanticVersion.parse("1.0.0-rc.1").orElseThrow();
        SemanticVersion release = SemanticVersion.parse("1.0.0").orElseThrow();
        assertTrue(candidate.compareTo(release) < 0);
        assertTrue(release.compareTo(candidate) > 0);
    }

    @Test
    @DisplayName("orders the specification's own example chain")
    void ordersSpecificationExample() {
        // Straight from semver.org §11.
        List<String> expected = List.of(
                "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta",
                "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0");
        List<SemanticVersion> parsed = new ArrayList<>(
                expected.stream().map(text -> SemanticVersion.parse(text).orElseThrow()).toList());
        java.util.Collections.shuffle(parsed, new java.util.Random(42));
        parsed.sort(SemanticVersion.ascending());
        assertEquals(expected, parsed.stream().map(SemanticVersion::toString).toList());
    }

    @Test
    @DisplayName("build metadata is retained but ignored when comparing")
    void buildMetadataIgnoredInComparison() {
        SemanticVersion left = SemanticVersion.parse("1.0.0+sha.aaa").orElseThrow();
        SemanticVersion right = SemanticVersion.parse("1.0.0+sha.bbb").orElseThrow();
        assertEquals(0, left.compareTo(right), "precedence ignores build metadata");
        assertTrue(left.samePrecedence(right));
        assertFalse(left.equals(right), "but the values are still distinct");
    }

    @Test
    @DisplayName("absurdly long numeric pre-release identifiers do not overflow")
    void hugeNumericIdentifiersCompareSafely() {
        SemanticVersion small = SemanticVersion.parse("1.0.0-1").orElseThrow();
        SemanticVersion huge =
                SemanticVersion.parse("1.0.0-99999999999999999999999999").orElseThrow();
        assertTrue(small.compareTo(huge) < 0, "compared as big integers, not ints");
    }
}
