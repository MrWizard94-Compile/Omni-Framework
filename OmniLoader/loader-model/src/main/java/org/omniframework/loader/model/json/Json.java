package org.omniframework.loader.model.json;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A parsed JSON value.
 *
 * <p><b>Why OmniLoader ships its own JSON model instead of taking a dependency.</b> A mod
 * loader reads manifests <em>before</em> any mod is on the classpath, so every library it
 * needs at that moment becomes a library every mod is forced to coexist with. The corpus is
 * unambiguous here: fabric-loader shades its own JSON reader for exactly this reason. A
 * strict, read-only JSON parser is a few hundred well-tested lines (SOUL §22: prefer that to
 * a dependency), and it lets parse errors carry the line/column detail that LDR-10's typed
 * issues require.
 *
 * <p>Third-party manifests are <em>only ever read</em> — the loader never rewrites a file a mod
 * author owns. Loader-owned files are a different matter: the transform cache writes its own
 * {@code meta.json} and {@code audit.json}, so {@link JsonWriter} serialises this model back out.
 * Reading and writing share one model deliberately, so a file the loader writes is provably one
 * it can read.
 */
public sealed interface Json {

    /** A JSON object. Member order is preserved so diagnostics read in file order. */
    record Obj(Map<String, Json> members) implements Json {
        public Obj {
            // Deliberately NOT Map.copyOf: that returns an immutable map with
            // unspecified iteration order, which would silently break the ordering
            // guarantee above and scramble the sequence diagnostics are reported in.
            members = Collections.unmodifiableMap(new LinkedHashMap<>(members));
        }

        /** The member under {@code key}, if present. */
        public Optional<Json> get(String key) {
            return Optional.ofNullable(members.get(key));
        }

        /** Whether the object has a member under {@code key}. */
        public boolean has(String key) {
            return members.containsKey(key);
        }
    }

    /** A JSON array. */
    record Arr(List<Json> elements) implements Json {
        public Arr {
            elements = List.copyOf(elements);
        }
    }

    /** A JSON string. */
    record Str(String value) implements Json {}

    /**
     * A JSON number.
     *
     * <p>{@code raw} keeps the literal exactly as written, because a manifest's version-like
     * numbers must not be reformatted by a round trip through {@code double}.
     */
    record Num(double value, String raw) implements Json {}

    /** A JSON boolean. */
    record Bool(boolean value) implements Json {}

    /** JSON {@code null}. */
    record Nul() implements Json {}

    /** This value as an object, if it is one. */
    default Optional<Obj> asObject() {
        return this instanceof Obj obj ? Optional.of(obj) : Optional.empty();
    }

    /** This value as an array, if it is one. */
    default Optional<Arr> asArray() {
        return this instanceof Arr arr ? Optional.of(arr) : Optional.empty();
    }

    /** This value as a string, if it is one. */
    default Optional<String> asString() {
        return this instanceof Str str ? Optional.of(str.value()) : Optional.empty();
    }

    /** This value as a boolean, if it is one. */
    default Optional<Boolean> asBoolean() {
        return this instanceof Bool bool ? Optional.of(bool.value()) : Optional.empty();
    }

    /** A short name for this value's kind, for diagnostics ("object", "string", …). */
    default String kind() {
        return switch (this) {
            case Obj ignored -> "object";
            case Arr ignored -> "array";
            case Str ignored -> "string";
            case Num ignored -> "number";
            case Bool ignored -> "boolean";
            case Nul ignored -> "null";
        };
    }
}
