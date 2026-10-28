package org.omniframework.loader.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.omniframework.api.OmniLoaderApi;
import org.omniframework.loader.bootstrap.BootConfig;
import org.omniframework.loader.bootstrap.BootResult;
import org.omniframework.loader.bootstrap.BootSequence;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.provider.GameProvider;
import org.omniframework.loader.provider.GameSide;
import org.omniframework.loader.provider.LocatedGame;

/**
 * ADR-0020 / B4 exit for Phase 4 fixtures: one native + one Fabric + one NeoForge-shaped mod
 * initialise on one BootSequence, then the game main runs.
 *
 * <p>These are not production Minecraft mods (no intermediary remap, no full FML game bus).
 * They prove claim → import → partition → entrypoint, that {@link OmniLoaderApi} is installed
 * before entrypoints, and that NeoForge construct runs on the shared boot (per-mod event bus
 * is covered by {@code OmniEventBusTest} on the shipped host types).
 */
class MixedPackBootTest {

    private static final String NATIVE_SIGNAL = "omni.it.mixed.native";
    private static final String FABRIC_SIGNAL = "omni.it.mixed.fabric";
    private static final String NEO_SIGNAL = "omni.it.mixed.neo";
    private static final String GAME_SIGNAL = "omni.it.mixed.game";
    private static final String ORDER_SIGNAL = "omni.it.mixed.order";

    @AfterEach
    void clear() {
        System.clearProperty(NATIVE_SIGNAL);
        System.clearProperty(FABRIC_SIGNAL);
        System.clearProperty(NEO_SIGNAL);
        System.clearProperty(GAME_SIGNAL);
        System.clearProperty(ORDER_SIGNAL);
    }

    @Test
    @DisplayName("native + fabric + neoforge fixtures initialise before game main")
    void mixedPackBoots(@TempDir Path dir) throws Exception {
        Path mods = dir.resolve("mods");
        Files.createDirectories(mods);
        nativeMod(mods);
        fabricMod(mods);
        neoforgeMod(mods);

        Path gameDir = dir.resolve("game");
        Files.createDirectories(gameDir);
        Path gameJar = gameJar(gameDir);

        LocatedGame game = new LocatedGame(
                "1.21.1",
                List.of(gameJar),
                "net.minecraft.FakeMain",
                List.of());

        BootConfig config = new BootConfig(
                gameDir,
                mods,
                GameSide.CLIENT,
                List.of(),
                List.of(),
                Optional.of(game));

        IssueCollector issues = new IssueCollector();
        Optional<BootResult> result =
                new BootSequence(List.<GameProvider>of()).run(config, issues);

        assertTrue(result.isPresent(), () -> "boot failed: " + issues.issues());
        assertTrue(
                OmniLoaderApi.Holder.isAvailable(),
                "OmniLoaderApi must be installed before entrypoints (shipped BootSequence path)");
        OmniLoaderApi api = OmniLoaderApi.Holder.get();
        assertEquals(BootSequence.LOADER_VERSION, api.loaderVersion());
        assertTrue(api.isModLoaded("native-mixed"), api.loadedMods().toString());
        assertTrue(api.isModLoaded("fabric-mixed"), api.loadedMods().toString());
        assertTrue(api.isModLoaded("neoforge-mixed"), api.loadedMods().toString());
        assertEquals("yes", System.getProperty(NATIVE_SIGNAL));
        assertEquals("yes", System.getProperty(FABRIC_SIGNAL));
        assertEquals("yes", System.getProperty(NEO_SIGNAL));
        assertEquals("yes", System.getProperty(GAME_SIGNAL));
        String order = System.getProperty(ORDER_SIGNAL, "");
        assertTrue(order.contains("native"), order);
        assertTrue(order.contains("fabric"), order);
        assertTrue(order.contains("neoforge"), order);
        assertTrue(order.endsWith("game,") || order.contains("game,"), order);
        assertEquals(3, result.get().loadedMods().size());
    }

    private static void nativeMod(Path mods) throws IOException {
        String cls = "net/example/mixed/NativeInit";
        String manifest = """
                { "schema": 0, "id": "native-mixed", "version": "1.0.0",
                  "entrypoints": { "main": ["net.example.mixed.NativeInit"] },
                  "depends": { "omniloader": ">=0.1", "minecraft": "1.21.1" } }
                """;
        jar(mods.resolve("native-mixed.jar"), Map.of(
                "omni.mod.json", manifest.getBytes(StandardCharsets.UTF_8),
                cls + ".class",
                initializer(cls, "org/omniframework/api/ModInitializer", NATIVE_SIGNAL, "native")));
    }

    private static void fabricMod(Path mods) throws IOException {
        String cls = "net/example/mixed/FabricInit";
        String manifest = """
                {
                  "schemaVersion": 1,
                  "id": "fabric-mixed",
                  "version": "1.0.0",
                  "name": "Fabric Mixed",
                  "environment": "*",
                  "entrypoints": { "main": ["net.example.mixed.FabricInit"] },
                  "depends": { "fabricloader": ">=0.1", "minecraft": "1.21.1" }
                }
                """;
        jar(mods.resolve("fabric-mixed.jar"), Map.of(
                "fabric.mod.json", manifest.getBytes(StandardCharsets.UTF_8),
                cls + ".class",
                initializer(
                        cls,
                        "net/fabricmc/api/ModInitializer",
                        FABRIC_SIGNAL,
                        "fabric")));
    }

    private static void neoforgeMod(Path mods) throws IOException {
        String cls = "net/example/mixed/NeoInit";
        String toml = """
                modLoader="javafml"
                loaderVersion="[1,)"
                license="MIT"

                [[mods]]
                modId="neoforge-mixed"
                version="1.0.0"
                displayName="NeoForge Mixed"
                """;
        jar(mods.resolve("neoforge-mixed.jar"), Map.of(
                "META-INF/neoforge.mods.toml", toml.getBytes(StandardCharsets.UTF_8),
                cls + ".class", neoModClass(cls, NEO_SIGNAL, "neoforge")));
    }

    private static Path gameJar(Path dir) throws IOException {
        return jar(dir.resolve("minecraft.jar"), Map.of(
                "net/minecraft/FakeMain.class", gameMain("net/minecraft/FakeMain")));
    }

    private static byte[] initializer(
            String internalName, String ifaceInternal, String signal, String orderTag) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(
                Opcodes.V21,
                Opcodes.ACC_PUBLIC,
                internalName,
                null,
                "java/lang/Object",
                new String[] {ifaceInternal});
        defaultCtor(w);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "()V", null, null);
        m.visitCode();
        signalAndOrder(m, signal, orderTag);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** @Mod class with no-arg ctor that sets signals (construction = "load"). */
    private static byte[] neoModClass(String internalName, String signal, String orderTag) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        AnnotationVisitor ann =
                w.visitAnnotation("Lnet/neoforged/fml/common/Mod;", true);
        ann.visitEnd();
        MethodVisitor ctor = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        signalAndOrder(ctor, signal, orderTag);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    private static byte[] gameMain(String internalName) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "main",
                "([Ljava/lang/String;)V",
                null,
                null);
        m.visitCode();
        signalAndOrder(m, GAME_SIGNAL, "game");
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

    private static void signalAndOrder(MethodVisitor m, String signal, String orderTag) {
        m.visitLdcInsn(signal);
        m.visitLdcInsn("yes");
        m.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/lang/System",
                "setProperty",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                false);
        m.visitInsn(Opcodes.POP);
        m.visitLdcInsn(ORDER_SIGNAL);
        m.visitLdcInsn(ORDER_SIGNAL);
        m.visitLdcInsn("");
        m.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/lang/System",
                "getProperty",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                false);
        m.visitLdcInsn(orderTag + ",");
        m.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "concat",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false);
        m.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/lang/System",
                "setProperty",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                false);
        m.visitInsn(Opcodes.POP);
    }

    private static Path jar(Path path, Map<String, byte[]> entries) throws IOException {
        try (OutputStream out = Files.newOutputStream(path);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (var e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return path;
    }
}
