package org.omniframework.loader.transform.mapping;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.omniframework.loader.transform.TransformException;

/**
 * Reads Mojang {@code *_mappings.txt} (ProGuard-style) into reverse maps from obfuscated
 * names to official names (ADR-0010).
 */
public final class ProguardMappingReader {

    private static final Pattern CLASS =
            Pattern.compile("^([^\\s#]+)\\s*->\\s*([^:\\s]+):\\s*$");
    // field: "    type name -> obf"
    private static final Pattern FIELD =
            Pattern.compile("^\\s+([^\\s]+)\\s+([^\\s]+)\\s+->\\s+(\\S+)\\s*$");
    // method: "    [from:to:]ret name(args) -> obf"
    private static final Pattern METHOD =
            Pattern.compile(
                    "^\\s+(?:\\d+:\\d+:)?([^\\s]+)\\s+([^\\s(]+)\\(([^)]*)\\)\\s+->\\s+(\\S+)\\s*$");

    private ProguardMappingReader() {}

    /** Obfuscated → official maps for classes, fields, and methods. */
    public record ReverseMaps(
            Map<String, String> classes,
            Map<MappingSet.MemberKey, String> fields,
            Map<MappingSet.MemberKey, String> methods) {}

    /** Parses Mojang proguard text into obfuscated→official renames. */
    public static ReverseMaps read(Reader source) throws IOException {
        Objects.requireNonNull(source, "source");
        BufferedReader reader = new BufferedReader(source);
        Map<String, String> classes = new HashMap<>();
        Map<MappingSet.MemberKey, String> fields = new HashMap<>();
        Map<MappingSet.MemberKey, String> methods = new HashMap<>();
        String currentObfClass = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            Matcher classMatch = CLASS.matcher(line);
            if (classMatch.matches()) {
                String official = toInternal(classMatch.group(1));
                String obfuscated = toInternal(classMatch.group(2));
                classes.put(obfuscated, official);
                currentObfClass = obfuscated;
                continue;
            }
            if (currentObfClass == null) {
                continue;
            }
            Matcher methodMatch = METHOD.matcher(line);
            if (methodMatch.matches()) {
                String ret = toDescriptorType(methodMatch.group(1));
                String officialName = methodMatch.group(2);
                String args = methodMatch.group(3);
                String obfName = methodMatch.group(4);
                String desc = "(" + toDescriptorArgs(args) + ")" + ret;
                methods.put(new MappingSet.MemberKey(currentObfClass, obfName, desc), officialName);
                continue;
            }
            Matcher fieldMatch = FIELD.matcher(line);
            if (fieldMatch.matches()) {
                String type = toDescriptorType(fieldMatch.group(1));
                String officialName = fieldMatch.group(2);
                String obfName = fieldMatch.group(3);
                fields.put(new MappingSet.MemberKey(currentObfClass, obfName, type), officialName);
            }
        }
        if (classes.isEmpty()) {
            throw new TransformException("proguard mappings contained no class lines");
        }
        // Mojang proguard text writes member descriptors with *named* types, while the owner
        // and member names on the right are obfuscated. Intermediary composition and the
        // remapper look up members with *obfuscated* type descriptors (inter→obf first).
        // Rewrite descriptors so both sides agree; otherwise only class renames apply and
        // mods keep intermediary method_*/field_* names → NoSuchMethodError at runtime.
        Map<String, String> namedToObf = invertClasses(classes);
        return new ReverseMaps(
                classes,
                rewriteMemberDescriptors(fields, namedToObf),
                rewriteMemberDescriptors(methods, namedToObf));
    }

    /**
     * Builds a {@link MappingSet} that renames obfuscated game classes into Mojang official
     * names — the runtime namespace OmniLoader uses (D2 / LDR-3).
     */
    public static MappingSet toMappingSet(ReverseMaps maps) {
        Objects.requireNonNull(maps, "maps");
        MappingSet.Builder out = new MappingSet.Builder("obfuscated", "official");
        maps.classes().forEach(out::mapClass);
        for (Map.Entry<MappingSet.MemberKey, String> entry : maps.fields().entrySet()) {
            MappingSet.MemberKey key = entry.getKey();
            out.mapField(key.owner(), key.name(), key.descriptor(), entry.getValue());
        }
        for (Map.Entry<MappingSet.MemberKey, String> entry : maps.methods().entrySet()) {
            MappingSet.MemberKey key = entry.getKey();
            out.mapMethod(key.owner(), key.name(), key.descriptor(), entry.getValue());
        }
        return out.build();
    }

    private static Map<String, String> invertClasses(Map<String, String> obfToNamed) {
        Map<String, String> namedToObf = new HashMap<>(obfToNamed.size());
        for (Map.Entry<String, String> entry : obfToNamed.entrySet()) {
            namedToObf.put(entry.getValue(), entry.getKey());
        }
        return namedToObf;
    }

    private static Map<MappingSet.MemberKey, String> rewriteMemberDescriptors(
            Map<MappingSet.MemberKey, String> members, Map<String, String> namedToObf) {
        Map<MappingSet.MemberKey, String> out = new HashMap<>(members.size());
        for (Map.Entry<MappingSet.MemberKey, String> entry : members.entrySet()) {
            MappingSet.MemberKey key = entry.getKey();
            String obfDesc = remapDescriptorTypes(key.descriptor(), namedToObf);
            out.put(new MappingSet.MemberKey(key.owner(), key.name(), obfDesc), entry.getValue());
        }
        return out;
    }

    /** Rewrites {@code Lnamed/Type;} references inside a descriptor using {@code namedToObf}. */
    static String remapDescriptorTypes(String descriptor, Map<String, String> namedToObf) {
        StringBuilder out = new StringBuilder(descriptor.length());
        for (int i = 0; i < descriptor.length(); i++) {
            char c = descriptor.charAt(i);
            if (c == 'L') {
                int end = descriptor.indexOf(';', i);
                if (end < 0) {
                    out.append(c);
                    continue;
                }
                String internal = descriptor.substring(i + 1, end);
                String mapped = namedToObf.getOrDefault(internal, internal);
                out.append('L').append(mapped).append(';');
                i = end;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String toInternal(String binaryOrInternal) {
        return binaryOrInternal.replace('.', '/');
    }

    private static String toDescriptorArgs(String javaArgs) {
        if (javaArgs == null || javaArgs.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String part : javaArgs.split(",")) {
            out.append(toDescriptorType(part.trim()));
        }
        return out.toString();
    }

    private static String toDescriptorType(String javaType) {
        String t = javaType.trim();
        int array = 0;
        while (t.endsWith("[]")) {
            array++;
            t = t.substring(0, t.length() - 2);
        }
        String base =
                switch (t) {
                    case "void" -> "V";
                    case "boolean" -> "Z";
                    case "byte" -> "B";
                    case "short" -> "S";
                    case "char" -> "C";
                    case "int" -> "I";
                    case "long" -> "J";
                    case "float" -> "F";
                    case "double" -> "D";
                    default -> "L" + toInternal(t) + ";";
                };
        return "[".repeat(array) + base;
    }
}
