package org.omniframework.loader.transform;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One stage of the AOT pipeline (ARCHITECTURE §6).
 *
 * <p>Passes see every entry in a jar, not only classes, because two of the shipped stages rewrite
 * resources rather than bytecode — a Mixin refmap is JSON, and an access-widener file is text.
 * Giving every pass the same view keeps the pipeline from needing two kinds of stage.
 *
 * <h2>Returning empty means "unchanged", and it matters</h2>
 *
 * <p>A pass that has nothing to do must return {@link Optional#empty()} rather than the bytes it
 * was handed. The pipeline uses that to decide whether to record an audit entry, so returning
 * identical bytes would fill the audit with thousands of no-op records and bury the handful of
 * real changes someone is actually looking for.
 *
 * <h2>Notes travel with the result, not in the pass</h2>
 *
 * <p>A pass explains what it did by returning notes alongside the bytes. The pipeline may
 * transform several jars concurrently through one pass instance, so a pass that recorded "what I
 * just did" in a field would report another thread's work. Implementations must hold no
 * per-transform state.
 */
public interface TransformPass {

    /** A stable identifier, recorded in the audit. Kebab-case, e.g. {@code remap-official}. */
    String id();

    /**
     * This pass's version, recorded in the audit and folded into the cache key by the caller.
     *
     * <p>Bump it whenever the pass's <em>output</em> changes for unchanged input. Forgetting to
     * is how a fixed transform bug survives an update: the key is unchanged, so every existing
     * cache entry stays a hit and the fix reaches nobody.
     */
    String version();

    /**
     * Transforms one jar entry.
     *
     * @return the new content and what was done, or empty if this pass leaves the entry alone
     */
    Optional<Outcome> transform(JarResource resource, PassContext context);

    /**
     * Called once before a jar's entries are transformed.
     *
     * <p>Passes that need to see the whole jar first — collecting an access-widener file before
     * applying it to classes — do that here. State derived from the jar belongs in
     * {@link PassContext}, never in the pass.
     */
    default void beginJar(List<JarResource> entries, PassContext context) {
        // Most passes are per-entry and need no preparation.
    }

    /**
     * New content plus a human-readable account of the change, for the audit.
     *
     * <p>An outcome can also say the entry should be <em>removed</em>. That is how a pass drops a
     * class belonging entirely to the other side: returning empty bytes would produce an
     * unloadable class file, and reporting it out-of-band would mean per-transform state on a
     * pass instance the pipeline shares between concurrently-transforming jars.
     */
    record Outcome(byte[] content, List<String> notes, boolean removed) {

        /** Canonical constructor; defensively copies. */
        public Outcome {
            content = Objects.requireNonNull(content, "content").clone();
            notes = List.copyOf(Objects.requireNonNull(notes, "notes"));
        }

        @Override
        public byte[] content() {
            return content.clone();
        }

        /** The content without copying — used by the pipeline, which will not modify it. */
        byte[] contentDirect() {
            return content;
        }

        /** A change with a single explanatory note. */
        public static Outcome of(byte[] content, String note) {
            return new Outcome(content, List.of(note), false);
        }

        /** A change with several notes. */
        public static Outcome of(byte[] content, List<String> notes) {
            return new Outcome(content, notes, false);
        }

        /**
         * The entry should be dropped from the output jar entirely.
         *
         * <p>Later passes are not run against a removed entry — there is nothing left to
         * transform — and the removal is recorded in the audit so the absence is explained
         * rather than mysterious.
         */
        public static Outcome removal(String note) {
            return new Outcome(new byte[0], List.of(note), true);
        }
    }
}
