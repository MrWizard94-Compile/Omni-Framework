package org.omniframework.loader.bootstrap;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.omniframework.loader.model.Dependency;
import org.omniframework.loader.model.ModMetadata;
import org.omniframework.loader.model.ModVersion;
import org.omniframework.loader.model.VersionPredicate;
import org.omniframework.loader.resolve.ModCandidate;
import org.omniframework.loader.resolve.ResolutionResult;
import org.omniframework.loader.resolve.Resolver;
import org.omniframework.loader.provider.GameSide;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Hosted fabric / fabric-api provides must satisfy Connector-style depends that
 * previously surfaced as resolve.dependency-missing on NeoForge packs.
 */
class HostedFabricAliasesTest {

    @Test
    @DisplayName("hosted fabric-api provides clear fabric and module dependency-missing")
    void hostedFabricApiSatisfiesGuestDepends() {
        ModMetadata guest = ModMetadata.builder("connector-guest", ModVersion.parse("1.0.0"))
                .dependency(depends("fabric", "*"))
                .dependency(depends("fabric-api", "*"))
                .dependency(depends("fabric-resource-loader-v0", "*"))
                .dependency(depends("fabric-lifecycle-events-v1", "*"))
                .build();
        ModMetadata mc = ModMetadata.builder("minecraft", ModVersion.parse("1.21.1")).build();
        List<ModCandidate> candidates = new ArrayList<>();
        candidates.add(ModCandidate.topLevel(mc, Path.of("mc.jar")));
        candidates.add(ModCandidate.topLevel(BootSequence.hostedFabricApiMetadata(), Path.of("host.jar")));
        candidates.add(ModCandidate.topLevel(guest, Path.of("guest.jar")));

        ResolutionResult result = new Resolver().resolve(candidates, GameSide.CLIENT);
        assertTrue(result.succeeded(), () -> result.errors().toString());
        assertTrue(result.loadOrderIds().contains("connector-guest"));
    }

    private static Dependency depends(String id, String range) {
        return new Dependency(
                Dependency.Kind.DEPENDS,
                id,
                VersionPredicate.expand(range).orElseThrow(),
                Dependency.LoadOrder.NONE,
                java.util.Optional.empty());
    }
}
