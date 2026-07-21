package org.omniframework.loader.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.omniframework.loader.provider.GameSide;
import org.omniframework.loader.provider.LocatedGame;

/**
 * The argument contract between the launcher and the loader (B2).
 *
 * <p>This is the shape the launcher must emit, so it is pinned here the way the IPC commands are
 * pinned on the launcher side: a change to either end that the other does not match is a launch
 * that fails at the process boundary, where it is hardest to debug.
 */
class OmniLoaderBootstrapParseTest {

    @Test
    @DisplayName("provider mode: just a game dir, no located game")
    void providerMode() {
        BootConfig config = OmniLoaderBootstrap.parse(
                new String[] {"--game-dir", "/instances/1"}).orElseThrow();

        assertEquals(Path.of("/instances/1"), config.gameDirectory());
        assertEquals(Path.of("/instances/1").resolve("mods"), config.modsDirectory());
        assertEquals(GameSide.CLIENT, config.side());
        assertTrue(config.providedGame().isEmpty(), "no --game-jar means discover via a provider");
        assertTrue(config.libraries().isEmpty());
    }

    @Test
    @DisplayName("launcher mode: a located game, libraries, and game args after --")
    void launcherMode() {
        BootConfig config = OmniLoaderBootstrap.parse(new String[] {
            "--game-dir", "/instances/1",
            "--mods-dir", "/instances/1/mods",
            "--side", "client",
            "--game-jar", "/cache/aa/client.jar",
            "--game-version", "1.21.1",
            "--game-main", "net.minecraft.client.main.Main",
            "--library", "/cache/bb/lwjgl.jar",
            "--library", "/cache/cc/authlib.jar",
            "--", "--username", "MrWizard94", "--accessToken", "xxx",
        }).orElseThrow();

        assertTrue(config.providedGame().isPresent(), "the launcher located the game");
        LocatedGame game = config.providedGame().orElseThrow();
        assertEquals("1.21.1", game.version());
        assertEquals("net.minecraft.client.main.Main", game.mainClass());
        assertEquals(List.of(Path.of("/cache/aa/client.jar")), game.gameJars());
        assertEquals(
                List.of(Path.of("/cache/bb/lwjgl.jar"), Path.of("/cache/cc/authlib.jar")),
                config.libraries());
        assertEquals(List.of("--username", "MrWizard94", "--accessToken", "xxx"),
                config.launchArguments(),
                "everything after -- is the game's, verbatim, including its own flags");
    }

    @Test
    @DisplayName("server side parses")
    void serverSide() {
        BootConfig config = OmniLoaderBootstrap.parse(
                new String[] {"--game-dir", "/s", "--side", "server"}).orElseThrow();
        assertEquals(GameSide.SERVER, config.side());
    }

    @Test
    @DisplayName("a game jar without version or main is refused, not half-accepted")
    void incompleteProvidedGameRefused() {
        // A launcher that sent jars but forgot the version would otherwise produce a boot that
        // fails deep in resolution; refusing at the boundary names the real fault.
        assertTrue(OmniLoaderBootstrap.parse(new String[] {
            "--game-dir", "/s", "--game-jar", "/c/client.jar",
        }).isEmpty(), "--game-jar needs --game-version and --game-main");

        assertTrue(OmniLoaderBootstrap.parse(new String[] {
            "--game-dir", "/s", "--game-jar", "/c/client.jar", "--game-version", "1.21.1",
        }).isEmpty(), "still missing --game-main");
    }

    @Test
    @DisplayName("game metadata without any jar is refused")
    void versionWithoutJarRefused() {
        assertTrue(OmniLoaderBootstrap.parse(new String[] {
            "--game-dir", "/s", "--game-version", "1.21.1",
        }).isEmpty(), "--game-version alone is meaningless without a jar to attach it to");
    }

    @Test
    @DisplayName("a missing game dir is refused")
    void gameDirRequired() {
        assertTrue(OmniLoaderBootstrap.parse(new String[] {"--side", "client"}).isEmpty());
    }

    @Test
    @DisplayName("a flag with no value is refused rather than swallowing the next flag")
    void danglingFlagRefused() {
        // Without the guard, `--game-dir --side client` would take "--side" as the dir.
        assertTrue(OmniLoaderBootstrap.parse(new String[] {"--game-dir"}).isEmpty());
        Optional<BootConfig> swallow =
                OmniLoaderBootstrap.parse(new String[] {"--side", "--game-dir", "/s"});
        assertFalse(swallow.isPresent(), "--side consumed the next token as its value and then "
                + "--game-dir was gone — the parser must treat a value-shaped flag as the value");
    }

    @Test
    @DisplayName("an unknown flag is refused")
    void unknownFlagRefused() {
        assertTrue(OmniLoaderBootstrap.parse(
                new String[] {"--game-dir", "/s", "--turbo"}).isEmpty());
    }
}
