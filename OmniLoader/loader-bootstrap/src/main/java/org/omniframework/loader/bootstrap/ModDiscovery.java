package org.omniframework.loader.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.omniframework.loader.compat.CompatLayer;
import org.omniframework.loader.compat.EcosystemClaimer;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.diag.OmniIssue;
import org.omniframework.loader.model.ModMetadata;
import org.omniframework.loader.model.OmniModJson;
import org.omniframework.loader.resolve.ModCandidate;

/**
 * Walks a mods directory: native {@code omni.mod.json}, then compat claimers (brick 15/16).
 */
public final class ModDiscovery {

    public static final String NATIVE_MANIFEST = OmniModJson.FILE_NAME;

    private ModDiscovery() {}

    /** Discovers with no compat layers (native only). */
    public static DiscoveredMods discover(Path modsDirectory, IssueCollector issues) {
        return discover(modsDirectory, List.of(), issues);
    }

    /**
     * Discovers mods, offering unclaimed jars to {@code layers} highest-priority claimer first.
     */
    public static DiscoveredMods discover(
            Path modsDirectory, List<CompatLayer> layers, IssueCollector issues) {
        Objects.requireNonNull(modsDirectory, "modsDirectory");
        Objects.requireNonNull(layers, "layers");
        Objects.requireNonNull(issues, "issues");

        if (!Files.isDirectory(modsDirectory)) {
            return new DiscoveredMods(List.of(), List.of(), List.of());
        }

        List<CompatLayer> ordered = layers.stream()
                .sorted(Comparator.comparingInt((CompatLayer layer) -> layer.claimer().priority())
                        .reversed())
                .toList();

        List<ModCandidate> nativeMods = new ArrayList<>();
        List<DiscoveredMods.HostedMod> hosted = new ArrayList<>();
        List<Path> unclaimed = new ArrayList<>();

        for (Path jar : jarsIn(modsDirectory)) {
            Probe probe = probe(jar, issues);
            switch (probe.kind()) {
                case UNREADABLE -> { }
                case NATIVE -> {
                    Optional<ModMetadata> metadata = OmniModJson.parse(probe.manifest(), issues);
                    if (metadata.isEmpty()) {
                        issues.add(OmniIssue.error("discovery.manifest-invalid")
                                .at(jar.getFileName().toString())
                                .remedy("This jar has an " + NATIVE_MANIFEST + " that could not be "
                                        + "parsed, so it was skipped.")
                                .build());
                    } else {
                        nativeMods.add(ModCandidate.topLevel(metadata.get(), jar));
                    }
                }
                case UNCLAIMED -> {
                    Optional<DiscoveredMods.HostedMod> claimed = claimWithLayers(jar, ordered, issues);
                    if (claimed.isPresent()) {
                        hosted.add(claimed.get());
                    } else {
                        unclaimed.add(jar);
                    }
                }
            }
        }
        return new DiscoveredMods(nativeMods, hosted, unclaimed);
    }

    private static Optional<DiscoveredMods.HostedMod> claimWithLayers(
            Path jar, List<CompatLayer> layers, IssueCollector issues) {
        CompatLayer winner = null;
        EcosystemClaimer.Claim winningClaim = null;
        int bestPriority = Integer.MIN_VALUE;
        for (CompatLayer layer : layers) {
            Optional<EcosystemClaimer.Claim> claim = layer.claimer().claim(jar);
            if (claim.isEmpty()) {
                continue;
            }
            int priority = layer.claimer().priority();
            if (winner != null && priority == bestPriority) {
                issues.add(OmniIssue.error("discovery.claim-tie")
                        .at(jar.getFileName().toString())
                        .with(winner.id() + "=" + layer.id())
                        .remedy("Two compat layers claimed this jar at the same priority. "
                                + "Adjust layer priorities or remove one manifest.")
                        .build());
                return Optional.empty();
            }
            if (priority > bestPriority) {
                bestPriority = priority;
                winner = layer;
                winningClaim = claim.get();
            }
        }
        if (winner == null || winningClaim == null) {
            return Optional.empty();
        }
        return importClaimed(jar, winner, winningClaim, issues);
    }

    private static Optional<DiscoveredMods.HostedMod> importClaimed(
            Path jar,
            CompatLayer layer,
            EcosystemClaimer.Claim claim,
            IssueCollector issues) {
        String text;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(claim.manifestPath());
            if (entry == null) {
                return Optional.empty();
            }
            try (InputStream in = zip.getInputStream(entry)) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            issues.add(OmniIssue.error("discovery.claim-read-failed")
                    .at(jar.getFileName().toString())
                    .remedy("Claimed as " + layer.id() + " but the manifest could not be read: "
                            + e.getMessage())
                    .build());
            return Optional.empty();
        }

        Optional<ModMetadata> metadata =
                layer.importer().importFromJar(jar, claim.manifestPath(), text, issues);
        if (metadata.isEmpty()) {
            return Optional.empty();
        }
        String ecosystem = ecosystemOf(layer);
        return Optional.of(new DiscoveredMods.HostedMod(
                layer.id(), ecosystem, ModCandidate.topLevel(metadata.get(), jar)));
    }

    private static String ecosystemOf(CompatLayer layer) {
        if (layer.id().contains("fabric")) {
            return "fabric";
        }
        if (layer.id().contains("neoforge") || layer.id().contains("forge")) {
            return "neoforge";
        }
        return layer.id();
    }

    private enum Kind {
        NATIVE,
        UNCLAIMED,
        UNREADABLE
    }

    private record Probe(Kind kind, String manifest) {}

    private static List<Path> jarsIn(Path directory) {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(ModDiscovery::isEnabledJar)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list mods directory " + directory, e);
        }
    }

    private static boolean isEnabledJar(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".jar");
    }

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
            issues.add(OmniIssue.error("discovery.jar-unreadable")
                    .at(jar.getFileName().toString())
                    .remedy("This file could not be read as a jar and was skipped; it may be a "
                            + "corrupt or partial download: " + e.getMessage())
                    .build());
            return new Probe(Kind.UNREADABLE, null);
        }
    }
}
