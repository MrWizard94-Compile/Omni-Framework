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
import org.omniframework.loader.bootstrap.BootConfig;
import org.omniframework.loader.bootstrap.BootResult;
import org.omniframework.loader.bootstrap.BootSequence;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.provider.GameProvider;
import org.omniframework.loader.provider.GameSide;
import org.omniframework.loader.provider.LocatedGame;

/**
 * Skeptic gap: activate-before-create must be per-mod. Two NeoForge {@code @Mod} classes each
 * register on {@code FMLJavaModLoadingContext.get().getModEventBus()} in their constructor and
 * receive {@code ModConstructedEvent} only on their own bus.
 */
class NeoForgePerModBusTest {

    private static final String CTOR_A = "omni.it.neo.ctor.neo-a";
    private static final String CTOR_B = "omni.it.neo.ctor.neo-b";
    private static final String POST_A = "omni.it.neo.posted.neo-a";
    private static final String POST_B = "omni.it.neo.posted.neo-b";
    private static final String GAME_SIGNAL = "omni.it.neo.game";

    @AfterEach
    void clear() {
        System.clearProperty(CTOR_A);
        System.clearProperty(CTOR_B);
        System.clearProperty(POST_A);
        System.clearProperty(POST_B);
        System.clearProperty(GAME_SIGNAL);
    }

    @Test
    @DisplayName("two NeoForge mods each construct and receive posts on their own event bus")
    void eachModUsesOwnBus(@TempDir Path dir) throws Exception {
        Path mods = dir.resolve("mods");
        Files.createDirectories(mods);
        neoMod(mods, "neo-a", "net/example/neo/ModA");
        neoMod(mods, "neo-b", "net/example/neo/ModB");

        Path gameDir = dir.resolve("game");
        Files.createDirectories(gameDir);
        Path gameJar = jar(gameDir.resolve("minecraft.jar"), Map.of(
                "net/minecraft/FakeMain.class", gameMain("net/minecraft/FakeMain")));

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
        // Constructors saw the correct active mod id (activate-before-create per mod).
        assertEquals("neo-a", System.getProperty(CTOR_A), "mod A ctor active id");
        assertEquals("neo-b", System.getProperty(CTOR_B), "mod B ctor active id");
        // Each bus received its own ModConstructedEvent (post by forMod(id), not last-active).
        assertEquals("yes", System.getProperty(POST_A), "mod A listener on own bus");
        assertEquals("yes", System.getProperty(POST_B), "mod B listener on own bus");
        assertEquals("yes", System.getProperty(GAME_SIGNAL));
        assertEquals(2, result.get().loadedMods().size());
    }

    private static void neoMod(Path mods, String modId, String internalName) throws IOException {
        String toml =
                """
                modLoader="javafml"
                loaderVersion="[1,)"
                license="MIT"

                [[mods]]
                modId="%s"
                version="1.0.0"
                displayName="%s"
                """
                        .formatted(modId, modId);
        jar(mods.resolve(modId + ".jar"), Map.of(
                "META-INF/neoforge.mods.toml", toml.getBytes(StandardCharsets.UTF_8),
                internalName + ".class", neoModClass(internalName, modId)));
    }

    /**
     * {@code @Mod} class whose constructor:
     * <ol>
     *   <li>records {@code FMLJavaModLoadingContext.get().getModId()} under
     *       {@code omni.it.neo.ctor.&lt;modId&gt;}
     *   <li>registers {@code this} on that context's bus
     * </ol>
     * and whose {@code @SubscribeEvent onConstructed(ModConstructedEvent)} sets
     * {@code omni.it.neo.posted.&lt;event.modId&gt;}.
     */
    private static byte[] neoModClass(String internalName, String expectedModId) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        AnnotationVisitor ann = w.visitAnnotation("Lnet/neoforged/fml/common/Mod;", true);
        ann.visit("value", expectedModId);
        ann.visitEnd();

        // public Mod() {
        //   super();
        //   var ctx = FMLJavaModLoadingContext.get();
        //   System.setProperty("omni.it.neo.ctor." + expectedModId, ctx.getModId());
        //   ctx.getModEventBus().register(this);
        // }
        MethodVisitor ctor = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);

        ctor.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "net/neoforged/fml/javafmlmod/FMLJavaModLoadingContext",
                "get",
                "()Lnet/neoforged/fml/javafmlmod/FMLJavaModLoadingContext;",
                false);
        ctor.visitVarInsn(Opcodes.ASTORE, 1); // ctx

        ctor.visitLdcInsn("omni.it.neo.ctor." + expectedModId);
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "net/neoforged/fml/javafmlmod/FMLJavaModLoadingContext",
                "getModId",
                "()Ljava/lang/String;",
                false);
        ctor.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/lang/System",
                "setProperty",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                false);
        ctor.visitInsn(Opcodes.POP);

        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "net/neoforged/fml/javafmlmod/FMLJavaModLoadingContext",
                "getModEventBus",
                "()Lnet/neoforged/bus/api/IEventBus;",
                false);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "net/neoforged/bus/api/IEventBus",
                "register",
                "(Ljava/lang/Object;)V",
                true);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        // @SubscribeEvent public void onConstructed(ModConstructedEvent e) {
        //   System.setProperty("omni.it.neo.posted." + e.getModId(), "yes");
        // }
        MethodVisitor on = w.visitMethod(
                Opcodes.ACC_PUBLIC,
                "onConstructed",
                "(Lorg/omniframework/loader/compat/neoforge/bus/ModConstructedEvent;)V",
                null,
                null);
        AnnotationVisitor sub =
                on.visitAnnotation("Lnet/neoforged/bus/api/SubscribeEvent;", true);
        sub.visitEnd();
        on.visitCode();
        on.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        on.visitInsn(Opcodes.DUP);
        on.visitLdcInsn("omni.it.neo.posted.");
        on.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "java/lang/StringBuilder",
                "<init>",
                "(Ljava/lang/String;)V",
                false);
        on.visitVarInsn(Opcodes.ALOAD, 1);
        on.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "org/omniframework/loader/compat/neoforge/bus/ModConstructedEvent",
                "getModId",
                "()Ljava/lang/String;",
                false);
        on.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                false);
        on.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "toString",
                "()Ljava/lang/String;",
                false);
        on.visitLdcInsn("yes");
        on.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/lang/System",
                "setProperty",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                false);
        on.visitInsn(Opcodes.POP);
        on.visitInsn(Opcodes.RETURN);
        on.visitMaxs(0, 0);
        on.visitEnd();

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
        m.visitLdcInsn(GAME_SIGNAL);
        m.visitLdcInsn("yes");
        m.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/lang/System",
                "setProperty",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                false);
        m.visitInsn(Opcodes.POP);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    private static Path jar(Path path, Map<String, byte[]> entries) throws IOException {
        Files.createDirectories(path.getParent());
        try (OutputStream out = Files.newOutputStream(path);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return path;
    }
}
