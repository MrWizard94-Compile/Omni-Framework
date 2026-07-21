package org.omniframework.loader.compat.neoforge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.omniframework.loader.compat.MetadataImporter;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.diag.OmniIssue;
import org.omniframework.loader.model.Entrypoint;
import org.omniframework.loader.model.Environment;
import org.omniframework.loader.model.ModMetadata;
import org.omniframework.loader.model.ModVersion;

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
        return importFromToml(manifestText, modClasses, issues);
    }

    private static Optional<ModMetadata> importFromToml(
            String manifestText, List<String> modClasses, IssueCollector issues) {
        Map<String, String> mod = NeoForgeToml.firstModsTable(manifestText);
        String modId = mod.get("modId");
        if (modId == null || modId.isBlank()) {
            issues.add(OmniIssue.error("compat.neoforge.identity-missing")
                    .at("mods.toml")
                    .remedy("No [[mods]] modId found; the jar was claimed but cannot be imported.")
                    .build());
            return Optional.empty();
        }
        String versionRaw = mod.getOrDefault("version", "0.0.0");
        if (versionRaw.contains("${")) {
            issues.add(OmniIssue.warning("compat.neoforge.version-placeholder")
                    .at(modId)
                    .with(versionRaw)
                    .remedy("Version uses a jar-substitution placeholder; treating as 0.0.0 for "
                            + "resolution until the real value is substituted.")
                    .build());
            versionRaw = "0.0.0";
        }
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
        builder.extension(
                "omni:ported-from", Map.of("ecosystem", "neoforge", "upstream", modId));
        builder.extension(EXTENSION_NAMESPACE + "modLoader", "javafml");
        return Optional.of(builder.build());
    }
}
