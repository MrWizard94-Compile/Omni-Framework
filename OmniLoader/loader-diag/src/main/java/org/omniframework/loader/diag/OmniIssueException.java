package org.omniframework.loader.diag;

import java.io.Serial;
import java.util.List;
import java.util.Objects;

/**
 * Thrown when blocking issues stop an operation, carrying every issue found rather than only
 * the one that happened to be thrown.
 *
 * <p>Callers that render failures (the boot UI's failure tree, the launcher's error surface)
 * should read {@link #issues()} rather than the message: the message is a summary for logs,
 * the issues are the structured, translatable truth.
 */
public final class OmniIssueException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final transient List<OmniIssue> issues;

    /** Creates an exception carrying every issue that contributed to the failure. */
    public OmniIssueException(String summary, List<OmniIssue> issues) {
        super(buildMessage(summary, issues));
        this.issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
        issues.stream()
                .filter(OmniIssue::blocksLoading)
                .map(OmniIssue::cause)
                .flatMap(java.util.Optional::stream)
                .findFirst()
                .ifPresent(this::initCause);
    }

    /** Every issue that contributed, blocking or not. */
    public List<OmniIssue> issues() {
        return issues;
    }

    private static String buildMessage(String summary, List<OmniIssue> issues) {
        StringBuilder text = new StringBuilder(Objects.requireNonNull(summary, "summary"));
        for (OmniIssue issue : issues) {
            if (issue.blocksLoading()) {
                text.append(System.lineSeparator()).append("  - ").append(issue.describe());
            }
        }
        return text.toString();
    }
}
