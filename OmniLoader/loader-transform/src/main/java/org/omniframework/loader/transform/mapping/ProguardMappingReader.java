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
        return new ReverseMaps(classes, fields, methods);
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
