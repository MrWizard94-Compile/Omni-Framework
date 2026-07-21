package org.omniframework.loader.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A Semantic Versioning 2.0.0 version.
 *
 * <p>Mod versions are compared constantly — by the resolver, by dependency predicates, by
 * update checks — so the ordering rules are implemented exactly as the specification states
 * them rather than approximated by string comparison. The two rules that string comparison
 * always gets wrong, and which are tested explicitly:
 *
 * <ul>
 *   <li>Numeric identifiers compare numerically: {@code 1.0.10 > 1.0.9}.
 *   <li>A pre-release version is <em>lower</em> than its release: {@code 1.0.0-rc.1 < 1.0.0}.
 * </ul>
 *
 * <p>Build metadata ({@code +sha.abc}) is retained for display but ignored in comparisons, per
 * the specification.
 *
 * <p>Mod ecosystems also ship versions that are not valid semver at all. Those are not forced
 * through this type: {@link ModVersion} wraps both cases so the model can hold what a manifest
 * actually said.
 */
public record SemanticVersion(
        int major,
        int minor,
        int patch,
        List<String> preRelease,
        Optional<String> buildMetadata)
        implements Comparable<SemanticVersion> {

    /** Canonical constructor; validates and defensively copies. */
    public SemanticVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("version numbers must not be negative");
        }
        preRelease = List.copyOf(Objects.requireNonNull(preRelease, "preRelease"));
        Objects.requireNonNull(buildMetadata, "buildMetadata");
    }

    /** A plain {@code major.minor.patch} version. */
    public static SemanticVersion of(int major, int minor, int patch) {
        return new SemanticVersion(major, minor, patch, List.of(), Optional.empty());
    }

    /**
     * Parses a semver string, or returns empty when the text is not valid semver.
     *
     * <p>Returning empty rather than throwing is deliberate: "not semver" is an ordinary,
     * expected condition when importing from other ecosystems, not an exceptional one.
     */
    public static Optional<SemanticVersion> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String rest = text;
        Optional<String> build = Optional.empty();
        int plus = rest.indexOf('+');
        if (plus >= 0) {
            String metadata = rest.substring(plus + 1);
            if (!isValidDotSeparatedIdentifiers(metadata, true)) {
                return Optional.empty();
            }
            build = Optional.of(metadata);
            rest = rest.substring(0, plus);
        }
        List<String> pre = List.of();
        int dash = rest.indexOf('-');
        if (dash >= 0) {
            String preText = rest.substring(dash + 1);
            if (!isValidDotSeparatedIdentifiers(preText, false)) {
                return Optional.empty();
            }
            pre = List.of(preText.split("\\.", -1));
            rest = rest.substring(0, dash);
        }
        String[] core = rest.split("\\.", -1);
        // Accept 1–N numeric components, using the first three for ordering:
        // "21" → 21.0.0, "1.21" → 1.21.0, "0.12.15.4" → 0.12.15 (Fabric often
        // ships 4-part versions; ignoring the 4th is better than non-semver
        // which made ">=0.10.3" fail against "0.12.15.4+1.21").
        if (core.length == 0) {
            return Optional.empty();
        }
        int limit = Math.min(core.length, 3);
        int[] numbers = new int[3];
        for (int i = 0; i < limit; i++) {
            if (!isNumericIdentifier(core[i])) {
                return Optional.empty();
            }
            try {
                numbers[i] = Integer.parseInt(core[i]);
            } catch (NumberFormatException overflow) {
                return Optional.empty();
            }
        }
        // Extra trailing components must still be numeric (e.g. 0.12.15.4), else reject.
        for (int i = 3; i < core.length; i++) {
            if (!isNumericIdentifier(core[i])) {
                return Optional.empty();
            }
        }
        return Optional.of(new SemanticVersion(numbers[0], numbers[1], numbers[2], pre, build));
    }

    /** Whether this is a pre-release (and therefore orders below its release). */
    public boolean isPreRelease() {
        return !preRelease.isEmpty();
    }

    @Override
    public int compareTo(SemanticVersion other) {
        int result = Integer.compare(major, other.major);
        if (result != 0) {
            return result;
        }
        result = Integer.compare(minor, other.minor);
        if (result != 0) {
            return result;
        }
        result = Integer.compare(patch, other.patch);
        if (result != 0) {
            return result;
        }
        return comparePreRelease(preRelease, other.preRelease);
    }

    /**
     * Whether two versions are the same release, ignoring build metadata — the equality the
     * specification defines for <em>precedence</em>, as distinct from record equality.
     */
    public boolean samePrecedence(SemanticVersion other) {
        return compareTo(other) == 0;
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder(16);
        text.append(major).append('.').append(minor).append('.').append(patch);
        if (!preRelease.isEmpty()) {
            text.append('-').append(String.join(".", preRelease));
        }
        buildMetadata.ifPresent(metadata -> text.append('+').append(metadata));
        return text.toString();
    }

    // ------------------------------------------------------------- ordering

    private static int comparePreRelease(List<String> left, List<String> right) {
        // "A pre-release version has lower precedence than a normal version."
        if (left.isEmpty() && right.isEmpty()) {
            return 0;
        }
        if (left.isEmpty()) {
            return 1;
        }
        if (right.isEmpty()) {
            return -1;
        }
        int shared = Math.min(left.size(), right.size());
        for (int i = 0; i < shared; i++) {
            int result = compareIdentifier(left.get(i), right.get(i));
            if (result != 0) {
                return result;
            }
        }
        // "A larger set of pre-release fields has a higher precedence."
        return Integer.compare(left.size(), right.size());
    }

    private static int compareIdentifier(String left, String right) {
        boolean leftNumeric = isNumericIdentifier(left);
        boolean rightNumeric = isNumericIdentifier(right);
        if (leftNumeric && rightNumeric) {
            // Compare as numbers, not text, and without overflowing on absurd input.
            return new java.math.BigInteger(left).compareTo(new java.math.BigInteger(right));
        }
        if (leftNumeric) {
            return -1; // "Numeric identifiers always have lower precedence."
        }
        if (rightNumeric) {
            return 1;
        }
        return left.compareTo(right);
    }

    /** Natural order, exposed for sorting collections of versions. */
    public static Comparator<SemanticVersion> ascending() {
        return Comparator.naturalOrder();
    }

    // ----------------------------------------------------------- validation

    private static boolean isNumericIdentifier(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        // "Numeric identifiers MUST NOT include leading zeroes."
        return text.length() == 1 || text.charAt(0) != '0';
    }

    private static boolean isValidDotSeparatedIdentifiers(String text, boolean isBuildMetadata) {
        if (text.isEmpty()) {
            return false;
        }
        List<String> parts = new ArrayList<>(List.of(text.split("\\.", -1)));
        for (String part : parts) {
            if (part.isEmpty()) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                boolean allowed = Character.isLetterOrDigit(c) && c < 128 || c == '-';
                if (!allowed) {
                    return false;
                }
            }
            // Build metadata may have leading zeros; pre-release numerics may not.
            if (!isBuildMetadata && isAllDigits(part) && part.length() > 1 && part.charAt(0) == '0') {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllDigits(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return !text.isEmpty();
    }
}
