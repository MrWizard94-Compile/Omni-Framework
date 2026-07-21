package org.omniframework.loader.compat.fabric;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.omniframework.loader.compat.EcosystemClaimer;
import org.omniframework.loader.model.json.Json;
import org.omniframework.loader.model.json.JsonParseException;
import org.omniframework.loader.model.json.JsonReader;

/**
 * Claims jars that carry a {@code fabric.mod.json} (CMP-2 contract 1).
 *
 * <p>Fingerprint is the manifest path alone — never the filename. Multi-loader jars that also
 * carry a NeoForge TOML are still claimed here; claim priority settles contested jars.
 */
public final class FabricEcosystemClaimer implements EcosystemClaimer {

    /** Manifest path this claimer recognises. */
    public static final String MANIFEST = "fabric.mod.json";

    /** Prefer Fabric over NeoForge when both manifests exist (Fabric is metadata-level first). */
    public static final int PRIORITY = 10;

    @Override
    public Optional<Claim> claim(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(MANIFEST);
            if (entry == null) {
                return Optional.empty();
            }
            try (InputStream in = zip.getInputStream(entry)) {
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return parseIdentity(text).map(id -> new Claim(MANIFEST, id.id(), id.version()));
            }
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    private static Optional<Identity> parseIdentity(String text) {
        try {
            Optional<Json.Obj> root = JsonReader.parse(text, true).asObject();
            if (root.isEmpty()) {
                return Optional.empty();
            }
            Optional<String> id = root.get().get("id").flatMap(Json::asString);
            Optional<String> version = root.get().get("version").flatMap(Json::asString);
            if (id.isEmpty() || version.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Identity(id.get(), version.get()));
        } catch (JsonParseException e) {
            return Optional.empty();
        }
    }

    private record Identity(String id, String version) {}
}
