package org.omniframework.loader.mixin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;
import org.omniframework.loader.core.Partition;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.ext.IExtensionRegistry;

/** The seam that applies mixins during classloading. */
class MixinClassTransformerTest {

    private static final byte[] ORIGINAL = "original".getBytes(StandardCharsets.UTF_8);
    private static final byte[] MODIFIED = "modified".getBytes(StandardCharsets.UTF_8);

    /** A transformer stub; only {@code transformClassBytes} is exercised by this seam. */
    private static class StubTransformer implements IMixinTransformer {
        byte[] result;
        RuntimeException failure;
        String lastName;

        @Override
        public byte[] transformClassBytes(String name, String transformedName, byte[] bytes) {
            lastName = name;
            if (failure != null) {
                throw failure;
            }
            return result == null ? bytes : result;
        }

        @Override public void audit(MixinEnvironment env) { }
        @Override public List<String> reload(String name, ClassNode node) { return List.of(); }
        @Override public boolean computeFramesForClass(
                MixinEnvironment env, String name, ClassNode node) { return false; }
        @Override public byte[] transformClass(MixinEnvironment env, String name, byte[] bytes) {
            return bytes;
        }
        @Override public boolean transformClass(
                MixinEnvironment env, String name, ClassNode node) { return false; }
        @Override public byte[] generateClass(MixinEnvironment env, String name) {
            return new byte[0];
        }
        @Override public boolean generateClass(
                MixinEnvironment env, String name, ClassNode node) { return false; }
        @Override public IExtensionRegistry getExtensions() { return null; }
    }

    @Test
    @DisplayName("a modified class is returned and counted")
    void appliesMixins() {
        StubTransformer stub = new StubTransformer();
        stub.result = MODIFIED;
        OmniClassTracker tracker = new OmniClassTracker();
        MixinClassTransformer bridge = new MixinClassTransformer(stub, tracker);

        byte[] out = bridge.transform("net.minecraft.Entity", null, ORIGINAL);

        assertArrayEquals(MODIFIED, out);
        assertEquals("net.minecraft.Entity", stub.lastName);
        assertEquals(1, bridge.transformedCount());
        assertEquals(0, bridge.unchangedCount());
    }

    @Test
    @DisplayName("an untouched class is passed through by identity, not copied")
    void passesThroughUnchanged() {
        // Mixin returns the input array when it had nothing to apply. Comparing by identity is
        // what it actually guarantees; hashing every class to learn the same thing would cost
        // real time on every single load.
        StubTransformer stub = new StubTransformer();
        MixinClassTransformer bridge = new MixinClassTransformer(stub, new OmniClassTracker());

        byte[] out = bridge.transform("net.minecraft.Untouched", null, ORIGINAL);

        assertSame(ORIGINAL, out);
        assertEquals(0, bridge.transformedCount());
        assertEquals(1, bridge.unchangedCount());
    }

    @Test
    @DisplayName("the loader's own classes are never handed to Mixin")
    void skipsRestrictedClasses() {
        // Handing these to Mixin means modifying the machinery mid-application — a no-op or a
        // crash depending on timing, never what an author intended.
        StubTransformer stub = new StubTransformer();
        stub.result = MODIFIED;
        MixinClassTransformer bridge = new MixinClassTransformer(stub, new OmniClassTracker());

        byte[] out = bridge.transform("org.omniframework.loader.core.Boot", null, ORIGINAL);

        assertSame(ORIGINAL, out);
        assertEquals(null, stub.lastName, "Mixin must not even be consulted for a restricted class");
    }

    @Test
    @DisplayName("a mixin failure fails the class load, naming the class")
    void failureIsFatalAndNamed() {
        // Falling back to the untransformed class would produce a mod that loads and then
        // misbehaves only where its mixin was meant to act — no error, no way to connect
        // symptom to cause.
        StubTransformer stub = new StubTransformer();
        stub.failure = new IllegalStateException("injection point not found");
        MixinClassTransformer bridge = new MixinClassTransformer(stub, new OmniClassTracker());

        MixinApplicationException thrown = assertThrows(MixinApplicationException.class,
                () -> bridge.transform("net.minecraft.Entity", (Partition) null, ORIGINAL));

        assertEquals("net.minecraft.Entity", thrown.className());
        assertTrue(thrown.getMessage().contains("net.minecraft.Entity"), thrown.getMessage());
        assertTrue(thrown.getCause() instanceof IllegalStateException);
    }

    @Test
    @DisplayName("a transformed class is recorded as loaded, so later mixins are refused")
    void recordsLoaded() {
        // Once the bytes are settled the class can no longer be a mixin target. Mixin needs to
        // know, or it reports success for a transformation that changed nothing.
        StubTransformer stub = new StubTransformer();
        stub.result = MODIFIED;
        OmniClassTracker tracker = new OmniClassTracker();
        MixinClassTransformer bridge = new MixinClassTransformer(stub, tracker);

        bridge.transform("net.minecraft.Entity", null, ORIGINAL);

        assertTrue(tracker.isClassLoaded("net.minecraft.Entity"));
        assertTrue(tracker.isClassLoaded("net/minecraft/Entity"));
    }

    @Test
    @DisplayName("a null return is treated as unchanged, not as empty bytecode")
    void nullMeansUnchanged() {
        // Defining a class from null (or zero-length) bytes is a ClassFormatError far from here.
        StubTransformer stub = new StubTransformer() {
            @Override
            public byte[] transformClassBytes(String name, String transformedName, byte[] bytes) {
                return null;
            }
        };
        MixinClassTransformer bridge = new MixinClassTransformer(stub, new OmniClassTracker());

        assertSame(ORIGINAL, bridge.transform("net.minecraft.Entity", null, ORIGINAL));
        assertEquals(1, bridge.unchangedCount());
    }

    @Test
    @DisplayName("the boot-log line reports the ratio actually applied")
    void describesCounts() {
        StubTransformer stub = new StubTransformer();
        stub.result = MODIFIED;
        MixinClassTransformer bridge = new MixinClassTransformer(stub, new OmniClassTracker());
        bridge.transform("net.minecraft.A", null, ORIGINAL);
        stub.result = null;
        bridge.transform("net.minecraft.B", null, ORIGINAL);

        assertEquals("1 of 2 classes had mixins applied", bridge.describe());
    }
}
