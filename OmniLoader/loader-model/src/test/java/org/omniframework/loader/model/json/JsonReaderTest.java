package org.omniframework.loader.model.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The reader is strict on purpose: near-JSON that one parser accepts and another rejects is how
 * a manifest becomes unportable. These tests pin both halves — what must parse, and what must
 * not.
 */
class JsonReaderTest {

    @Test
    @DisplayName("parses the whole value grammar")
    void parsesEveryKind() {
        Json value = JsonReader.parse("""
                { "string": "text", "number": -1.5e3, "true": true, "false": false,
                  "null": null, "array": [1, "two", [3]], "object": { "nested": {} } }
                """);
        Json.Obj root = value.asObject().orElseThrow();
        assertEquals("text", root.get("string").orElseThrow().asString().orElseThrow());
        assertEquals(-1500.0, ((Json.Num) root.get("number").orElseThrow()).value());
        assertEquals("-1.5e3", ((Json.Num) root.get("number").orElseThrow()).raw(),
                "the literal survives verbatim so versions are never reformatted");
        assertTrue(root.get("true").orElseThrow().asBoolean().orElseThrow());
        assertTrue(root.get("null").orElseThrow() instanceof Json.Nul);
        assertEquals(3, root.get("array").orElseThrow().asArray().orElseThrow().elements().size());
    }

    @Test
    @DisplayName("handles every escape sequence, including surrogate pairs")
    void parsesEscapes() {
        Json value = JsonReader.parse("\"a\\\"b\\\\c\\/d\\be\\ff\\ng\\rh\\ti\\u00e9\\uD83D\\uDE00\"");
        String text = value.asString().orElseThrow();
        assertTrue(text.contains("\""), "escaped quote");
        assertTrue(text.contains("\n"), "escaped newline");
        assertTrue(text.contains("é"), "\\u escape");
        assertTrue(text.contains("😀"), "surrogate pair round-trips");
    }

    @Test
    @DisplayName("member order is preserved so diagnostics read in file order")
    void preservesMemberOrder() {
        Json.Obj object = JsonReader.parse("{ \"z\": 1, \"a\": 2, \"m\": 3 }")
                .asObject()
                .orElseThrow();
        assertEquals(java.util.List.of("z", "a", "m"),
                java.util.List.copyOf(object.members().keySet()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{ \"a\": 1, }",          // trailing comma
        "[1, 2, ]",               // trailing comma in array
        "{ a: 1 }",               // unquoted key
        "{ 'a': 1 }",             // single quotes
        "{ \"a\": 01 }",          // leading zero
        "{ \"a\": .5 }",          // bare fraction
        "{ \"a\": NaN }",         // not JSON
        "{ \"a\": Infinity }",    // not JSON
        "{ \"a\": 1 } // comment", // comments
        "{ \"a\": 1 }{ \"b\": 2 }", // two documents
        "{ \"a\": 1",             // unterminated object
        "\"unterminated",          // unterminated string
        "{ \"a\": 1, \"a\": 2 }", // duplicate key
    })
    @DisplayName("rejects near-JSON that other parsers might tolerate")
    void rejectsNearJson(String text) {
        assertThrows(JsonParseException.class, () -> JsonReader.parse(text),
                () -> "should reject: " + text);
    }

    @Test
    @DisplayName("errors carry a 1-based line and column")
    void errorsAreLocated() {
        JsonParseException failure = assertThrows(JsonParseException.class,
                () -> JsonReader.parse("{\n  \"a\": 1,\n  \"b\": }\n}"));
        assertEquals(3, failure.line(), "the fault is on the third line");
        assertTrue(failure.column() > 0);
        assertTrue(failure.getMessage().contains("line 3"));
    }

    @Test
    @DisplayName("deep nesting is bounded rather than exhausting the stack")
    void boundsNesting() {
        // A hostile manifest must fail with a diagnosable error, not a StackOverflowError
        // that takes the whole JVM's boot down with it.
        String hostile = "[".repeat(500) + "]".repeat(500);
        assertThrows(JsonParseException.class, () -> JsonReader.parse(hostile));
    }

    @Test
    @DisplayName("unescaped control characters in strings are rejected")
    void rejectsRawControlCharacters() {
        assertThrows(JsonParseException.class, () -> JsonReader.parse("\"line\nbreak\""));
    }
}
