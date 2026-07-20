package org.omniframework.loader.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.omniframework.loader.transform.TransformCache;

/**
 * Answers "who changed this class" across both halves of the pipeline (LDR-8, ARCHITECTURE §8).
 *
 * <h2>Why one query and not two</h2>
 *
 * <p>A class arriving at a debugger may have been remapped, access-widened and stripped ahead of
 * time, then had several mods' mixins applied at load. Those two histories live in different
 * places for a real reason — the AOT half is cached and keyed on its inputs, while Mixin's half
 * cannot be, because its targets are unknown until a class is requested (§6 excludes it
 * deliberately).
 *
 * <p>But that is the loader's problem, not the user's. Someone asking why a method looks wrong
 * should not have to know that two mechanisms exist, which one to consult, or that one of them
 * evaporates when the process exits. This joins them into a single answer, ordered the way the
 * work actually happened: everything ahead-of-time first, then everything at load.
 *
 * <p>LDR-8 asks for the Mixin audit trail to be "wired into LDR-2's store". It is wired at the
 * <em>query</em>, not by writing load-time events into a cache entry — a cache entry is keyed on
 * jar bytes, mappings and pipeline version, none of which say anything about which mods happened
 * to be present this run. Writing per-run events into a content-addressed entry would make the
 * entry's contents depend on things outside its key, which is the one property a cache must never
 * have.
 */
public record AuditQuery(TransformCache cache, OmniAuditTrail mixinTrail) {

    /** Canonical constructor. */
    public AuditQuery {
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(mixinTrail, "mixinTrail");
    }

    /** Everything known about one class, ahead-of-time work first, then load-time. */
    public record History(
            String className,
            List<TransformCache.ClassHistory> aheadOfTime,
            Optional<String> loadTime) {

        /** Canonical constructor; defensively copies. */
        public History {
            Objects.requireNonNull(className, "className");
            aheadOfTime = List.copyOf(Objects.requireNonNull(aheadOfTime, "aheadOfTime"));
            Objects.requireNonNull(loadTime, "loadTime");
        }

        /** Whether anything at all touched this class. */
        public boolean isEmpty() {
            return aheadOfTime.isEmpty() && loadTime.isEmpty();
        }

        /** The rendering {@code omniloader --audit <class>} prints. */
        public String describe() {
            if (isEmpty()) {
                // Distinguishing "nothing touched it" from "I have no record" matters: the
                // first is a fact, the second would be an admission the audit is incomplete.
                return className.replace('/', '.')
                        + "\n  nothing modified this class — neither the transform pipeline nor "
                        + "any mixin";
            }
            StringBuilder out = new StringBuilder();
            if (aheadOfTime.isEmpty()) {
                out.append(className.replace('/', '.'))
                        .append("\n  ahead-of-time: unmodified");
            } else {
                for (TransformCache.ClassHistory entry : aheadOfTime) {
                    if (out.length() > 0) {
                        out.append('\n');
                    }
                    out.append(entry.describe());
                }
            }
            loadTime.ifPresent(trail -> out.append('\n').append(trail));
            return out.toString();
        }
    }

    /** Looks a class up in both halves. Accepts dotted or slashed names. */
    public History forClass(String className) {
        Objects.requireNonNull(className, "className");
        List<TransformCache.ClassHistory> aot = new ArrayList<>(cache.auditClass(className));
        return new History(className, aot, mixinTrail.describeClass(className));
    }

    /** The full rendering for one class. */
    public String describe(String className) {
        return forClass(className).describe();
    }
}
