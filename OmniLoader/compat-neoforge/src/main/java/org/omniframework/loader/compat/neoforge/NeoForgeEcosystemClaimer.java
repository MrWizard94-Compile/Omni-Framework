package org.omniframework.loader.compat.neoforge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.omniframework.loader.compat.EcosystemClaimer;

/**
 * Claims jars carrying NeoForge/Forge mods.toml (CMP-2 contract 1).
 *
 * <p>Prefers {@code META-INF/neoforge.mods.toml}, then {@code META-INF/mods.toml}. Priority is
 * below Fabric so multi-loader jars default to the metadata-level Fabric path when both exist.
 */
public final class NeoForgeEcosystemClaimer implements EcosystemClaimer {

    public static final String NEOFORGE_MANIFEST = "META-INF/neoforge.mods.toml";
    public static final String FORGE_MANIFEST = "META-INF/mods.toml";
    public static final int PRIORITY = 5;

    @Override
    public Optional<Claim> claim(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (String path : new String[] {NEOFORGE_MANIFEST, FORGE_MANIFEST}) {
                ZipEntry entry = zip.getEntry(path);
                if (entry == null) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    Optional<Identity> id = NeoForgeToml.identity(text);
                    if (id.isPresent()) {
                        return Optional.of(new Claim(path, id.get().modId(), id.get().version()));
                    }
                }
            }
            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    record Identity(String modId, String version) {}
}
