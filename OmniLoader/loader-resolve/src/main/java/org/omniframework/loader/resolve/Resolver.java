package org.omniframework.loader.resolve;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.diag.OmniIssue;
import org.omniframework.loader.model.Dependency;
import org.omniframework.loader.model.ModVersion;
import org.omniframework.loader.model.ProvidedMod;
import org.omniframework.loader.provider.GameSide;

/**
 * Decides which mods load, and in what order (LDR-5).
 *
 * <h2>Why this is a search and not a checklist</h2>
 *
 * <p>With one jar per mod, resolution is just validation. Nested jars break that: two mods each
 * bundling a different version of the same library means the loader must <em>choose</em>, and a
 * choice made for one library can invalidate a choice made for another. That is a constraint
 * satisfaction problem, so this is a backtracking search — assign a candidate to an id, propagate
 * the consequences, undo and try the next on conflict.
 *
 * <h2>Why the search is bespoke rather than a SAT library</h2>
 *
 * <p>Both loaders in the corpus reach for Sat4j, and for pure satisfiability that is the right
 * call. But LDR-5 does not ask only for an answer; it asks for "unsat-core → human-quality error
 * rendering", and that is where a generic solver is weakest. A SAT core is a set of clauses; a
 * user needs a sentence. Searching directly means every failure is caught at the point where the
 * loader knows what it was trying to do, so the message can say "Create needs Flywheel 1.0 or
 * newer, but the copy bundled in Valkyrien Skies is 0.6" instead of naming variable indices.
 *
 * <p>The search is deliberately simple: candidates are tried newest-first, and the common cases
 * (a missing dependency, a version mismatch, an explicit conflict) are detected before search
 * begins so their messages are exact. Search exists for the genuinely ambiguous remainder.
 */
public final class Resolver {

    private final List<ResolverRule> rules;

    /** A resolver with no ecosystem rules — native Omni mods only. */
    public Resolver() {
        this(List.of());
    }

    /** A resolver with ecosystem rule plugins (LDR-5's rule SPI). */
    public Resolver(List<ResolverRule> rules) {
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
    }

    /**
     * Resolves a candidate set for one side.
     *
     * @param candidates every discovered candidate, in discovery order
     * @param side which program is launching; candidates for the other side are excluded
     * @return an ordered load set, or a failure carrying the reason
     */
    public ResolutionResult resolve(List<ModCandidate> candidates, GameSide side) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(side, "side");
        IssueCollector issues = new IssueCollector();

        List<ModCandidate> eligible = applyEnvironmentAndRules(candidates, side, issues);
        Map<String, List<ModCandidate>> byId = groupById(eligible);

        // Roots: every top-level mod the user put in the folder is expected to load. Nested
        // candidates are only pulled in when something needs them, which is the whole point of
        // bundling — a mod's private library should not become a mod in its own right.
        Set<String> roots = new LinkedHashSet<>();
        eligible.stream().filter(candidate -> !candidate.isNested()).map(ModCandidate::id).forEach(roots::add);
        for (ResolverRule rule : rules) {
            roots.addAll(rule.additionalRequirements(eligible));
        }

        Map<String, List<ModCandidate>> providers = providerIndex(eligible);

        Optional<List<OmniIssue>> obvious = explainObviousFailures(roots, byId, eligible);
        if (obvious.isPresent()) {
            List<OmniIssue> all = new ArrayList<>(issues.issues());
            all.addAll(obvious.get());
            return ResolutionResult.failure(all);
        }

        // Greedy only for large packs: full backtracking is exponential and hangs on BMC3
        // (1000+ loadable Fabric modules). Search remains for small graphs and tests.
        Optional<Map<String, ModCandidate>> selection;
        if (eligible.size() > 200) {
            GreedyResult greedy = greedySelectDetailed(roots, byId, providers);
            if (greedy.selection().isEmpty()) {
                List<OmniIssue> all = new ArrayList<>(issues.issues());
                all.addAll(explainGreedyFailure(greedy, byId, providers, eligible));
                return ResolutionResult.failure(all);
            }
            selection = greedy.selection();
        } else {
            selection = greedySelect(roots, byId, providers);
            if (selection.isEmpty()) {
                selection = search(roots, byId, providers);
            }
            if (selection.isEmpty()) {
                List<OmniIssue> all = new ArrayList<>(issues.issues());
                all.add(renderUnsatisfiable(unsatisfiableCore(roots, byId, providers)));
                return ResolutionResult.failure(all);
            }
        }

        // One candidate can occupy several ids at once (its own, plus everything it provides),
        // so the same mod appears under more than one key. Deduplicate before ordering: a mod
        // loads once regardless of how many names it answers to.
        List<ModCandidate> selected = new ArrayList<>(new LinkedHashSet<>(selection.get().values()));
        LoadOrder ordering = new LoadOrder(selected);
        Optional<List<ModCandidate>> ordered = ordering.sort(issues);
        if (ordered.isEmpty()) {
            return ResolutionResult.failure(issues.issues());
        }
        warnAboutMissingRecommendations(ordered.get(), selection.get(), issues);
        return ResolutionResult.success(ordered.get(), issues.issues());
    }

    // ------------------------------------------------------------- filtering

    private List<ModCandidate> applyEnvironmentAndRules(
            List<ModCandidate> candidates, GameSide side, IssueCollector issues) {
        List<ModCandidate> kept = new ArrayList<>();
        for (ModCandidate candidate : candidates) {
            // Side filtering happens before anything else: a client-only mod on a server is not
            // a resolution failure, it simply is not part of this launch (LDR-9).
            if (!candidate.metadata().loadsOn(side.asEnvironment())) {
                continue;
            }
            boolean accepted = true;
            for (ResolverRule rule : rules) {
                if (!rule.filter(candidate, issues)) {
                    accepted = false;
                    break;
                }
            }
            if (accepted) {
                kept.add(candidate);
            }
        }
        return kept;
    }

    /** Groups candidates by the id they occupy, newest first. */
    private static Map<String, List<ModCandidate>> groupById(List<ModCandidate> candidates) {
        Map<String, List<ModCandidate>> byId = new LinkedHashMap<>();
        for (ModCandidate candidate : candidates) {
            byId.computeIfAbsent(candidate.id(), ignored -> new ArrayList<>()).add(candidate);
            // Fabric uses hyphens; NeoForge often uses underscores for the same mod
            // (c2me-base vs c2me_base). Index both forms so deps cross the ecosystems.
            String alt = alternateSeparatorId(candidate.id());
            if (!alt.equals(candidate.id())) {
                byId.computeIfAbsent(alt, ignored -> new ArrayList<>()).add(candidate);
            }
        }
        byId.values().forEach(list -> list.sort(newestFirst()));
        return byId;
    }

    /**
     * Swaps {@code _} and {@code -} so Fabric-style and NeoForge-style ids alias each other.
     */
    static String alternateSeparatorId(String id) {
        if (id.indexOf('_') >= 0) {
            return id.replace('_', '-');
        }
        if (id.indexOf('-') >= 0) {
            return id.replace('-', '_');
        }
        return id;
    }

    /**
     * Newest first, with top-level candidates beating nested ones at equal version.
     *
     * <p>The tie-break matters: when a user has installed a library themselves and a mod also
     * bundles it, preferring the user's copy respects a decision they made deliberately.
     */
    private static Comparator<ModCandidate> newestFirst() {
        return Comparator.<ModCandidate, ModVersion>comparing(ModCandidate::version, versionOrder())
                .reversed()
                .thenComparing(ModCandidate::isNested);
    }

    /** Semantic versions order properly; anything else keeps a stable, arbitrary order. */
    private static Comparator<ModVersion> versionOrder() {
        return (left, right) -> {
            if (left.semantic().isPresent() && right.semantic().isPresent()) {
                return left.semantic().get().compareTo(right.semantic().get());
            }
            if (left.semantic().isPresent()) {
                return 1; // a comparable version is preferred over one we cannot order
            }
            if (right.semantic().isPresent()) {
                return -1;
            }
            return left.raw().compareTo(right.raw());
        };
    }

    // ---------------------------------------------------------- explanations

    /**
     * Catches the failures that account for nearly every real report, before search begins.
     *
     * <p>This exists purely for message quality. Search can prove these unsatisfiable too, but
     * only as "no assignment exists" — and a user reading that learns nothing. Detecting them
     * here means the message names the mod, the requirement, and what is actually installed.
     */
    private Optional<List<OmniIssue>> explainObviousFailures(
            Set<String> roots,
            Map<String, List<ModCandidate>> byId,
            List<ModCandidate> eligible) {
        List<OmniIssue> failures = new ArrayList<>();
        Map<String, List<ModCandidate>> providers = providerIndex(eligible);

        for (String root : roots) {
            if (!byId.containsKey(root) && !providers.containsKey(root)) {
                failures.add(OmniIssue.error("resolve.required-mod-absent")
                        .at(root)
                        .with(root)
                        .remedy("A rule required " + root + ", but no installed mod provides it.")
                        .build());
            }
        }

        for (ModCandidate candidate : eligible) {
            if (candidate.isNested()) {
                continue; // nested candidates only matter once something selects them
            }
            for (Dependency dependency : candidate.metadata().dependenciesOf(Dependency.Kind.DEPENDS)) {
                List<ModCandidate> available = candidatesFor(dependency.modId(), byId, providers);
                if (available.isEmpty()) {
                    failures.add(OmniIssue.error("resolve.dependency-missing")
                            .at(candidate.id())
                            .with(candidate.metadata().displayName(), dependency.modId())
                            .remedy(candidate.metadata().displayName() + " requires "
                                    + dependency.modId() + ", which is not installed."
                                    + dependency.reason().map(why -> " (" + why + ")").orElse(""))
                            .build());
                    continue;
                }
                boolean anyMatches = available.stream()
                        .anyMatch(option -> satisfies(option, dependency));
                if (!anyMatches) {
                    String installed = available.stream()
                            .map(option -> option.version().raw())
                            .distinct()
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("none");
                    failures.add(OmniIssue.error("resolve.dependency-version")
                            .at(candidate.id())
                            .with(candidate.metadata().displayName(), dependency.modId(),
                                    dependency.versions().raw(), installed)
                            .remedy(candidate.metadata().displayName() + " requires "
                                    + dependency.modId() + " " + dependency.versions()
                                    + ", but the installed version is " + installed + ".")
                            .build());
                }
            }
        }
        return failures.isEmpty() ? Optional.empty() : Optional.of(failures);
    }

    /** Warns about absent {@code recommends} — never fatal, but worth saying once. */
    private static void warnAboutMissingRecommendations(
            List<ModCandidate> selected, Map<String, ModCandidate> selection, IssueCollector issues) {
        for (ModCandidate candidate : selected) {
            for (Dependency recommended
                    : candidate.metadata().dependenciesOf(Dependency.Kind.RECOMMENDS)) {
                ModCandidate present = selection.get(recommended.modId());
                if (present == null || !recommended.matches(present.version())) {
                    issues.add(OmniIssue.warning("resolve.recommendation-unmet")
                            .at(candidate.id())
                            .with(candidate.metadata().displayName(), recommended.modId())
                            .remedy(candidate.metadata().displayName() + " recommends "
                                    + recommended.modId() + " " + recommended.versions()
                                    + ", which is not loaded. It will work without it."
                                    + recommended.reason().map(why -> " (" + why + ")").orElse(""))
                            .build());
                }
            }
        }
    }

    // ------------------------------------------------------------ unsat core

    /**
     * Narrows a failure to the mods that are actually in conflict (LDR-5's "unsat core").
     *
     * <p>"These mods cannot all load together" is a true statement and a useless one — with 300
     * mods installed it tells the user nothing they can act on. This shrinks the failure to a
     * <em>minimal</em> set: every mod named is genuinely part of the conflict, and removing any
     * one of them lets the rest load.
     *
     * <p>The method is deletion-based, the standard way to reach a minimal unsatisfiable subset:
     * try each mod in turn, and if the remainder is <em>still</em> unresolvable then that mod was
     * a bystander and is dropped for good. What survives is irreducible. This costs one re-solve
     * per root, which is affordable precisely because it only runs on the failure path.
     */
    private List<String> unsatisfiableCore(
            Set<String> roots,
            Map<String, List<ModCandidate>> byId,
            Map<String, List<ModCandidate>> providers) {
        List<String> core = new ArrayList<>(new java.util.TreeSet<>(roots));
        for (String suspect : List.copyOf(core)) {
            if (core.size() <= 1) {
                break; // a single mod cannot be narrowed further
            }
            List<String> without = new ArrayList<>(core);
            without.remove(suspect);
            if (search(new LinkedHashSet<>(without), byId, providers).isEmpty()) {
                core = without; // still broken without it, so it was never the problem
            }
        }
        return core;
    }

    /** Turns a core into a sentence that names names. */
    private static OmniIssue renderUnsatisfiable(List<String> core) {
        if (core.size() == 1) {
            return OmniIssue.error("resolve.unsatisfiable")
                    .at(core.get(0))
                    .with(core.get(0))
                    .remedy(core.get(0) + " cannot load: its own requirements contradict each "
                            + "other, so no combination of the installed versions satisfies it.")
                    .build();
        }
        String names = String.join(" and ", core);
        return OmniIssue.error("resolve.unsatisfiable")
                .at(String.join(", ", core))
                .with(names)
                .remedy(names + " cannot load together: no combination of the installed versions "
                        + "satisfies all of them at once. Removing or updating any one of them "
                        + "lets the rest load.")
                .build();
    }

    // --------------------------------------------------------------- search

    /**
     * Assigns a candidate to every required id, backtracking on conflict.
     *
     * <p>Ids are processed in a stable order and candidates newest-first, so the same inputs
     * always produce the same selection — a resolver that picks differently between runs turns
     * "it worked yesterday" into an unanswerable bug report.
     */
    private record GreedyResult(
            Optional<Map<String, ModCandidate>> selection, String failedId, List<ModCandidate> options) {}

    private Optional<Map<String, ModCandidate>> greedySelect(
            Set<String> roots,
            Map<String, List<ModCandidate>> byId,
            Map<String, List<ModCandidate>> providers) {
        return greedySelectDetailed(roots, byId, providers).selection();
    }

    /**
     * Newest-first greedy assignment (no backtracking). Sufficient when each id has a single
     * viable candidate — the common case for expanded Fabric multi-module packs.
     */
    private GreedyResult greedySelectDetailed(
            Set<String> roots,
            Map<String, List<ModCandidate>> byId,
            Map<String, List<ModCandidate>> providers) {
        Map<String, ModCandidate> selection = new LinkedHashMap<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(new java.util.TreeSet<>(roots));
        java.util.HashSet<String> enqueued = new java.util.HashSet<>(roots);
        while (!queue.isEmpty()) {
            String id = queue.removeFirst();
            if (alreadyOccupied(selection, id)) {
                continue;
            }
            List<ModCandidate> options = candidatesFor(id, byId, providers);
            ModCandidate pick = null;
            for (ModCandidate option : options) {
                if (compatibleWithSelection(option, selection)) {
                    pick = option;
                    break;
                }
            }
            if (pick == null) {
                return new GreedyResult(Optional.empty(), id, options);
            }
            for (String key : occupyKeys(pick, id)) {
                selection.put(key, pick);
            }
            for (Dependency dependency : pick.metadata().dependenciesOf(Dependency.Kind.DEPENDS)) {
                String depId = dependency.modId();
                if (enqueued.add(depId) && !alreadyOccupied(selection, depId)) {
                    queue.addLast(depId);
                }
            }
        }
        return new GreedyResult(Optional.of(selection), "", List.of());
    }

    /** Typed issues for the first id greedy could not fill. */
    private static List<OmniIssue> explainGreedyFailure(
            GreedyResult greedy,
            Map<String, List<ModCandidate>> byId,
            Map<String, List<ModCandidate>> providers,
            List<ModCandidate> eligible) {
        String failedId = greedy.failedId();
        List<ModCandidate> options = greedy.options();
        if (options.isEmpty()) {
            return List.of(OmniIssue.error("resolve.dependency-missing")
                    .at(failedId)
                    .with(failedId)
                    .remedy("Requires " + failedId + ", which is not installed.")
                    .build());
        }
        // Prefer version-mismatch messages for options that exist but conflict.
        List<OmniIssue> out = new ArrayList<>();
        for (ModCandidate option : options) {
            for (Dependency dependency : option.metadata().dependenciesOf(Dependency.Kind.DEPENDS)) {
                List<ModCandidate> available = candidatesFor(dependency.modId(), byId, providers);
                if (available.isEmpty()) {
                    out.add(OmniIssue.error("resolve.dependency-missing")
                            .at(option.id())
                            .with(option.metadata().displayName(), dependency.modId())
                            .remedy(option.metadata().displayName()
                                    + " requires "
                                    + dependency.modId()
                                    + ", which is not installed.")
                            .build());
                    continue;
                }
                boolean any = available.stream().anyMatch(a -> satisfies(a, dependency));
                if (!any) {
                    String installed = available.stream()
                            .map(a -> a.version().raw())
                            .distinct()
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("?");
                    out.add(OmniIssue.error("resolve.dependency-version")
                            .at(option.id())
                            .with(
                                    option.metadata().displayName(),
                                    dependency.modId(),
                                    dependency.versions().toString(),
                                    installed)
                            .remedy(option.metadata().displayName()
                                    + " requires "
                                    + dependency.modId()
                                    + " "
                                    + dependency.versions()
                                    + ", but the installed version is "
                                    + installed
                                    + ".")
                            .build());
                }
            }
        }
        if (out.isEmpty()) {
            out.add(OmniIssue.error("resolve.unsatisfiable")
                    .at(failedId)
                    .with(failedId)
                    .remedy("No installed version of "
                            + failedId
                            + " is compatible with the rest of the selection.")
                    .build());
        }
        return out;
    }

    private Optional<Map<String, ModCandidate>> search(
            Set<String> roots,
            Map<String, List<ModCandidate>> byId,
            Map<String, List<ModCandidate>> providers) {
        Map<String, ModCandidate> selection = new LinkedHashMap<>();
        List<String> queue = new ArrayList<>(new java.util.TreeSet<>(roots));
        return assign(queue, 0, selection, byId, providers) ? Optional.of(selection) : Optional.empty();
    }

    private boolean assign(
            List<String> queue,
            int index,
            Map<String, ModCandidate> selection,
            Map<String, List<ModCandidate>> byId,
            Map<String, List<ModCandidate>> providers) {
        if (index == queue.size()) {
            return true;
        }
        String id = queue.get(index);
        // Skip ids already covered by a selected candidate (own id, _/- alias, or provides).
        // Without this, Fabric multi-module packs re-assign the same providers for every
        // provided id and the search tree explodes to multi-minute hangs on BMC3-scale packs.
        if (alreadyOccupied(selection, id)) {
            return assign(queue, index + 1, selection, byId, providers);
        }
        for (ModCandidate option : candidatesFor(id, byId, providers)) {
            if (!compatibleWithSelection(option, selection)) {
                continue;
            }
            List<String> occupiedKeys = occupyKeys(option, id);
            for (String key : occupiedKeys) {
                selection.put(key, option);
            }
            List<String> extended = new ArrayList<>(queue);
            for (Dependency dependency
                    : option.metadata().dependenciesOf(Dependency.Kind.DEPENDS)) {
                if (!extended.contains(dependency.modId())
                        && !alreadyOccupied(selection, dependency.modId())) {
                    extended.add(dependency.modId());
                }
            }
            if (assign(extended, index + 1, selection, byId, providers)) {
                return true;
            }
            for (String key : occupiedKeys) {
                selection.remove(key);
            }
        }
        return false;
    }

    /** Whether some selected candidate already fills {@code id} (including alias/provides). */
    private static boolean alreadyOccupied(Map<String, ModCandidate> selection, String id) {
        if (selection.containsKey(id)) {
            return true;
        }
        String alt = alternateSeparatorId(id);
        if (!alt.equals(id) && selection.containsKey(alt)) {
            return true;
        }
        // Keys only — do not scan every selection value (O(n²) thrash on large packs).
        return false;
    }

    /**
     * Keys under which {@code option} should be recorded so later queue entries for the same
     * identity (alias or provide) short-circuit.
     */
    private static List<String> occupyKeys(ModCandidate option, String requestedId) {
        List<String> keys = new ArrayList<>();
        keys.add(requestedId);
        if (!keys.contains(option.id())) {
            keys.add(option.id());
        }
        String alt = alternateSeparatorId(option.id());
        if (!alt.equals(option.id()) && !keys.contains(alt)) {
            keys.add(alt);
        }
        for (ProvidedMod provided : option.metadata().provides()) {
            if (!keys.contains(provided.id())) {
                keys.add(provided.id());
            }
            String pAlt = alternateSeparatorId(provided.id());
            if (!pAlt.equals(provided.id()) && !keys.contains(pAlt)) {
                keys.add(pAlt);
            }
        }
        return keys;
    }

    /** Candidates that occupy an id, either directly or by providing it. */
    private static List<ModCandidate> candidatesOccupying(
            String id, Map<String, List<ModCandidate>> byId) {
        // Direct occupancy only — providers are merged in candidatesFor via providerIndex.
        // Scanning every byId list for provides() here made Fabric multi-module packs (BMC3)
        // spend minutes per assign step.
        List<ModCandidate> direct = new ArrayList<>(byId.getOrDefault(id, List.of()));
        String alt = alternateSeparatorId(id);
        if (!alt.equals(id)) {
            for (ModCandidate c : byId.getOrDefault(alt, List.of())) {
                if (!direct.contains(c)) {
                    direct.add(c);
                }
            }
        }
        return direct;
    }

    /**
     * Whether adding {@code option} keeps the current selection consistent: every dependency
     * already satisfiable, and no {@code breaks}/{@code conflicts} violated in either direction.
     */
    private static boolean compatibleWithSelection(
            ModCandidate option, Map<String, ModCandidate> selection) {
        for (Dependency dependency : option.metadata().dependencies()) {
            ModCandidate present = selection.get(dependency.modId());
            if (present == null) {
                continue;
            }
            boolean matches = satisfies(present, dependency);
            if (dependency.kind().isNegative() && matches) {
                return false;
            }
            if (dependency.kind() == Dependency.Kind.DEPENDS && !matches) {
                return false;
            }
        }
        // Constraints are symmetric, and both directions have to be checked. Missing the
        // positive direction is not a missed optimisation: the search would accept a candidate
        // that violates an already-selected mod's requirement and report success, leaving the
        // user with a mod set that fails at runtime for no visible reason.
        for (ModCandidate present : selection.values()) {
            for (Dependency dependency : present.metadata().dependencies()) {
                if (!occupies(option, dependency.modId())) {
                    continue;
                }
                boolean matches = satisfies(option, dependency);
                if (dependency.kind().isNegative() && matches) {
                    return false;
                }
                if (dependency.kind() == Dependency.Kind.DEPENDS && !matches) {
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------- matching

    /** Whether a candidate satisfies a dependency's id and version range, provides included. */
    private static boolean satisfies(ModCandidate candidate, Dependency dependency) {
        if (idsMatch(candidate.id(), dependency.modId())) {
            return matchesVersion(dependency, candidate.version(), candidate.id());
        }
        for (ProvidedMod provided : candidate.metadata().provides()) {
            if (idsMatch(provided.id(), dependency.modId())
                    && matchesVersion(dependency, provided.version(), dependency.modId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Version match with Omni host capability rules for platform identities.
     *
     * <p>Stock loaders install an exact NeoForge/Forge build. Omni hosts a capability line
     * ({@code 21.1.999}, {@code 52.0.999}). Exact pins like {@code neoforge 21.1.216} must
     * therefore mean "at least this version" against the host identity, not binary equality.
     * Minecraft exact {@code 1.21} accepts any {@code 1.21.x} patch (common pack authoring).
     */
    private static boolean matchesVersion(
            Dependency dependency, ModVersion installed, String occupiedId) {
        if (dependency.matches(installed)) {
            return true;
        }
        if (!isPlatformHostId(occupiedId) && !"minecraft".equals(dependency.modId())) {
            return false;
        }
        return matchesPlatformCapability(dependency, installed, occupiedId);
    }

    private static boolean isPlatformHostId(String id) {
        return "neoforge".equals(id)
                || "forge".equals(id)
                || "fabricloader".equals(id)
                || "java".equals(id)
                || "omniloader".equals(id)
                || "minecraft".equals(id);
    }

    private static boolean matchesPlatformCapability(
            Dependency dependency, ModVersion installed, String occupiedId) {
        if (installed.semantic().isEmpty()) {
            return false;
        }
        var semantic = installed.semantic().get();
        var terms = dependency.versions().terms();
        // Minecraft: exact 1.21 accepts 1.21.1 (patch-tolerant equality on two-component pins).
        if ("minecraft".equals(dependency.modId()) || "minecraft".equals(occupiedId)) {
            boolean onlyEqual = terms.stream()
                    .allMatch(t -> t.operator() == org.omniframework.loader.model.VersionPredicate.Operator.EQUAL
                            || t.operator() == org.omniframework.loader.model.VersionPredicate.Operator.ANY);
            if (onlyEqual) {
                for (var term : terms) {
                    if (term.operator() == org.omniframework.loader.model.VersionPredicate.Operator.ANY) {
                        continue;
                    }
                    if (term.bound().isEmpty()) {
                        if (!term.rawBound().equals(installed.raw())) {
                            return false;
                        }
                        continue;
                    }
                    var bound = term.bound().get();
                    if (bound.major() != semantic.major() || bound.minor() != semantic.minor()) {
                        return false;
                    }
                    // If the pin specified a non-zero patch, require exact patch; else any patch.
                    if (bound.patch() != 0 && bound.patch() != semantic.patch()) {
                        return false;
                    }
                }
                return true;
            }
        }
        // Loader hosts: EQUAL means >= (capability, not bit-identical install).
        if (isPlatformHostId(occupiedId) && !"minecraft".equals(occupiedId)) {
            // Connector-style Fabric guests pin fabricloader >=999 as a "Connector present"
            // marker. Omni hosts Fabric identity without that sentinel number — accept it.
            if ("fabricloader".equals(occupiedId) || "fabricloader".equals(dependency.modId())) {
                boolean connectorMarker = terms.stream().anyMatch(t -> t.bound()
                        .map(b -> b.major() >= 999)
                        .orElse(false));
                if (connectorMarker) {
                    return true;
                }
            }
            for (var term : terms) {
                if (term.operator() == org.omniframework.loader.model.VersionPredicate.Operator.ANY) {
                    continue;
                }
                if (term.bound().isEmpty()) {
                    return false;
                }
                int cmp = semantic.compareTo(term.bound().get());
                boolean ok = switch (term.operator()) {
                    case EQUAL, GREATER_EQUAL -> cmp >= 0;
                    case GREATER -> cmp > 0;
                    case LESS -> cmp < 0;
                    case LESS_EQUAL -> cmp <= 0;
                    case ANY -> true;
                };
                if (!ok) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /** Whether a candidate occupies an id, directly or by providing it. */
    private static boolean occupies(ModCandidate candidate, String id) {
        if (idsMatch(candidate.id(), id)) {
            return true;
        }
        return candidate.metadata().provides().stream()
                .anyMatch(provided -> idsMatch(provided.id(), id));
    }

    /** Id equality with Fabric/NeoForge separator aliasing ({@code _} ↔ {@code -}). */
    static boolean idsMatch(String left, String right) {
        if (left.equals(right)) {
            return true;
        }
        return left.replace('_', '-').equals(right.replace('_', '-'));
    }

    /** Candidates available for an id, direct entries first, then providers. */
    private static List<ModCandidate> candidatesFor(
            String id,
            Map<String, List<ModCandidate>> byId,
            Map<String, List<ModCandidate>> providers) {
        List<ModCandidate> all = new ArrayList<>(byId.getOrDefault(id, List.of()));
        providers.getOrDefault(id, List.of()).stream()
                .filter(candidate -> !all.contains(candidate))
                .forEach(all::add);
        return all;
    }

    /** id → candidates that claim to provide it. */
    private static Map<String, List<ModCandidate>> providerIndex(List<ModCandidate> candidates) {
        Map<String, List<ModCandidate>> index = new TreeMap<>();
        for (ModCandidate candidate : candidates) {
            for (ProvidedMod provided : candidate.metadata().provides()) {
                index.computeIfAbsent(provided.id(), ignored -> new ArrayList<>()).add(candidate);
                String alt = alternateSeparatorId(provided.id());
                if (!alt.equals(provided.id())) {
                    index.computeIfAbsent(alt, ignored -> new ArrayList<>()).add(candidate);
                }
            }
        }
        return index;
    }
}
