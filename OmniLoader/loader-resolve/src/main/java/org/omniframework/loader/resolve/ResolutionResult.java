package org.omniframework.loader.resolve;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.omniframework.loader.diag.OmniIssue;

/**
 * The outcome of resolution: an ordered load set, or the reason there isn't one.
 *
 * <p>Success is a <em>list</em>, not a set: order is part of the answer, not something computed
 * later. Two mods that both resolve but load in the wrong order fail in ways that look like
 * corruption, so the resolver owns ordering and hands back the sequence it proved.
 */
public record ResolutionResult(List<ModCandidate> loadOrder, List<OmniIssue> issues) {

    /** Canonical constructor; defensively copies. */
    public ResolutionResult {
        loadOrder = List.copyOf(Objects.requireNonNull(loadOrder, "loadOrder"));
        issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
    }

    /** A successful resolution, possibly with warnings. */
    public static ResolutionResult success(List<ModCandidate> loadOrder, List<OmniIssue> issues) {
        return new ResolutionResult(loadOrder, issues);
    }

    /** A failed resolution: no load order, and at least one blocking issue. */
    public static ResolutionResult failure(List<OmniIssue> issues) {
        if (issues.stream().noneMatch(OmniIssue::blocksLoading)) {
            throw new IllegalArgumentException("a failed resolution must carry a blocking issue");
        }
        return new ResolutionResult(List.of(), issues);
    }

    /** Whether a load order was produced. */
    public boolean succeeded() {
        return issues.stream().noneMatch(OmniIssue::blocksLoading);
    }

    /** Only the issues that prevented loading. */
    public List<OmniIssue> errors() {
        return issues.stream().filter(OmniIssue::blocksLoading).toList();
    }

    /** The selected candidate for a mod id, if it was selected. */
    public Optional<ModCandidate> selected(String modId) {
        Objects.requireNonNull(modId, "modId");
        return loadOrder.stream().filter(candidate -> candidate.id().equals(modId)).findFirst();
    }

    /** The selected mod ids, in load order. */
    public List<String> loadOrderIds() {
        return loadOrder.stream().map(ModCandidate::id).toList();
    }
}
