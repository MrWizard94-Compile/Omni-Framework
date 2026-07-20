package org.omniframework.loader.compat.fabric;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.diag.OmniIssue;
import org.omniframework.loader.model.ModMetadata;

/**
 * The importer, run against real {@code fabric.mod.json} files taken from shipped mods.
 *
 * <h2>Why real manifests and not only crafted ones</h2>
 *
 * <p>Hand-written fixtures test what the author already thought of. These four came out of jars
 * on the director's own machine (Geophilic, Moog's Voyager Structures, Terralith, Immersive
 * Portals — extracted 2026-07-20), and they are the reason two fields got handled at all: nothing
 * in the spec made {@code icon} or {@code contributors} feel important until real files used them.
 *
 * <p>They are checked in as resources rather than read from the instance folder, so the suite does
 * not depend on a machine-local pack that can change under it.
 */
class RealManifestImportTest {

    private static final String RESOURCE_DIR = "real";

    private record Imported(String file, Optional<ModMetadata> metadata, List<OmniIssue> issues) { }

    /** Imports every checked-in real manifest. */
    private static List<Imported> importAll() throws IOException, URISyntaxException {
        Path dir = Path.of(
                RealManifestImportTest.class.getClassLoader().getResource(RESOURCE_DIR).toURI());
        List<Imported> results = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                IssueCollector issues = new IssueCollector();
                results.add(new Imported(
                        file.getFileName().toString(),
                        new FabricMetadataImporter().importMetadata(text, issues),
                        issues.issues()));
            }
        }
        return results;
    }

    @Test
    @DisplayName("every real manifest imports without a blocking error")
    void allRealManifestsImport() throws Exception {
        List<Imported> results = importAll();
        assertFalse(results.isEmpty(), "no real manifests were checked in — the measurement is "
                + "the point of this test, so an empty run is a failure");

        for (Imported result : results) {
            assertTrue(result.metadata().isPresent(),
                    () -> result.file() + " failed to import: " + result.issues());
            assertTrue(result.issues().stream().noneMatch(OmniIssue::blocksLoading),
                    () -> result.file() + " produced blocking issues: " + result.issues());
        }

        // Surfaced so the number is visible in the build log rather than inferred.
        System.out.println("[fabric] imported " + results.size() + " real manifests, "
                + results.stream().mapToLong(r -> r.issues().size()).sum() + " warnings total");
        for (Imported result : results) {
            ModMetadata metadata = result.metadata().orElseThrow();
            System.out.println("  " + metadata.id() + " " + metadata.version().raw()
                    + " — " + metadata.dependencies().size() + " deps, "
                    + metadata.entrypoints().size() + " entrypoints, "
                    + result.issues().size() + " warnings");
        }
    }

    @Test
    @DisplayName("identity, dependencies and environment survive the round trip")
    void coreFieldsSurvive() throws Exception {
        for (Imported result : importAll()) {
            ModMetadata metadata = result.metadata().orElseThrow();

            assertFalse(metadata.id().isBlank(), result.file() + " lost its id");
            assertFalse(metadata.version().raw().isBlank(), result.file() + " lost its version");
            // Every real Fabric mod in the sample declares dependencies; losing them silently
            // would let a mod load without the API it needs, which fails much later and looks
            // like a mod bug.
            assertFalse(metadata.dependencies().isEmpty(),
                    result.file() + " lost all its dependencies");
        }
    }

    @Test
    @DisplayName("the Fabric API dependency is preserved, because it is the whole problem")
    void fabricApiDependencyIsVisible() throws Exception {
        // Almost every real Fabric mod depends on fabric-api, a surface this layer does not
        // provide yet. Preserving the declaration is what makes that gap show up at resolution
        // — as a named missing dependency — instead of as NoSuchMethodError deep in gameplay.
        long dependingOnApi = importAll().stream()
                .map(result -> result.metadata().orElseThrow())
                .filter(metadata -> metadata.dependencies().stream()
                        .anyMatch(dependency -> dependency.modId().equals("fabric-api")))
                .count();

        System.out.println("[fabric] " + dependingOnApi + " of the real manifests require "
                + "fabric-api");
        assertTrue(dependingOnApi > 0,
                "the sample should contain at least one fabric-api dependent; if not, the "
                        + "sample is not representative of the ecosystem");
    }

    @Test
    @DisplayName("no real manifest uses a key the importer silently ignores")
    void noUnhandledKeysInRealManifests() throws Exception {
        // The honest version of "we support fabric.mod.json": every key these files actually
        // use is one the importer reads. A key nobody handles is data thrown away in silence.
        List<String> understood = FabricMetadataImporter.understoodKeys();
        List<String> unhandled = new ArrayList<>();

        Path dir = Path.of(getClass().getClassLoader().getResource(RESOURCE_DIR).toURI());
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                org.omniframework.loader.model.json.Json parsed =
                        org.omniframework.loader.model.json.JsonReader.parse(text);
                parsed.asObject().ifPresent(obj -> obj.members().keySet().stream()
                        .filter(key -> !understood.contains(key))
                        .forEach(key -> unhandled.add(file.getFileName() + ": " + key)));
            }
        }

        assertTrue(unhandled.isEmpty(),
                () -> "real manifests use keys the importer ignores: " + unhandled);
    }

    /** Guards the checked-in sample against being emptied by accident. */
    @Test
    @DisplayName("the real-manifest sample is present on the classpath")
    void sampleIsPresent() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(RESOURCE_DIR)) {
            assertTrue(in != null || getClass().getClassLoader().getResource(RESOURCE_DIR) != null,
                    "the real/ resource directory is missing — this suite would silently pass "
                            + "with nothing to measure");
        }
    }
}
