package org.omniframework.loader.compat.neoforge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import org.omniframework.loader.compat.MetadataImporter;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.diag.OmniIssue;
import org.omniframework.loader.model.Dependency;
import org.omniframework.loader.model.Entrypoint;
import org.omniframework.loader.model.Environment;
import org.omniframework.loader.model.ModMetadata;
import org.omniframework.loader.model.ModVersion;
import org.omniframework.loader.model.VersionPredicate;

/**
 * Imports NeoForge/Forge mods.toml into the canonical model (contract 2).
 *
 * <p>When a jar path is known, {@link #importJar} also scans for {@code @Mod} classes and
 * records them as {@code main} entrypoints so the boot dispatcher can construct them.
 */
public final class NeoForgeMetadataImporter implements MetadataImporter {

    public static final String EXTENSION_NAMESPACE = "neoforge:";

    @Override
    public Optional<ModMetadata> importMetadata(String manifestText, IssueCollector issues) {
        return importFromToml(manifestText, List.of(), issues);
    }

    @Override
    public Optional<ModMetadata> importFromJar(
            Path jar, String manifestPath, String manifestText, IssueCollector issues) {
        List<String> modClasses;
        try {
            modClasses = NeoForgeModAnnotationScanner.findModClasses(jar);
        } catch (IOException e) {
            issues.add(OmniIssue.warning("compat.neoforge.scan-failed")
                    .at(jar.getFileName().toString())
                    .remedy("Could not scan for @Mod classes: " + e.getMessage()
                            + ". The mod may still resolve but will not initialise.")
                    .build());
            modClasses = List.of();
        }
        return importFromToml(manifestText, modClasses, issues, Optional.of(jar));
    }

    private static Optional<ModMetadata> importFromToml(
            String manifestText, List<String> modClasses, IssueCollector issues) {
        return importFromToml(manifestText, modClasses, issues, Optional.empty());
    }

    private static Optional<ModMetadata> importFromToml(
            String manifestText,
            List<String> modClasses,
            IssueCollector issues,
            Optional<Path> jar) {
        Map<String, String> mod = NeoForgeToml.firstModsTable(manifestText);
        String modId = mod.get("modId");
        if (modId == null || modId.isBlank()) {
            issues.add(OmniIssue.error("compat.neoforge.identity-missing")
                    .at("mods.toml")
                    .remedy("No [[mods]] modId found; the jar was claimed but cannot be imported.")
                    .build());
            return Optional.empty();
        }
        String versionRaw = resolveVersionField(mod.getOrDefault("version", "0.0.0"), modId, jar, issues);
        ModMetadata.Builder builder =
                ModMetadata.builder(modId, ModVersion.parse(versionRaw));
        Optional.ofNullable(mod.get("displayName")).ifPresent(builder::name);
        Optional.ofNullable(mod.get("description")).ifPresent(builder::description);
        NeoForgeToml.license(manifestText).ifPresent(builder::license);
        builder.environment(Environment.BOTH);
        for (String internal : modClasses) {
            builder.entrypoint(Entrypoint.java("main", internal.replace('/', '.')));
        }
        if (modClasses.isEmpty()) {
            issues.add(OmniIssue.warning("compat.neoforge.no-mod-annotation")
                    .at(modId)
                    .remedy("No @Mod class was found. The jar is on the classpath for libraries "
                            + "but nothing will be constructed as an entrypoint.")
                    .build());
        }
        importDependencies(builder, modId, manifestText, issues);
        builder.extension(
                "omni:ported-from", Map.of("ecosystem", "neoforge", "upstream", modId));
        builder.extension(EXTENSION_NAMESPACE + "modLoader", "javafml");
        return Optional.of(builder.build());
    }

    private static void importDependencies(
            ModMetadata.Builder builder,
            String modId,
            String manifestText,
            IssueCollector issues) {
        for (Map<String, String> table : NeoForgeToml.dependencyTables(manifestText)) {
            String owner = table.getOrDefault("_owner", "");
            // Only dependencies owned by this mod (or unscoped) apply.
            if (!owner.isBlank() && !owner.equals(modId) && !owner.equals("\"" + modId + "\"")) {
                continue;
            }
            String depId = table.get("modId");
            if (depId == null || depId.isBlank()) {
                continue;
            }
            // NeoForge uses type=required|optional|…; classic Forge used mandatory=true|false.
            String type = table.getOrDefault("type", "").toLowerCase();
            String mandatory = table.getOrDefault("mandatory", "").toLowerCase();
            Dependency.Kind kind;
            if (!type.isEmpty()) {
                kind = switch (type) {
                    case "optional" -> Dependency.Kind.RECOMMENDS;
                    case "incompatible" -> Dependency.Kind.BREAKS;
                    case "discouraged" -> Dependency.Kind.CONFLICTS;
                    default -> Dependency.Kind.DEPENDS;
                };
            } else if (mandatory.equals("false") || mandatory.equals("0")) {
                kind = Dependency.Kind.RECOMMENDS;
            } else {
                // mandatory=true or absent → required (Forge default for [[dependencies]])
                kind = Dependency.Kind.DEPENDS;
            }
            String range = table.getOrDefault("versionRange", "*");
            VersionPredicate predicate = translateNeoForgeRange(range, depId).orElseGet(() -> {
                issues.add(OmniIssue.warning("compat.neoforge.dep-range")
                        .at(modId)
                        .with(depId, range)
                        .remedy("Could not parse NeoForge versionRange; treating as any (*)")
                        .build());
                return VersionPredicate.any();
            });
            String ordering = table.getOrDefault("ordering", "NONE").toUpperCase();
            Dependency.LoadOrder order = switch (ordering) {
                case "BEFORE" -> Dependency.LoadOrder.BEFORE;
                case "AFTER" -> Dependency.LoadOrder.AFTER;
                default -> Dependency.LoadOrder.NONE;
            };
            builder.dependency(new Dependency(kind, depId, predicate, order, Optional.empty()));
        }
    }

    /**
     * Resolves {@code version} from mods.toml, substituting common jar placeholders when possible.
     */
    static String resolveVersionField(
            String versionRaw, String modId, Optional<Path> jar, IssueCollector issues) {
        if (versionRaw == null || versionRaw.isBlank()) {
            return "0.0.0";
        }
        if (!versionRaw.contains("${")) {
            return versionRaw;
        }
        Optional<String> fromJar = jar.flatMap(NeoForgeMetadataImporter::readJarImplementationVersion);
        if (fromJar.isPresent() && !fromJar.get().isBlank()) {
            return fromJar.get();
        }
        issues.add(OmniIssue.warning("compat.neoforge.version-placeholder")
                .at(modId)
                .with(versionRaw)
                .remedy("Version uses a jar-substitution placeholder and no Implementation-Version "
                        + "was found; treating as 0.0.0 for resolution.")
                .build());
        return "0.0.0";
    }

    private static Optional<String> readJarImplementationVersion(Path jar) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            Manifest manifest = jarFile.getManifest();
            if (manifest == null) {
                return Optional.empty();
            }
            Attributes attrs = manifest.getMainAttributes();
            String impl = attrs.getValue(Attributes.Name.IMPLEMENTATION_VERSION);
            if (impl != null && !impl.isBlank()) {
                return Optional.of(impl.trim());
            }
            String spec = attrs.getValue(Attributes.Name.SPECIFICATION_VERSION);
            if (spec != null && !spec.isBlank()) {
                return Optional.of(spec.trim());
            }
            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Maps common NeoForge/Maven version ranges onto Omni's predicate dialect.
     * Unknown forms become empty so the caller can warn and fall back to any.
     *
     * <p><b>Order matters:</b> Maven bracket forms must be handled <em>before</em>
     * {@link VersionPredicate#parse}. That parser treats commas as AND of exact
     * terms, so {@code [1.21, 1.21.1]} would otherwise become exact matches on the
     * raw strings {@code "[1.21"} and {@code "1.21.1]"} — never matching installed
     * {@code 1.21.1} and producing hundreds of false {@code resolve.dependency-version}
     * errors on real packs.
     */
    public static Optional<VersionPredicate> translateNeoForgeRange(String range) {
        return translateNeoForgeRange(range, null);
    }

    /**
     * @param depId dependency mod id when known; {@code minecraft} gets half-open upper-bound
     *     softening so author ranges like {@code [1.21,1.21.1)} accept installed {@code 1.21.1}
     *     (common NeoForge pack authoring; jar names declare 1.21.1 support)
     */
    public static Optional<VersionPredicate> translateNeoForgeRange(String range, String depId) {
        if (range == null || range.isBlank() || "*".equals(range.trim())) {
            return Optional.of(VersionPredicate.any());
        }
        String trimmed = range.trim();
        // Broken half-ranges from bad interpolation: "1.21.1-1.5.0-neoforge,)" → "[1.5.0,)"
        // Only when the author forgot the opening bracket (do not touch real "[21,)").
        if (!trimmed.startsWith("[")
                && !trimmed.startsWith("(")
                && (trimmed.endsWith(",)") || trimmed.endsWith(",]") || trimmed.endsWith(","))) {
            String recovered = recoverBrokenMavenHalf(trimmed);
            if (recovered != null) {
                trimmed = recovered;
            }
        }
        // Minecraft half-open authoring: treat exclusive upper as inclusive.
        if ("minecraft".equals(depId)
                && (trimmed.startsWith("[") || trimmed.startsWith("("))
                && trimmed.endsWith(")")
                && trimmed.contains(",")) {
            String inclusive = trimmed.substring(0, trimmed.length() - 1) + "]";
            Optional<VersionPredicate> softened = VersionPredicate.expand(inclusive);
            if (softened.isPresent()) {
                return softened;
            }
        }
        // Bare version without operators/brackets: NeoForge packs almost always mean
        // "at least this version" for library deps (midnightlib 1.6.9 with 1.9.3 installed).
        // Rewrite to [version,) before expand. Keep true Maven exacts that already use [].
        if (isBareVersionPin(trimmed)) {
            Optional<VersionPredicate> min = VersionPredicate.expand("[" + trimmed + ",)");
            if (min.isPresent()) {
                return min;
            }
        }
        // Maven intervals + Omni/semver forms (shared with Fabric via VersionPredicate.expand).
        return VersionPredicate.expand(trimmed);
    }

    /** True when the range is a single version token, not a Maven/Omni operator form. */
    static boolean isBareVersionPin(String range) {
        if (range.isEmpty()) {
            return false;
        }
        char c0 = range.charAt(0);
        if (c0 == '[' || c0 == '(' || c0 == '>' || c0 == '<' || c0 == '=' || c0 == '~' || c0 == '^'
                || c0 == '*') {
            return false;
        }
        if (range.contains(",") || range.contains(" ")) {
            return false;
        }
        return true;
    }

    /**
     * Recovers mangled ranges like {@code 1.21.1-1.5.0-neoforge,)} into {@code [1.5.0,)}.
     */
    static String recoverBrokenMavenHalf(String range) {
        String body = range;
        while (body.endsWith(",") || body.endsWith(")") || body.endsWith("]")) {
            body = body.substring(0, body.length() - 1);
        }
        body = body.trim();
        if (body.isEmpty()) {
            return null;
        }
        // Prefer hybrid mod half when present.
        Optional<org.omniframework.loader.model.SemanticVersion> hybrid =
                org.omniframework.loader.model.SemanticVersion.parse(body);
        if (hybrid.isPresent()) {
            return "[" + hybrid.get().major() + "." + hybrid.get().minor() + "."
                    + hybrid.get().patch()
                    + (hybrid.get().revision() != 0 ? "." + hybrid.get().revision() : "")
                    + ",)";
        }
        return "[" + body + ",)";
    }
}
