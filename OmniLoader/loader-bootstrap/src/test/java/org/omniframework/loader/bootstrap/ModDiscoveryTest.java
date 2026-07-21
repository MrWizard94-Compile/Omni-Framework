package org.omniframework.loader.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.diag.OmniIssue;

/** Discovery of native mods from a real mods folder (B1, step 2). */
class ModDiscoveryTest {

    private static String manifest(String id) {
        return """
                { "schema": 0, "id": "%s", "version": "1.0.0", "name": "%s" }
                """.formatted(id, id);
    }

    /** Writes a jar holding the given entries. */
    private static Path jar(Path dir, String name, java.util.Map<String, String> entries)
            throws IOException {
        Path path = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(path);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return path;
    }

    private static Path nativeJar(Path dir, String fileName, String id) throws IOException {
        return jar(dir, fileName, java.util.Map.of(
                ModDiscovery.NATIVE_MANIFEST, manifest(id),
                "net/example/Mod.class", "not real bytecode, discovery never loads it"));
    }

    @Test
    @DisplayName("a native jar becomes a candidate with its declared id")
    void findsNativeMod(@TempDir Path dir) throws IOException {
        nativeJar(dir, "examplemod.jar", "examplemod");
        IssueCollector issues = new IssueCollector();

        DiscoveredMods found = ModDiscovery.discover(dir, issues);

        assertEquals(1, found.nativeCount());
        assertEquals("examplemod", found.nativeMods().get(0).id());
        assertEquals(0, found.unclaimedCount());
        assertFalse(issues.hasErrors(), () -> issues.issues().toString());
    }

    @Test
    @DisplayName("a jar with no omni.mod.json is unclaimed, not an error")
    void nonNativeJarIsUnclaimed(@TempDir Path dir) throws IOException {
        // This is the overwhelmingly common case today: a Fabric or NeoForge jar. It must be
        // counted and set aside for a compat claimer, never treated as broken.
        jar(dir, "fabricmod.jar", java.util.Map.of(
                "fabric.mod.json", "{}", "net/example/Fabric.class", "x"));
        IssueCollector issues = new IssueCollector();

        DiscoveredMods found = ModDiscovery.discover(dir, issues);

        assertEquals(0, found.nativeCount());
        assertEquals(1, found.unclaimedCount());
        assertFalse(issues.hasErrors(), "an unrecognised jar is not an error");
    }

    @Test
    @DisplayName("disabled and backup jars are skipped entirely")
    void skipsDisabledJars(@TempDir Path dir) throws IOException {
        nativeJar(dir, "on.jar", "on");
        nativeJar(dir, "off.jar.disabled", "off");
        nativeJar(dir, "old.jar.bak", "old");
        IssueCollector issues = new IssueCollector();

        DiscoveredMods found = ModDiscovery.discover(dir, issues);

        assertEquals(List.of("on"), found.nativeMods().stream().map(m -> m.id()).toList(),
                "only the enabled jar should be discovered");
        assertEquals(0, found.unclaimedCount());
    }

    @Test
    @DisplayName("a corrupt jar is reported against its name and set aside")
    void corruptJarIsReported(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("broken.jar"), "this is not a zip file");
        IssueCollector issues = new IssueCollector();

        DiscoveredMods found = ModDiscovery.discover(dir, issues);

        assertEquals(0, found.nativeCount());
        assertEquals(0, found.unclaimedCount(), "a broken file is not a mod awaiting a layer");
        OmniIssue issue = issues.errors().get(0);
        assertEquals("discovery.jar-unreadable", issue.code());
        assertTrue(issue.location().contains("broken.jar"), issue::describe);
    }

    @Test
    @DisplayName("a jar with a malformed omni.mod.json is skipped with an error")
    void malformedManifestIsReported(@TempDir Path dir) throws IOException {
        jar(dir, "bad.jar", java.util.Map.of(ModDiscovery.NATIVE_MANIFEST, "{ not json"));
        IssueCollector issues = new IssueCollector();

        DiscoveredMods found = ModDiscovery.discover(dir, issues);

        assertEquals(0, found.nativeCount(),
                "a mod whose manifest will not parse must not load half-understood");
        assertTrue(issues.hasErrors());
    }

    @Test
    @DisplayName("a missing mods directory is an empty result, not an error")
    void missingDirectoryIsEmpty(@TempDir Path dir) {
        // A no-mods (vanilla) instance is valid; discovery must not manufacture a failure.
        DiscoveredMods found = ModDiscovery.discover(dir.resolve("does-not-exist"), new IssueCollector());

        assertEquals(0, found.nativeCount());
        assertEquals(0, found.unclaimedCount());
    }

    @Test
    @DisplayName("a real-shaped pack: many jars, none native yet")
    void mixedPackIsHonestlyCounted(@TempDir Path dir) throws IOException {
        // The truth about the director's packs today: a folder full of Fabric/NeoForge jars and
        // one native test mod. Discovery reports exactly that split rather than pretending.
        nativeJar(dir, "omni-testmod.jar", "testmod");
        for (int i = 0; i < 40; i++) {
            jar(dir, "hosted" + i + ".jar", java.util.Map.of("neoforge.mods.toml", "modId=\"m" + i + "\""));
        }
        IssueCollector issues = new IssueCollector();

        DiscoveredMods found = ModDiscovery.discover(dir, issues);

        assertEquals(1, found.nativeCount());
        assertEquals(0, found.hostedCount());
        assertEquals(40, found.unclaimedCount());
        assertFalse(issues.hasErrors());
        assertTrue(
                found.describe().contains("1 native")
                        && found.describe().contains("40 unclaimed"),
                found::describe);
    }
}
