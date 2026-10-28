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
        String cleaned = stripFabricDash(text);
        // Trailing dots from incomplete bounds like "21.1."
        while (cleaned.endsWith(".") && cleaned.length() > 1) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        // Trailing "+" from bounds like "3.2.1.+" (NeoForge template fragments)
        while (cleaned.endsWith("+") && cleaned.length() > 1) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        Optional<SemanticVersion> exact = SemanticVersion.parse(cleaned);
        if (exact.isPresent()) {
            return exact;
        }
        String[] parts = cleaned.split("\\.", -1);
        if (parts.length == 0) {
            return Optional.empty();
        }
        int[] numbers = {0, 0, 0};
        int limit = Math.min(parts.length, 3);
        for (int i = 0; i < limit; i++) {
            if (parts[i].isEmpty()) {
                continue;
            }
            // Strip letter suffixes for range bounds (0.5.1.e → 0.5.1)
            String numeric = parts[i];
            int cut = 0;
            while (cut < numeric.length() && Character.isDigit(numeric.charAt(cut))) {
                cut++;
            }
            if (cut == 0) {
                return Optional.empty();
            }
            try {
                numbers[i] = Integer.parseInt(numeric.substring(0, cut));
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
     * Expands caret, tilde, Fabric x-wildcards, and Fabric interval notation into explicit
     * bounds.
     *
     * <p>Kept separate from {@link #parse(String)} so the original text survives for
     * diagnostics: the user wrote {@code ^1.2.3} and should see that in an error, even though
     * the resolver reasons about {@code >=1.2.3, <2.0.0}.
     *
     * <p>Fabric Loader also accepts space-separated conjunctions ({@code ">=1.21- <1.21.2-"})
     * and trailing {@code -} on version components (include pre-releases of that version).
     * Those are normalized here so real pack manifests stop becoming range-unreadable ANY.
     */
    public static Optional<VersionPredicate> expand(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String original = text.trim();
        if (original.isEmpty()) {
            return Optional.empty();
        }
        // Unresolved Gradle/Forge placeholders cannot be enforced honestly — widen to any
        // so packs do not mass-fail on "#{exdeorum_version_range}" style templates.
        if (original.contains("#{") || original.contains("${")) {
            return Optional.of(any());
        }
        // Maven intervals used by NeoForge and dual-loader jars ([1.21.1,), [1.21,1.21.1]).
        // Must run before comma-split parse, which would treat "[1.21" as an exact bound.
        Optional<VersionPredicate> maven = parseMavenInterval(original);
        if (maven.isPresent()) {
            return Optional.of(new VersionPredicate(original, maven.get().terms()));
        }
        // Fabric intervals: spaces separate AND-terms when commas are absent.
        String normalized = normalizeFabricInterval(original);
        // Expand ~ / ^ tokens inside multi-term ranges ("~1.21, <1.21.2") so they are not
        // misread as a single tilde bound with garbage after the version.
        normalized = expandCaretTildeTokens(normalized);
        // Fabric-style "1.21.x" / "21.0.x" → >=N.M.0, <next
        Optional<VersionPredicate> xRange = expandXWildcard(normalized);
        if (xRange.isPresent()) {
            // Preserve author text for diagnostics.
            return Optional.of(new VersionPredicate(original, xRange.get().terms()));
        }
        Optional<VersionPredicate> parsed = parse(normalized);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        // Keep the author's original text on the record.
        return Optional.of(new VersionPredicate(original, parsed.get().terms()));
    }

    /**
     * Rewrites each {@code ~x} / {@code ^x} token into explicit {@code >=} / {@code <} bounds
     * so multi-clause Fabric ranges like {@code ~1.21 <1.21.2} match installed {@code 1.21.1}.
     */
    private static String expandCaretTildeTokens(String text) {
        String[] pieces = text.split(",", -1);
        StringBuilder out = new StringBuilder(text.length() * 2);
        for (int i = 0; i < pieces.length; i++) {
            if (i > 0) {
                out.append(", ");
            }
            String piece = pieces[i].trim();
            if (piece.startsWith("~") || piece.startsWith("^")) {
                Optional<SemanticVersion> lower =
                        parseVersionish(stripFabricDash(piece.substring(1)));
                if (lower.isEmpty()) {
                    out.append(piece);
                    continue;
                }
                SemanticVersion from = lower.get();
                SemanticVersion until = piece.startsWith("^")
                        ? SemanticVersion.of(from.major() + 1, 0, 0)
                        : SemanticVersion.of(from.major(), from.minor() + 1, 0);
                // Preserve pre-release on lower bound so ^1.0.0-beta.4 matches 1.0.0-beta.4
                // (writing ">=1.0.0" would exclude pre-releases of 1.0.0).
                out.append(">=").append(from);
                out.append(", <").append(until.major()).append('.').append(until.minor()).append('.')
                        .append(until.patch());
            } else {
                out.append(piece);
            }
        }
        return out.toString();
    }

    /**
     * Rewrites Fabric-style intervals into the comma-separated form {@link #parse} understands.
     * Strips trailing {@code -} from version tokens ({@code 1.21-} → {@code 1.21}).
     */
    private static String normalizeFabricInterval(String text) {
        // Already comma-joined: still strip trailing dashes on each piece.
        if (text.contains(",")) {
            String[] pieces = text.split(",", -1);
            StringBuilder out = new StringBuilder(text.length());
            for (int i = 0; i < pieces.length; i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append(normalizeTerm(pieces[i].trim()));
            }
            return out.toString();
        }
        // Space-separated operators: ">=1.21- <1.21.2-" or "~1.21 <1.22"
        String[] tokens = text.split("\\s+");
        if (tokens.length <= 1) {
            return normalizeTerm(text);
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(normalizeTerm(tokens[i]));
        }
        return out.toString();
    }

    private static String normalizeTerm(String term) {
        if (term.isEmpty()) {
            return term;
        }
        // Operator + version, or bare version / caret / tilde.
        if (term.startsWith(">=") || term.startsWith("<=")) {
            return term.substring(0, 2) + stripFabricDash(term.substring(2));
        }
        if (term.startsWith(">") || term.startsWith("<") || term.startsWith("=")
                || term.startsWith("^") || term.startsWith("~")) {
            return term.charAt(0) + stripFabricDash(term.substring(1));
        }
        return stripFabricDash(term);
    }

    /** Fabric's trailing {@code -} means "this version including pre-releases"; strip for order. */
    private static String stripFabricDash(String version) {
        String v = version.trim();
        while (v.endsWith("-") && v.length() > 1) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    /**
     * Parses a Maven version interval: {@code [1.0,)}, {@code (1.0,2.0]}, {@code [1.21.1]}, …
     *
     * <p>Shared by NeoForge import and Fabric dual-loader jars that embed NeoForge-style ranges
     * in {@code fabric.mod.json}.
     */
    public static Optional<VersionPredicate> parseMavenInterval(String text) {
        if (text == null || text.length() < 2) {
            return Optional.empty();
        }
        char open = text.charAt(0);
        char close = text.charAt(text.length() - 1);
        if ((open != '[' && open != '(') || (close != ']' && close != ')')) {
            return Optional.empty();
        }
        boolean lowerInclusive = open == '[';
        boolean upperInclusive = close == ']';
        String inner = text.substring(1, text.length() - 1).trim();
        if (!inner.contains(",")) {
            if (inner.isEmpty() || !lowerInclusive || !upperInclusive) {
                return Optional.empty();
            }
            return parse(inner);
        }
        String[] parts = inner.split(",", -1);
        if (parts.length != 2) {
            return Optional.empty();
        }
        String lower = parts[0].trim();
        String upper = parts[1].trim();
        // Trailing letters on an upper bound (e.g. "1.21z") mean "any build of this minor"
        // in some pack manifests — open to next minor rather than pinning patch 0.
        boolean upperLetterWildcard = upper.matches(".*\\d[A-Za-z]+$");
        StringBuilder omni = new StringBuilder();
        if (!lower.isEmpty()) {
            omni.append(lowerInclusive ? ">=" : ">").append(stripFabricDash(lower));
        }
        if (!upper.isEmpty()) {
            if (!omni.isEmpty()) {
                omni.append(", ");
            }
            if (upperLetterWildcard) {
                Optional<SemanticVersion> u = parseVersionish(stripFabricDash(upper));
                if (u.isPresent()) {
                    SemanticVersion next = SemanticVersion.of(u.get().major(), u.get().minor() + 1, 0);
                    omni.append('<')
                            .append(next.major())
                            .append('.')
                            .append(next.minor())
                            .append('.')
                            .append(next.patch());
                } else {
                    omni.append(upperInclusive ? "<=" : "<").append(stripFabricDash(upper));
                }
            } else {
                omni.append(upperInclusive ? "<=" : "<").append(stripFabricDash(upper));
            }
        }
        if (omni.isEmpty()) {
            return Optional.of(any());
        }
        return parse(omni.toString());
    }

    /**
     * Expands {@code 1.21.x} / {@code 1.21.*} / {@code 1.x} into an inclusive lower bound and
     * exclusive upper bound on the varying component. Fabric manifests write these constantly.
     */
    private static Optional<VersionPredicate> expandXWildcard(String text) {
        String normalized = text;
        if (normalized.endsWith(".*")) {
            normalized = normalized.substring(0, normalized.length() - 2) + ".x";
        }
        if (!normalized.endsWith(".x")
                && !normalized.endsWith(".X")
                && !normalized.equalsIgnoreCase("x")) {
            return Optional.empty();
        }
        if (normalized.equalsIgnoreCase("x") || normalized.equalsIgnoreCase(".x")) {
            return Optional.of(any());
        }
        String prefix = normalized.substring(0, normalized.length() - 2); // drop ".x"
        Optional<SemanticVersion> lower = parseVersionish(prefix);
        if (lower.isEmpty()) {
            return Optional.empty();
        }
        SemanticVersion from = lower.get();
        String[] parts = prefix.split("\\.", -1);
        SemanticVersion until;
        if (parts.length <= 1) {
            until = SemanticVersion.of(from.major() + 1, 0, 0);
        } else if (parts.length == 2) {
            until = SemanticVersion.of(from.major(), from.minor() + 1, 0);
        } else {
            until = SemanticVersion.of(from.major(), from.minor(), from.patch() + 1);
        }
        return Optional.of(new VersionPredicate(
                text,
                List.of(
                        new Term(Operator.GREATER_EQUAL, Optional.of(from), from.toString()),
                        new Term(Operator.LESS, Optional.of(until), until.toString()))));
    }
}
