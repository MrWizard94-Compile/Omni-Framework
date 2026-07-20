package org.omniframework.loader.transform;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.transform.mapping.ClassIndex;
import org.omniframework.loader.transform.mapping.MappingSet;
import org.omniframework.loader.transform.pass.RemapPass;

/**
 * Remapping is the pass whose failures are least visible: a jar that was remapped incorrectly
 * still verifies and still loads. So these tests load the transformed classes and call them.
 */
class RemapPassTest {

    private static PassContext context(MappingSet mappings, ClassIndex hierarchy) {
        return new PassContext("test.jar", "testmod", mappings, hierarchy,
                PassContext.GameSideTarget.CLIENT, new IssueCollector());
    }

    private static byte[] remap(byte[] classBytes, String name, MappingSet mappings,
            ClassIndex hierarchy) {
        Optional<TransformPass.Outcome> result = new RemapPass()
                .transform(new JarResource(name + ".class", classBytes),
                        context(mappings, hierarchy));
        return result.map(TransformPass.Outcome::content).orElse(classBytes);
    }

    @Test
    @DisplayName("a class is renamed and still loads and runs")
    void renamesAndStillRuns() throws Exception {
        MappingSet mappings = new MappingSet.Builder("intermediary", "official")
                .mapClass("game/class_1", "net/minecraft/Entity")
                .build();
        ClassIndex hierarchy = new ClassIndex();
        byte[] original = Bytecode.valueClass("game/class_1", 42);
        hierarchy.add(original);

        byte[] remapped = remap(original, "game/class_1", mappings, hierarchy);

        Object instance = new Bytecode.Loader()
                .with("net/minecraft/Entity", remapped)
                .load("net/minecraft/Entity")
                .getDeclaredConstructor()
                .newInstance();
        Method value = instance.getClass().getMethod("value");
        assertEquals(42, value.invoke(instance), "the remapped class must still behave the same");
    }

    @Test
    @DisplayName("a method declared on a superclass is remapped at a subclass call site")
    void remapsInheritedMemberAtSubclassCallSite() throws Exception {
        // The case a hierarchy-unaware remapper gets silently wrong. The mapping is recorded
        // against the declaring class; the call site names the subclass. Miss this and the jar
        // loads fine and throws NoSuchMethodError the first time the path executes.
        MappingSet mappings = new MappingSet.Builder("intermediary", "official")
                .mapMethod("game/Base", "method_1", "()I", "getValue")
                .build();

        byte[] base = Bytecode.baseClass("game/Base", "method_1", 99);
        byte[] sub = Bytecode.subclass("game/Sub", "game/Base");
        byte[] caller = Bytecode.callerClass("game/Caller", "game/Sub", "method_1");

        ClassIndex hierarchy = new ClassIndex();
        hierarchy.add(base);
        hierarchy.add(sub);
        hierarchy.add(caller);

        byte[] remappedBase = remap(base, "game/Base", mappings, hierarchy);
        byte[] remappedSub = remap(sub, "game/Sub", mappings, hierarchy);
        byte[] remappedCaller = remap(caller, "game/Caller", mappings, hierarchy);

        Bytecode.Loader loader = new Bytecode.Loader()
                .with("game/Base", remappedBase)
                .with("game/Sub", remappedSub)
                .with("game/Caller", remappedCaller);

        Object callerInstance = loader.load("game/Caller").getDeclaredConstructor().newInstance();
        // If the call site was not remapped it still names method_1, which no longer exists on
        // the renamed base class — this invocation is what proves the rename landed.
        assertEquals(99, callerInstance.getClass().getMethod("call").invoke(callerInstance));
        assertNotNull(loader.load("game/Base").getMethod("getValue"),
                "the declaration itself must carry the new name");
    }

    @Test
    @DisplayName("frames are recomputed without loading the classes being transformed")
    void recomputesFramesFromTheIndex() throws Exception {
        // COMPUTE_FRAMES must merge the two branch types. ASM's default answer loads both
        // classes with the JVM, which cannot work for classes that are mid-transformation —
        // it throws TypeNotPresentException. This is the test that the override works.
        MappingSet mappings = new MappingSet.Builder("intermediary", "official")
                .mapClass("game/Animal", "net/minecraft/Animal")
                .mapClass("game/Cat", "net/minecraft/Cat")
                .mapClass("game/Dog", "net/minecraft/Dog")
                .build();

        byte[] animal = Bytecode.baseClass("game/Animal", "noise", 1);
        byte[] cat = Bytecode.subclass("game/Cat", "game/Animal");
        byte[] dog = Bytecode.subclass("game/Dog", "game/Animal");
        byte[] chooser = Bytecode.branchingClass("game/Chooser", "game/Cat", "game/Dog");

        ClassIndex hierarchy = new ClassIndex();
        hierarchy.add(animal);
        hierarchy.add(cat);
        hierarchy.add(dog);
        hierarchy.add(chooser);

        byte[] remapped = assertDoesNotThrow(
                () -> remap(chooser, "game/Chooser", mappings, hierarchy),
                "frame computation must not require loading the classes being transformed");

        Bytecode.Loader loader = new Bytecode.Loader()
                .with("net/minecraft/Animal", remap(animal, "game/Animal", mappings, hierarchy))
                .with("net/minecraft/Cat", remap(cat, "game/Cat", mappings, hierarchy))
                .with("net/minecraft/Dog", remap(dog, "game/Dog", mappings, hierarchy))
                .with("game/Chooser", remapped);

        Object instance = loader.load("game/Chooser").getDeclaredConstructor().newInstance();
        Object picked = instance.getClass().getMethod("pick", boolean.class).invoke(instance, true);
        // A wrongly-merged frame produces VerifyError at load; reaching here means it verified.
        assertEquals("net.minecraft.Cat", picked.getClass().getName());
    }

    @Test
    @DisplayName("an identity mapping leaves the class untouched")
    void identityIsSkipped() {
        Optional<TransformPass.Outcome> result = new RemapPass().transform(
                new JarResource("game/Thing.class", Bytecode.valueClass("game/Thing", 1)),
                context(MappingSet.identity(), new ClassIndex()));

        assertTrue(result.isEmpty(),
                "a native jar is already official-named; rewriting it would be pure waste");
    }

    @Test
    @DisplayName("a class mentioning nothing mapped is left untouched")
    void unmappedClassIsSkipped() {
        MappingSet mappings = new MappingSet.Builder("intermediary", "official")
                .mapClass("game/Something", "net/minecraft/Else")
                .build();
        ClassIndex hierarchy = new ClassIndex();
        byte[] unrelated = Bytecode.valueClass("mod/Unrelated", 5);
        hierarchy.add(unrelated);

        Optional<TransformPass.Outcome> result = new RemapPass().transform(
                new JarResource("mod/Unrelated.class", unrelated), context(mappings, hierarchy));

        assertTrue(result.isEmpty(),
                "recording a no-op in the audit would bury the changes that matter");
    }

    @Test
    @DisplayName("constructors are never renamed")
    void constructorsAreLeftAlone() throws Exception {
        // <init> is named by the JVM specification. A mapping set containing a bogus entry for
        // it must not be able to produce a class that fails verification.
        MappingSet mappings = new MappingSet.Builder("intermediary", "official")
                .mapClass("game/Thing", "net/minecraft/Thing")
                .mapMethod("game/Thing", "<init>", "()V", "renamedConstructor")
                .build();
        ClassIndex hierarchy = new ClassIndex();
        byte[] original = Bytecode.valueClass("game/Thing", 3);
        hierarchy.add(original);

        byte[] remapped = remap(original, "game/Thing", mappings, hierarchy);

        Object instance = new Bytecode.Loader()
                .with("net/minecraft/Thing", remapped)
                .load("net/minecraft/Thing")
                .getDeclaredConstructor()
                .newInstance();
        assertEquals(3, instance.getClass().getMethod("value").invoke(instance));
    }

    @Test
    @DisplayName("the audit says what was renamed, not merely that something was")
    void auditIsSpecific() {
        MappingSet mappings = new MappingSet.Builder("intermediary", "official")
                .mapClass("game/class_1", "net/minecraft/Entity")
                .build();
        ClassIndex hierarchy = new ClassIndex();
        byte[] original = Bytecode.valueClass("game/class_1", 1);
        hierarchy.add(original);

        List<String> notes = new RemapPass()
                .transform(new JarResource("game/class_1.class", original),
                        context(mappings, hierarchy))
                .orElseThrow()
                .notes();

        assertTrue(notes.stream().anyMatch(note -> note.contains("game/class_1")
                        && note.contains("net/minecraft/Entity")),
                () -> "the audit should name both sides of the rename: " + notes);
    }
}
