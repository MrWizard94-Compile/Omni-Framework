package org.omniframework.loader.core.entrypoint;

import java.io.Serial;
import java.util.List;
import java.util.Objects;

/**
 * One or more entrypoints failed during a phase.
 *
 * <p>Carries <em>every</em> failure rather than only the first. A pack with three broken mods
 * should tell the user about three broken mods, not send them round a fix-relaunch-discover loop
 * that costs a full game start each time.
 */
public final class EntrypointPhaseException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final String phase;
    private final transient List<EntrypointException> failures;

    /** Records the failures from one phase. */
    public EntrypointPhaseException(String phase, List<EntrypointException> failures) {
        super(describe(phase, failures));
        this.phase = phase;
        this.failures = List.copyOf(failures);
        // Attached rather than chained: there is no single "cause" when several mods failed
        // independently, and picking one would imply a relationship that does not exist.
        failures.forEach(this::addSuppressed);
    }

    private static String describe(String phase, List<EntrypointException> failures) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(failures, "failures");
        StringBuilder out = new StringBuilder(failures.size() == 1
                ? "1 mod failed to initialise during the '" + phase + "' phase:"
                : failures.size() + " mods failed to initialise during the '" + phase
                        + "' phase:");
        for (EntrypointException failure : failures) {
            out.append("\n  - ").append(failure.getMessage());
        }
        return out.toString();
    }

    /** The phase that failed. */
    public String phase() {
        return phase;
    }

    /** Every failure, in the order the entrypoints were attempted. */
    public List<EntrypointException> failures() {
        return failures;
    }
}
