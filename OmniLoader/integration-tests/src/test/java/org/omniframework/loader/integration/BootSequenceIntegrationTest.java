package org.omniframework.loader.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.omniframework.loader.bootstrap.BootConfig;
import org.omniframework.loader.bootstrap.BootResult;
import org.omniframework.loader.bootstrap.BootSequence;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.provider.GameProvider;
import org.omniframework.loader.provider.GameSide;
import org.omniframework.loader.provider.LocatedGame;

/**
 * The whole native boot, composed (B1). Locate → discover → resolve → partition → dispatch
 * entrypoints → launch, run for real against fabricated-but-genuine jars.
 *
 * <p>Two signals prove the flow end to end, and both are system properties because a property
 * is observable from outside whatever classloader each class ended up in — so the test cannot
 * pass by accidentally reaching its own copy of a class:
 *
 * <ul>
 *   <li>the mod's {@code onInitialize} set its property → discovery, resolution, and entrypoint
 *       dispatch all worked;
 *   <li>the game's {@code main} set its property → the launch handed off, on the same loader.
 * </ul>
 *
 * <p>Ordering matters and is checked: the mod initialises before the game main, which is the
 * contract mods rely on to register before the game reads their registrations.
 */
class BootSequenceIntegrationTest {

    private static final String MOD_SIGNAL = "omni.it.boot.mod";
    private static final String GAME_SIGNAL = "omni.it.boot.game";
    private static final String ORDER_SIGNAL = "omni.it.boot.order";

    @AfterEach
    void clearSignals() {
        System.clearProperty(MOD_SIGNAL);
        System.clearProperty(GAME_SIGNAL);
        System.clearProperty(ORDER_SIGNAL);
    }

    // ------------------------------------------------------------- fixtures

    /** A native mod: manifest + a ModInitializer that records it ran, and the order it ran in. */
    private static Path nativeModJar(Path dir, String id) throws IOException {
        String modClass = "net/example/" + id + "Init";
        String manifest = """
                { "schema": 0, "id": "%s", "version": "1.0.0", "name": "%s",
                  "entrypoints": { "main": ["%s"] },
                  "depends": { "omniloader": ">=0.1", "minecraft": "1.21.1" } }
                """.formatted(id, id, modClass.replace('/', '.'));
        return jar(dir, id + ".jar", java.util.Map.of(
                "omni.mod.json", manifest.getBytes(StandardCharsets.UTF_8),
                modClass + ".class", initializerSetting(modClass, MOD_SIGNAL, id)));
    }

    /** A "game" jar whose main records that it ran, and that the mod ran before it. */
    private static Path gameJar(Path dir) throws IOException {
        return jar(dir, "minecraft.jar", java.util.Map.of(
                "net/minecraft/FakeMain.class", gameMain("net/minecraft/FakeMain")));
    }

    /** Bytecode: a ModInitializer whose onInitialize sets a signal and appends its id to order. */
    private static byte[] initializerSetting(String internalName, String signal, String id) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object",
                new String[] {"org/omniframework/api/ModInitializer"});
        defaultCtor(w);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "()V", null, null);
        m.visitCode();
        // System.setProperty(signal, "yes")
        m.visitLdcInsn(signal);
        m.visitLdcInsn("yes");
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "setProperty",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
        m.visitInsn(Opcodes.POP);
        // System.setProperty(ORDER, System.getProperty(ORDER,"") + id + ",")
        m.visitLdcInsn(ORDER_SIGNAL);
        m.visitLdcInsn(ORDER_SIGNAL);
        m.visitLdcInsn("");
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperty",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
        m.visitLdcInsn(id + ",");
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "setProperty",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
        m.visitInsn(Opcodes.POP);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** Bytecode: a static main(String[]) that records the game ran and appends to order. */
    private static byte[] gameMain(String internalName) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        defaultCtor(w);
        MethodVisitor m = w.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
        m.visitCode();
        m.visitLdcInsn(GAME_SIGNAL);
        m.visitLdcInsn("yes");
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "setProperty",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
        m.visitInsn(Opcodes.POP);
        m.visitLdcInsn(ORDER_SIGNAL);
        m.visitLdcInsn(ORDER_SIGNAL);
        m.visitLdcInsn("");
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperty",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
        m.visitLdcInsn("GAME");
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "setProperty",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
        m.visitInsn(Opcodes.POP);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    private static void defaultCtor(ClassWriter w) {
        MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        c.visitCode();
        c.visitVarInsn(Opcodes.ALOAD, 0);
        c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        c.visitInsn(Opcodes.RETURN);
        c.visitMaxs(0, 0);
        c.visitEnd();
    }

    private static Path jar(Path dir, String name, java.util.Map<String, byte[]> entries)
            throws IOException {
        Files.createDirectories(dir);
        Path path = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(path);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return path;
    }

    /** A provider that returns the fabricated game, so the sequence is exercised without a real MC. */
    private static GameProvider providerFor(Path gameJar) {
        return new GameProvider() {
            @Override
            public String id() {
                return "test";
            }

            @Override
            public String displayName() {
                return "Test Provider";
            }

            @Override
            public boolean canHandle(Path gameDirectory, GameSide side) {
                return true;
            }

            @Override
            public Optional<LocatedGame> locate(
                    Path gameDirectory, GameSide side, IssueCollector issues) {
                return Optional.of(new LocatedGame(
                        "1.21.1", List.of(gameJar), "net.minecraft.FakeMain", List.of()));
            }
        };
    }

    // ------------------------------------------------------------- the boot

    @Test
    @DisplayName("a native mod is discovered, resolved, initialised, then the game launches")
    void nativePathBootsEndToEnd(@TempDir Path dir) throws IOException {
        Path gameDir = dir.resolve("instance");
        Path modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);
        Path game = gameJar(gameDir.resolve("bin"));
        nativeModJar(modsDir, "examplemod");

        BootConfig config = new BootConfig(gameDir, modsDir, GameSide.CLIENT, List.of());
        IssueCollector issues = new IssueCollector();

        Optional<BootResult> result = new BootSequence(List.of(providerFor(game))).run(config, issues);

        assertTrue(result.isPresent(),
                () -> "boot did not begin: " + issues.issues());
        assertFalse(issues.hasErrors(), () -> issues.issues().toString());
        assertEquals("yes", System.getProperty(MOD_SIGNAL), "the mod's entrypoint never ran");
        assertEquals("yes", System.getProperty(GAME_SIGNAL), "the game's main never ran");
        assertEquals("examplemod,GAME", System.getProperty(ORDER_SIGNAL),
                "the mod must initialise before the game main");
        assertEquals(List.of("examplemod"), result.get().loadedMods());
    }

    @Test
    @DisplayName("mods initialise in resolved dependency order")
    void loadOrderFollowsDependencies(@TempDir Path dir) throws IOException {
        Path gameDir = dir.resolve("instance");
        Path modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);
        Path game = gameJar(gameDir.resolve("bin"));

        // 'app' depends on 'lib'; lib must initialise first regardless of filesystem order.
        nativeModJar(modsDir, "lib");
        String appClass = "net/example/appInit";
        jar(modsDir, "app.jar", java.util.Map.of(
                "omni.mod.json", ("""
                        { "schema": 0, "id": "app", "version": "1.0.0", "name": "app",
                          "entrypoints": { "main": ["net.example.appInit"] },
                          "depends": { "omniloader": ">=0.1", "lib": ">=1.0" } }
                        """).getBytes(StandardCharsets.UTF_8),
                appClass + ".class", initializerSetting(appClass, MOD_SIGNAL, "app")));

        IssueCollector issues = new IssueCollector();
        Optional<BootResult> result = new BootSequence(List.of(providerFor(game)))
                .run(new BootConfig(gameDir, modsDir, GameSide.CLIENT, List.of()), issues);

        assertTrue(result.isPresent(), () -> issues.issues().toString());
        assertEquals("lib,app,GAME", System.getProperty(ORDER_SIGNAL),
                "lib depends-before app, and both before the game");
    }

    @Test
    @DisplayName("a mod missing a dependency fails the boot before the game launches")
    void unresolvedModStopsTheBoot(@TempDir Path dir) throws IOException {
        Path gameDir = dir.resolve("instance");
        Path modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);
        Path game = gameJar(gameDir.resolve("bin"));
        jar(modsDir, "needy.jar", java.util.Map.of("omni.mod.json", ("""
                { "schema": 0, "id": "needy", "version": "1.0.0", "name": "needy",
                  "entrypoints": { "main": ["net.example.needyInit"] },
                  "depends": { "omniloader": ">=0.1", "absentlib": ">=1.0" } }
                """).getBytes(StandardCharsets.UTF_8),
                "net/example/needyInit.class", initializerSetting("net/example/needyInit", MOD_SIGNAL, "needy")));

        IssueCollector issues = new IssueCollector();
        Optional<BootResult> result = new BootSequence(List.of(providerFor(game)))
                .run(new BootConfig(gameDir, modsDir, GameSide.CLIENT, List.of()), issues);

        assertTrue(result.isEmpty(), "a missing dependency must stop the boot");
        assertTrue(issues.hasErrors());
        assertTrue(issues.errors().stream().anyMatch(i -> i.remedy().contains("absentlib")),
                () -> issues.issues().toString());
        org.junit.jupiter.api.Assertions.assertNull(System.getProperty(GAME_SIGNAL),
                "the game must not launch when a mod could not be resolved");
    }

    @Test
    @DisplayName("no provider claiming the directory fails cleanly, not with a crash")
    void noProviderIsReported(@TempDir Path dir) {
        IssueCollector issues = new IssueCollector();
        Optional<BootResult> result = new BootSequence(List.of())
                .run(new BootConfig(dir, dir.resolve("mods"), GameSide.CLIENT, List.of()), issues);

        assertTrue(result.isEmpty());
        assertTrue(issues.errors().stream().anyMatch(i -> i.code().equals("boot.no-provider")),
                () -> issues.issues().toString());
    }
}
