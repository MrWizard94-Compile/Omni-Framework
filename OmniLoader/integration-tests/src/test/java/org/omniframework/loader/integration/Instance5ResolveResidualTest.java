package org.omniframework.loader.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * Reconstructs BootSequence against the director's real instance-5 mods folder when present.
 * Asserts mass false minecraft/neoforge version failures are gone; residual is classified.
 */
class Instance5ResolveResidualTest {

    private static final Path INSTANCE5_MODS = Path.of(
            System.getenv().getOrDefault("APPDATA", ""),
            "OmniLauncher",
            "instances",
            "5",
            "game",
            "mods");

    @Test
    @DisplayName("instance 5 real mods: no mass false minecraft/neoforge version rejects")
    void instance5ResolveIsHonest(@TempDir Path dir) throws Exception {
        assumeTrue(Files.isDirectory(INSTANCE5_MODS), "instance 5 mods not present: " + INSTANCE5_MODS);
        long jarCount;
        try (var stream = Files.list(INSTANCE5_MODS)) {
            jarCount = stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .count();
        }
        assumeTrue(jarCount > 50, "expected a large pack, got " + jarCount + " jars");

        Path gameDir = dir.resolve("game");
        Files.createDirectories(gameDir);
        // Point discovery at the real mods directory (do not copy hundreds of jars).
        Path gameJar = writeGameJar(gameDir.resolve("minecraft.jar"));
        LocatedGame game =
                new LocatedGame("1.21.1", List.of(gameJar), "net.minecraft.FakeMain", List.of());
        BootConfig config = new BootConfig(
                gameDir, INSTANCE5_MODS, GameSide.CLIENT, List.of(), List.of(), Optional.of(game));

        IssueCollector issues = new IssueCollector();
        // resolveOnly: avoid AOT transform of 300+ jars after a clean resolve.
        new BootSequence(List.<GameProvider>of()).resolveOnly(config, issues);

        List<OmniIssue> errors = issues.errors();
        long falseMc = errors.stream()
                .filter(i -> "resolve.dependency-version".equals(i.code()))
                .filter(i -> i.describe().toLowerCase(Locale.ROOT).contains("minecraft")
                        && i.describe().contains("1.21.1")
                        && i.describe().contains("installed version is 1.21.1"))
                .count();
        long falseNeo01 = errors.stream()
                .filter(i -> "resolve.dependency-version".equals(i.code()))
                .filter(i -> i.describe().toLowerCase(Locale.ROOT).contains("neoforge")
                        && i.describe().contains("0.1.0"))
                .count();

        assertTrue(
                falseMc < 5,
                () -> "mass false minecraft-in-range rejects still present: " + falseMc
                        + sample(errors, "minecraft"));
        assertTrue(
                falseNeo01 < 5,
                () -> "hosted neoforge still looks like 0.1.0: " + falseNeo01
                        + sample(errors, "0.1.0"));

        // Fabric platform aliases should clear pure fabric-api style missing (when only that).
        long fabricMissing = errors.stream()
                .filter(i -> "resolve.dependency-missing".equals(i.code()))
                .filter(i -> {
                    String d = i.describe().toLowerCase(Locale.ROOT);
                    return d.contains("fabric-api")
                            || d.contains("requires fabric,")
                            || d.contains("fabric-resource-loader")
                            || d.contains("fabric-lifecycle-events");
                })
                .count();
        assertTrue(
                fabricMissing < 5,
                () -> "fabric host aliases should clear fabric platform missing: " + fabricMissing
                        + sample(errors, "fabric"));

        // Residual missing content is allowed and should be dependency-missing (honest).
        long missing = errors.stream().filter(i -> i.code().contains("missing")).count();
        long version = errors.stream().filter(i -> i.code().contains("version")).count();
        System.out.println("[instance5] jars=" + jarCount + " errors=" + errors.size()
                + " missing=" + missing + " version=" + version);
        System.out.println("[instance5] residual codes: "
                + errors.stream().map(OmniIssue::code).distinct().collect(Collectors.joining(", ")));
        System.out.println("[instance5] residual sample:");
        errors.stream().limit(40).forEach(i -> System.out.println("  " + i.code() + " " + i.describe()));
        // Sanity: must be a large improvement over the pre-fix ~564 error session.
        assertTrue(errors.size() < 100, () -> "expected residual << 564, got " + errors.size());

        // On-disk mods that previously failed claim due to "[[mods]] #mandatory" must not
        // appear as dependency-missing for their own ids (false residual while jars exist).
        for (String mustBeFound : List.of(
                "citadel", "jei", "formations", "kotlinforforge", "xaeroworldmap")) {
            long falseMissing = errors.stream()
                    .filter(i -> "resolve.dependency-missing".equals(i.code()))
                    .filter(i -> {
                        String d = i.describe().toLowerCase(Locale.ROOT);
                        // "X requires citadel, which is not installed"
                        return d.contains("requires " + mustBeFound + ",")
                                || d.contains("requires " + mustBeFound + " ");
                    })
                    .count();
            assertTrue(
                    falseMissing == 0,
                    () -> "on-disk modId '" + mustBeFound
                            + "' still reported missing (claim/import bug): "
                            + sample(errors, mustBeFound));
        }
    }

    private static String sample(List<OmniIssue> errors, String needle) {
        return errors.stream()
                .filter(i -> i.describe().toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT)))
                .limit(5)
                .map(OmniIssue::describe)
                .collect(Collectors.joining(" | "));
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
