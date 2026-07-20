package org.omniframework.loader.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.omniframework.loader.model.ModMetadata;
import org.omniframework.loader.model.ModVersion;
import org.spongepowered.asm.mixin.MixinEnvironment.CompatibilityLevel;

/**
 * Per-mod compatibility levels (LDR-8).
 *
 * <p>The asymmetry these protect: too low produces a clear error naming the level, too high
 * changes bytecode semantics with no error at all.
 */
class CompatibilityLevelsTest {

    /** Java 21 — Minecraft 1.21.1's runtime (TGT-1). */
    private static final int JAVA_21_MAJOR = 65;

    private static CompatibilityLevels policy() {
        return CompatibilityLevels.forGame(JAVA_21_MAJOR);
    }

    private static ModMetadata mod(String id) {
        return ModMetadata.builder(id, ModVersion.parse("1.0.0")).name(id).build();
    }

    private static ModMetadata modDeclaring(String id, String level) {
        return ModMetadata.builder(id, ModVersion.parse("1.0.0"))
                .name(id)
                .extension(CompatibilityLevels.EXTENSION_KEY, level)
                .build();
    }

    @Test
    @DisplayName("the game's Java version sets both ceiling and fallback")
    void gamePolicy() {
        // The trap this guards: Mixin's own MAX_SUPPORTED/DEFAULT read JAVA_13/JAVA_6 on a
        // stock 0.8.7 runtime. Trusting those on a Java 21 game silently clamps every mod to
        // Java 13 semantics, and anything undeclared to Java 6.
        CompatibilityLevels resolved = policy();

        assertEquals(CompatibilityLevel.JAVA_21, resolved.ceiling());
        assertEquals(CompatibilityLevel.JAVA_21, resolved.fallback());
        assertFalse(resolved.ceiling().equals(CompatibilityLevel.MAX_SUPPORTED),
                "this test is pointless if the runtime default already equals the game's level");
    }

    @Test
    @DisplayName("an explicit declaration wins over the class-file version")
    void declarationWins() {
        // The only channel an author has to pin behaviour across a Mixin bump.
        assertEquals(CompatibilityLevel.JAVA_8,
                policy().forMod(modDeclaring("declares", "JAVA_8"), JAVA_21_MAJOR));
    }

    @Test
    @DisplayName("author-friendly spellings all parse")
    void parsesSpellings() {
        assertEquals(Optional.of(CompatibilityLevel.JAVA_17), CompatibilityLevels.parse("JAVA_17"));
        assertEquals(Optional.of(CompatibilityLevel.JAVA_17), CompatibilityLevels.parse("java17"));
        assertEquals(Optional.of(CompatibilityLevel.JAVA_17), CompatibilityLevels.parse("17"));
        assertEquals(Optional.of(CompatibilityLevel.JAVA_17), CompatibilityLevels.parse(" Java-17 "));
    }

    @Test
    @DisplayName("nonsense does not resolve to some level anyway")
    void rejectsNonsense() {
        assertTrue(CompatibilityLevels.parse("").isEmpty());
        assertTrue(CompatibilityLevels.parse(null).isEmpty());
        assertTrue(CompatibilityLevels.parse("banana").isEmpty());
        assertTrue(CompatibilityLevels.parse("JAVA_999").isEmpty());
    }

    @Test
    @DisplayName("with nothing declared, the class-file version decides")
    void inferredFromBytecode() {
        assertEquals(CompatibilityLevel.JAVA_17, policy().forMod(mod("seventeen"), 61));
        assertEquals(CompatibilityLevel.JAVA_8, policy().forMod(mod("eight"), 52));
        assertEquals(CompatibilityLevel.JAVA_21, policy().forMod(mod("twentyone"), 65));
    }

    @Test
    @DisplayName("a class file between known levels rounds down, never up")
    void inferenceRoundsDown() {
        // Rounding up hands a jar semantics newer than it was compiled for — the silent
        // failure. Rounding down at worst costs a language feature and says so.
        CompatibilityLevel resolved = CompatibilityLevels.nearestAtOrBelow(62).orElseThrow();
        assertTrue(resolved.getClassMajorVersion() <= 62,
                () -> "rounded up to " + resolved);
        assertEquals(CompatibilityLevel.JAVA_18, resolved);
    }

    @Test
    @DisplayName("with nothing to go on, the policy's fallback applies")
    void fallsBack() {
        assertEquals(CompatibilityLevel.JAVA_21, policy().forMod(mod("silent"), 0));
    }

    @Test
    @DisplayName("a level above the ceiling is clamped, not rejected")
    void clampsAboveCeiling() {
        // A pack moved to an older loader is real; refusing to load is worse than running at
        // the highest level actually available.
        CompatibilityLevels older = new CompatibilityLevels(
                CompatibilityLevel.JAVA_17, CompatibilityLevel.JAVA_17);

        assertEquals(CompatibilityLevel.JAVA_17, older.clamp(CompatibilityLevel.JAVA_21));
        assertEquals(CompatibilityLevel.JAVA_17, older.forMod(modDeclaring("a", "JAVA_21"), 0));
        assertEquals(CompatibilityLevel.JAVA_8, older.clamp(CompatibilityLevel.JAVA_8),
                "a level below the ceiling is untouched");
    }

    @Test
    @DisplayName("a policy whose fallback exceeds its ceiling is refused at construction")
    void incoherentPolicyRejected() {
        // Silently clamping here would hide a misconfiguration that then looks like a mod bug.
        assertThrows(IllegalArgumentException.class, () -> new CompatibilityLevels(
                CompatibilityLevel.JAVA_8, CompatibilityLevel.JAVA_21));
    }

    @Test
    @DisplayName("the explanation names where the level came from")
    void explains() {
        // "Why is my mixin running at Java 8" is a real support question with three answers.
        assertTrue(policy().explain(modDeclaring("a", "JAVA_8"), 0)
                .contains(CompatibilityLevels.EXTENSION_KEY));
        assertTrue(policy().explain(mod("b"), 61).contains("class-file version 61"));
        assertTrue(policy().explain(mod("c"), 0).contains("fallback"));

        CompatibilityLevels older = new CompatibilityLevels(
                CompatibilityLevel.JAVA_17, CompatibilityLevel.JAVA_17);
        assertTrue(older.explain(modDeclaring("d", "JAVA_21"), 0).contains("clamped"),
                "a clamped level must say so, or the author cannot tell why it differs");
    }
}
