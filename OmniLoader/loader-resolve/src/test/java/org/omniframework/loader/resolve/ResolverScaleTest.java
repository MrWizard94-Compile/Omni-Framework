package org.omniframework.loader.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.omniframework.loader.model.Dependency;
import org.omniframework.loader.model.ModMetadata;
import org.omniframework.loader.model.ModVersion;
import org.omniframework.loader.model.VersionPredicate;
import org.omniframework.loader.provider.GameSide;

/**
 * Does the search hold up at real modpack size?
 *
 * <p>This exists because the resolver is a backtracking search rather than a SAT solver
 * (ADR-0018). Chronological backtracking has a worse worst case than a real solver, so the claim
 * "fast enough for real packs" has to be measured rather than assumed — otherwise the first
 * evidence would arrive as a user reporting a launcher that hangs.
 *
 * <p>The fixture is shaped like an actual pack: a layer of shared libraries with competing bundled
 * versions (the thing that makes resolution a search at all), and a large body of content mods
 * depending on them through loose ranges. 400 mods is above the 324 of the ATM10 pack used for
 * launcher validation.
 *
 * <p>The bound is deliberately loose. It is not a performance target; it is a tripwire for
 * exponential blowup, which would blow past any bound rather than nudge it.
 */
class ResolverScaleTest {

    private static final int CONTENT_MODS = 400;
    private static final int LIBRARIES = 24;
    private static final long SEED = 20_260_720L;

    @Test
    @Timeout(30)
    @DisplayName("a 400-mod pack with contended libraries resolves quickly")
    void realisticPackResolvesQuickly() {
        List<ModCandidate> candidates = buildPack();

        long startedAt = System.nanoTime();
        ResolutionResult result = new Resolver().resolve(candidates, GameSide.CLIENT);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertTrue(result.succeeded(),
                () -> "a well-formed pack failed to resolve: " + result.errors());

        List<String> order = result.loadOrderIds();
        assertEquals(order.size(), Set.copyOf(order).size(), "a mod was loaded twice");
        for (int i = 0; i < CONTENT_MODS; i++) {
            String id = "mod" + i;
            assertTrue(order.contains(id), () -> id + " was silently dropped");
        }
        assertTrue(elapsed.toSeconds() < 10,
                () -> "resolution took " + elapsed.toMillis() + "ms, which suggests the search is "
                        + "blowing up rather than converging");

        // Surfaced so a regression shows up as a number in the build log, not just pass/fail.
        System.out.println("[scale] " + candidates.size() + " candidates, "
                + order.size() + " loaded, " + elapsed.toMillis() + "ms");
    }

    @Test
    @Timeout(30)
    @DisplayName("load order still honours every dependency at scale")
    void orderingHoldsAtScale() {
        ResolutionResult result = new Resolver().resolve(buildPack(), GameSide.CLIENT);
        List<String> order = result.loadOrderIds();
        assertTrue(result.succeeded(), () -> result.errors().toString());

        for (ModCandidate candidate : result.loadOrder()) {
            int position = order.indexOf(candidate.id());
            for (Dependency dependency
                    : candidate.metadata().dependenciesOf(Dependency.Kind.DEPENDS)) {
                int dependencyPosition = order.indexOf(dependency.modId());
                if (dependencyPosition >= 0) {
                    assertTrue(dependencyPosition < position,
                            () -> candidate.id() + " is initialised before its dependency "
                                    + dependency.modId());
                }
            }
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("property: whatever the search returns satisfies every declared rule")
    void solutionSatisfiesEveryRule() {
        // ARCHITECTURE §11 asks for this as a property rather than by example: instead of
        // checking one expected answer, check that the answer the resolver chose — whatever it
        // chose — violates nothing. This is what catches a search that finds *an* assignment
        // rather than a *valid* one, which is precisely the defect class that made the
        // symmetric-constraint bug possible.
        ResolutionResult result = new Resolver().resolve(buildPack(), GameSide.CLIENT);
        assertTrue(result.succeeded(), () -> result.errors().toString());

        List<ModCandidate> loaded = result.loadOrder();
        for (ModCandidate candidate : loaded) {
            for (Dependency dependency : candidate.metadata().dependencies()) {
                Optional<ModCandidate> present = loaded.stream()
                        .filter(other -> other.id().equals(dependency.modId()))
                        .findFirst();
                switch (dependency.kind()) {
                    case DEPENDS -> {
                        assertTrue(present.isPresent(),
                                () -> candidate.id() + " loaded without its dependency "
                                        + dependency.modId());
                        assertTrue(dependency.matches(present.orElseThrow().version()),
                                () -> candidate.id() + " requires " + dependency.modId() + " "
                                        + dependency.versions() + " but got "
                                        + present.orElseThrow().version().raw());
                    }
                    case BREAKS, CONFLICTS -> present.ifPresent(other ->
                            assertTrue(!dependency.matches(other.version()),
                                    () -> candidate.id() + " loaded alongside "
                                            + other.id() + " " + other.version().raw()
                                            + ", which it declares incompatible"));
                    default -> { /* recommends/suggests place no obligation on the solution */ }
                }
            }
        }
    }

    /**
     * Builds a pack shaped like a real one: contended libraries plus content mods that need them.
     *
     * <p>Seeded, so a failure here is reproducible rather than a story about one unlucky run.
     */
    private static List<ModCandidate> buildPack() {
        Random random = new Random(SEED);
        List<ModCandidate> candidates = new ArrayList<>();
        ModCandidate host = ModCandidate.topLevel(
                ModMetadata.builder("host", ModVersion.parse("1.0.0")).name("host").build(),
                Path.of("mods", "host.jar"));

        // Libraries: each bundled at two versions, so the resolver has to choose. This is the
        // shape that makes resolution a search instead of a checklist.
        for (int i = 0; i < LIBRARIES; i++) {
            String id = "lib" + i;
            candidates.add(ModCandidate.nested(
                    ModMetadata.builder(id, ModVersion.parse("1.0.0")).name(id).build(),
                    Path.of("nested", id + "-1.jar"), host));
            candidates.add(ModCandidate.nested(
                    ModMetadata.builder(id, ModVersion.parse("2.0.0")).name(id).build(),
                    Path.of("nested", id + "-2.jar"), host));
        }

        for (int i = 0; i < CONTENT_MODS; i++) {
            String id = "mod" + i;
            ModMetadata.Builder builder =
                    ModMetadata.builder(id, ModVersion.parse("1.0.0")).name(id);
            Set<String> wanted = new HashSet<>();
            int count = 1 + random.nextInt(3);
            while (wanted.size() < count) {
                wanted.add("lib" + random.nextInt(LIBRARIES));
            }
            for (String library : wanted) {
                builder.dependency(new Dependency(
                        Dependency.Kind.DEPENDS, library,
                        VersionPredicate.expand(">=1.0").orElseThrow(),
                        Dependency.LoadOrder.NONE, Optional.empty()));
            }
            // Roughly one mod in eight is not yet updated for its library's 2.x line. This is
            // what real packs look like, and it is what forces the search to actually back
            // out of its newest-first first choice instead of walking straight through.
            if (random.nextInt(8) == 0) {
                String stubborn = wanted.iterator().next();
                builder.dependency(new Dependency(
                        Dependency.Kind.BREAKS, stubborn,
                        VersionPredicate.expand(">=2.0").orElseThrow(),
                        Dependency.LoadOrder.NONE, Optional.empty()));
            }
            candidates.add(ModCandidate.topLevel(builder.build(), Path.of("mods", id + ".jar")));
        }
        return candidates;
    }
}
