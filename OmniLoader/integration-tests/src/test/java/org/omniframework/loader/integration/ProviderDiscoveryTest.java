package org.omniframework.loader.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.omniframework.loader.core.GameLauncher;
import org.omniframework.loader.provider.GameProvider;

/**
 * The boot sequence's very first step: finding a {@link GameProvider} at all (LDR-1).
 *
 * <h2>Why this test exists, specifically</h2>
 *
 * <p>An audit on 2026-07-20 found {@code provider-minecraft}'s {@code META-INF/services}
 * registration committed one directory too deep, so Gradle never packaged it and the built jar
 * carried no service entry. {@code MinecraftGameProvider} was undiscoverable in any real run —
 * the loader would have reported {@code boot.no-provider} and stopped before touching the game.
 *
 * <p>Nothing caught it. The existing test asserted only that discovery returned a non-null list,
 * which is equally true of an empty one, and every module built and passed. A registration file
 * is invisible to the compiler: nothing references it, so nothing can fail to resolve.
 *
 * <p>So these tests assert the thing that actually matters — that a specific provider is found,
 * and that the file is where the build will package it — rather than that the mechanism did not
 * throw.
 */
class ProviderDiscoveryTest {

    private static final String SERVICE_FILE =
            "META-INF/services/org.omniframework.loader.provider.GameProvider";
    private static final String EXPECTED_PROVIDER =
            "org.omniframework.loader.provider.minecraft.MinecraftGameProvider";

    @Test
    @DisplayName("the Minecraft provider is actually discovered, not merely 'not null'")
    void minecraftProviderIsDiscovered() {
        List<GameProvider> providers = GameLauncher.discoverProviders();

        assertNotNull(providers, "discovery must never return null");
        assertFalse(providers.isEmpty(),
                "no GameProvider was discovered — the boot sequence would stop at "
                        + "'boot.no-provider' before reaching the game");
        assertTrue(
                providers.stream().anyMatch(
                        provider -> provider.getClass().getName().equals(EXPECTED_PROVIDER)),
                () -> "MinecraftGameProvider is not on the discovered list, only: "
                        + providers.stream().map(p -> p.getClass().getName()).toList());
    }

    @Test
    @DisplayName("the service registration is on the classpath where the build packages it")
    void registrationIsPackaged() throws IOException {
        // Asserts the resource is reachable as a classpath resource, which is exactly what
        // ServiceLoader does and exactly what the misplaced file broke. Checking the filesystem
        // path instead would pass for a file the build never packages.
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(SERVICE_FILE)) {
            assertNotNull(in, SERVICE_FILE + " is not on the classpath — if this fails, the "
                    + "registration is in the wrong directory again and no provider will load");
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(content.contains(EXPECTED_PROVIDER),
                    () -> "registration names something unexpected: " + content);
        }
    }
}
