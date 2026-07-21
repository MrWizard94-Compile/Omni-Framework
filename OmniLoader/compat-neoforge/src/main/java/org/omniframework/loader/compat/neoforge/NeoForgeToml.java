package org.omniframework.loader.compat.neoforge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal TOML subset reader for NeoForge/Forge {@code mods.toml}.
 *
 * <p>Not a general TOML parser — only the fields the Phase 4 layer needs: the first
 * {@code [[mods]]} table's {@code modId}/{@code version}/{@code displayName}, and license.
 * Nested tables, arrays-of-tables beyond the first mods entry, and multiline strings are out of
 * scope; a real pack that needs them still claims, and import reports what was lossy.
 */
public final class NeoForgeToml {

    private static final Pattern KEY_VALUE = Pattern.compile(
            "^\\s*([A-Za-z0-9_]+)\\s*=\\s*(.+?)\\s*$");

    private NeoForgeToml() {}

    /** First mod identity from a mods.toml body. */
    public static Optional<NeoForgeEcosystemClaimer.Identity> identity(String text) {
        Map<String, String> firstMod = firstModsTable(text);
        String modId = firstMod.get("modId");
        String version = firstMod.getOrDefault("version", "0.0.0");
        if (modId == null || modId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new NeoForgeEcosystemClaimer.Identity(modId, strip(version)));
    }

    /** License at file scope, if present. */
    public static Optional<String> license(String text) {
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("[")) {
                break; // left the root table
            }
            Matcher m = KEY_VALUE.matcher(trimmed);
            if (m.matches() && "license".equals(m.group(1))) {
                return Optional.of(strip(m.group(2)));
            }
        }
        return Optional.empty();
    }

    /** Fields of the first {@code [[mods]]} table. */
    public static Map<String, String> firstModsTable(String text) {
        List<String> lines = List.of(text.split("\\R"));
        boolean inMods = false;
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("[")) {
                if (inMods) {
                    break; // next table ends the first mods entry
                }
                if ("[[mods]]".equals(trimmed)) {
                    inMods = true;
                }
                continue;
            }
            if (!inMods) {
                continue;
            }
            Matcher m = KEY_VALUE.matcher(trimmed);
            if (m.matches()) {
                fields.put(m.group(1), strip(m.group(2)));
            }
        }
        return fields;
    }

    /** All {@code [[mods]]} tables in order (modId → fields). */
    public static List<Map<String, String>> allModsTables(String text) {
        List<Map<String, String>> tables = new ArrayList<>();
        Map<String, String> current = null;
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("[")) {
                if (current != null) {
                    tables.add(current);
                    current = null;
                }
                if ("[[mods]]".equals(trimmed)) {
                    current = new LinkedHashMap<>();
                }
                continue;
            }
            if (current == null) {
                continue;
            }
            Matcher m = KEY_VALUE.matcher(trimmed);
            if (m.matches()) {
                current.put(m.group(1), strip(m.group(2)));
            }
        }
        if (current != null) {
            tables.add(current);
        }
        return tables;
    }

    private static String strip(String raw) {
        String value = raw.trim();
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        // ${file.jarVersion} style — keep literal; importer warns.
        return value;
    }
}
