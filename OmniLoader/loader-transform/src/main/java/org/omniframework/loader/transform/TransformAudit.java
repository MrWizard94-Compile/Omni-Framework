package org.omniframework.loader.transform;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.omniframework.loader.model.json.Json;
import org.omniframework.loader.model.json.JsonParseException;
import org.omniframework.loader.model.json.JsonReader;
import org.omniframework.loader.model.json.JsonWriter;

/**
 * The record of what the pipeline did to every class in one jar (LDR-2, ADR-0010).
 *
 * <p><b>Why a loader keeps this.</b> Once several passes and a Mixin runtime can all rewrite the
 * same class, "why does this method look wrong in the debugger" becomes unanswerable by reading
 * source. The audit store is the answer to <em>who changed this class</em> (SOUL §16): for each
 * class, every pass that touched it, in order, with what it did.
 *
 * <p>It is written once per cache entry and read on demand — by {@code --audit <class>}, and by a
 * crash report wanting to name the passes that touched the frame it is reporting. That makes it
 * part of the cache's contract, not a debug aid that can drift: a cache entry without a readable
 * audit is treated as corrupt rather than merely undocumented.
 *
 * <p>Entries are sorted by class name so the file diffs cleanly between builds.
 */
public record TransformAudit(Map<String, ClassAudit> byClass) {

    /** The current audit schema. Bumping it invalidates readers, so it is versioned in-file. */
    public static final int SCHEMA = 0;

    /** Canonical constructor; sorts and defensively copies. */
    public TransformAudit {
        Objects.requireNonNull(byClass, "byClass");
        byClass = java.util.Collections.unmodifiableMap(new TreeMap<>(byClass));
    }

    /** An empty audit — a jar the pipeline passed through untouched. */
    public static TransformAudit empty() {
        return new TransformAudit(Map.of());
    }

    /** What happened to one class, if the pipeline touched it at all. */
    public Optional<ClassAudit> forClass(String className) {
        return Optional.ofNullable(byClass.get(normalise(className)));
    }

    /** Every class the pipeline modified, in name order. */
    public List<String> modifiedClasses() {
        return List.copyOf(byClass.keySet());
    }

    /** Total number of individual pass applications recorded. */
    public int passApplications() {
        return byClass.values().stream().mapToInt(entry -> entry.passes().size()).sum();
    }

    /**
     * Accepts either {@code a/b/C} or {@code a.b.C}.
     *
     * <p>Bytecode uses slashes and humans type dots. Requiring the user of {@code --audit} to
     * know which one the file happens to use would be a needless way to return "not found" for a
     * class that is right there.
     */
    static String normalise(String className) {
        return Objects.requireNonNull(className, "className").replace('.', '/');
    }

    /** What one pass did to one class. */
    public record PassRecord(
            String passId,
            String passVersion,
            int bytesBefore,
            int bytesAfter,
            long micros,
            List<String> notes) {

        /** Canonical constructor; defensively copies notes. */
        public PassRecord {
            Objects.requireNonNull(passId, "passId");
            Objects.requireNonNull(passVersion, "passVersion");
            notes = List.copyOf(Objects.requireNonNull(notes, "notes"));
        }

        /** Signed change in class size. Negative means the pass stripped more than it added. */
        public int sizeDelta() {
            return bytesAfter - bytesBefore;
        }

        /** A one-line rendering for {@code --audit} output. */
        public String describe() {
            String delta = (sizeDelta() >= 0 ? "+" : "") + sizeDelta();
            String detail = notes.isEmpty() ? "" : " — " + String.join("; ", notes);
            return passId + " v" + passVersion + " (" + bytesBefore + " → " + bytesAfter
                    + " bytes, " + delta + ", " + micros + "µs)" + detail;
        }
    }

    /** Everything that happened to one class, in application order. */
    public record ClassAudit(String className, List<PassRecord> passes) {

        /** Canonical constructor; defensively copies. */
        public ClassAudit {
            Objects.requireNonNull(className, "className");
            passes = List.copyOf(Objects.requireNonNull(passes, "passes"));
        }

        /** Net size change across every pass. */
        public int netSizeDelta() {
            if (passes.isEmpty()) {
                return 0;
            }
            return passes.get(passes.size() - 1).bytesAfter() - passes.get(0).bytesBefore();
        }

        /** Total time spent transforming this class. */
        public long totalMicros() {
            return passes.stream().mapToLong(PassRecord::micros).sum();
        }

        /** The multi-line rendering {@code --audit <class>} prints. */
        public String describe() {
            StringBuilder out = new StringBuilder(className.replace('/', '.'));
            out.append('\n');
            for (PassRecord pass : passes) {
                out.append("  ").append(pass.describe()).append('\n');
            }
            out.append("  net ").append(netSizeDelta() >= 0 ? "+" : "").append(netSizeDelta())
                    .append(" bytes across ").append(passes.size())
                    .append(passes.size() == 1 ? " pass" : " passes")
                    .append(", ").append(totalMicros()).append("µs total");
            return out.toString();
        }
    }

    /** Accumulates records while the pipeline runs. Not thread-safe; one per jar. */
    public static final class Builder {

        private final Map<String, List<PassRecord>> entries = new LinkedHashMap<>();

        /** Records that {@code passId} transformed {@code className}. */
        public void record(String className, PassRecord record) {
            entries.computeIfAbsent(normalise(className), ignored -> new ArrayList<>())
                    .add(Objects.requireNonNull(record, "record"));
        }

        /** Whether anything has been recorded. */
        public boolean isEmpty() {
            return entries.isEmpty();
        }

        /** Freezes into an audit. */
        public TransformAudit build() {
            Map<String, ClassAudit> byClass = new TreeMap<>();
            entries.forEach((className, passes) ->
                    byClass.put(className, new ClassAudit(className, passes)));
            return new TransformAudit(byClass);
        }
    }

    // ------------------------------------------------------------ persistence

    /** Serialises to the {@code audit.json} text written into a cache entry. */
    public String toJson() {
        List<Json> classes = new ArrayList<>();
        byClass.values().stream()
                .sorted(Comparator.comparing(ClassAudit::className))
                .forEach(entry -> {
                    List<Json> passes = new ArrayList<>();
                    for (PassRecord pass : entry.passes()) {
                        List<Json> notes = pass.notes().stream().map(JsonWriter::string).toList();
                        passes.add(JsonWriter.ordered(
                                "pass", JsonWriter.string(pass.passId()),
                                "version", JsonWriter.string(pass.passVersion()),
                                "bytesBefore", JsonWriter.number(pass.bytesBefore()),
                                "bytesAfter", JsonWriter.number(pass.bytesAfter()),
                                "micros", JsonWriter.number(pass.micros()),
                                "notes", JsonWriter.list(notes)));
                    }
                    classes.add(JsonWriter.ordered(
                            "class", JsonWriter.string(entry.className()),
                            "passes", JsonWriter.list(passes)));
                });
        return JsonWriter.pretty(JsonWriter.ordered(
                "schema", JsonWriter.number(SCHEMA),
                "classes", JsonWriter.list(classes)));
    }

    /**
     * Reads an {@code audit.json} back.
     *
     * @throws TransformException if the file is unreadable or of an unknown schema — a cache
     *     entry whose audit cannot be read is corrupt, and silently continuing would leave the
     *     "who changed this class" question permanently unanswerable for that jar
     */
    public static TransformAudit fromJson(String text) {
        Json parsed;
        try {
            parsed = JsonReader.parse(text);
        } catch (JsonParseException e) {
            throw new TransformException("audit.json is not valid JSON: " + e.getMessage(), e);
        }
        Json.Obj root = parsed.asObject()
                .orElseThrow(() -> new TransformException("audit.json must be a JSON object"));
        int schema = root.get("schema")
                .flatMap(value -> value instanceof Json.Num num
                        ? Optional.of((int) num.value())
                        : Optional.empty())
                .orElseThrow(() -> new TransformException("audit.json has no numeric 'schema'"));
        if (schema != SCHEMA) {
            throw new TransformException(
                    "audit.json schema " + schema + " is not readable by this loader (expected "
                            + SCHEMA + ")");
        }
        Map<String, ClassAudit> byClass = new TreeMap<>();
        Json.Arr classes = root.get("classes").flatMap(Json::asArray)
                .orElseThrow(() -> new TransformException("audit.json has no 'classes' array"));
        for (Json element : classes.elements()) {
            Json.Obj entry = element.asObject()
                    .orElseThrow(() -> new TransformException("audit entry must be an object"));
            String className = entry.get("class").flatMap(Json::asString)
                    .orElseThrow(() -> new TransformException("audit entry has no 'class'"));
            List<PassRecord> passes = new ArrayList<>();
            Json.Arr passArray = entry.get("passes").flatMap(Json::asArray)
                    .orElseThrow(() -> new TransformException(
                            "audit entry for " + className + " has no 'passes'"));
            for (Json passElement : passArray.elements()) {
                passes.add(readPass(className, passElement));
            }
            byClass.put(className, new ClassAudit(className, passes));
        }
        return new TransformAudit(byClass);
    }

    private static PassRecord readPass(String className, Json element) {
        Json.Obj pass = element.asObject()
                .orElseThrow(() -> new TransformException(
                        "audit pass for " + className + " must be an object"));
        List<String> notes = pass.get("notes").flatMap(Json::asArray)
                .map(arr -> arr.elements().stream()
                        .map(note -> note.asString().orElse(""))
                        .toList())
                .orElse(List.of());
        return new PassRecord(
                required(pass, "pass", className),
                required(pass, "version", className),
                (int) requiredNumber(pass, "bytesBefore", className),
                (int) requiredNumber(pass, "bytesAfter", className),
                (long) requiredNumber(pass, "micros", className),
                notes);
    }

    private static String required(Json.Obj obj, String key, String className) {
        return obj.get(key).flatMap(Json::asString).orElseThrow(() -> new TransformException(
                "audit pass for " + className + " has no string '" + key + "'"));
    }

    private static double requiredNumber(Json.Obj obj, String key, String className) {
        return obj.get(key)
                .flatMap(value -> value instanceof Json.Num num
                        ? Optional.of(num.value())
                        : Optional.empty())
                .orElseThrow(() -> new TransformException(
                        "audit pass for " + className + " has no number '" + key + "'"));
    }
}
