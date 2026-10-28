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
        NESTED_BUFFER.get().clear();

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
                        hosted.addAll(NESTED_BUFFER.get());
                        NESTED_BUFFER.get().clear();
                        // JarJar + META-INF/jars nests (KFF, C2ME multi-module, …).
                        // Nested as non-roots so resolve does not treat 1000+ modules as roots.
                        hosted.addAll(expandEmbeddedJars(
                                jar, Optional.of(claimed.get().candidate()), ordered, issues));
                    } else {
                        List<DiscoveredMods.HostedMod> embedded =
                                expandEmbeddedJars(jar, Optional.empty(), ordered, issues);
                        if (!embedded.isEmpty()) {
                            hosted.addAll(embedded);
                        } else {
                            unclaimed.add(jar);
                        }
                    }
                }
            }
        }
        NESTED_BUFFER.remove();
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
        ModCandidate parent = ModCandidate.topLevel(metadata.get(), jar);
        // Expand Fabric nested jars (fabric-api modules, etc.) so deps like
        // fabric-resource-loader-v0 resolve instead of failing as "not installed".
        expandNestedJars(jar, parent, layer, ecosystem, issues);
        return Optional.of(new DiscoveredMods.HostedMod(layer.id(), ecosystem, parent));
    }

    /**
     * Materializes nested jar paths declared by the parent and claims them as additional
     * hosted candidates (same ecosystem/layer). Nested candidates are attached to the parent
     * for resolution ordering.
     */
    private static void expandNestedJars(
            Path parentJar,
            ModCandidate parent,
            CompatLayer layer,
            String ecosystem,
            IssueCollector issues) {
        List<String> nested = parent.metadata().nestedJars();
        if (nested.isEmpty()) {
            return;
        }
        Path nestDir = parentJar
                .getParent()
                .resolveSibling(".omniloader")
                .resolve("nested")
                .resolve(parent.id());
        try {
            Files.createDirectories(nestDir);
        } catch (IOException e) {
            issues.add(OmniIssue.warning("discovery.nested-dir")
                    .at(parentJar.getFileName().toString())
                    .with(e.getMessage() == null ? "io" : e.getMessage())
                    .remedy("Could not create nested-jar extract dir; sub-modules will be missing")
                    .build());
            return;
        }
        try (ZipFile zip = new ZipFile(parentJar.toFile())) {
            for (String entryPath : nested) {
                ZipEntry entry = zip.getEntry(entryPath);
                if (entry == null) {
                    issues.add(OmniIssue.warning("discovery.nested-missing")
                            .at(parent.id())
                            .with(entryPath)
                            .remedy("Nested jar path declared but not found inside parent")
                            .build());
                    continue;
                }
                String fileName = Path.of(entryPath).getFileName().toString();
                Path out = nestDir.resolve(fileName);
                long expected = entry.getSize();
                boolean needWrite = !Files.isRegularFile(out)
                        || Files.size(out) == 0
                        || (expected >= 0 && Files.size(out) != expected);
                if (needWrite) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.write(out, in.readAllBytes());
                    }
                }
                Optional<DiscoveredMods.HostedMod> nestedHosted =
                        claimNested(out, parent, layer, ecosystem, issues);
                // Nested mods are separate loadable candidates; attach via ModCandidate.nested
                // is done inside claimNested.
                nestedHosted.ifPresent(h -> {
                    // Re-add is handled by collecting into a side list — see discover loop.
                    // For simplicity, promote nested into the same hosted list by mutating
                    // via a thread-local buffer is messy; instead store on parent extensions
                    // is wrong. Use a static ThreadLocal list filled during discover.
                    NESTED_BUFFER.get().add(h);
                });
            }
        } catch (IOException e) {
            issues.add(OmniIssue.warning("discovery.nested-extract")
                    .at(parentJar.getFileName().toString())
                    .with(e.getMessage() == null ? "io" : e.getMessage())
                    .remedy("Failed to extract nested jars from this mod")
                    .build());
        }
    }

    private static final ThreadLocal<List<DiscoveredMods.HostedMod>> NESTED_BUFFER =
            ThreadLocal.withInitial(ArrayList::new);

    /**
     * Expands embedded module jars: JarJar metadata + Fabric/NeoForge {@code META-INF/jars/*}
     * (C2ME multi-module, KFF, …) so nested modIds resolve.
     *
     * <p>When {@code parent} is present, nested modules are {@link ModCandidate#nested} so they
     * are not resolution roots (BMC3-scale Fabric packs otherwise create 1000+ roots and hang).
     */
    static List<DiscoveredMods.HostedMod> expandEmbeddedJars(
            Path parentJar,
            Optional<ModCandidate> parent,
            List<CompatLayer> layers,
            IssueCollector issues) {
        List<String> nestedPaths = new ArrayList<>();
        nestedPaths.addAll(jarJarNestedPaths(parentJar));
        nestedPaths.addAll(metaInfJarsPaths(parentJar));
        if (nestedPaths.isEmpty()) {
            return List.of();
        }
        // de-dupe paths
        nestedPaths = nestedPaths.stream().distinct().toList();
        Path nestDir = parentJar
                .getParent()
                .resolveSibling(".omniloader")
                .resolve("embedded")
                .resolve(parentJar.getFileName().toString().replace(".jar", ""));
        try {
            Files.createDirectories(nestDir);
        } catch (IOException e) {
            issues.add(OmniIssue.warning("discovery.embedded-dir")
                    .at(parentJar.getFileName().toString())
                    .with(e.getMessage() == null ? "io" : e.getMessage())
                    .remedy("Could not create embedded-jar extract dir; nested modules missing")
                    .build());
            return List.of();
        }
        List<DiscoveredMods.HostedMod> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(parentJar.toFile())) {
            for (String entryPath : nestedPaths) {
                ZipEntry entry = zip.getEntry(entryPath);
                if (entry == null) {
                    continue;
                }
                String fileName = Path.of(entryPath).getFileName().toString();
                Path extracted = nestDir.resolve(fileName);
                // ZipEntry.getSize() is often -1 (unknown); never treat that as a size mismatch
                // or every residual IT re-extracts multi-MB nests and can run for tens of minutes.
                long expected = entry.getSize();
                boolean needWrite = !Files.isRegularFile(extracted)
                        || Files.size(extracted) == 0
                        || (expected >= 0 && Files.size(extracted) != expected);
                if (needWrite) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.write(extracted, in.readAllBytes());
                    }
                }
                NESTED_BUFFER.get().clear();
                Optional<DiscoveredMods.HostedMod> claimed = claimWithLayers(extracted, layers, issues);
                if (claimed.isPresent()) {
                    DiscoveredMods.HostedMod hosted = claimed.get();
                    if (parent.isPresent() && !hosted.candidate().isNested()) {
                        hosted = new DiscoveredMods.HostedMod(
                                hosted.layerId(),
                                hosted.ecosystem(),
                                ModCandidate.nested(
                                        hosted.candidate().metadata(),
                                        hosted.candidate().source(),
                                        parent.get()));
                    }
                    out.add(hosted);
                    out.addAll(NESTED_BUFFER.get());
                    NESTED_BUFFER.get().clear();
                    // One level of nesting is enough for C2ME/KFF; avoid explosion.
                }
            }
        } catch (IOException e) {
            issues.add(OmniIssue.warning("discovery.embedded-extract")
                    .at(parentJar.getFileName().toString())
                    .with(e.getMessage() == null ? "io" : e.getMessage())
                    .remedy("Failed to extract embedded nested modules")
                    .build());
        }
        return out;
    }

    /** Paths listed in {@code META-INF/jarjar/metadata.json}, or empty when absent. */
    static List<String> jarJarNestedPaths(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry meta = zip.getEntry("META-INF/jarjar/metadata.json");
            if (meta == null) {
                return List.of();
            }
            String json;
            try (InputStream in = zip.getInputStream(meta)) {
                json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            List<String> paths = new ArrayList<>();
            int idx = 0;
            while (true) {
                int p = json.indexOf("\"path\"", idx);
                if (p < 0) {
                    break;
                }
                int colon = json.indexOf(':', p);
                int q1 = json.indexOf('"', colon + 1);
                int q2 = q1 >= 0 ? json.indexOf('"', q1 + 1) : -1;
                if (q1 >= 0 && q2 > q1) {
                    String path = json.substring(q1 + 1, q2);
                    if (path.endsWith(".jar")) {
                        paths.add(path);
                    }
                }
                idx = p + 6;
            }
            return paths;
        } catch (IOException e) {
            return List.of();
        }
    }

    /** All {@code META-INF/jars/*.jar} entries (Fabric nested / C2ME multi-module). */
    static List<String> metaInfJarsPaths(Path jar) {
        List<String> paths = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (!e.isDirectory()
                        && name.startsWith("META-INF/jars/")
                        && name.endsWith(".jar")
                        && name.chars().filter(ch -> ch == '/').count() == 2) {
                    paths.add(name);
                }
            }
        } catch (IOException ignored) {
            return List.of();
        }
        return paths;
    }

    private static Optional<DiscoveredMods.HostedMod> claimNested(
            Path nestedJar,
            ModCandidate parent,
            CompatLayer layer,
            String ecosystem,
            IssueCollector issues) {
        Optional<EcosystemClaimer.Claim> claim = layer.claimer().claim(nestedJar);
        if (claim.isEmpty()) {
            // Nested jars may still be plain fabric.mod.json modules.
            return Optional.empty();
        }
        try (ZipFile zip = new ZipFile(nestedJar.toFile())) {
            ZipEntry entry = zip.getEntry(claim.get().manifestPath());
            if (entry == null) {
                return Optional.empty();
            }
            String text;
            try (InputStream in = zip.getInputStream(entry)) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Optional<ModMetadata> metadata =
                    layer.importer().importFromJar(nestedJar, claim.get().manifestPath(), text, issues);
            if (metadata.isEmpty()) {
                return Optional.empty();
            }
            ModCandidate nested = ModCandidate.nested(metadata.get(), nestedJar, parent);
            return Optional.of(new DiscoveredMods.HostedMod(layer.id(), ecosystem, nested));
        } catch (IOException e) {
            issues.add(OmniIssue.warning("discovery.nested-read")
                    .at(nestedJar.getFileName().toString())
                    .with(e.getMessage() == null ? "io" : e.getMessage())
                    .remedy("Nested jar could not be read")
                    .build());
            return Optional.empty();
        }
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
