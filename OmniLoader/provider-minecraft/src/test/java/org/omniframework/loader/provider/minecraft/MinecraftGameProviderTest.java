package org.omniframework.loader.provider.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.provider.GameSide;
import org.omniframework.loader.provider.LocatedGame;

/** The one place that knows about Minecraft. */
class MinecraftGameProviderTest {

    private static Path touch(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "not really a jar, but it exists");
        return path;
    }

    @Test
    @DisplayName("an explicit jar and version are used as given — the launcher's path")
    void explicitJarWins(@TempDir Path dir) throws Exception {
        Path jar = touch(dir.resolve("cache/abc123/client.jar"));
        var provider = new MinecraftGameProvider(jar, "1.21.1");
        IssueCollector issues = new IssueCollector();

        assertTrue(provider.canHandle(dir, GameSide.CLIENT));
        LocatedGame game = provider.locate(dir, GameSide.CLIENT, issues).orElseThrow();

        assertEquals("1.21.1", game.version());
        assertEquals(MinecraftGameProvider.CLIENT_MAIN_CLASS, game.mainClass());
        assertEquals(java.util.List.of(jar), game.gameJars());
        assertTrue(issues.isEmpty(), () -> "unexpected issues: " + issues.issues());
    }

    @Test
    @DisplayName("the side selects the entry point")
    void sideSelectsMainClass(@TempDir Path dir) throws Exception {
        Path jar = touch(dir.resolve("server.jar"));
        var provider = new MinecraftGameProvider(jar, "1.21.1");
        LocatedGame game =
                provider.locate(dir, GameSide.SERVER, new IssueCollector()).orElseThrow();
        assertEquals(MinecraftGameProvider.SERVER_MAIN_CLASS, game.mainClass());
    }

    @Test
    @DisplayName("falls back to scanning a hand-assembled instance")
    void scansTheGameDirectory(@TempDir Path dir) throws Exception {
        touch(dir.resolve("versions/1.21.1/1.21.1.jar"));
        var provider = new MinecraftGameProvider();
        IssueCollector issues = new IssueCollector();

        assertTrue(provider.canHandle(dir, GameSide.CLIENT));
        LocatedGame game = provider.locate(dir, GameSide.CLIENT, issues).orElseThrow();
        assertEquals("1.21.1", game.version(), "the version comes from the version folder");
        assertFalse(issues.hasErrors());
    }

    @Test
    @DisplayName("a missing game is an actionable issue, not an exception")
    void missingGameIsReported(@TempDir Path dir) {
        var provider = new MinecraftGameProvider();
        IssueCollector issues = new IssueCollector();

        assertFalse(provider.canHandle(dir, GameSide.CLIENT));
        assertTrue(provider.locate(dir, GameSide.CLIENT, issues).isEmpty());

        var issue = issues.errors().get(0);
        assertEquals("minecraft.jar-not-found", issue.code());
        assertTrue(issue.remedy().contains("Install / Repair"),
                () -> "the remedy must point at the fix: " + issue.remedy());
    }

    @Test
    @DisplayName("a jar that vanished after being recorded is distinguished from never having one")
    void vanishedJarIsItsOwnIssue(@TempDir Path dir) {
        var provider = new MinecraftGameProvider(dir.resolve("gone.jar"), "1.21.1");
        IssueCollector issues = new IssueCollector();

        assertTrue(provider.locate(dir, GameSide.CLIENT, issues).isEmpty());
        assertEquals("minecraft.jar-missing", issues.errors().get(0).code(),
                "a deleted file and an uninstalled instance need different messages");
    }

    @Test
    @DisplayName("a generically-named jar with no version is refused rather than guessed at")
    void unknownVersionIsRefused(@TempDir Path dir) throws Exception {
        // The launcher's cache names files by role, not version; without an explicit version
        // there is nothing to infer, and inventing one would poison the whole stack.
        Path jar = touch(dir.resolve("client.jar"));
        var provider = new MinecraftGameProvider();
        IssueCollector issues = new IssueCollector();

        assertTrue(provider.locate(dir, GameSide.CLIENT, issues).isEmpty());
        assertEquals("minecraft.version-unknown", issues.errors().get(0).code());
    }
}
