package org.omniframework.loader.diag;

/**
 * Severity of an {@link OmniIssue}.
 *
 * <p>The distinction that matters is between {@link #WARNING} and {@link #ERROR}: a warning
 * never prevents a mod from loading, an error always does. There is deliberately no
 * "severe warning" tier, because a tier that sometimes blocks loading and sometimes does not
 * is exactly the ambiguity that makes launcher logs untrustworthy.
 */
public enum IssueLevel {
    /**
     * Something the user should know but which changes nothing about the outcome — an unknown
     * manifest key, a lossy metadata import from a foreign ecosystem.
     */
    WARNING,

    /**
     * The mod cannot load. Resolution fails, and the boot UI renders this in the failure tree
     * (LDR-10).
     */
    ERROR;

    /** Whether this level prevents loading. */
    public boolean blocksLoading() {
        return this == ERROR;
    }
}
