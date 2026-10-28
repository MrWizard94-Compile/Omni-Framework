package org.omniframework.loader.compat.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NeoForgeTomlTest {

    @Test
    void parsesDependencyTables() {
        String toml =
                """
                license = "MIT"
                [[mods]]
                modId = "demo"
                version = "1.0.0"
                [[dependencies.demo]]
                modId = "neoforge"
                type = "required"
                versionRange = "[21,)"
                ordering = "NONE"
                [[dependencies.demo]]
                modId = "minecraft"
                type = "required"
                versionRange = "[1.21.1,1.22)"
                """;
        List<Map<String, String>> deps = NeoForgeToml.dependencyTables(toml);
        assertEquals(2, deps.size());
        assertEquals("demo", deps.get(0).get("_owner"));
        assertEquals("neoforge", deps.get(0).get("modId"));
        assertEquals("[21,)", deps.get(0).get("versionRange"));
    }

    @Test
    void identityFromFirstModsTable() {
        String toml =
                """
                [[mods]]
                modId = "alpha"
                version = "2.0.0"
                """;
        assertTrue(NeoForgeToml.identity(toml).isPresent());
        assertEquals("alpha", NeoForgeToml.identity(toml).orElseThrow().modId());
    }

    @Test
    void modsTableHeaderAcceptsTrailingComment() {
        String toml =
                """
                [[mods]] #mandatory
                modId = "citadel"
                version = "2.7.6"
                """;
        assertEquals("citadel", NeoForgeToml.firstModsTable(toml).get("modId"));
        assertTrue(NeoForgeToml.identity(toml).isPresent());
        assertEquals("citadel", NeoForgeToml.identity(toml).orElseThrow().modId());
    }

    @Test
    void dependencyHeaderAcceptsTrailingComment() {
        String toml =
                """
                [[mods]] #mandatory
                modId = "demo"
                version = "1.0"
                [[dependencies.demo]] #optional
                modId = "neoforge"
                versionRange = "[21,)"
                """;
        var deps = NeoForgeToml.dependencyTables(toml);
        assertEquals(1, deps.size());
        assertEquals("demo", deps.get(0).get("_owner"));
        assertEquals("[21,)", deps.get(0).get("versionRange"));
    }

    @Test
    void stripsInlineCommentsAndQuotesOnVersionRange() {
        String toml =
                """
                [[mods]]
                modId = "demo"
                version = "1.0.0"
                [[dependencies.demo]]
                modId = "minecraft"
                type = "required"
                versionRange = "[1.21.1,)" #optional:any
                """;
        var deps = NeoForgeToml.dependencyTables(toml);
        assertEquals(1, deps.size());
        assertEquals("[1.21.1,)", deps.get(0).get("versionRange"));
        assertTrue(
                NeoForgeMetadataImporter.translateNeoForgeRange(deps.get(0).get("versionRange"))
                        .orElseThrow()
                        .matches(org.omniframework.loader.model.ModVersion.parse("1.21.1")));
    }
}
