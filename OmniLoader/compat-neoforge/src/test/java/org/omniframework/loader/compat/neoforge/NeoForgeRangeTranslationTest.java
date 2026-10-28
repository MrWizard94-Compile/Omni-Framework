package org.omniframework.loader.compat.neoforge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.omniframework.loader.model.ModVersion;
import org.omniframework.loader.model.VersionPredicate;

/**
 * Drives shipped {@link NeoForgeMetadataImporter#translateNeoForgeRange} — the import path
 * every NeoForge pack dependency takes before the resolver runs.
 */
class NeoForgeRangeTranslationTest {

    private static boolean matches(String mavenOrOmniRange, String installed) {
        VersionPredicate predicate =
                NeoForgeMetadataImporter.translateNeoForgeRange(mavenOrOmniRange).orElseThrow();
        return predicate.matches(ModVersion.parse(installed));
    }

    @ParameterizedTest
    @CsvSource({
        // Exact Maven single-bound (the form that previously matched the raw string \"[1.21.1]\")
        "'[1.21.1]', 1.21.1, true",
        "'[1.21.1]', 1.21.2, false",
        // Closed interval used by many structure/worldgen mods
        "'[1.21, 1.21.1]', 1.21.1, true",
        "'[1.21, 1.21.1]', 1.21.0, true",
        "'[1.21, 1.21.1]', 1.21.2, false",
        "'[1.21, 1.21.3]', 1.21.1, true",
        // Open-ended lower bounds (NeoForge + Minecraft)
        "'[1.21,)', 1.21.1, true",
        "'[1.21.1,)', 1.21.1, true",
        "'[1.21.1,1.21.2)', 1.21.1, true",
        "'[1.21.1,1.21.2)', 1.21.2, false",
        // Hosted NeoForge line that packs declare
        "'[21,)', 21.1.999, true",
        "'[21.1.0,)', 21.1.999, true",
        "'[21.1.72,)', 21.1.999, true",
        "'[21.1.191,)', 21.1.999, true",
        "'[21.1.72,)', 21.1.50, false",
        "'[21.0.0-beta,)', 21.1.999, true",
        // True mismatch still fails
        "'[1.20, 1.20.4]', 1.21.1, false",
        "'[22,)', 21.1.999, false",
    })
    @DisplayName("Maven/NeoForge ranges match installed versions that satisfy them")
    void mavenRangesMatchInstalled(String range, String installed, boolean expected) {
        assertTrue(
                matches(range, installed) == expected,
                () -> range + " vs " + installed + " expected " + expected
                        + " (predicate="
                        + NeoForgeMetadataImporter.translateNeoForgeRange(range).orElseThrow()
                        + ")");
    }

    @Test
    @DisplayName("bracket ranges are not misread as exact raw equality on '[1.21'")
    void bracketCommaIsNotRawExact() {
        // Regression: VersionPredicate.parse alone would treat this as EQUAL "[1.21" AND EQUAL "1.21.1]"
        VersionPredicate brokenIfDirect = VersionPredicate.parse("[1.21, 1.21.1]").orElseThrow();
        assertFalse(
                brokenIfDirect.matches(ModVersion.parse("1.21.1")),
                "direct parse must still be wrong — translator must not use it first");
        assertTrue(matches("[1.21, 1.21.1]", "1.21.1"), "translator path must succeed");
    }

    @Test
    @DisplayName("Omni/semver forms still translate")
    void omniFormsStillWork() {
        assertTrue(matches(">=1.21", "1.21.1"));
        assertTrue(matches("*", "9.9.9"));
        assertTrue(matches("1.21.1", "1.21.1"));
    }

    @Test
    @DisplayName("bare NeoForge version pins mean at-least (library min)")
    void bareVersionIsMinimum() {
        assertTrue(matches("1.6.9+1.21-neoforge", "1.9.3"), "midnightlib-style bump");
        assertTrue(matches("2.3.9-NEOFORGE-1.21.1", "2.3.16.1-NEOFORGE-1.21.1"));
        assertTrue(matches("1.21.1-1.2.10", "1.3.2"), "hybrid MC-mod pin as min");
        assertTrue(matches("1.21.1-1.5.0-neoforge,)", "1.5.10"), "broken half-range recovery");
        assertFalse(matches("3.1", "3.0"), "still fails when installed is older");
    }

    @Test
    @DisplayName("minecraft dep softens exclusive upper so 1.21.1 matches [1.21,1.21.1)")
    void minecraftSoftensHalfOpenUpper() {
        assertFalse(
                matches("[1.21,1.21.1)", "1.21.1"),
                "raw half-open still excludes without dep id");
        assertTrue(
                NeoForgeMetadataImporter.translateNeoForgeRange("[1.21,1.21.1)", "minecraft")
                        .orElseThrow()
                        .matches(ModVersion.parse("1.21.1")));
        assertTrue(
                NeoForgeMetadataImporter.translateNeoForgeRange("[1.21.1,)", "minecraft")
                        .orElseThrow()
                        .matches(ModVersion.parse("1.21.1")));
    }
}
