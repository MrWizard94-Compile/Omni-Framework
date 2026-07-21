package org.omniframework.loader.model.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A JSON reader with a strict RFC 8259 mode and a Fabric-compat lenient mode.
 *
 * <p>Strict mode (default): no comments, no trailing commas, no unquoted keys, no single
 * quotes, no NaN or Infinity, no unescaped control characters in strings. Native
 * {@code omni.mod.json} stays strict — a parser that quietly accepts near-JSON teaches
 * authors to write files other tools reject.
 *
 * <p>Lenient mode exists only for third-party {@code fabric.mod.json}: a handful of shipped
 * packs (Better End, etc.) put raw newlines inside description strings. Fabric Loader still
 * loads those jars; refusing them here made popular mods vanish as "not installed".
 *
 * <p>Depth is bounded so a hostile manifest cannot exhaust the stack (SOUL §9 — manifests
 * are untrusted input). Not thread-safe; construct one per document.
 */
public final class JsonReader {

    /**
     * Maximum nesting depth. Real manifests nest three or four levels; 64 is far beyond any
     * legitimate document while keeping recursion well inside the default stack.
     */
    private static final int MAX_DEPTH = 64;

    private final String source;
    private final boolean lenientStrings;
    private int index;
    private int line = 1;
    private int column = 1;

    private JsonReader(String source, boolean lenientStrings) {
        this.source = source;
        this.lenientStrings = lenientStrings;
    }

    /**
     * Parses a complete strict JSON document.
     *
     * @throws JsonParseException if the text is not exactly one well-formed JSON value
     */
    public static Json parse(String text) {
        return parse(text, false);
    }

    /**
     * Parses a JSON document. When {@code lenientStrings} is true, unescaped control
     * characters (notably raw newlines) are kept as literal content instead of rejected —
     * matching what Fabric Loader tolerates in wild {@code fabric.mod.json} files.
     *
     * @throws JsonParseException if the text is not a single parseable JSON value
     */
    public static Json parse(String text, boolean lenientStrings) {
        JsonReader reader = new JsonReader(Objects.requireNonNull(text, "text"), lenientStrings);
        reader.skipWhitespace();
        Json value = reader.readValue(0);
        reader.skipWhitespace();
        if (!reader.atEnd()) {
            throw reader.error("trailing content after the JSON value");
        }
        return value;
    }

    // ---------------------------------------------------------------- values

    private Json readValue(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("JSON nested deeper than " + MAX_DEPTH + " levels");
        }
        if (atEnd()) {
            throw error("expected a value but the document ended");
        }
        char c = peek();
        return switch (c) {
            case '{' -> readObject(depth);
            case '[' -> readArray(depth);
            case '"' -> new Json.Str(readString());
            case 't' -> readKeyword("true", new Json.Bool(true));
            case 'f' -> readKeyword("false", new Json.Bool(false));
            case 'n' -> readKeyword("null", new Json.Nul());
            default -> readNumber();
        };
    }

    private Json readObject(int depth) {
        expect('{');
        Map<String, Json> members = new LinkedHashMap<>();
        skipWhitespace();
        if (peekIs('}')) {
            advance();
            return new Json.Obj(members);
        }
        while (true) {
            skipWhitespace();
            if (!peekIs('"')) {
                throw error("expected a quoted member name");
            }
            String key = readString();
            if (members.containsKey(key)) {
                throw error("duplicate member name \"" + key + "\"");
            }
            skipWhitespace();
            expect(':');
            skipWhitespace();
            members.put(key, readValue(depth + 1));
            skipWhitespace();
            if (peekIs(',')) {
                advance();
                continue;
            }
            if (peekIs('}')) {
                advance();
                return new Json.Obj(members);
            }
            throw error("expected ',' or '}' in object");
        }
    }

    private Json readArray(int depth) {
        expect('[');
        List<Json> elements = new ArrayList<>();
        skipWhitespace();
        if (peekIs(']')) {
            advance();
            return new Json.Arr(elements);
        }
        while (true) {
            skipWhitespace();
            elements.add(readValue(depth + 1));
            skipWhitespace();
            if (peekIs(',')) {
                advance();
                continue;
            }
            if (peekIs(']')) {
                advance();
                return new Json.Arr(elements);
            }
            throw error("expected ',' or ']' in array");
        }
    }

    private String readString() {
        expect('"');
        StringBuilder text = new StringBuilder();
        while (true) {
            if (atEnd()) {
                throw error("string is not terminated");
            }
            char c = peek();
            if (c == '"') {
                advance();
                return text.toString();
            }
            if (c == '\\') {
                advance();
                text.append(readEscape());
                continue;
            }
            if (c < 0x20) {
                if (!lenientStrings) {
                    throw error("unescaped control character in string");
                }
                // Fabric-compat: keep raw CR/LF/TAB inside string content.
                text.append(c);
                advance();
                continue;
            }
            text.append(c);
            advance();
        }
    }

    private char readEscape() {
        if (atEnd()) {
            throw error("escape sequence is not terminated");
        }
        char c = peek();
        advance();
        return switch (c) {
            case '"' -> '"';
            case '\\' -> '\\';
            case '/' -> '/';
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> readUnicodeEscape();
            default -> throw error("unknown escape sequence \\" + c);
        };
    }

    private char readUnicodeEscape() {
        int value = 0;
        for (int i = 0; i < 4; i++) {
            if (atEnd()) {
                throw error("incomplete \\u escape");
            }
            int digit = Character.digit(peek(), 16);
            if (digit < 0) {
                throw error("invalid hex digit in \\u escape");
            }
            value = value * 16 + digit;
            advance();
        }
        return (char) value;
    }

    private Json readNumber() {
        int start = index;
        if (peekIs('-')) {
            advance();
        }
        readDigits("a digit after '-'");
        if (peekIs('.')) {
            advance();
            readDigits("a digit after '.'");
        }
        if (peekIs('e') || peekIs('E')) {
            advance();
            if (peekIs('+') || peekIs('-')) {
                advance();
            }
            readDigits("a digit in the exponent");
        }
        String raw = source.substring(start, index);
        if (raw.isEmpty() || raw.equals("-")) {
            throw error("expected a number");
        }
        // Leading zeros are invalid JSON ("01"), but "0" and "0.5" are fine.
        String digits = raw.startsWith("-") ? raw.substring(1) : raw;
        if (digits.length() > 1 && digits.charAt(0) == '0' && Character.isDigit(digits.charAt(1))) {
            throw error("numbers may not have leading zeros");
        }
        return new Json.Num(Double.parseDouble(raw), raw);
    }

    private void readDigits(String expectation) {
        if (atEnd() || !Character.isDigit(peek())) {
            throw error("expected " + expectation);
        }
        while (!atEnd() && Character.isDigit(peek())) {
            advance();
        }
    }

    private Json readKeyword(String keyword, Json value) {
        if (!source.startsWith(keyword, index)) {
            throw error("expected " + keyword);
        }
        for (int i = 0; i < keyword.length(); i++) {
            advance();
        }
        return value;
    }

    // ----------------------------------------------------------- scanning

    private boolean atEnd() {
        return index >= source.length();
    }

    private char peek() {
        return source.charAt(index);
    }

    private boolean peekIs(char c) {
        return !atEnd() && peek() == c;
    }

    private void advance() {
        if (peek() == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
        index++;
    }

    private void expect(char c) {
        if (!peekIs(c)) {
            throw error("expected '" + c + "'");
        }
        advance();
    }

    private void skipWhitespace() {
        // RFC 8259 whitespace only: space, tab, LF, CR. Anything else that looks
        // like whitespace (e.g. a non-breaking space pasted from a web page) is a
        // real error worth reporting rather than silently skipping.
        while (!atEnd()) {
            char c = peek();
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                advance();
            } else {
                return;
            }
        }
    }

    private JsonParseException error(String message) {
        return new JsonParseException(message, line, column);
    }
}
