package org.omniframework.loader.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.diag.OmniIssue;
import org.omniframework.loader.model.ModMetadata;
import org.omniframework.loader.model.OmniModJson;
import org.omniframework.loader.resolve.ModCandidate;

/**
 * Walks a mods directory and turns each jar into something the boot sequence can use (B1).
 *
 * <p>This is the discovery stage the boot sequence had no implementation of: {@code loader-core}
 * knew how to locate the game and {@code loader-resolve} how to resolve candidates, but nothing
 * produced candidates from a folder of jars. This does.
 *
 * <h2>What it recognises, and what it deliberately does not</h2>
 *
 * <p>A jar carrying {@code omni.mod.json} is a native mod, parsed into the canonical model here.
 * A jar without one is <em>unclaimed</em> — not an error, because it is almost certainly a Fabric
 * or NeoForge mod, and those are claimed by a compat layer's {@code EcosystemClaimer}, which
 * discovery will consult once B2/B3 land. Until then an unclaimed jar is counted and reported,
 * never silently dropped: a user whose 300-mod pack loads nothing must be told 300 jars are
 * waiting on a layer, not left to guess.
 *
 * <h2>Boundaries held on purpose</h2>
 *
 * <ul>
 *   <li>Non-recursive: only the top level of the directory. Nested-jar extraction (a mod
 *       bundling libraries in {@code META-INF/jars}) is the resolver's and a later discovery
 *       pass's concern; conflating it here would hide which file a problem came from.
 *   <li>{@code .disabled} and {@code .bak} suffixes are skipped, matching the universal launcher
 *       convention for "present but turned off".
 *   <li>A malformed {@code omni.mod.json} is a reported error and the jar is skipped — importing
 *       it half-parsed would be worse than refusing it (SOUL: a wrong answer sends the reader
 *       somewhere confidently).
 * </ul>
 */
public final class ModDiscovery {

    /** The native manifest a jar must carry to be recognised without a compat layer. */
    public static final String NATIVE_MANIFEST = OmniModJson.FILE_NAME;

    private ModDiscovery() {}

    /**
     * Discovers mods in {@code modsDirectory}.
     *
     * <p>A missing or empty directory yields an empty result rather than an error: an instance
     * with no mods is a perfectly valid instance (it is how the vanilla path launches).
     *
     * @param modsDirectory the instance's {@code mods} folder
     * @param issues collects a per-jar error for anything malformed
     */
    public static DiscoveredMods discover(Path modsDirectory, IssueCollector issues) {
        Objects.requireNonNull(modsDirectory, "modsDirectory");
        Objects.requireNonNull(issues, "issues");

        if (!Files.isDirectory(modsDirectory)) {
            return new DiscoveredMods(List.of(), List.of());
        }

        List<ModCandidate> nativeMods = new ArrayList<>();
        List<Path> unclaimed = new ArrayList<>();
        for (Path jar : jarsIn(modsDirectory)) {
            Probe probe = probe(jar, issues);
            switch (probe.kind()) {
                case UNREADABLE -> { /* already reported against the file; not a mod */ }
                case UNCLAIMED -> unclaimed.add(jar);
                case NATIVE -> {
                    Optional<ModMetadata> metadata = OmniModJson.parse(probe.manifest(), issues);
                    if (metadata.isEmpty()) {
                        issues.add(OmniIssue.error("discovery.manifest-invalid")
                                .at(jar.getFileName().toString())
                                .remedy("This jar has an " + NATIVE_MANIFEST + " that could not be "
                                        + "parsed, so it was skipped. See the issues above for the "
                                        + "exact fault.")
                                .build());
                    } else {
                        nativeMods.add(ModCandidate.topLevel(metadata.get(), jar));
                    }
                }
            }
        }
        return new DiscoveredMods(nativeMods, unclaimed);
    }

    /** What a single pass over a jar found. One zip open per jar, no more. */
    private enum Kind {
        /** Carries a readable {@code omni.mod.json}. */
        NATIVE,
        /** Opened fine but has no native manifest — awaits a compat claimer. */
        UNCLAIMED,
        /** Could not be opened as a jar; reported and set aside. */
        UNREADABLE
    }

    /** A probe result: the kind, plus the manifest text when {@link Kind#NATIVE}. */
    private record Probe(Kind kind, String manifest) {}

    /** Top-level jars that are not switched off, in a stable order. */
    private static List<Path> jarsIn(Path directory) {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(ModDiscovery::isEnabledJar)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            // The directory existed a moment ago (checked by the caller); a failure listing it
            // now is a real IO fault, not "no mods", so it must not masquerade as an empty pack.
            throw new UncheckedIOException("cannot list mods directory " + directory, e);
        }
    }

    /** A {@code .jar} that carries no "turned off" suffix. */
    private static boolean isEnabledJar(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".jar");
    }

    /** Opens a jar once and classifies it. */
    private static Probe probe(Path jar, IssueCollector issues) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(NATIVE_MANIFEST);
            if (entry == null) {
                return new Probe(Kind.UNCLAIMED, null);
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return new Probe(Kind.NATIVE, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            // A jar that will not open is a corrupt download, not a mod. Report it against the
            // file so the user knows which one.
            issues.add(OmniIssue.error("discovery.jar-unreadable")
                    .at(jar.getFileName().toString())
                    .remedy("This file could not be read as a jar and was skipped; it may be a "
                            + "corrupt or partial download: " + e.getMessage())
                    .build());
            return new Probe(Kind.UNREADABLE, null);
        }
    }
}
