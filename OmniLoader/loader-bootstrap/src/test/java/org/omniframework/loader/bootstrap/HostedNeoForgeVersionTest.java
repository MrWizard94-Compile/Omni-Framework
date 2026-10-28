package org.omniframework.loader.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.omniframework.loader.compat.neoforge.NeoForgeMetadataImporter;
import org.omniframework.loader.model.ModVersion;
import org.omniframework.loader.model.VersionPredicate;

/**
 * Hosted platform {@code neoforge}/{@code forge} must not advertise product
 * {@link BootSequence#LOADER_VERSION} ({@code 0.1.0}) — real packs require 21.x.
 */
class HostedNeoForgeVersionTest {

    @Test
    @DisplayName("1.21.1 maps to NeoForge 21.1.x line, not product 0.1.0")
    void mapsMinecraft1211() {
        String hosted = BootSequence.hostedNeoForgeVersion("1.21.1");
        assertEquals("21.1.999", hosted);
        assertFalse(hosted.startsWith("0."), "must not advertise OmniLoader product version");
    }

    @Test
    @DisplayName("hosted version satisfies pack ranges [21,) and [21.1.72,)")
    void satisfiesPackRanges() {
        String hosted = BootSequence.hostedNeoForgeVersion("1.21.1");
        ModVersion version = ModVersion.parse(hosted);
        for (String range : new String[] {"[21,)", "[21.1.0,)", "[21.1.72,)", "[21.1.191,)", "[21.0.0-beta,)"}) {
            VersionPredicate predicate =
                    NeoForgeMetadataImporter.translateNeoForgeRange(range).orElseThrow();
            assertTrue(predicate.matches(version), () -> range + " must accept hosted " + hosted);
        }
        assertFalse(
                NeoForgeMetadataImporter.translateNeoForgeRange("[22,)")
                        .orElseThrow()
                        .matches(version));
    }

    @Test
    @DisplayName("product LOADER_VERSION 0.1.0 fails those same ranges (proves the old bug)")
    void productVersionFailsPackRanges() {
        ModVersion product = ModVersion.parse(BootSequence.LOADER_VERSION);
        assertEquals("0.1.0", product.raw());
        assertFalse(
                NeoForgeMetadataImporter.translateNeoForgeRange("[21,)")
                        .orElseThrow()
                        .matches(product),
                "0.1.0 must not satisfy NeoForge [21,) — that was the mass-fail root cause");
    }

    @Test
    @DisplayName("hosted classic forge is 52.x for MC 1.21.1, not NeoForge 21.1.x")
    void hostedForgeIsNotNeoForgeScheme() {
        String forge = BootSequence.hostedMinecraftForgeVersion("1.21.1");
        assertEquals("52.0.999", forge);
        ModVersion v = ModVersion.parse(forge);
        assertTrue(
                NeoForgeMetadataImporter.translateNeoForgeRange("[51,)")
                        .orElseThrow()
                        .matches(v));
        assertTrue(
                NeoForgeMetadataImporter.translateNeoForgeRange("[52.0.8,)")
                        .orElseThrow()
                        .matches(v));
        // Must not use NeoForge-line version for classic forge identity.
        assertFalse(forge.startsWith("21."));
    }
}
