package org.omniframework.loader.transform.mapping;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.omniframework.loader.transform.TransformException;

/**
 * Reads Tiny v2, the mapping format the Fabric-family toolchain publishes.
 *
 * <p>The loader parses this itself for the same reason it parses its own JSON: mappings are read
 * before any mod is on the classpath, so a library taken here becomes a library every mod is
 * forced to coexist with. The format is a tab-indented TSV and the subset that matters is small.
 *
 * <pre>{@code
 * tiny	2	0	intermediary	official
 * c	net/minecraft/class_1297	net/minecraft/world/entity/Entity
 * 	f	D	field_5987	xo
 * 	m	(D)V	method_5814	setPosX
 * 		p	1	arg0	x
 * }</pre>
 *
 * <p>Indentation is significant: a line's depth says what it belongs to. Members are indented
 * under their class, parameters under their method.
 *
 * <h2>What is deliberately skipped</h2>
 *
 * <p>Parameter and local-variable names ({@code p}, {@code v}) and comments ({@code c} at member
 * depth) are parsed-and-discarded rather than rejected. They are real parts of the format that
 * carry no information the transformer can act on — parameter names do not exist in bytecode
 * unless a jar was compiled with debug info, and renaming them would change nothing at a call
 * site. Rejecting them would mean refusing perfectly valid published artifacts.
 */
public final class TinyV2Reader {

    private static final String MAGIC = "tiny";

    private TinyV2Reader() {}

    /**
     * Parses a Tiny v2 document, renaming from {@code sourceNamespace} to {@code targetNamespace}.
     *
     * <p>Both namespaces are named by the caller rather than assumed positionally. A mappings
     * artifact with its columns in an unexpected order would otherwise produce a mapping set that
     * renames confidently in the wrong direction — which yields a jar that verifies, loads, and
     * then cannot find a single game method.
     *
     * @throws TransformException if the document is malformed or lacks a requested namespace
     */
    public static MappingSet read(Reader source, String sourceNamespace, String targetNamespace)
            throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sourceNamespace, "sourceNamespace");
        Objects.requireNonNull(targetNamespace, "targetNamespace");

        BufferedReader reader = new BufferedReader(source);
        String header = reader.readLine();
        if (header == null) {
            throw new TransformException("mappings file is empty");
        }
        List<String> namespaces = readHeader(header);
        int from = indexOfNamespace(namespaces, sourceNamespace);
        int to = indexOfNamespace(namespaces, targetNamespace);

        MappingSet.Builder mappings = new MappingSet.Builder(sourceNamespace, targetNamespace);
        String currentClass = null;
        String line;
        int lineNumber = 1;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            int depth = indentDepth(line);
            List<String> columns = split(line.substring(depth));
            if (columns.isEmpty()) {
                continue;
            }
            switch (depth) {
                case 0 -> currentClass = readClass(columns, from, to, mappings, lineNumber);
                case 1 -> readMember(columns, from, to, currentClass, mappings, lineNumber);
                default -> { /* parameters, locals, comments — see the class note */ }
            }
        }
        return mappings.build();
    }

    private static List<String> readHeader(String header) {
        List<String> columns = split(header);
        if (columns.size() < 4 || !MAGIC.equals(columns.get(0))) {
            throw new TransformException(
                    "not a Tiny mappings file (expected a 'tiny' header, got: " + header + ")");
        }
        if (!"2".equals(columns.get(1))) {
            throw new TransformException(
                    "unsupported Tiny major version " + columns.get(1) + " (this loader reads v2)");
        }
        return columns.subList(3, columns.size());
    }

    private static int indexOfNamespace(List<String> namespaces, String wanted) {
        int index = namespaces.indexOf(wanted);
        if (index < 0) {
            throw new TransformException("mappings have no namespace '" + wanted
                    + "'; available: " + String.join(", ", namespaces));
        }
        return index;
    }

    private static String readClass(
            List<String> columns, int from, int to, MappingSet.Builder mappings, int lineNumber) {
        if (!"c".equals(columns.get(0))) {
            // An unknown top-level record type is a format extension, not a corruption. Skipping
            // keeps older loaders working against newer artifacts.
            return null;
        }
        String source = column(columns, from + 1, lineNumber, "class");
        String target = optionalColumn(columns, to + 1);
        if (target == null || target.isEmpty()) {
            // Unmapped in the target namespace: the class keeps its name. Common and legal.
            return source;
        }
        mappings.mapClass(source, target);
        return source;
    }

    private static void readMember(
            List<String> columns,
            int from,
            int to,
            String currentClass,
            MappingSet.Builder mappings,
            int lineNumber) {
        String kind = columns.get(0);
        if (!"f".equals(kind) && !"m".equals(kind)) {
            return; // comments and other member-depth records
        }
        if (currentClass == null) {
            throw new TransformException(
                    "line " + lineNumber + ": a member appears before any class");
        }
        String descriptor = column(columns, 1, lineNumber, "descriptor");
        String source = column(columns, from + 2, lineNumber, "member name");
        String target = optionalColumn(columns, to + 2);
        if (target == null || target.isEmpty() || target.equals(source)) {
            return;
        }
        if ("f".equals(kind)) {
            mappings.mapField(currentClass, source, descriptor, target);
        } else {
            mappings.mapMethod(currentClass, source, descriptor, target);
        }
    }

    private static String column(List<String> columns, int index, int lineNumber, String what) {
        String value = optionalColumn(columns, index);
        if (value == null || value.isEmpty()) {
            throw new TransformException("line " + lineNumber + ": missing " + what);
        }
        return value;
    }

    private static String optionalColumn(List<String> columns, int index) {
        return index >= 0 && index < columns.size() ? columns.get(index) : null;
    }

    private static int indentDepth(String line) {
        int depth = 0;
        while (depth < line.length() && line.charAt(depth) == '\t') {
            depth++;
        }
        return depth;
    }

    /**
     * Splits on tabs, preserving empty columns.
     *
     * <p>{@code String.split} would drop trailing empties, and a trailing empty column is exactly
     * how the format spells "not mapped in the last namespace" — dropping it turns an unmapped
     * member into an out-of-range read.
     */
    private static List<String> split(String line) {
        List<String> columns = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == '\t') {
                columns.add(line.substring(start, i));
                start = i + 1;
            }
        }
        columns.add(line.substring(start));
        return columns;
    }
}
