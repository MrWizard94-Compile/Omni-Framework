package org.omniframework.loader.transform;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;
import org.omniframework.loader.model.json.Json;
import org.omniframework.loader.model.json.JsonParseException;
import org.omniframework.loader.model.json.JsonReader;
import org.omniframework.loader.model.json.JsonWriter;

/**
 * The {@code meta.json} beside a cached jar: what went in, and what produced it (ADR-0010).
 *
 * <p>The cache key is a hash, which is the right thing for lookup and the wrong thing for a
 * human. When someone is staring at a cache directory asking why a jar was rebuilt — or why it
 * was <em>not</em> — the hash tells them nothing. This file is the plain-language answer sitting
 * next to it: which jar, which mappings, which pipeline version, when, and by which loader build.
 *
 * <p>It is written for diagnosis, never read for correctness. Lookup is decided by the key alone;
 * if these two ever disagreed, the key would win. That separation is deliberate — a cache that
 * trusted a human-editable metadata file for correctness would be one text editor away from
 * serving the wrong bytes.
 */
public record EntryMeta(
        String sourceName,
        String inputDescription,
        int pipelineVersion,
        String loaderVersion,
        Instant producedAt,
        int classCount,
        int modifiedClassCount) {

    /** The current meta schema. */
    public static final int SCHEMA = 0;

    /** Canonical constructor. */
    public EntryMeta {
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(inputDescription, "inputDescription");
        Objects.requireNonNull(loaderVersion, "loaderVersion");
        Objects.requireNonNull(producedAt, "producedAt");
    }

    /** A one-line summary for logs and {@code --audit} headers. */
    public String describe() {
        return sourceName + " — pipeline v" + pipelineVersion + ", loader " + loaderVersion
                + ", " + modifiedClassCount + " of " + classCount + " classes modified, built "
                + producedAt;
    }

    /** Serialises to {@code meta.json} text. */
    public String toJson() {
        return JsonWriter.pretty(JsonWriter.ordered(
                "schema", JsonWriter.number(SCHEMA),
                "source", JsonWriter.string(sourceName),
                "inputs", JsonWriter.string(inputDescription),
                "pipelineVersion", JsonWriter.number(pipelineVersion),
                "loaderVersion", JsonWriter.string(loaderVersion),
                "producedAt", JsonWriter.string(producedAt.toString()),
                "classCount", JsonWriter.number(classCount),
                "modifiedClassCount", JsonWriter.number(modifiedClassCount)));
    }

    /** Reads {@code meta.json} back. */
    public static EntryMeta fromJson(String text) {
        Json parsed;
        try {
            parsed = JsonReader.parse(text);
        } catch (JsonParseException e) {
            throw new TransformException("meta.json is not valid JSON: " + e.getMessage(), e);
        }
        Json.Obj root = parsed.asObject()
                .orElseThrow(() -> new TransformException("meta.json must be a JSON object"));
        int schema = (int) number(root, "schema");
        if (schema != SCHEMA) {
            throw new TransformException(
                    "meta.json schema " + schema + " is not readable by this loader (expected "
                            + SCHEMA + ")");
        }
        Instant producedAt;
        String raw = string(root, "producedAt");
        try {
            producedAt = Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw new TransformException("meta.json has an unparseable 'producedAt': " + raw, e);
        }
        return new EntryMeta(
                string(root, "source"),
                string(root, "inputs"),
                (int) number(root, "pipelineVersion"),
                string(root, "loaderVersion"),
                producedAt,
                (int) number(root, "classCount"),
                (int) number(root, "modifiedClassCount"));
    }

    private static String string(Json.Obj obj, String key) {
        return obj.get(key).flatMap(Json::asString).orElseThrow(() ->
                new TransformException("meta.json has no string '" + key + "'"));
    }

    private static double number(Json.Obj obj, String key) {
        return obj.get(key)
                .flatMap(value -> value instanceof Json.Num num
                        ? Optional.of(num.value())
                        : Optional.empty())
                .orElseThrow(() -> new TransformException(
                        "meta.json has no number '" + key + "'"));
    }
}
