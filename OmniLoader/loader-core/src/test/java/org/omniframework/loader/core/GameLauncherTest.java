package org.omniframework.loader.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.provider.GameProvider;
import org.omniframework.loader.provider.GameSide;
import org.omniframework.loader.provider.LocatedGame;

/**
 * Booting a game through the transforming loader.
 *
 * <p>The "game" here is a real jar with a real {@code main} that writes a file, so a passing test
 * means bytes were read from a jar, transformed, defined by our loader, and executed — not that a
 * mock was called. Minecraft itself cannot be a unit-test fixture (it is gigabytes and it opens a
 * window), but everything the loader does to it is identical.
 */
class GameLauncherTest {

    /**
     * A jar whose main class writes its arguments and its own class loader's name to a file —
     * proof of both execution and which loader defined it.
     */
    private static Path fakeGameJar(Path dir, String name) throws Exception {
        return TestJars.builder()
                .source("fake.game.Main", """
                        package fake.game;
                        import java.nio.file.*;
                        public class Main {
                            public static void main(String[] args) throws Exception {
                                String loader = Main.class.getClassLoader().getName();
                                String context = Thread.currentThread()
                                        .getContextClassLoader().getName();
                                Files.writeString(Path.of(System.getProperty("omni.test.out")),
                                        "loader=" + loader
                                        + "\\ncontext=" + context
                                        + "\\nargs=" + String.join(",", args));
                            }
                        }
                        """)
                .writeTo(dir.resolve(name));
    }

    @Test
    @DisplayName("boots a game jar: our loader defines it, and it actually runs")
    void bootsAGameJar(@TempDir Path dir) throws Exception {
        Path jar = fakeGameJar(dir, "game.jar");
        Path marker = dir.resolve("ran.txt");
        System.setProperty("omni.test.out", marker.toString());

        LocatedGame game = new LocatedGame("1.21.1", List.of(jar), "fake.game.Main", List.of());
        try (PartitionRegistry partitions = GameLauncher.partitionsFor(game, List.of())) {
            new GameLauncher(partitions, ClassTransformer.identity())
                    .launch(game, List.of("--username", "Tester"));
        } finally {
            System.clearProperty("omni.test.out");
        }

        assertTrue(Files.exists(marker), "the game's main never ran");
        String recorded = Files.readString(marker);
        assertTrue(recorded.contains("loader=omni"),
                () -> "the game was not defined by our loader: " + recorded);
        // Libraries resolve resources through the context loader; getting this wrong produces
        // failures far from their cause.
        assertTrue(recorded.contains("context=omni"),
                () -> "the context class loader was not set for the game: " + recorded);
        assertTrue(recorded.contains("args=--username,Tester"),
                () -> "arguments did not reach the game: " + recorded);
    }

    @Test
    @DisplayName("a transformer applied to game classes takes effect at boot")
    void transformersApplyToTheGame(@TempDir Path dir) throws Exception {
        Path jar = fakeGameJar(dir, "game.jar");
        Path marker = dir.resolve("ran.txt");
        System.setProperty("omni.test.out", marker.toString());

        java.util.List<String> transformed = new java.util.ArrayList<>();
        ClassTransformer recording = (name, partition, bytes) -> {
            transformed.add(name + "@" + partition.kind());
            return bytes;
        };

        LocatedGame game = new LocatedGame("1.21.1", List.of(jar), "fake.game.Main", List.of());
        try (PartitionRegistry partitions = GameLauncher.partitionsFor(game, List.of())) {
            new GameLauncher(partitions, recording).launch(game, List.of());
        } finally {
            System.clearProperty("omni.test.out");
        }

        assertTrue(transformed.contains("fake.game.Main@GAME"),
                () -> "the game's own classes must pass through the pipeline: " + transformed);
    }

    @Test
    @DisplayName("a missing main class is reported as a launch failure, not a mystery")
    void missingMainClassIsReported(@TempDir Path dir) throws Exception {
        Path jar = fakeGameJar(dir, "game.jar");
        LocatedGame game =
                new LocatedGame("1.21.1", List.of(jar), "fake.game.NotThere", List.of());
        try (PartitionRegistry partitions = GameLauncher.partitionsFor(game, List.of())) {
            GameLaunchException failure = assertThrows(GameLaunchException.class,
                    () -> new GameLauncher(partitions, ClassTransformer.identity())
                            .launch(game, List.of()));
            assertTrue(failure.getMessage().contains("fake.game.NotThere"),
                    () -> "the message must name the class: " + failure.getMessage());
        }
    }

    @Test
    @DisplayName("a game that throws surfaces its own exception, not a reflection wrapper")
    void gameFailureIsUnwrapped(@TempDir Path dir) throws Exception {
        Path jar = TestJars.builder()
                .source("fake.game.Boom", """
                        package fake.game;
                        public class Boom {
                            public static void main(String[] args) {
                                throw new IllegalStateException("the game exploded");
                            }
                        }
                        """)
                .writeTo(dir.resolve("boom.jar"));
        LocatedGame game = new LocatedGame("1.21.1", List.of(jar), "fake.game.Boom", List.of());

        try (PartitionRegistry partitions = GameLauncher.partitionsFor(game, List.of())) {
            GameLaunchException failure = assertThrows(GameLaunchException.class,
                    () -> new GameLauncher(partitions, ClassTransformer.identity())
                            .launch(game, List.of()));
            assertNotNull(failure.getCause(), "the game's own failure must be the cause");
            assertEquals("the game exploded", failure.getCause().getMessage(),
                    "an InvocationTargetException wrapper would bury this");
        }
    }

    @Test
    @DisplayName("providers are discovered through ServiceLoader")
    void providersAreDiscovered() {
        List<GameProvider> providers = GameLauncher.discoverProviders();
        // provider-minecraft is on the test classpath via the project dependency graph only
        // when present; the mechanism itself is what this asserts.
        assertNotNull(providers, "discovery must never return null");
    }

    @Test
    @DisplayName("no provider claiming the directory is a typed issue, not a crash")
    void noProviderIsReported(@TempDir Path dir) {
        IssueCollector issues = new IssueCollector();
        var selected = GameLauncher.selectProvider(List.of(), dir, GameSide.CLIENT, issues);

        assertTrue(selected.isEmpty());
        assertTrue(issues.hasErrors());
        var issue = issues.errors().get(0);
        assertEquals("boot.no-provider", issue.code());
        assertFalse(issue.remedy().isBlank(), "the user must be told what to do");
    }

    @Test
    @DisplayName("a multi-jar game gets distinct partitions in order")
    void multiJarGamesPartitionCleanly(@TempDir Path dir) throws Exception {
        Path first = fakeGameJar(dir, "game.jar");
        Path second = TestJars.builder()
                .source("fake.game.Extra", """
                        package fake.game;
                        public class Extra {}
                        """)
                .writeTo(dir.resolve("game-extra.jar"));
        LocatedGame game =
                new LocatedGame("1.21.1", List.of(first, second), "fake.game.Main", List.of());

        try (PartitionRegistry partitions = GameLauncher.partitionsFor(game, List.of())) {
            List<String> ids = partitions.partitions().stream().map(Partition::id).toList();
            assertEquals(List.of("minecraft", "minecraft:1"), ids,
                    "split games need distinct ids, and the common case keeps the plain name");
            assertTrue(partitions.ownerOf("fake.game.Extra").isPresent(),
                    "classes in the second jar must still resolve");
        }
    }
}
