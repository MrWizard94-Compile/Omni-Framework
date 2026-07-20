package org.omniframework.loader.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.omniframework.api.ModInitializer;
import org.omniframework.loader.core.entrypoint.EntrypointDispatcher;
import org.omniframework.loader.core.entrypoint.EntrypointPhaseException;
import org.omniframework.loader.model.ModMetadata;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.model.OmniModJson;
import org.omniframework.loader.provider.GameSide;
import org.omniframework.loader.resolve.ModCandidate;
import org.omniframework.loader.resolve.ResolutionResult;
import org.omniframework.loader.resolve.Resolver;

/**
 * The native path, end to end (brick 14).
 *
 * <p>Every brick so far is tested inside its own module, which proves each piece works and
 * nothing about whether they fit together. This builds a real mod jar — a real
 * {@code omni.mod.json}, a real compiled entrypoint class — and walks it through the actual
 * stages a boot performs: parse the manifest into the canonical model, resolve it, load its
 * classes through a classloader, and dispatch its entrypoint.
 *
 * <p>The mod signals it ran through a system property. That is deliberately crude: it is
 * observable from outside whatever classloader the mod ended up in, so the assertion cannot
 * accidentally pass by reaching the test's own copy of a class.
 */
class NativeModPathTest {

    private static final String RAN = "omni.integration.testmod.ran";
    private static final String MOD_CLASS = "net/example/TestMod";

    @AfterEach
    void clearSignal() {
        System.clearProperty(RAN);
    }

    // ------------------------------------------------------------- fixtures

    /** The manifest a native mod author would actually write (ARCHITECTURE §5). */
    private static String manifest(String id, String entrypointClass) {
        return """
                {
                  "schema": 0,
                  "id": "%s",
                  "version": "1.0.0",
                  "name": "Test Mod",
                  "environment": "both",
                  "entrypoints": { "main": ["%s"] },
                  "depends": { "omniloader": ">=0.1" }
                }
                """.formatted(id, entrypointClass.replace('/', '.'));
    }

    /**
     * A real class implementing {@link ModInitializer}, whose {@code onInitialize} sets the
     * signal property.
     */
    private static byte[] entrypointClass(String internalName, String signalValue) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object",
                new String[] {"org/omniframework/api/ModInitializer"});

        MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        MethodVisitor init =
                writer.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "()V", null, null);
        init.visitCode();
        init.visitLdcInsn(RAN);
        init.visitLdcInsn(signalValue);
        init.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "setProperty",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
        init.visitInsn(Opcodes.POP);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    /** An entrypoint whose onInitialize throws — the common mod bug. */
    private static byte[] throwingEntrypoint(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object",
                new String[] {"org/omniframework/api/ModInitializer"});
        MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        MethodVisitor init =
                writer.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "()V", null, null);
        init.visitCode();
        init.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
        init.visitInsn(Opcodes.DUP);
        init.visitLdcInsn("this mod is broken");
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException",
                "<init>", "(Ljava/lang/String;)V", false);
        init.visitInsn(Opcodes.ATHROW);
        init.visitMaxs(0, 0);
        init.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Writes a jar holding a manifest and a class — a mod as it ships. */
    private static Path modJar(Path dir, String name, String manifest, String className,
            byte[] classBytes) throws IOException {
        Files.createDirectories(dir);
        Path jar = dir.resolve(name);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("omni.mod.json", manifest.getBytes(StandardCharsets.UTF_8));
        entries.put(className + ".class", classBytes);
        try (OutputStream out = Files.newOutputStream(jar);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return jar;
    }

    /** Reads a jar entry. */
    private static byte[] read(Path jar, String entry) throws IOException {
        try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
            ZipEntry found = zip.getEntry(entry);
            assertTrue(found != null, entry + " missing from " + jar);
            try (var in = zip.getInputStream(found)) {
                return in.readAllBytes();
            }
        }
    }

    /** Loads the mod's classes, delegating the API to the test's own loader. */
    private static ClassLoader loaderFor(Map<String, byte[]> classes) {
        return new ClassLoader(NativeModPathTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    /**
     * Parses a manifest, failing the test if the parser reported problems.
     *
     * <p>A manifest that half-parsed would make every later assertion meaningless, so the
     * issues are checked here rather than left to surface as a confusing NoSuchElement.
     */
    private static ModMetadata parseManifest(String text) {
        IssueCollector issues = new IssueCollector();
        return OmniModJson.parse(text, issues).orElseThrow(
                () -> new AssertionError("manifest did not parse: " + issues.issues()));
    }

    /**
     * The loader itself, as a resolvable candidate.
     *
     * <p>Mods declare {@code "depends": {"omniloader": ...}}, so a real boot presents the loader
     * as something the resolver can satisfy. Omitting it makes every mod unresolvable — which is
     * exactly what this test caught the first time it ran.
     */
    private static ModCandidate loaderCandidate() {
        return ModCandidate.topLevel(
                ModMetadata.builder("omniloader",
                                org.omniframework.loader.model.ModVersion.parse("0.1.0"))
                        .name("OmniLoader").build(),
                Path.of("omniloader.jar"));
    }

    // ------------------------------------------------------------ the path

    @Test
    @DisplayName("a native mod jar is parsed, resolved, loaded, and its entrypoint runs")
    void nativeModRunsEndToEnd(@TempDir Path dir) throws IOException {
        // 1. It ships as a jar.
        Path jar = modJar(dir, "testmod.jar", manifest("testmod", MOD_CLASS), MOD_CLASS,
                entrypointClass(MOD_CLASS, "yes"));

        // 2. The manifest parses into the canonical model (LDR-6).
        String manifestText = new String(read(jar, "omni.mod.json"), StandardCharsets.UTF_8);
        ModMetadata metadata = parseManifest(manifestText);
        assertEquals("testmod", metadata.id());
        assertEquals(1, metadata.entrypointsFor("main").size());

        // 3. It resolves, together with the loader itself satisfying its dependency (LDR-5).
        ModCandidate mod = ModCandidate.topLevel(metadata, jar);
        ResolutionResult resolution =
                new Resolver().resolve(List.of(mod, loaderCandidate()), GameSide.CLIENT);
        assertTrue(resolution.succeeded(), () -> resolution.errors().toString());
        assertTrue(resolution.loadOrderIds().contains("testmod"));

        // 4. Its classes load.
        ClassLoader modLoader = loaderFor(Map.of(
                MOD_CLASS.replace('/', '.'), read(jar, MOD_CLASS + ".class")));

        // 5. Its entrypoint is dispatched (LDR-7).
        assertNull(System.getProperty(RAN), "the mod must not have run before dispatch");
        EntrypointDispatcher dispatcher = new EntrypointDispatcher();
        dispatcher.register(metadata, modLoader);
        dispatcher.run("main", ModInitializer.class,
                (mod2, instance) -> instance.onInitialize());

        // 6. The mod's own code actually executed.
        assertEquals("yes", System.getProperty(RAN),
                "the entrypoint never ran — the native path is broken somewhere between the "
                        + "manifest and dispatch");
    }

    @Test
    @DisplayName("two mods initialise in the order resolution decided")
    void loadOrderIsHonoured(@TempDir Path dir) throws IOException {
        // The resolver orders a mod after its dependencies; dispatch must not undo that.
        Path libJar = modJar(dir, "lib.jar", manifest("lib", "net/example/Lib"),
                "net/example/Lib", entrypointClass("net/example/Lib", "lib"));
        String appManifest = """
                {
                  "schema": 0, "id": "app", "version": "1.0.0", "name": "App",
                  "entrypoints": { "main": ["net.example.App"] },
                  "depends": { "lib": ">=1.0" }
                }
                """;
        Path appJar = modJar(dir, "app.jar", appManifest, "net/example/App",
                entrypointClass("net/example/App", "app"));

        ModMetadata lib = parseManifest(new String(read(libJar, "omni.mod.json"), StandardCharsets.UTF_8));
        ModMetadata app = parseManifest(new String(read(appJar, "omni.mod.json"), StandardCharsets.UTF_8));

        ResolutionResult resolution = new Resolver().resolve(
                List.of(ModCandidate.topLevel(app, appJar), ModCandidate.topLevel(lib, libJar),
                        loaderCandidate()),
                GameSide.CLIENT);
        assertTrue(resolution.succeeded(), () -> resolution.errors().toString());
        assertTrue(resolution.loadOrderIds().indexOf("lib")
                        < resolution.loadOrderIds().indexOf("app"),
                () -> "the dependency must resolve first: " + resolution.loadOrderIds());

        ClassLoader modLoader = loaderFor(Map.of(
                "net.example.Lib", read(libJar, "net/example/Lib.class"),
                "net.example.App", read(appJar, "net/example/App.class")));

        // Registration follows resolution order, and the dispatcher preserves it.
        EntrypointDispatcher dispatcher = new EntrypointDispatcher();
        List<String> initialised = new java.util.ArrayList<>();
        for (ModCandidate candidate : resolution.loadOrder()) {
            if (candidate.id().equals("omniloader")) {
                continue; // the loader is not a mod with entrypoints
            }
            dispatcher.register(candidate.metadata(), modLoader);
        }
        dispatcher.run("main", ModInitializer.class, (metadata, instance) -> {
            initialised.add(metadata.id());
            instance.onInitialize();
        });

        assertEquals(List.of("lib", "app"), initialised,
                "entrypoints must fire in the order the resolver established");
    }

    @Test
    @DisplayName("a mod missing its dependency never reaches dispatch, and is named")
    void unresolvableModNeverRuns(@TempDir Path dir) throws IOException {
        // The failure has to land at resolution, not as a crash halfway through initialising.
        String needsMissing = """
                {
                  "schema": 0, "id": "needy", "version": "1.0.0", "name": "Needy",
                  "entrypoints": { "main": ["net.example.TestMod"] },
                  "depends": { "absentlib": ">=1.0" }
                }
                """;
        Path jar = modJar(dir, "needy.jar", needsMissing, MOD_CLASS,
                entrypointClass(MOD_CLASS, "yes"));
        ModMetadata metadata = parseManifest(new String(read(jar, "omni.mod.json"), StandardCharsets.UTF_8));

        ResolutionResult resolution = new Resolver()
                .resolve(List.of(ModCandidate.topLevel(metadata, jar)), GameSide.CLIENT);

        assertTrue(!resolution.succeeded(), "a missing dependency must fail resolution");
        assertTrue(resolution.errors().get(0).remedy().contains("absentlib"),
                resolution.errors().get(0)::describe);
        assertNull(System.getProperty(RAN), "nothing should have run");
    }

    @Test
    @DisplayName("a mod that throws on init is named, and does not stop the others")
    void brokenModIsAttributed(@TempDir Path dir) throws IOException {
        Path goodJar = modJar(dir, "good.jar", manifest("good", MOD_CLASS), MOD_CLASS,
                entrypointClass(MOD_CLASS, "yes"));
        Path badJar = modJar(dir, "bad.jar", manifest("bad", "net/example/Broken"),
                "net/example/Broken", throwingEntrypoint("net/example/Broken"));

        ModMetadata good = parseManifest(new String(read(goodJar, "omni.mod.json"), StandardCharsets.UTF_8));
        ModMetadata bad = parseManifest(new String(read(badJar, "omni.mod.json"), StandardCharsets.UTF_8));

        ClassLoader modLoader = loaderFor(Map.of(
                MOD_CLASS.replace('/', '.'), read(goodJar, MOD_CLASS + ".class"),
                "net.example.Broken", read(badJar, "net/example/Broken.class")));

        EntrypointDispatcher dispatcher = new EntrypointDispatcher();
        dispatcher.register(bad, modLoader);
        dispatcher.register(good, modLoader);

        EntrypointPhaseException thrown = assertThrows(EntrypointPhaseException.class,
                () -> dispatcher.run("main", ModInitializer.class,
                        (metadata, instance) -> instance.onInitialize()));

        assertTrue(thrown.getMessage().contains("bad"),
                () -> "the broken mod must be named: " + thrown.getMessage());
        assertEquals("yes", System.getProperty(RAN),
                "the working mod must still have initialised");
    }
}
