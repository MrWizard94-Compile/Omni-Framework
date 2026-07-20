package org.omniframework.loader.model.json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Serialises a {@link Json} value back to text.
 *
 * <p>The loader writes JSON for exactly one reason: the transform cache records what it did
 * ({@code meta.json}, {@code audit.json}). Those files are read back by the loader itself and by
 * a human debugging a bad transform, which sets two requirements.
 *
 * <p><b>Deterministic.</b> Byte-identical input must produce a byte-identical file. Cache entries
 * are compared and hashed; a serialiser that reordered members or reformatted numbers between
 * runs would manufacture spurious cache misses and make diffs unreadable. Member order is the
 * insertion order {@link Json.Obj} already guarantees, and numbers are written from their
 * preserved {@code raw} literal rather than re-rendered from the double.
 *
 * <p><b>Readable.</b> {@link #pretty} indents, because the first person to open {@code audit.json}
 * is someone asking why their class changed, and a single-line file answers nothing.
 */
public final class JsonWriter {

    private static final String INDENT = "  ";

    private JsonWriter() {}

    /** Serialises compactly, with no insignificant whitespace. */
    public static String compact(Json value) {
        StringBuilder out = new StringBuilder();
        write(Objects.requireNonNull(value, "value"), out, -1);
        return out.toString();
    }

    /** Serialises indented, with a trailing newline, for files humans open. */
    public static String pretty(Json value) {
        StringBuilder out = new StringBuilder();
        write(Objects.requireNonNull(value, "value"), out, 0);
        return out.append('\n').toString();
    }

    /** Convenience for the common shape: an object built from ordered members. */
    public static Json.Obj object(Map<String, Json> members) {
        return new Json.Obj(members);
    }

    /** Convenience for a string-valued member. */
    public static Json string(String value) {
        return new Json.Str(Objects.requireNonNull(value, "value"));
    }

    /** Convenience for an integral number, written without a decimal point. */
    public static Json number(long value) {
        return new Json.Num(value, Long.toString(value));
    }

    /** Convenience for an array. */
    public static Json array(List<Json> elements) {
        return new Json.Arr(elements);
    }

    /**
     * Writes {@code value}; {@code depth} of -1 means compact, otherwise it is the indent level.
     */
    private static void write(Json value, StringBuilder out, int depth) {
        switch (value) {
            case Json.Obj obj -> writeObject(obj, out, depth);
            case Json.Arr arr -> writeArray(arr, out, depth);
            case Json.Str str -> writeString(str.value(), out);
            // The raw literal is authoritative: re-rendering from the double would turn
            // 1.0 into 1.0 but 1e100 into something else, and the round trip must be exact.
            case Json.Num num -> out.append(num.raw());
            case Json.Bool bool -> out.append(bool.value());
            case Json.Nul ignored -> out.append("null");
        }
    }

    private static void writeObject(Json.Obj obj, StringBuilder out, int depth) {
        if (obj.members().isEmpty()) {
            out.append("{}");
            return;
        }
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Json> member : obj.members().entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            newline(out, depth + 1);
            writeString(member.getKey(), out);
            out.append(':');
            if (depth >= 0) {
                out.append(' ');
            }
            write(member.getValue(), out, depth < 0 ? -1 : depth + 1);
        }
        newline(out, depth);
        out.append('}');
    }

    private static void writeArray(Json.Arr arr, StringBuilder out, int depth) {
        if (arr.elements().isEmpty()) {
            out.append("[]");
            return;
        }
        out.append('[');
        boolean first = true;
        for (Json element : arr.elements()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            newline(out, depth + 1);
            write(element, out, depth < 0 ? -1 : depth + 1);
        }
        newline(out, depth);
        out.append(']');
    }

    private static void newline(StringBuilder out, int depth) {
        if (depth < 0) {
            return;
        }
        out.append('\n');
        out.append(INDENT.repeat(depth));
    }

    /**
     * Writes a JSON string literal.
     *
     * <p>Escaping is done against the specification's list rather than a "looks fine" subset:
     * these files carry class names and file paths from arbitrary third-party jars, so a stray
     * control character in a mod's class name must not be able to produce a file the loader
     * cannot read back.
     */
    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** Builds an object from alternating key/value pairs, preserving the order given. */
    public static Json.Obj ordered(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("expected alternating keys and values");
        }
        Map<String, Json> members = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            members.put((String) keysAndValues[i], (Json) keysAndValues[i + 1]);
        }
        return new Json.Obj(members);
    }

    /** Builds an array from its elements. */
    public static Json.Arr list(List<? extends Json> elements) {
        return new Json.Arr(new ArrayList<>(elements));
    }
}
