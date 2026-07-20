package org.omniframework.loader.diag;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One typed, translatable, actionable diagnostic (LDR-10).
 *
 * <p>Every failure in OmniLoader is an {@code OmniIssue}, never a bare log line. Three
 * requirements shaped this type, each taken from a specific corpus finding:
 *
 * <ul>
 *   <li><b>Translatable</b> — the {@linkplain #code() code} is a stable identifier and
 *       {@linkplain #arguments() arguments} are supplied separately, so a UI can render the
 *       issue in the user's language rather than echoing an English string the loader
 *       happened to build. This is why the message is <em>assembled</em> rather than passed in.
 *   <li><b>Located</b> — {@linkplain #location() location} names the exact thing at fault: a
 *       jar path, a manifest key path such as {@code depends.somelib.version}, a class name.
 *       "Invalid metadata" without a location is the failure mode this field exists to prevent.
 *   <li><b>Actionable</b> — {@linkplain #remedy() remedy} tells the user what to <em>do</em>.
 *       The (quilt-loader) {@code ResultAnalyzer} quality bar: a diagnostic that only states
 *       what went wrong has done half its job.
 * </ul>
 *
 * <p>Instances are immutable and safe to share across threads, which matters because
 * discovery and transformation run in parallel (ARCHITECTURE §3).
 */
public record OmniIssue(
        IssueLevel level,
        String code,
        String location,
        List<String> arguments,
        String remedy,
        Optional<Throwable> cause) {

    /** Canonical constructor; defensively copies and validates. */
    public OmniIssue {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(remedy, "remedy");
        Objects.requireNonNull(cause, "cause");
        if (code.isBlank()) {
            throw new IllegalArgumentException("an issue code must identify the issue");
        }
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
    }

    /** Starts building an issue with the given level and stable code. */
    public static Builder of(IssueLevel level, String code) {
        return new Builder(level, code);
    }

    /** Starts building an {@link IssueLevel#ERROR}. */
    public static Builder error(String code) {
        return new Builder(IssueLevel.ERROR, code);
    }

    /** Starts building an {@link IssueLevel#WARNING}. */
    public static Builder warning(String code) {
        return new Builder(IssueLevel.WARNING, code);
    }

    /** Whether this issue prevents loading. */
    public boolean blocksLoading() {
        return level.blocksLoading();
    }

    /**
     * A single-line, English rendering for logs and tests.
     *
     * <p>This is a <em>fallback</em>, not the presentation path: a UI should translate
     * {@link #code()} with {@link #arguments()}. Kept here so that a headless boot still
     * produces something a human can act on.
     */
    public String describe() {
        StringBuilder text = new StringBuilder(64);
        text.append(level).append(' ').append(code).append(" at ").append(location);
        if (!arguments.isEmpty()) {
            text.append(' ').append(arguments);
        }
        text.append(" — ").append(remedy);
        return text.toString();
    }

    @Override
    public String toString() {
        return describe();
    }

    /** Fluent builder; every field except {@code cause} is required. */
    public static final class Builder {
        private final IssueLevel level;
        private final String code;
        private String location = "";
        private List<String> arguments = List.of();
        private String remedy = "";
        private Throwable cause;

        private Builder(IssueLevel level, String code) {
            this.level = Objects.requireNonNull(level, "level");
            this.code = Objects.requireNonNull(code, "code");
        }

        /** Where the problem is: a jar path, a manifest key path, a class name. */
        public Builder at(String where) {
            this.location = Objects.requireNonNull(where, "where");
            return this;
        }

        /** Values interpolated into the translated message, in order. */
        public Builder with(String... values) {
            this.arguments = List.of(values);
            return this;
        }

        /** What the user should do about it. */
        public Builder remedy(String text) {
            this.remedy = Objects.requireNonNull(text, "text");
            return this;
        }

        /** The underlying exception, when one exists. */
        public Builder causedBy(Throwable throwable) {
            this.cause = throwable;
            return this;
        }

        /** Builds the immutable issue. */
        public OmniIssue build() {
            return new OmniIssue(level, code, location, arguments, remedy, Optional.ofNullable(cause));
        }
    }
}
