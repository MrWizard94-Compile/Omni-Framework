package org.omniframework.loader.integration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.omniframework.loader.bootstrap.BootConfig;
import org.omniframework.loader.bootstrap.BootSequence;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.diag.OmniIssue;
import org.omniframework.loader.provider.GameProvider;
import org.omniframework.loader.provider.GameSide;
import org.omniframework.loader.provider.LocatedGame;

/**
 * Reconstructs BootSequence against every director OmniLauncher instance that has a mods folder.
 * Prints residual classification; fails only on mass false minecraft/neoforge version rejects.
 */
class AllInstancesResolveResidualTest {

    private static final Path INSTANCES_ROOT = Path.of(
            System.getenv().getOrDefault("APPDATA", ""), "OmniLauncher", "instances");

    @Test
    @DisplayName("all local OmniLauncher instances: no mass false mc/neoforge resolve fails")
    void allInstancesResolveHonest(@TempDir Path dir) throws Exception {
        assumeTrue(Files.isDirectory(INSTANCES_ROOT), "no OmniLauncher instances dir: " + INSTANCES_ROOT);

        List<Path> instanceDirs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(INSTANCES_ROOT)) {
            for (Path p : stream) {
                if (Files.isDirectory(p)) {
                    instanceDirs.add(p);
                }
            }
        }
        assumeTrue(!instanceDirs.isEmpty(), "no instances under " + INSTANCES_ROOT);
        instanceDirs.sort(Path::compareTo);

        int checked = 0;
        for (Path instanceDir : instanceDirs) {
            Path mods = instanceDir.resolve("game").resolve("mods");
            if (!Files.isDirectory(mods)) {
                System.out.println("[all-instances] skip " + instanceDir.getFileName() + " (no mods dir)");
                continue;
            }
            long jars;
            try (var s = Files.list(mods)) {
                jars = s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                        .count();
            }
            if (jars == 0) {
                System.out.println("[all-instances] skip " + instanceDir.getFileName() + " (0 jars)");
                continue;
            }
            checked++;
            Path gameDir = dir.resolve("game-" + instanceDir.getFileName());
            Files.createDirectories(gameDir);
            Path gameJar = writeGameJar(gameDir.resolve("minecraft.jar"));
            LocatedGame game =
                    new LocatedGame("1.21.1", List.of(gameJar), "net.minecraft.FakeMain", List.of());
            BootConfig config =
                    new BootConfig(gameDir, mods, GameSide.CLIENT, List.of(), List.of(), Optional.of(game));
            IssueCollector issues = new IssueCollector();
            long t0 = System.nanoTime();
            Path progress = Path.of(System.getProperty("java.io.tmpdir"), "omni-residual-progress.txt");
            Files.writeString(
                    progress,
                    "begin " + instanceDir.getFileName() + " jars=" + jars + " t=" + System.currentTimeMillis()
                            + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            System.out.println("[all-instances] begin id=" + instanceDir.getFileName()
                    + " jars=" + jars);
            System.out.flush();
            // resolveOnly: full run() AOT-remaps hundreds of jars after a clean resolve and hangs ITs.
            new BootSequence(List.<GameProvider>of()).resolveOnly(config, issues);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            Files.writeString(
                    progress,
                    "done " + instanceDir.getFileName() + " ms=" + ms + " errors=" + issues.errors().size()
                            + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            List<OmniIssue> errors = issues.errors();
            long falseMc = errors.stream()
                    .filter(i -> "resolve.dependency-version".equals(i.code()))
                    .filter(i -> {
                        String d = i.describe().toLowerCase(Locale.ROOT);
                        return d.contains("minecraft")
                                && d.contains("1.21.1")
                                && d.contains("installed version is 1.21.1");
                    })
                    .count();
            long falseNeo01 = errors.stream()
                    .filter(i -> "resolve.dependency-version".equals(i.code()))
                    .filter(i -> i.describe().toLowerCase(Locale.ROOT).contains("neoforge")
                            && i.describe().contains("0.1.0"))
                    .count();
            long missing = errors.stream().filter(i -> i.code().contains("missing")).count();
            long version = errors.stream().filter(i -> i.code().contains("version")).count();
            System.out.println("[all-instances] id=" + instanceDir.getFileName()
                    + " jars=" + jars
                    + " ms=" + ms
                    + " errors=" + errors.size()
                    + " missing=" + missing
                    + " version=" + version
                    + " falseMc=" + falseMc
                    + " falseNeo01=" + falseNeo01);
            System.out.println("[all-instances] sample " + instanceDir.getFileName() + ":");
            errors.stream().limit(12).forEach(i -> System.out.println("  " + i.code() + " " + i.describe()));
            System.out.flush();

            assertTrue(
                    falseMc < 5,
                    () -> "instance " + instanceDir.getFileName()
                            + " mass false minecraft rejects: " + falseMc);
            assertTrue(
                    falseNeo01 == 0,
                    () -> "instance " + instanceDir.getFileName()
                            + " still has neoforge 0.1.0 host mass-fail: " + falseNeo01);
            long falseForgeAsNeo = errors.stream()
                    .filter(i -> "resolve.dependency-version".equals(i.code()))
                    .filter(i -> {
                        String d = i.describe().toLowerCase(Locale.ROOT);
                        return d.contains("forge")
                                && d.contains("[5")
                                && d.contains("21.1.");
                    })
                    .count();
            assertTrue(
                    falseForgeAsNeo == 0,
                    () -> "instance " + instanceDir.getFileName()
                            + " classic forge pins still hit NeoForge 21.1 host: "
                            + falseForgeAsNeo);
            assertTrue(
                    errors.size() < 200,
                    () -> "instance " + instanceDir.getFileName()
                            + " residual still catastrophic: " + errors.size());
        }
        assumeTrue(checked > 0, "no instances with jars to check");
        System.out.println("[all-instances] checked=" + checked);
    }

    private static Path writeGameJar(Path path) throws IOException {
        String internal = "net/minecraft/FakeMain";
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
        m.visitCode();
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 1);
        m.visitEnd();
        w.visitEnd();
        Files.createDirectories(path.getParent());
        try (OutputStream out = Files.newOutputStream(path);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(internal + ".class"));
            zip.write(w.toByteArray());
            zip.closeEntry();
        }
        return path;
    }
}
