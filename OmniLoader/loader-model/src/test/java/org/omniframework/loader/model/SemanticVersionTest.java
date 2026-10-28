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
    @DisplayName("multi-plus build tags used by real mods still parse (0.7.6+1.21+neoforge)")
    void multiPlusBuildMetadataParses() {
        SemanticVersion v = SemanticVersion.parse("0.7.6+1.21+neoforge").orElseThrow();
        assertEquals(0, v.major());
        assertEquals(7, v.minor());
        assertEquals(6, v.patch());
        assertTrue(v.buildMetadata().isPresent());
        assertTrue(
                VersionPredicate.expand(">=0")
                        .orElseThrow()
                        .matches(ModVersion.parse("0.7.6+1.21+neoforge")));
    }

    @Test
    @DisplayName("letter suffixes on components parse for Create-style versions")
    void letterSuffixComponentsParse() {
        assertEquals(2, SemanticVersion.parse("1.11.2b").orElseThrow().patch());
        assertTrue(VersionPredicate.expand("[1.11.1,)")
                .orElseThrow()
                .matches(ModVersion.parse("1.11.2b")));
        assertTrue(VersionPredicate.expand("[0.5.1.e,)")
                .orElseThrow()
                .matches(ModVersion.parse("6.0.10")));
    }

    @Test
    @DisplayName("four-part versions order by revision (owo 0.12.15.5-beta > 0.12.15.0)")
    void fourPartRevisionOrders() {
        SemanticVersion lower = SemanticVersion.parse("0.12.15.0+1.21").orElseThrow();
        SemanticVersion higherBeta = SemanticVersion.parse("0.12.15.5-beta.1+1.21").orElseThrow();
        assertEquals(0, lower.revision());
        assertEquals(5, higherBeta.revision());
        assertTrue(higherBeta.compareTo(lower) > 0, "0.12.15.5-beta must sort above 0.12.15.0");
        assertTrue(VersionPredicate.expand("[0.12.15.0+1.21,)")
                .orElseThrow()
                .matches(ModVersion.parse("0.12.15.5-beta.1+1.21")));
        assertTrue(VersionPredicate.expand("[0.12.15.1-beta.6+1.21,)")
                .orElseThrow()
                .matches(ModVersion.parse("0.12.15.5-beta.1+1.21")));
    }

    @Test
    @DisplayName("loader channel suffixes are not pre-releases (2.0-NEOFORGE matches [2.0,))")
    void loaderChannelIsBuildMetadata() {
        SemanticVersion v = SemanticVersion.parse("2.0-NEOFORGE-1.21.1").orElseThrow();
        assertFalse(v.isPreRelease());
        assertTrue(VersionPredicate.expand("[2.0,)")
                .orElseThrow()
                .matches(ModVersion.parse("2.0-NEOFORGE-1.21.1")));
    }

    @Test
    @DisplayName("hybrid MC-mod versions order by the mod half")
    void hybridMcModVersions() {
        SemanticVersion pin = SemanticVersion.parse("1.21.1-1.2.10").orElseThrow();
        assertEquals(1, pin.major());
        assertEquals(2, pin.minor());
        assertEquals(10, pin.patch());
        assertTrue(VersionPredicate.expand(">=1.21.1-1.2.10")
                .orElseThrow()
                .matches(ModVersion.parse("1.3.2")));
        assertTrue(VersionPredicate.expand(">=1.21-1.3")
                .orElseThrow()
                .matches(ModVersion.parse("1.3.2")));
        assertFalse(VersionPredicate.expand(">=1.21.1-1.2.10")
                .orElseThrow()
                .matches(ModVersion.parse("1.1.0")));
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
