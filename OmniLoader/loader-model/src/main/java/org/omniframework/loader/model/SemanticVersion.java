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
        /** Fourth numeric component used by Fabric mods (e.g. owo {@code 0.12.15.5}). */
        int revision,
        List<String> preRelease,
        Optional<String> buildMetadata)
        implements Comparable<SemanticVersion> {

    /** Canonical constructor; validates and defensively copies. */
    public SemanticVersion {
        if (major < 0 || minor < 0 || patch < 0 || revision < 0) {
            throw new IllegalArgumentException("version numbers must not be negative");
        }
        preRelease = List.copyOf(Objects.requireNonNull(preRelease, "preRelease"));
        Objects.requireNonNull(buildMetadata, "buildMetadata");
    }

    /** A plain {@code major.minor.patch} version (revision 0). */
    public static SemanticVersion of(int major, int minor, int patch) {
        return new SemanticVersion(major, minor, patch, 0, List.of(), Optional.empty());
    }

    /** A four-part {@code major.minor.patch.revision} version. */
    public static SemanticVersion of(int major, int minor, int patch, int revision) {
        return new SemanticVersion(major, minor, patch, revision, List.of(), Optional.empty());
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
        // Hybrid MC+mod versions: "1.21.1-1.2.10", "1.21-1.3" → order by the mod half.
        Optional<String> hybridMod = extractHybridModVersion(text);
        if (hybridMod.isPresent()) {
            return parse(hybridMod.get());
        }
        String rest = text;
        Optional<String> build = Optional.empty();
        int plus = rest.indexOf('+');
        if (plus >= 0) {
            // Semver allows one '+'; real mods ship multi-plus tags like
            // "0.7.6+1.21+neoforge". Normalize extra '+' to '.' so ordering still works
            // (build metadata is ignored in compare, but parse must not return empty).
            String metadata = rest.substring(plus + 1).replace('+', '.');
            if (!isValidDotSeparatedIdentifiers(metadata, true)) {
                // Last resort: keep opaque metadata by sanitizing illegal chars.
                metadata = sanitizeBuildMetadata(metadata);
                if (metadata.isEmpty()) {
                    return Optional.empty();
                }
            }
            build = Optional.of(metadata);
            rest = rest.substring(0, plus);
        }
        List<String> pre = List.of();
        int dash = rest.indexOf('-');
        // dash must follow core numbers ("1.0.0-rc.1"); a leading '-' is not semver.
        if (dash > 0) {
            String preText = rest.substring(dash + 1);
            if (!isValidDotSeparatedIdentifiers(preText, false)) {
                return Optional.empty();
            }
            pre = List.of(preText.split("\\.", -1));
            rest = rest.substring(0, dash);
        } else if (dash == 0) {
            return Optional.empty();
        }
        String[] core = rest.split("\\.", -1);
        // Accept 1–N components. First three are major.minor.patch; a fourth numeric
        // component is revision (owo 0.12.15.5). Further numeric segments are ignored for
        // ordering but letter suffixes still fold into build metadata.
        // Letter suffixes ("1.11.2b", "0.5.1.e", "1.21z") are common in Create/NeoForge —
        // strip them into build metadata so ranges work.
        if (core.length == 0) {
            return Optional.empty();
        }
        int limit = Math.min(core.length, 4);
        int[] numbers = new int[4];
        StringBuilder letterSuffix = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            if (core[i].isEmpty()) {
                // Trailing/empty segment from "21.1." → treat as 0
                numbers[i] = 0;
                continue;
            }
            Optional<int[]> stripped = stripTrailingLetters(core[i]);
            if (stripped.isEmpty()) {
                return Optional.empty();
            }
            numbers[i] = stripped.get()[0];
            String letters = trailingLetters(core[i]);
            if (!letters.isEmpty()) {
                if (!letterSuffix.isEmpty()) {
                    letterSuffix.append('.');
                }
                letterSuffix.append(letters);
            }
        }
        // Extra trailing components beyond revision: letter-stripped only.
        for (int i = 4; i < core.length; i++) {
            if (core[i].isEmpty()) {
                continue;
            }
            Optional<int[]> stripped = stripTrailingLetters(core[i]);
            if (stripped.isEmpty()) {
                return Optional.empty();
            }
            String letters = trailingLetters(core[i]);
            if (!letters.isEmpty()) {
                if (!letterSuffix.isEmpty()) {
                    letterSuffix.append('.');
                }
                letterSuffix.append(letters);
            }
        }
        if (!letterSuffix.isEmpty()) {
            String extra = letterSuffix.toString();
            build = build.map(existing -> existing + "." + extra).or(() -> Optional.of(extra));
        }
        // Loader channel suffixes (2.0-NEOFORGE-1.21.1) are not semver pre-releases — they are
        // distribution tags. Keep them in build metadata so [2.0,) matches 2.0-NEOFORGE-….
        if (!pre.isEmpty() && isLoaderChannelPreRelease(pre)) {
            String channel = String.join(".", pre);
            build = build.map(existing -> channel + "." + existing).or(() -> Optional.of(channel));
            pre = List.of();
        }
        return Optional.of(new SemanticVersion(
                numbers[0], numbers[1], numbers[2], numbers[3], pre, build));
    }

    /**
     * Whether pre-release tokens look like a loader/distribution channel rather than a real
     * pre-release ({@code beta}, {@code rc}, …).
     */
    private static boolean isLoaderChannelPreRelease(List<String> pre) {
        if (pre.isEmpty()) {
            return false;
        }
        String first = pre.get(0).toLowerCase(java.util.Locale.ROOT);
        // "NEOFORGE-1" after split on '.' from "NEOFORGE-1.21.1", or plain "neoforge"
        String head = first.contains("-") ? first.substring(0, first.indexOf('-')) : first;
        return head.equals("neoforge")
                || head.equals("forge")
                || head.equals("fabric")
                || head.equals("quilt")
                || head.equals("bukkit")
                || head.equals("spigot")
                || head.equals("paper");
    }

    /**
     * Detects hybrid {@code <minecraft>-<modversion>} pins used by Farmer's Delight addons
     * and similar: {@code 1.21.1-1.2.10}, {@code 1.21-1.3}. Returns the mod-version half.
     */
    static Optional<String> extractHybridModVersion(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String raw = text.trim();
        // Strip build metadata for pattern match; re-attach later if present.
        String build = "";
        int plus = raw.indexOf('+');
        if (plus >= 0) {
            build = raw.substring(plus);
            raw = raw.substring(0, plus);
        }
        // MC half: 1.16–1.2x with optional patch; mod half starts with a digit.
        // Avoid treating 2.3.9-NEOFORGE-… or 1.0.0-rc.1 as hybrid.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "^(1\\.1[6-9]|1\\.2[0-9])(?:\\.\\d+)?-(\\d.*)$")
                .matcher(raw);
        if (!m.matches()) {
            return Optional.empty();
        }
        String modHalf = m.group(2);
        // Reject if mod half is a pure pre-release word (rc, beta, alpha, …).
        String first = modHalf.split("[.\\-]", 2)[0].toLowerCase(java.util.Locale.ROOT);
        if (first.equals("rc")
                || first.equals("beta")
                || first.equals("alpha")
                || first.equals("snapshot")
                || first.equals("pre")
                || first.equals("preview")) {
            return Optional.empty();
        }
        // Reject loader-channel only (handled separately as build metadata).
        if (first.equals("neoforge")
                || first.equals("forge")
                || first.equals("fabric")
                || first.equals("quilt")) {
            return Optional.empty();
        }
        return Optional.of(modHalf + build);
    }

    /**
     * Parses a core component that may end with letters ({@code 2b}, {@code 1e}).
     *
     * @return empty if not a number with optional letter suffix; otherwise {@code {number}}
     */
    private static Optional<int[]> stripTrailingLetters(String component) {
        int end = 0;
        while (end < component.length() && Character.isDigit(component.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return Optional.empty();
        }
        for (int i = end; i < component.length(); i++) {
            char c = component.charAt(i);
            if (!Character.isLetter(c) || c >= 128) {
                return Optional.empty();
            }
        }
        try {
            int n = Integer.parseInt(component.substring(0, end));
            if (end > 1 && component.charAt(0) == '0') {
                // Leading zeros not allowed on numeric identifiers (semver).
                return Optional.empty();
            }
            return Optional.of(new int[] {n});
        } catch (NumberFormatException overflow) {
            return Optional.empty();
        }
    }

    private static String trailingLetters(String component) {
        int end = 0;
        while (end < component.length() && Character.isDigit(component.charAt(end))) {
            end++;
        }
        return end < component.length() ? component.substring(end) : "";
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
        result = Integer.compare(revision, other.revision);
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
        if (revision != 0) {
            text.append('.').append(revision);
        }
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

    /** Keeps alphanumerics, dots, and hyphens so multi-ecosystem build tags still parse. */
    private static String sanitizeBuildMetadata(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((Character.isLetterOrDigit(c) && c < 128) || c == '-' || c == '.') {
                out.append(c);
            } else if (c == '+' || c == '_' || c == ' ') {
                out.append('.');
            }
        }
        // Collapse empty segments from consecutive separators.
        String collapsed = out.toString().replaceAll("\\.+", ".");
        if (collapsed.startsWith(".")) {
            collapsed = collapsed.substring(1);
        }
        if (collapsed.endsWith(".")) {
            collapsed = collapsed.substring(0, collapsed.length() - 1);
        }
        return collapsed;
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
