package org.omniframework.loader.resolve;

import java.util.List;
import org.omniframework.loader.diag.IssueCollector;

/**
 * A pluggable source of extra resolution constraints — the rule SPI of LDR-5.
 *
 * <p>Each hosted ecosystem gets a compat layer, and each layer's dependency dialect has rules the
 * canonical model does not express. Fabric's {@code minecraft} dependency means something
 * specific; NeoForge's mandatory/optional split means something else; a layer may need to insist
 * its own API surface is present whenever it hosts a guest mod. Building those into the resolver
 * would make the core know about ecosystems it is supposed to be ignorant of (LDR-1).
 *
 * <p>Rules run on the fully discovered candidate set before search begins. They can:
 * <ul>
 *   <li>reject candidates outright ({@link #filter}) — a guest mod whose loader is not present;
 *   <li>add requirements ({@link #additionalRequirements}) — "if any Fabric mod loads, the
 *       Fabric API surface must load too".
 * </ul>
 *
 * <p>A rule that cannot decide should say nothing. Rules run in registration order, and a rule
 * must not depend on having run before or after another — that coupling is exactly the
 * ordering-sensitivity this SPI exists to avoid.
 */
public interface ResolverRule {

    /** Stable name, for diagnostics that attribute a rejection to the rule that caused it. */
    String name();

    /**
     * Decides whether a candidate may participate at all.
     *
     * <p>Rejecting here rather than through a failed dependency produces a better message: "this
     * NeoForge mod needs the NeoForge compat layer, which is not installed" beats a resolution
     * failure about a mod id the user has never heard of.
     *
     * @param issues record a typed issue when rejecting, so the user learns why
     * @return true to keep the candidate
     */
    default boolean filter(ModCandidate candidate, IssueCollector issues) {
        return true;
    }

    /**
     * Extra mod ids that must resolve, given the candidates that survived filtering.
     *
     * <p>Returned ids are treated exactly like roots: if one cannot be satisfied, resolution
     * fails with an explanation naming this rule.
     */
    default List<String> additionalRequirements(List<ModCandidate> candidates) {
        return List.of();
    }
}
