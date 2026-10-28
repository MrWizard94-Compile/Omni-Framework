package org.omniframework.loader.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Representative NeoForge-shaped resolve: mods that previously produced false
 * {@code resolve.dependency-version} against minecraft 1.21.1 and hosted neoforge 0.1.0
 * must pass resolve on the shipped BootSequence path.
 */
class NeoForgeResolveMainstreamTest {

    @Test
    @DisplayName("NeoForge pack deps on minecraft+neoforge Maven ranges resolve on BootSequence")
    void neoForgeMavenDepsResolve(@TempDir Path dir) throws Exception {
        Path gameDir = dir.resolve("game");
        Path modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);

        // Two mods with the exact range shapes from the director's session log failures.
        writeNeoMod(
                modsDir.resolve("lib.jar"),
                "libmod",
                "1.0.0",
                """
                [[dependencies.libmod]]
                modId="minecraft"
                type="required"
                versionRange="[1.21.1,1.21.2)"
                [[dependencies.libmod]]
                modId="neoforge"
                type="required"
                versionRange="[21.1.72,)"
                """);
        writeNeoMod(
                modsDir.resolve("app.jar"),
                "appmod",
                "2.0.0",
                """
                [[dependencies.appmod]]
                modId="minecraft"
                type="required"
                versionRange="[1.21, 1.21.1]"
                [[dependencies.appmod]]
                modId="neoforge"
                type="required"
                versionRange="[21,)"
                [[dependencies.appmod]]
                modId="libmod"
                type="required"
                versionRange="[1.0,)"
                """);

        Path gameJar = writeGameJar(gameDir.resolve("minecraft.jar"));
        LocatedGame game =
                new LocatedGame("1.21.1", List.of(gameJar), "net.minecraft.FakeMain", List.of());
        BootConfig config =
                new BootConfig(gameDir, modsDir, GameSide.CLIENT, List.of(), List.of(), Optional.of(game));

        IssueCollector issues = new IssueCollector();
        Optional<BootResult> result = new BootSequence(List.<GameProvider>of()).run(config, issues);

        assertFalse(
                issues.errors().stream().anyMatch(i -> i.code().startsWith("resolve.")),
                () -> "resolve must not fail on in-range minecraft/neoforge: " + issues.issues());
        assertTrue(result.isPresent(), () -> "boot should complete after resolve: " + issues.issues());
        assertTrue(result.get().loadedMods().contains("appmod"), result.get().loadedMods().toString());
        assertTrue(result.get().loadedMods().contains("libmod"), result.get().loadedMods().toString());
    }

    @Test
    @DisplayName("exact Maven [1.21.1] minecraft dep resolves")
    void exactMinecraftBracketResolves(@TempDir Path dir) throws Exception {
        Path gameDir = dir.resolve("game");
        Path modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);
        writeNeoMod(
                modsDir.resolve("exact.jar"),
                "exactmod",
                "1.0.0",
                """
                [[dependencies.exactmod]]
                modId="minecraft"
                type="required"
                versionRange="[1.21.1]"
                [[dependencies.exactmod]]
                modId="neoforge"
                type="required"
                versionRange="[21.1.0,)"
                """);

        Path gameJar = writeGameJar(gameDir.resolve("minecraft.jar"));
        LocatedGame game =
                new LocatedGame("1.21.1", List.of(gameJar), "net.minecraft.FakeMain", List.of());
        BootConfig config =
                new BootConfig(gameDir, modsDir, GameSide.CLIENT, List.of(), List.of(), Optional.of(game));
        IssueCollector issues = new IssueCollector();
        Optional<BootResult> result = new BootSequence(List.<GameProvider>of()).run(config, issues);

        assertFalse(
                issues.errors().stream().anyMatch(i -> i.code().startsWith("resolve.")),
                () -> "exact [1.21.1] must match installed 1.21.1: " + issues.issues());
        assertTrue(result.isPresent(), () -> issues.issues().toString());
        assertTrue(result.get().loadedMods().contains("exactmod"));
    }

    private static void writeNeoMod(Path jar, String modId, String version, String dependencyToml)
            throws IOException {
        String toml = """
                modLoader="javafml"
                loaderVersion="[1,)"
                license="MIT"
                [[mods]]
                modId="%s"
                version="%s"
                displayName="%s"
                %s
                """
                .formatted(modId, version, modId, dependencyToml);
        // Minimal @Mod-annotated class so construct path can find an entrypoint if reached.
        String internal = "com/example/" + modId + "/ModMain";
        ClassWriter w = new ClassWriter(0);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
        w.visitAnnotation("Lnet/neoforged/fml/common/Mod;", true).visitEnd();
        var ctor = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();
        w.visitEnd();
        jar(jar, Map.of(
                "META-INF/neoforge.mods.toml", toml.getBytes(StandardCharsets.UTF_8),
                internal + ".class", w.toByteArray()));
    }

    /** Minimal game jar with a static main so BootSequence can finish after resolve. */
    private static Path writeGameJar(Path path) throws IOException {
        String internal = "net/minecraft/FakeMain";
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
        MethodVisitor m =
                w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
        m.visitCode();
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 1);
        m.visitEnd();
        w.visitEnd();
        jar(path, Map.of(internal + ".class", w.toByteArray()));
        return path;
    }

    private static void jar(Path path, Map<String, byte[]> entries) throws IOException {
        Files.createDirectories(path.getParent());
        try (OutputStream out = Files.newOutputStream(path);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (var e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
    }
}
