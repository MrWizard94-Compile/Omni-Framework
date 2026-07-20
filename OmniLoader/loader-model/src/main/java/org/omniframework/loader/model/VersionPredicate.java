package org.omniframework.loader.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A dependency's version constraint, e.g. {@code ">=0.1"}, {@code "1.21.1"}, {@code "^2.0.0"},
 * or {@code "*"}.
 *
 * <p>Supported forms, chosen to match what mod manifests across the corpus actually contain:
 *
 * <table border="1">
 *   <caption>Constraint syntax</caption>
 *   <tr><th>Form</th><th>Meaning</th></tr>
 *   <tr><td>{@code *} or {@code any}</td><td>any version</td></tr>
 *   <tr><td>{@code 1.2.3} or {@code =1.2.3}</td><td>exactly that version</td></tr>
 *   <tr><td>{@code >=1.2.3}, {@code >1.2.3}, {@code <=1.2.3}, {@code <1.2.3}</td><td>comparison</td></tr>
 *   <tr><td>{@code ~1.2.3}</td><td>{@code >=1.2.3} and {@code <1.3.0} (patch-level changes)</td></tr>
 *   <tr><td>{@code ^1.2.3}</td><td>{@code >=1.2.3} and {@code <2.0.0} (compatible changes)</td></tr>
 * </table>
 *
 * <p>Several constraints separated by commas must <em>all</em> hold: {@code ">=1.2, <2.0"}.
 *
 * <p>Partial versions are accepted where they are unambiguous — {@code ">=1.2"} means
 * {@code ">=1.2.0"} — because manifests write them constantly and rejecting them would be
 * pedantry rather than safety.
 */
public record VersionPredicate(String raw, List<Term> terms) {

    /** Canonical constructor. */
    public VersionPredicate {
        Objects.requireNonNull(raw, "raw");
        terms = List.copyOf(Objects.requireNonNull(terms, "terms"));
    }

    /** How a term compares a candidate against its bound. */
    public enum Operator {
        /** Any version satisfies. */
        ANY,
        /** Exactly equal (by semver precedence, or by raw text for non-semver). */
        EQUAL,
        /** Greater than. */
        GREATER,
        /** Greater than or equal. */
        GREATER_EQUAL,
        /** Less than. */
        LESS,
        /** Less than or equal. */
        LESS_EQUAL
    }

    /** One clause of a predicate. */
    public record Term(Operator operator, Optional<SemanticVersion> bound, String rawBound) {
        /** Canonical constructor. */
        public Term {
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(bound, "bound");
            Objects.requireNonNull(rawBound, "rawBound");
        }
    }

    /** The predicate that accepts everything. */
    public static VersionPredicate any() {
        return new VersionPredicate("*", List.of(new Term(Operator.ANY, Optional.empty(), "*")));
    }

    /**
     * Parses a constraint string.
     *
     * @return empty when the text cannot be understood, so the caller can raise a located,
     *     typed issue instead of silently accepting a constraint it will never enforce
     */
    public static Optional<VersionPredicate> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        if (trimmed.equals("*") || trimmed.equalsIgnoreCase("any")) {
            return Optional.of(new VersionPredicate(trimmed, any().terms()));
        }
        List<Term> terms = new ArrayList<>();
        for (String piece : trimmed.split(",", -1)) {
            Optional<Term> term = parseTerm(piece.trim());
            if (term.isEmpty()) {
                return Optional.empty();
            }
            terms.add(term.get());
        }
        return terms.isEmpty() ? Optional.empty() : Optional.of(new VersionPredicate(trimmed, terms));
    }

    /** Whether {@code version} satisfies every term. */
    public boolean matches(ModVersion version) {
        Objects.requireNonNull(version, "version");
        for (Term term : terms) {
            if (!matchesTerm(term, version)) {
                return false;
            }
        }
        return true;
    }

    /** Whether this predicate accepts every version. */
    public boolean isAny() {
        return terms.size() == 1 && terms.get(0).operator() == Operator.ANY;
    }

    @Override
    public String toString() {
        return raw;
    }

    // -------------------------------------------------------------- matching

    private static boolean matchesTerm(Term term, ModVersion version) {
        if (term.operator() == Operator.ANY) {
            return true;
        }
        // A non-semver version can only ever satisfy an exact textual match. Pretending
        // otherwise would let ">=2.0" silently accept "v3-BETA".
        if (version.semantic().isEmpty() || term.bound().isEmpty()) {
            return term.operator() == Operator.EQUAL && term.rawBound().equals(version.raw());
        }
        int comparison = version.semantic().get().compareTo(term.bound().get());
        return switch (term.operator()) {
            case EQUAL -> comparison == 0;
            case GREATER -> comparison > 0;
            case GREATER_EQUAL -> comparison >= 0;
            case LESS -> comparison < 0;
            case LESS_EQUAL -> comparison <= 0;
            case ANY -> true;
        };
    }

    // --------------------------------------------------------------- parsing

    private static Optional<Term> parseTerm(String text) {
        if (text.isEmpty()) {
            return Optional.empty();
        }
        if (text.startsWith("^") || text.startsWith("~")) {
            // Caret/tilde are ranges, but a Term holds a single bound, so they are only
            // representable as the lower bound here; `expand` produces both halves.
            return parseVersionish(text.substring(1))
                    .map(version -> new Term(Operator.GREATER_EQUAL, Optional.of(version), version.toString()));
        }
        Operator operator;
        String rest;
        if (text.startsWith(">=")) {
            operator = Operator.GREATER_EQUAL;
            rest = text.substring(2);
        } else if (text.startsWith("<=")) {
            operator = Operator.LESS_EQUAL;
            rest = text.substring(2);
        } else if (text.startsWith(">")) {
            operator = Operator.GREATER;
            rest = text.substring(1);
        } else if (text.startsWith("<")) {
            operator = Operator.LESS;
            rest = text.substring(1);
        } else if (text.startsWith("=")) {
            operator = Operator.EQUAL;
            rest = text.substring(1);
        } else {
            operator = Operator.EQUAL;
            rest = text;
        }
        rest = rest.trim();
        if (rest.isEmpty()) {
            return Optional.empty();
        }
        Optional<SemanticVersion> bound = parseVersionish(rest);
        // A non-semver exact bound is legitimate ("=nightly-2026-07-19"); a non-semver
        // comparison bound is not, because there is no order to compare against.
        if (bound.isEmpty() && operator != Operator.EQUAL) {
            return Optional.empty();
        }
        return Optional.of(new Term(operator, bound, rest));
    }

    /** Parses a version, filling in omitted components: {@code 1.2} becomes {@code 1.2.0}. */
    private static Optional<SemanticVersion> parseVersionish(String text) {
        Optional<SemanticVersion> exact = SemanticVersion.parse(text);
        if (exact.isPresent()) {
            return exact;
        }
        String[] parts = text.split("\\.", -1);
        if (parts.length == 0 || parts.length > 3) {
            return Optional.empty();
        }
        int[] numbers = {0, 0, 0};
        for (int i = 0; i < parts.length; i++) {
            try {
                numbers[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException notANumber) {
                return Optional.empty();
            }
            if (numbers[i] < 0) {
                return Optional.empty();
            }
        }
        return Optional.of(SemanticVersion.of(numbers[0], numbers[1], numbers[2]));
    }

    /**
     * Expands caret and tilde shorthand into explicit bounds.
     *
     * <p>Kept separate from {@link #parse(String)} so the original text survives for
     * diagnostics: the user wrote {@code ^1.2.3} and should see that in an error, even though
     * the resolver reasons about {@code >=1.2.3, <2.0.0}.
     */
    public static Optional<VersionPredicate> expand(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith("^") && !trimmed.startsWith("~")) {
            return parse(trimmed);
        }
        Optional<SemanticVersion> lower = parseVersionish(trimmed.substring(1));
        if (lower.isEmpty()) {
            return Optional.empty();
        }
        SemanticVersion from = lower.get();
        SemanticVersion until = trimmed.startsWith("^")
                ? SemanticVersion.of(from.major() + 1, 0, 0)
                : SemanticVersion.of(from.major(), from.minor() + 1, 0);
        return Optional.of(new VersionPredicate(
                trimmed,
                List.of(
                        new Term(Operator.GREATER_EQUAL, Optional.of(from), from.toString()),
                        new Term(Operator.LESS, Optional.of(until), until.toString()))));
    }
}
