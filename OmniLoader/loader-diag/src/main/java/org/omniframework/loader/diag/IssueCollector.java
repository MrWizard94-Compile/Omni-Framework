package org.omniframework.loader.diag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Accumulates issues during an operation that must report <em>everything</em> wrong, not just
 * the first problem.
 *
 * <p>This exists because of a specific, repeated failure in the corpus: loaders that throw on
 * the first bad key make the user fix a manifest one error per launch. Parsing collects
 * instead, so a single boot reports every problem at once (the (fancymodloader)
 * {@code ModLoadingIssue} pattern).
 *
 * <p>Not thread-safe by itself: one collector belongs to one operation. Parallel discovery
 * gives each candidate its own collector and merges the results, which is cheaper and clearer
 * than sharing a synchronized one.
 */
public final class IssueCollector {

    private final List<OmniIssue> issues = new ArrayList<>();

    /** Records an issue. */
    public void add(OmniIssue issue) {
        issues.add(Objects.requireNonNull(issue, "issue"));
    }

    /** Records every issue from another collector (merge after parallel work). */
    public void addAll(IssueCollector other) {
        issues.addAll(Objects.requireNonNull(other, "other").issues);
    }

    /** Every issue recorded, in the order they were found. */
    public List<OmniIssue> issues() {
        return Collections.unmodifiableList(issues);
    }

    /** Only the issues that prevent loading. */
    public List<OmniIssue> errors() {
        return issues.stream().filter(OmniIssue::blocksLoading).toList();
    }

    /** Whether anything recorded prevents loading. */
    public boolean hasErrors() {
        return issues.stream().anyMatch(OmniIssue::blocksLoading);
    }

    /** Whether nothing at all was recorded. */
    public boolean isEmpty() {
        return issues.isEmpty();
    }

    /**
     * Throws when any recorded issue blocks loading, carrying <em>all</em> of them.
     *
     * @throws OmniIssueException if {@link #hasErrors()}
     */
    public void throwIfErrors(String summary) {
        if (hasErrors()) {
            throw new OmniIssueException(summary, List.copyOf(issues));
        }
    }
}
