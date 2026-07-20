package org.omniframework.loader.mixin;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.omniframework.loader.model.ModMetadata;
import org.spongepowered.asm.mixin.MixinEnvironment.CompatibilityLevel;

/**
 * Decides which Mixin compatibility level a mod's configs run under (LDR-8).
 *
 * <h2>The problem this exists to solve</h2>
 *
 * <p>Mixin's compatibility level controls which Java language features it accepts in a mixin
 * class — and, more importantly, how it treats bytecode it does not fully understand. Raising the
 * global level to suit a new mod can change how an <em>old</em> mod's mixins apply, which is how
 * a Mixin upgrade silently breaks mods that were working. ADR-0009 anticipates this: version
 * bumps require a compat-level impact assessment, and per-config levels preserve old behaviour.
 *
 * <h2>Why the ceiling and fallback are parameters, not globals</h2>
 *
 * <p>{@code CompatibilityLevel.MAX_SUPPORTED} and {@code CompatibilityLevel.DEFAULT} are
 * <em>mutable static fields</em>, and on a stock 0.8.7 runtime they read {@code JAVA_13} and
 * {@code JAVA_6}. They are not statements of fact about the platform; they are defaults that the
 * host is expected to raise, which is exactly what {@code IMixinService.getMaxCompatibilityLevel}
 * exists for.
 *
 * <p>Reading them as though they were authoritative is a live trap. On a Java 21 game every mod
 * would be clamped to Java 13 semantics, and anything with no declaration would fall to Java 6 —
 * silently, with no error, because a lower level is "valid". So this class never consults them
 * implicitly: callers pass the ceiling they actually support and the fallback they actually want,
 * and {@link #ofRuntime()} exists only for the caller that genuinely wants whatever the runtime
 * currently holds.
 *
 * <h2>Deriving a level</h2>
 *
 * <ol>
 *   <li>An explicit {@code ext."omni:mixin-compat"} value — an author who states a level means
 *       it, and inferring over the top would be presumptuous.
 *   <li>The class-file version of the mod's own bytecode, the most honest evidence available of
 *       what it was compiled for.
 *   <li>The caller's fallback.
 * </ol>
 *
 * <p>Inference rounds <em>down</em> to the nearest known level. Rounding up would hand a jar
 * semantics newer than it was compiled for, which misbehaves silently; rounding down at worst
 * costs a language feature and says so in an error that names the level.
 */
public record CompatibilityLevels(CompatibilityLevel ceiling, CompatibilityLevel fallback) {

    /** Manifest key an author can use to state a level explicitly. */
    public static final String EXTENSION_KEY = "omni:mixin-compat";

    /** Canonical constructor. */
    public CompatibilityLevels {
        Objects.requireNonNull(ceiling, "ceiling");
        Objects.requireNonNull(fallback, "fallback");
        if (fallback.isAtLeast(ceiling) && fallback != ceiling) {
            throw new IllegalArgumentException(
                    "fallback " + fallback + " is above the ceiling " + ceiling);
        }
    }

    /**
     * A policy matching the game's Java version — what the loader actually wants.
     *
     * <p>Both the ceiling and the fallback come from the game: a mod with nothing declared and no
     * readable bytecode is overwhelmingly likely to have been built for the same Java the game
     * runs on, and defaulting lower would reject mixin classes that are perfectly ordinary.
     *
     * @param classFileMajor the game's class-file major version (65 for Java 21)
     */
    public static CompatibilityLevels forGame(int classFileMajor) {
        CompatibilityLevel level = nearestAtOrBelow(classFileMajor)
                .orElse(CompatibilityLevel.JAVA_8);
        return new CompatibilityLevels(level, level);
    }

    /**
     * A policy taking whatever the Mixin runtime currently holds.
     *
     * <p>Only meaningful <em>after</em> the service has raised the globals. Before that it
     * reports {@code JAVA_13}/{@code JAVA_6} — see the class note.
     */
    public static CompatibilityLevels ofRuntime() {
        CompatibilityLevel max = CompatibilityLevel.MAX_SUPPORTED;
        CompatibilityLevel def = CompatibilityLevel.DEFAULT;
        return new CompatibilityLevels(max, def.isAtLeast(max) ? max : def);
    }

    /**
     * The level for a mod.
     *
     * @param metadata the mod's canonical metadata
     * @param classFileMajor major class-file version of the mod's own classes, or 0 if unknown
     */
    public CompatibilityLevel forMod(ModMetadata metadata, int classFileMajor) {
        Objects.requireNonNull(metadata, "metadata");
        Optional<CompatibilityLevel> declared = declaredLevel(metadata);
        if (declared.isPresent()) {
            return clamp(declared.get());
        }
        if (classFileMajor > 0) {
            return nearestAtOrBelow(classFileMajor).map(this::clamp).orElse(fallback);
        }
        return fallback;
    }

    /** Holds a level at or below this policy's ceiling. */
    public CompatibilityLevel clamp(CompatibilityLevel level) {
        Objects.requireNonNull(level, "level");
        return level == ceiling || !level.isAtLeast(ceiling) ? level : ceiling;
    }

    /** A sentence for diagnostics explaining where a mod's level came from. */
    public String explain(ModMetadata metadata, int classFileMajor) {
        Objects.requireNonNull(metadata, "metadata");
        Optional<CompatibilityLevel> declared = declaredLevel(metadata);
        if (declared.isPresent()) {
            CompatibilityLevel resolved = clamp(declared.get());
            String suffix = resolved == declared.get()
                    ? ""
                    : " (clamped from " + declared.get() + ", above the ceiling " + ceiling + ")";
            return metadata.id() + " runs at " + resolved + ", declared via '"
                    + EXTENSION_KEY + "'" + suffix;
        }
        if (classFileMajor > 0) {
            return metadata.id() + " runs at " + forMod(metadata, classFileMajor)
                    + ", inferred from class-file version " + classFileMajor;
        }
        return metadata.id() + " runs at " + fallback
                + ", this policy's fallback (nothing declared, bytecode not inspected)";
    }

    /** The level a mod declared in its manifest, if it declared a parseable one. */
    public static Optional<CompatibilityLevel> declaredLevel(ModMetadata metadata) {
        return Objects.requireNonNull(metadata, "metadata")
                .extension(EXTENSION_KEY)
                .map(String::valueOf)
                .flatMap(CompatibilityLevels::parse);
    }

    /**
     * Parses a declared level.
     *
     * <p>Accepts what authors actually write: {@code JAVA_17}, {@code java17}, or bare
     * {@code 17}. Rejecting a plausible spelling would push an author toward deleting the
     * declaration, which loses the information rather than tidying it.
     */
    public static Optional<CompatibilityLevel> parse(String declared) {
        if (declared == null || declared.isBlank()) {
            return Optional.empty();
        }
        String text = declared.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (text.startsWith("JAVA_")) {
            text = text.substring("JAVA_".length());
        } else if (text.startsWith("JAVA")) {
            text = text.substring("JAVA".length());
        }
        String name = "JAVA_" + text;
        for (CompatibilityLevel level : CompatibilityLevel.values()) {
            if (level.name().equals(name)) {
                return Optional.of(level);
            }
        }
        return Optional.empty();
    }

    /**
     * The highest declared level whose class-file version is at or below {@code major}.
     *
     * <p>Matched against Mixin's own {@code getClassMajorVersion()} rather than the well-known
     * {@code major = java + 44} arithmetic. The arithmetic is right today, but the enum is the
     * runtime's own statement of the mapping, and reading it from the thing that will enforce it
     * removes a place for the two to drift.
     */
    public static Optional<CompatibilityLevel> nearestAtOrBelow(int major) {
        CompatibilityLevel best = null;
        for (CompatibilityLevel level : CompatibilityLevel.values()) {
            if (level.getClassMajorVersion() == major) {
                return Optional.of(level);
            }
            if (level.getClassMajorVersion() < major
                    && (best == null
                            || level.getClassMajorVersion() > best.getClassMajorVersion())) {
                best = level;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Whether {@code level} is at least {@code required}. */
    public static boolean atLeast(CompatibilityLevel level, CompatibilityLevel required) {
        return Objects.requireNonNull(level, "level").isAtLeast(required);
    }
}
