package org.omniframework.loader.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment.CompatibilityLevel;
import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IMixinService;
import org.spongepowered.asm.service.IMixinServiceBootstrap;

/** The service SPI implementation (LDR-8): discovery, wiring, and the bytecode contract. */
class MixinServiceOmniTest {

    @AfterEach
    void clearWiring() {
        MixinServiceOmni.reset();
    }

    private static MixinServiceOmni.Wiring wiring() {
        return new MixinServiceOmni.Wiring(
                new OmniClassProvider(MixinServiceOmniTest.class.getClassLoader(), List.of()),
                OmniBytecodeProvider.ofClassLoader(MixinServiceOmniTest.class.getClassLoader()),
                new OmniClassTracker(),
                new OmniAuditTrail(),
                CompatibilityLevels.forGame(65));
    }

    // ------------------------------------------------------------- discovery

    @Test
    @DisplayName("Mixin's ServiceLoader discovery finds all three registrations")
    void serviceLoaderDiscovery() {
        // The META-INF/services files are the only thing making this service exist as far as
        // Mixin is concerned. A typo in a filename or class name produces no compile error and
        // no test failure anywhere else — Mixin just silently falls back and reports no service.
        assertTrue(ServiceLoader.load(IMixinService.class).stream()
                        .anyMatch(p -> p.type() == MixinServiceOmni.class),
                "IMixinService registration is missing or misspelled");
        assertTrue(ServiceLoader.load(IMixinServiceBootstrap.class).stream()
                        .anyMatch(p -> p.type() == MixinServiceOmniBootstrap.class),
                "IMixinServiceBootstrap registration is missing or misspelled");
        assertTrue(ServiceLoader.load(IGlobalPropertyService.class).stream()
                        .anyMatch(p -> p.type() == OmniGlobalPropertyService.class),
                "IGlobalPropertyService registration is missing or misspelled");
    }

    @Test
    @DisplayName("the bootstrap names the service class it will load")
    void bootstrapNamesService() {
        // Mixin loads the service by this name; a mismatch fails at runtime, not compile time.
        IMixinServiceBootstrap bootstrap = new MixinServiceOmniBootstrap();
        assertEquals(MixinServiceOmni.class.getName(), bootstrap.getServiceClassName());
        assertEquals(MixinServiceOmni.NAME, bootstrap.getName());
    }

    // --------------------------------------------------------------- wiring

    @Test
    @DisplayName("the service is invalid until wired, and valid after")
    void validityTracksWiring() {
        // isValid() is how Mixin is told to refuse rather than proceed against a half-built
        // host. Reporting true too early means failures land deep inside Mixin instead.
        MixinServiceOmni service = new MixinServiceOmni();
        assertFalse(service.isValid(), "a service with no collaborators must not claim validity");

        MixinServiceOmni.install(wiring());
        assertTrue(service.isValid());
        assertEquals(MixinServiceOmni.NAME, service.getName());
    }

    @Test
    @DisplayName("using the service before install fails loudly, naming the cause")
    void unwiredUseFailsLoudly() {
        // The alternative is returning a null collaborator that Mixin dereferences several
        // frames later, producing an NPE that names none of this.
        MixinServiceOmni service = new MixinServiceOmni();
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, service::getClassProvider);
        assertTrue(thrown.getMessage().contains("install()"), thrown.getMessage());
    }

    @Test
    @DisplayName("the compatibility ceiling is raised above Mixin's stock default")
    void raisesCompatibilityCeiling() {
        // The whole point of implementing getMaxCompatibilityLevel. Stock 0.8.7 reports
        // JAVA_13; leaving it there clamps every mod on a Java 21 game to Java 13 semantics
        // with no error at all.
        MixinServiceOmni service = new MixinServiceOmni();
        MixinServiceOmni.install(wiring());

        assertEquals(CompatibilityLevel.JAVA_21, service.getMaxCompatibilityLevel());
        assertTrue(service.getMaxCompatibilityLevel().isAtLeast(CompatibilityLevel.JAVA_17));
        assertEquals(CompatibilityLevel.JAVA_8, service.getMinCompatibilityLevel());
    }

    @Test
    @DisplayName("before wiring, the ceiling query answers instead of throwing")
    void ceilingSafeBeforeWiring() {
        // Mixin queries this during its own static init, which can run before the loader gets
        // a chance to install. Throwing there would abort the boot for a question that has a
        // safe answer.
        assertNotNull(new MixinServiceOmni().getMaxCompatibilityLevel());
    }

    @Test
    @DisplayName("no transformer provider is claimed, rather than an empty one")
    void noTransformerProvider() {
        // All AOT work happens in loader-transform before Mixin sees a class. An empty provider
        // would imply a chain exists but is currently empty, which is a different claim.
        MixinServiceOmni service = new MixinServiceOmni();
        MixinServiceOmni.install(wiring());
        assertNull(service.getTransformerProvider());
    }

    @Test
    @DisplayName("the service reports a primary container and no platform agents")
    void containerAndAgents() {
        MixinServiceOmni service = new MixinServiceOmni();
        MixinServiceOmni.install(wiring());

        assertNotNull(service.getPrimaryContainer());
        assertTrue(service.getPlatformAgents().isEmpty());
    }

    // ------------------------------------------------------------- bytecode

    @Test
    @DisplayName("transformed bytes are served in preference to the original")
    void prefersTransformedBytes() throws Exception {
        // The subtle heart of the integration. Mixin resolves injection points against member
        // names, so it must see POST-pipeline bytes. Showing it the original jar would make it
        // report "target method not found" for a method plainly present in the source.
        byte[] original = classNamed("game/Thing", "originalMethod");
        byte[] transformed = classNamed("game/Thing", "remappedMethod");

        OmniBytecodeProvider provider = new OmniBytecodeProvider(
                name -> name.equals("game/Thing") ? Optional.of(transformed) : Optional.empty(),
                name -> name.equals("game/Thing") ? Optional.of(original) : Optional.empty());

        ClassNode node = provider.getClassNode("game.Thing");

        assertTrue(node.methods.stream().anyMatch(m -> m.name.equals("remappedMethod")),
                "Mixin must see the transformed class, not the original");
        assertEquals(1, provider.servedFromCache());
        assertEquals(0, provider.servedFromSource());
    }

    @Test
    @DisplayName("runTransformers=false asks for the untransformed class, and gets it")
    void honoursRunTransformersFlag() throws Exception {
        // Mixin uses this for hierarchy questions where the pipeline's edits are irrelevant.
        // Answering with transformed bytes can make a superclass appear to declare members it
        // never originally had.
        byte[] original = classNamed("game/Thing", "originalMethod");
        byte[] transformed = classNamed("game/Thing", "remappedMethod");

        OmniBytecodeProvider provider = new OmniBytecodeProvider(
                name -> Optional.of(transformed), name -> Optional.of(original));

        ClassNode node = provider.getClassNode("game.Thing", false);

        assertTrue(node.methods.stream().anyMatch(m -> m.name.equals("originalMethod")),
                "the flag must be honoured, not ignored as an optimisation");
        assertEquals(1, provider.servedFromSource());
    }

    @Test
    @DisplayName("an untransformed class falls back to its original bytes")
    void fallsBackToOriginal() throws Exception {
        byte[] original = classNamed("mod/Untouched", "method");
        OmniBytecodeProvider provider = new OmniBytecodeProvider(
                name -> Optional.empty(), name -> Optional.of(original));

        assertNotNull(provider.getClassNode("mod.Untouched"));
        assertEquals(1, provider.servedFromSource());
    }

    @Test
    @DisplayName("a class nobody can supply is a ClassNotFoundException that says why")
    void missingClassExplains() {
        OmniBytecodeProvider provider = new OmniBytecodeProvider(
                name -> Optional.empty(), name -> Optional.empty());

        ClassNotFoundException thrown = assertThrows(ClassNotFoundException.class,
                () -> provider.getClassNode("nowhere.Missing"));
        assertTrue(thrown.getMessage().contains("transform cache"), thrown.getMessage());
    }

    @Test
    @DisplayName("each request gets a fresh node, since Mixin mutates what it is given")
    void nodesAreNotShared() throws Exception {
        // Mixin applies a mixin by mutating the ClassNode. A shared instance would leak one
        // mod's modifications into what the next mod sees.
        byte[] bytes = classNamed("game/Thing", "method");
        OmniBytecodeProvider provider = new OmniBytecodeProvider(
                name -> Optional.of(bytes), name -> Optional.empty());

        ClassNode first = provider.getClassNode("game.Thing");
        first.methods.clear();
        ClassNode second = provider.getClassNode("game.Thing");

        assertFalse(second.methods.isEmpty(),
                "mutating one caller's node must not affect the next caller's");
    }

    // -------------------------------------------------------------- tracking

    @Test
    @DisplayName("loader and Mixin packages are refused as mixin targets")
    void restrictsOwnPackages() {
        // A mixin targeting the loader modifies the machinery applying it — a no-op or a crash
        // depending on timing, never what the author meant.
        OmniClassTracker tracker = new OmniClassTracker();

        assertFalse(tracker.getClassRestrictions("org.omniframework.loader.core.Boot").isEmpty());
        assertFalse(tracker.getClassRestrictions("org.spongepowered.asm.mixin.Mixin").isEmpty());
        assertFalse(tracker.getClassRestrictions("java.lang.String").isEmpty());
        assertEquals("", tracker.getClassRestrictions("net.minecraft.Entity"),
                "an ordinary game class must carry no restriction, and the empty string is "
                        + "Mixin's contract for that — null would print as 'null'");
    }

    @Test
    @DisplayName("a defined class is reported as loaded, in either name form")
    void tracksLoadedClasses() {
        // Mixin uses this to refuse targets it can no longer modify. Getting it wrong produces
        // a mod that appears to load and silently does nothing.
        OmniClassTracker tracker = new OmniClassTracker();
        assertFalse(tracker.isClassLoaded("net.minecraft.Entity"));

        tracker.recordLoaded("net/minecraft/Entity");

        assertTrue(tracker.isClassLoaded("net.minecraft.Entity"));
        assertTrue(tracker.isClassLoaded("net/minecraft/Entity"));
        assertEquals(1, tracker.loadedCount());
    }

    @Test
    @DisplayName("an invalidated class counts as loaded, so Mixin stops trying")
    void invalidClassesCountAsLoaded() {
        OmniClassTracker tracker = new OmniClassTracker();
        tracker.registerInvalidClass("net.minecraft.Broken");

        assertTrue(tracker.isInvalid("net.minecraft.Broken"));
        assertTrue(tracker.isClassLoaded("net.minecraft.Broken"));
    }

    // ------------------------------------------------------------ properties

    @Test
    @DisplayName("property keys compare by name, not identity")
    void propertyKeysCompareByName() {
        // Mixin resolves a key by string and hands the object back later. Identity comparison
        // would make two resolveKey("x") calls miss each other, and Mixin would re-initialise
        // state it believed it had stored — a phase mysteriously running twice.
        OmniGlobalPropertyService service = new OmniGlobalPropertyService();
        service.setProperty(service.resolveKey("phase"), "PREINIT");

        assertEquals("PREINIT", service.getProperty(service.resolveKey("phase")));
        assertEquals("PREINIT", service.getPropertyString(service.resolveKey("phase"), "none"));
    }

    @Test
    @DisplayName("absent properties fall back, and a null store does not blow up")
    void propertyDefaults() {
        OmniGlobalPropertyService service = new OmniGlobalPropertyService();

        assertEquals("fallback", service.getProperty(service.resolveKey("absent"), "fallback"));
        assertNull(service.getProperty(service.resolveKey("absent")));

        // ConcurrentHashMap forbids null values; removing is the honest equivalent.
        service.setProperty(service.resolveKey("k"), "v");
        service.setProperty(service.resolveKey("k"), null);
        assertNull(service.getProperty(service.resolveKey("k")));
    }

    /** A loadable class with one named method, for bytecode-identity assertions. */
    private static byte[] classNamed(String internalName, String methodName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
