package org.omniframework.loader.compat.fabric;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.diag.OmniIssue;
import org.omniframework.loader.model.Dependency;
import org.omniframework.loader.model.ModMetadata;
import org.omniframework.loader.model.json.Json;
import org.omniframework.loader.model.json.JsonReader;

/**
 * Measures the importer against a large external corpus of real manifests.
 *
 * <pre>{@code
 * gradlew :compat-fabric:test -Domni.fabric.corpus=<dir of fabric.mod.json files>
 * }</pre>
 *
 * <p>Skipped when the property is absent, so the ordinary suite stays hermetic — the same
 * discipline as the launcher's real-instance import probe. The checked-in four-manifest sample in
 * {@code RealManifestImportTest} is the permanent regression guard; this is the scoping
 * instrument, pointed at whatever pack is to hand.
 *
 * <p>It exists because "does the importer work" is not answerable from a handful of files. The
 * questions that decide brick 15's scope are statistical: what fraction of a real pack imports,
 * which manifest keys actually occur, and how many mods depend on the Fabric API surface this
 * layer does not yet provide. Those need hundreds of manifests, and they are the numbers a
 * scoping conversation should be held over.
 */
class ExternalCorpusImportTest {

    private static final String CORPUS_PROPERTY = "omni.fabric.corpus";

    @Test
    @DisplayName("import an external corpus and report what it cost")
    void measureCorpus() throws IOException {
        String configured = System.getProperty(CORPUS_PROPERTY);
        assumeTrue(configured != null && !configured.isBlank(),
                "set -D" + CORPUS_PROPERTY + "=<dir> to run the corpus measurement");
        Path dir = Path.of(configured);
        assumeTrue(Files.isDirectory(dir), configured + " is not a directory");

        List<Path> manifests;
        try (Stream<Path> files = Files.list(dir)) {
            manifests = files.filter(f -> f.toString().endsWith(".json")).sorted().toList();
        }
        assumeTrue(!manifests.isEmpty(), "corpus directory holds no .json files");

        int imported = 0;
        int refused = 0;
        int withWarnings = 0;
        long warningTotal = 0;
        int needsFabricApi = 0;
        Map<String, Integer> issueCodes = new TreeMap<>();
        Map<String, Integer> keyUsage = new TreeMap<>();
        Map<String, Integer> unhandledKeys = new TreeMap<>();
        List<String> refusals = new ArrayList<>();
        List<String> understood = FabricMetadataImporter.understoodKeys();

        for (Path manifest : manifests) {
            String text = Files.readString(manifest, StandardCharsets.UTF_8);

            // Key census runs independently of the import, so a refused manifest still tells us
            // which keys exist in the wild.
            try {
                JsonReader.parse(text).asObject().ifPresent(obj -> obj.members().keySet()
                        .forEach(key -> {
                            keyUsage.merge(key, 1, Integer::sum);
                            if (!understood.contains(key)) {
                                unhandledKeys.merge(key, 1, Integer::sum);
                            }
                        }));
            } catch (RuntimeException ignored) {
                // Counted as a refusal below; the census simply cannot see this one.
            }

            IssueCollector issues = new IssueCollector();
            Optional<ModMetadata> result =
                    new FabricMetadataImporter().importMetadata(text, issues);
            for (OmniIssue issue : issues.issues()) {
                issueCodes.merge(issue.code(), 1, Integer::sum);
            }
            warningTotal += issues.issues().size();
            if (!issues.issues().isEmpty()) {
                withWarnings++;
            }
            if (result.isEmpty()) {
                refused++;
                refusals.add(manifest.getFileName() + " — " + issues.issues().stream()
                        .filter(OmniIssue::blocksLoading).map(OmniIssue::code).toList());
                continue;
            }
            imported++;
            if (result.get().dependencies().stream()
                    .anyMatch(d -> d.kind() == Dependency.Kind.DEPENDS
                            && d.modId().equals("fabric-api"))) {
                needsFabricApi++;
            }
        }

        int total = manifests.size();
        System.out.println();
        System.out.println("=== fabric.mod.json corpus: " + total + " manifests ===");
        System.out.println("imported            : " + imported + "  ("
                + percent(imported, total) + ")");
        System.out.println("refused             : " + refused);
        System.out.println("with warnings       : " + withWarnings + "  ("
                + percent(withWarnings, total) + ")");
        System.out.println("warnings total      : " + warningTotal);
        System.out.println("require fabric-api  : " + needsFabricApi + "  ("
                + percent(needsFabricApi, total) + ")   <-- the CMP-4 surface gap");
        System.out.println();
        System.out.println("--- issue codes ---");
        issueCodes.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> System.out.println("  " + e.getValue() + "x " + e.getKey()));
        System.out.println();
        System.out.println("--- manifest keys NOT handled by the importer ---");
        if (unhandledKeys.isEmpty()) {
            System.out.println("  (none)");
        } else {
            unhandledKeys.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> System.out.println("  " + e.getValue() + "x " + e.getKey()));
        }
        System.out.println();
        System.out.println("--- key usage (top 25) ---");
        keyUsage.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(25)
                .forEach(e -> System.out.println("  " + e.getValue() + "x " + e.getKey()));
        if (!refusals.isEmpty()) {
            System.out.println();
            System.out.println("--- refusals ---");
            refusals.stream().sorted(Comparator.naturalOrder()).limit(20)
                    .forEach(r -> System.out.println("  " + r));
        }
        System.out.println();

        // The only hard assertion: the importer must not be refusing most of a real pack. The
        // rest of this test is instrumentation, and dressing measurements up as pass/fail
        // thresholds would just invite tuning the threshold.
        assertTrue(imported * 2 >= total,
                "fewer than half the corpus imported (" + imported + "/" + total
                        + ") — the importer is not fit for real manifests");
    }

    private static String percent(int part, int whole) {
        return whole == 0 ? "n/a" : Math.round(part * 100.0 / whole) + "%";
    }
}
