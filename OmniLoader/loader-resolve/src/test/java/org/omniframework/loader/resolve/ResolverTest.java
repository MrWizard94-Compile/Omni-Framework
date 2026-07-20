package org.omniframework.loader.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.diag.OmniIssue;
import org.omniframework.loader.model.Dependency;
import org.omniframework.loader.model.Environment;
import org.omniframework.loader.model.ModMetadata;
import org.omniframework.loader.model.ModVersion;
import org.omniframework.loader.model.ProvidedMod;
import org.omniframework.loader.model.VersionPredicate;
import org.omniframework.loader.provider.GameSide;

/**
 * Resolution: what loads, in what order, and — the part that matters most — what the user is told
 * when nothing can.
 */
class ResolverTest {

    // ------------------------------------------------------------- fixtures

    private static ModMetadata.Builder mod(String id, String version) {
        return ModMetadata.builder(id, ModVersion.parse(version)).name(id);
    }

    private static Dependency depends(String id, String range) {
        return new Dependency(
                Dependency.Kind.DEPENDS, id, VersionPredicate.expand(range).orElseThrow(),
                Dependency.LoadOrder.NONE, Optional.empty());
    }

    private static Dependency depends(String id, String range, Dependency.LoadOrder order) {
        return new Dependency(
                Dependency.Kind.DEPENDS, id, VersionPredicate.expand(range).orElseThrow(),
                order, Optional.empty());
    }

    private static Dependency breaks(String id, String range) {
        return new Dependency(
                Dependency.Kind.BREAKS, id, VersionPredicate.expand(range).orElseThrow(),
                Dependency.LoadOrder.NONE, Optional.empty());
    }

    private static ModCandidate top(ModMetadata metadata) {
        return ModCandidate.topLevel(metadata, Path.of("mods", metadata.id() + ".jar"));
    }

    private static ModCandidate nested(ModMetadata metadata, ModCandidate parent) {
        return ModCandidate.nested(metadata, Path.of("nested", metadata.id() + ".jar"), parent);
    }

    private static ResolutionResult resolve(ModCandidate... candidates) {
        return new Resolver().resolve(List.of(candidates), GameSide.CLIENT);
    }

    private static String codesOf(ResolutionResult result) {
        return result.issues().stream().map(OmniIssue::code).toList().toString();
    }

    // ---------------------------------------------------------- happy paths

    @Test
    @DisplayName("independent mods all load")
    void independentModsLoad() {
        ResolutionResult result = resolve(top(mod("alpha", "1.0.0").build()),
                top(mod("beta", "1.0.0").build()));

        assertTrue(result.succeeded(), () -> codesOf(result));
        assertEquals(List.of("alpha", "beta"), result.loadOrderIds());
    }

    @Test
    @DisplayName("a satisfied dependency resolves and orders the dependency first")
    void dependenciesLoadFirst() {
        ResolutionResult result = resolve(
                top(mod("app", "1.0.0").dependency(depends("lib", ">=1.0")).build()),
                top(mod("lib", "1.2.0").build()));

        assertTrue(result.succeeded(), () -> codesOf(result));
        assertEquals(List.of("lib", "app"), result.loadOrderIds(),
                "a mod is initialised after what it depends on");
    }

    @Test
    @DisplayName("a mod can be satisfied by another mod's provides")
    void providesSatisfiesADependency() {
        ResolutionResult result = resolve(
                top(mod("app", "1.0.0").dependency(depends("oldlib", ">=1.0")).build()),
                top(mod("newlib", "2.0.0")
                        .provides(new ProvidedMod("oldlib", ModVersion.parse("1.5.0")))
                        .build()));

        assertTrue(result.succeeded(), () -> codesOf(result));
        assertTrue(result.loadOrderIds().contains("newlib"));
        assertFalse(result.loadOrderIds().contains("oldlib"), "nothing invents a mod that provides");
    }

    @Test
    @DisplayName("the newest candidate wins when several versions are available")
    void newestVersionWins() {
        ModCandidate host = top(mod("host", "1.0.0").build());
        ResolutionResult result = resolve(
                top(mod("app", "1.0.0").dependency(depends("lib", ">=1.0")).build()),
                nested(mod("lib", "1.1.0").build(), host),
                nested(mod("lib", "1.4.0").build(), host));

        assertTrue(result.succeeded(), () -> codesOf(result));
        assertEquals("1.4.0", result.selected("lib").orElseThrow().version().raw());
    }

    @Test
    @DisplayName("a user-installed copy beats a bundled one at the same version")
    void topLevelBeatsNestedAtEqualVersion() {
        ModCandidate host = top(mod("host", "1.0.0").build());
        ResolutionResult result = resolve(
                top(mod("app", "1.0.0").dependency(depends("lib", "1.0.0")).build()),
                nested(mod("lib", "1.0.0").build(), host),
                top(mod("lib", "1.0.0").build()));

        assertTrue(result.succeeded(), () -> codesOf(result));
        assertFalse(result.selected("lib").orElseThrow().isNested(),
                "a copy the user installed deliberately should win");
    }

    @Test
    @DisplayName("mods for the other side are excluded, not failed")
    void otherSideIsExcluded() {
        ResolutionResult result = new Resolver().resolve(
                List.of(top(mod("clientonly", "1.0.0").environment(Environment.CLIENT).build()),
                        top(mod("both", "1.0.0").build())),
                GameSide.SERVER);

        assertTrue(result.succeeded(), () -> codesOf(result));
        assertEquals(List.of("both"), result.loadOrderIds(),
                "a client mod on a server is simply not part of this launch");
    }

    // ------------------------------------------------------ failure quality

    @Nested
    @DisplayName("failures explain themselves")
    class Failures {

        @Test
        @DisplayName("a missing dependency names both mods")
        void missingDependency() {
            ResolutionResult result = resolve(
                    top(mod("app", "1.0.0").dependency(depends("lib", ">=1.0")).build()));

            assertFalse(result.succeeded());
            OmniIssue issue = result.errors().get(0);
            assertEquals("resolve.dependency-missing", issue.code());
            assertTrue(issue.remedy().contains("app"), issue::describe);
            assertTrue(issue.remedy().contains("lib"), issue::describe);
        }

        @Test
        @DisplayName("a version mismatch says what is required AND what is installed")
        void versionMismatch() {
            ResolutionResult result = resolve(
                    top(mod("app", "1.0.0").dependency(depends("lib", ">=2.0")).build()),
                    top(mod("lib", "1.5.0").build()));

            assertFalse(result.succeeded());
            OmniIssue issue = result.errors().get(0);
            assertEquals("resolve.dependency-version", issue.code());
            // Both halves are needed: knowing only the requirement leaves the user guessing
            // what they actually have.
            assertTrue(issue.remedy().contains(">=2.0"), issue::describe);
            assertTrue(issue.remedy().contains("1.5.0"), issue::describe);
        }

        @Test
        @DisplayName("a dependency's stated reason is carried into the message")
        void reasonIsSurfaced() {
            Dependency withReason = new Dependency(
                    Dependency.Kind.DEPENDS, "lib",
                    VersionPredicate.any(), Dependency.LoadOrder.NONE,
                    Optional.of("used for world generation"));
            ResolutionResult result =
                    resolve(top(mod("app", "1.0.0").dependency(withReason).build()));

            assertTrue(result.errors().get(0).remedy().contains("used for world generation"),
                    () -> result.errors().get(0).describe());
        }

        @Test
        @DisplayName("mods that break each other cannot both load")
        void breakingModsConflict() {
            ResolutionResult result = resolve(
                    top(mod("alpha", "1.0.0").dependency(breaks("beta", "*")).build()),
                    top(mod("beta", "1.0.0").build()));

            assertFalse(result.succeeded(), () -> "both loaded despite a breaks: "
                    + result.loadOrderIds());
            assertTrue(codesOf(result).contains("resolve.unsatisfiable"), () -> codesOf(result));
        }

        @Test
        @DisplayName("a breaks that does not match the installed version is not a conflict")
        void breaksIsVersionScoped() {
            ResolutionResult result = resolve(
                    top(mod("alpha", "1.0.0").dependency(breaks("beta", "<1.0")).build()),
                    top(mod("beta", "2.0.0").build()));

            assertTrue(result.succeeded(), () -> codesOf(result));
        }

        @Test
        @DisplayName("an unsatisfiable set names the mods in conflict and spares the bystanders")
        void unsatisfiableSetIsNarrowedToACore() {
            // app needs lib 2.x, plugin forbids lib 2.x, and 2.0 is the only lib there is.
            // `innocent` is installed but uninvolved — naming it would send the user to
            // uninstall the wrong mod, which is worse than saying nothing.
            ModCandidate host = top(mod("host", "1.0.0").build());
            ResolutionResult result = resolve(
                    top(mod("app", "1.0.0").dependency(depends("lib", ">=2.0")).build()),
                    top(mod("plugin", "1.0.0").dependency(breaks("lib", ">=2.0")).build()),
                    top(mod("innocent", "1.0.0").build()),
                    nested(mod("lib", "2.0.0").build(), host));

            assertFalse(result.succeeded());
            OmniIssue issue = result.errors().get(0);
            assertEquals("resolve.unsatisfiable", issue.code());
            assertTrue(issue.remedy().contains("app"), issue::describe);
            assertTrue(issue.remedy().contains("plugin"), issue::describe);
            assertFalse(issue.remedy().contains("innocent"),
                    () -> "an uninvolved mod was blamed: " + issue.describe());
        }

        @Test
        @DisplayName("an unmet recommendation warns but still loads")
        void recommendationIsNotFatal() {
            Dependency recommended = new Dependency(
                    Dependency.Kind.RECOMMENDS, "extras", VersionPredicate.any(),
                    Dependency.LoadOrder.NONE, Optional.empty());
            ResolutionResult result =
                    resolve(top(mod("app", "1.0.0").dependency(recommended).build()));

            assertTrue(result.succeeded(), () -> codesOf(result));
            assertTrue(codesOf(result).contains("resolve.recommendation-unmet"), () -> codesOf(result));
        }
    }

    // -------------------------------------------------------------- ordering

    @Nested
    @DisplayName("load order")
    class Ordering {

        @Test
        @DisplayName("explicit AFTER is honoured")
        void explicitAfter() {
            ResolutionResult result = resolve(
                    top(mod("late", "1.0.0")
                            .dependency(depends("early", "*", Dependency.LoadOrder.AFTER))
                            .build()),
                    top(mod("early", "1.0.0").build()));

            assertEquals(List.of("early", "late"), result.loadOrderIds());
        }

        @Test
        @DisplayName("explicit BEFORE is honoured")
        void explicitBefore() {
            ResolutionResult result = resolve(
                    top(mod("first", "1.0.0")
                            .dependency(depends("second", "*", Dependency.LoadOrder.BEFORE))
                            .build()),
                    top(mod("second", "1.0.0").build()));

            assertEquals(List.of("first", "second"), result.loadOrderIds());
        }

        @Test
        @DisplayName("contradictory explicit ordering fails and names the loop")
        void explicitCycleFails() {
            ResolutionResult result = resolve(
                    top(mod("alpha", "1.0.0")
                            .dependency(depends("beta", "*", Dependency.LoadOrder.AFTER))
                            .build()),
                    top(mod("beta", "1.0.0")
                            .dependency(depends("alpha", "*", Dependency.LoadOrder.AFTER))
                            .build()));

            assertFalse(result.succeeded());
            OmniIssue issue = result.errors().get(0);
            assertEquals("resolve.ordering-cycle", issue.code());
            assertTrue(issue.location().contains("alpha"), issue::describe);
            assertTrue(issue.location().contains("beta"), issue::describe);
        }

        @Test
        @DisplayName("mutual dependencies still load — the implied order is relaxed, not obeyed")
        void mutualDependencyIsNotACycle() {
            // This is the case that makes implied edges soft. Both mods require the other's
            // presence; neither said anything about order. Treating requirement as ordering
            // would refuse a perfectly legal mod set.
            ResolutionResult result = resolve(
                    top(mod("alpha", "1.0.0").dependency(depends("beta", "*")).build()),
                    top(mod("beta", "1.0.0").dependency(depends("alpha", "*")).build()));

            assertTrue(result.succeeded(), () -> codesOf(result));
            assertEquals(2, result.loadOrder().size());
            assertTrue(codesOf(result).contains("resolve.ordering-relaxed"),
                    () -> "the user should be told the order is not guaranteed: " + codesOf(result));
        }

        @Test
        @DisplayName("order is deterministic across runs")
        void orderIsDeterministic() {
            List<ModCandidate> candidates = List.of(
                    top(mod("zeta", "1.0.0").dependency(depends("core", "*")).build()),
                    top(mod("alpha", "1.0.0").dependency(depends("core", "*")).build()),
                    top(mod("core", "1.0.0").build()));

            List<String> first = new Resolver().resolve(candidates, GameSide.CLIENT).loadOrderIds();
            for (int run = 0; run < 5; run++) {
                assertEquals(first, new Resolver().resolve(candidates, GameSide.CLIENT).loadOrderIds(),
                        "a load order that varies between runs makes bugs unreproducible");
            }
            assertEquals("core", first.get(0), "the dependency still goes first");
        }

        @Test
        @DisplayName("a chain of dependencies orders transitively")
        void transitiveOrdering() {
            ResolutionResult result = resolve(
                    top(mod("top", "1.0.0").dependency(depends("middle", "*")).build()),
                    top(mod("middle", "1.0.0").dependency(depends("bottom", "*")).build()),
                    top(mod("bottom", "1.0.0").build()));

            assertEquals(List.of("bottom", "middle", "top"), result.loadOrderIds());
        }
    }

    // ------------------------------------------------------------- rule SPI

    @Nested
    @DisplayName("ecosystem rules")
    class Rules {

        @Test
        @DisplayName("a rule can reject a candidate with its own explanation")
        void ruleCanReject() {
            ResolverRule rejectFabric = new ResolverRule() {
                @Override
                public String name() {
                    return "test-fabric-rule";
                }

                @Override
                public boolean filter(ModCandidate candidate, IssueCollector issues) {
                    if ("fabric".equals(candidate.metadata().extension("ecosystem").orElse(null))) {
                        issues.add(OmniIssue.warning("compat.layer-absent")
                                .at(candidate.id())
                                .remedy("This Fabric mod needs the Fabric compat layer.")
                                .build());
                        return false;
                    }
                    return true;
                }
            };

            ResolutionResult result = new Resolver(List.of(rejectFabric)).resolve(
                    List.of(top(mod("guest", "1.0.0").extension("ecosystem", "fabric").build()),
                            top(mod("native", "1.0.0").build())),
                    GameSide.CLIENT);

            assertTrue(result.succeeded(), () -> codesOf(result));
            assertEquals(List.of("native"), result.loadOrderIds());
            assertTrue(codesOf(result).contains("compat.layer-absent"), () -> codesOf(result));
        }

        @Test
        @DisplayName("a rule can require a mod that nothing else asked for")
        void ruleCanRequire() {
            ResolverRule needsApi = new ResolverRule() {
                @Override
                public String name() {
                    return "test-api-rule";
                }

                @Override
                public List<String> additionalRequirements(List<ModCandidate> candidates) {
                    return List.of("required-api");
                }
            };

            // Absent, it is a failure that names the requirement...
            ResolutionResult missing = new Resolver(List.of(needsApi))
                    .resolve(List.of(top(mod("app", "1.0.0").build())), GameSide.CLIENT);
            assertFalse(missing.succeeded());
            assertEquals("resolve.required-mod-absent", missing.errors().get(0).code());

            // ...and present, it loads.
            ResolutionResult present = new Resolver(List.of(needsApi)).resolve(
                    List.of(top(mod("app", "1.0.0").build()),
                            top(mod("required-api", "1.0.0").build())),
                    GameSide.CLIENT);
            assertTrue(present.succeeded(), () -> codesOf(present));
            assertTrue(present.loadOrderIds().contains("required-api"));
        }
    }

    // ---------------------------------------------------------------- search

    @Test
    @DisplayName("rejects a candidate that an installed mod breaks, and takes the older one")
    void skipsABrokenCandidate() {
        // app needs lib >=1.0; the newest lib is 2.0, but plugin breaks lib 2.x, so the
        // resolver must pass over its first-choice candidate.
        ModCandidate host = top(mod("host", "1.0.0").build());
        ResolutionResult result = resolve(
                top(mod("app", "1.0.0").dependency(depends("lib", ">=1.0")).build()),
                top(mod("plugin", "1.0.0").dependency(breaks("lib", ">=2.0")).build()),
                nested(mod("lib", "2.0.0").build(), host),
                nested(mod("lib", "1.5.0").build(), host));

        assertTrue(result.succeeded(), () -> codesOf(result));
        assertEquals("1.5.0", result.selected("lib").orElseThrow().version().raw(),
                "the resolver had to reject its first choice and try the older one");
    }

    @Test
    @DisplayName("undoes an earlier choice when it is the later one that cannot be satisfied")
    void backtracksAcrossAnEarlierChoice() {
        // The case a checklist cannot handle. Taking the newest libA (2.0) forbids libB 2.0,
        // but plugin requires libB >=2.0 — so the only consistent world is libA 1.0 + libB 2.0,
        // and reaching it means going back and un-choosing libA after libB fails.
        ModCandidate host = top(mod("host", "1.0.0").build());
        ResolutionResult result = resolve(
                top(mod("app", "1.0.0")
                        .dependency(depends("libA", ">=1.0"))
                        .dependency(depends("libB", ">=1.0"))
                        .build()),
                top(mod("plugin", "1.0.0").dependency(depends("libB", ">=2.0")).build()),
                nested(mod("libA", "2.0.0").dependency(breaks("libB", ">=2.0")).build(), host),
                nested(mod("libA", "1.0.0").build(), host),
                nested(mod("libB", "2.0.0").build(), host),
                nested(mod("libB", "1.0.0").build(), host));

        assertTrue(result.succeeded(), () -> codesOf(result));
        assertEquals("1.0.0", result.selected("libA").orElseThrow().version().raw(),
                "libA 2.0 forbids the libB that plugin requires, so it must be abandoned");
        assertEquals("2.0.0", result.selected("libB").orElseThrow().version().raw(),
                "plugin requires libB >=2.0 and that requirement cannot be quietly dropped");
    }

    @Test
    @DisplayName("an empty mod set resolves to an empty load order")
    void emptyIsFine() {
        ResolutionResult result = new Resolver().resolve(List.of(), GameSide.CLIENT);
        assertTrue(result.succeeded());
        assertTrue(result.loadOrder().isEmpty());
    }
}
