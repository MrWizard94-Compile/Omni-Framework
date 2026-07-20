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

        Optional<List<OmniIssue>> obvious = explainObviousFailures(roots, byId, eligible);
        if (obvious.isPresent()) {
            List<OmniIssue> all = new ArrayList<>(issues.issues());
            all.addAll(obvious.get());
            return ResolutionResult.failure(all);
        }

        Optional<Map<String, ModCandidate>> selection = search(roots, byId);
        if (selection.isEmpty()) {
            List<OmniIssue> all = new ArrayList<>(issues.issues());
            all.add(renderUnsatisfiable(unsatisfiableCore(roots, byId)));
            return ResolutionResult.failure(all);
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
        }
        byId.values().forEach(list -> list.sort(newestFirst()));
        return byId;
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
    private List<String> unsatisfiableCore(Set<String> roots, Map<String, List<ModCandidate>> byId) {
        List<String> core = new ArrayList<>(new java.util.TreeSet<>(roots));
        for (String suspect : List.copyOf(core)) {
            if (core.size() <= 1) {
                break; // a single mod cannot be narrowed further
            }
            List<String> without = new ArrayList<>(core);
            without.remove(suspect);
            if (search(new LinkedHashSet<>(without), byId).isEmpty()) {
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
    private Optional<Map<String, ModCandidate>> search(
            Set<String> roots, Map<String, List<ModCandidate>> byId) {
        Map<String, ModCandidate> selection = new LinkedHashMap<>();
        List<String> queue = new ArrayList<>(new java.util.TreeSet<>(roots));
        return assign(queue, 0, selection, byId) ? Optional.of(selection) : Optional.empty();
    }

    private boolean assign(
            List<String> queue,
            int index,
            Map<String, ModCandidate> selection,
            Map<String, List<ModCandidate>> byId) {
        if (index == queue.size()) {
            return true;
        }
        String id = queue.get(index);
        if (selection.containsKey(id)) {
            return assign(queue, index + 1, selection, byId);
        }
        for (ModCandidate option : candidatesOccupying(id, byId)) {
            if (!compatibleWithSelection(option, selection)) {
                continue;
            }
            selection.put(id, option);
            List<String> extended = new ArrayList<>(queue);
            for (Dependency dependency
                    : option.metadata().dependenciesOf(Dependency.Kind.DEPENDS)) {
                if (!extended.contains(dependency.modId())) {
                    extended.add(dependency.modId());
                }
            }
            if (assign(extended, index + 1, selection, byId)) {
                return true;
            }
            selection.remove(id);
        }
        return false;
    }

    /** Candidates that occupy an id, either directly or by providing it. */
    private static List<ModCandidate> candidatesOccupying(
            String id, Map<String, List<ModCandidate>> byId) {
        List<ModCandidate> direct = byId.getOrDefault(id, List.of());
        List<ModCandidate> viaProvides = byId.values().stream()
                .flatMap(List::stream)
                .filter(candidate -> candidate.metadata().provides().stream()
                        .anyMatch(provided -> provided.id().equals(id)))
                .toList();
        if (viaProvides.isEmpty()) {
            return direct;
        }
        List<ModCandidate> all = new ArrayList<>(direct);
        viaProvides.stream().filter(candidate -> !all.contains(candidate)).forEach(all::add);
        return all;
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
        if (candidate.id().equals(dependency.modId())) {
            return dependency.matches(candidate.version());
        }
        for (ProvidedMod provided : candidate.metadata().provides()) {
            if (provided.id().equals(dependency.modId()) && dependency.matches(provided.version())) {
                return true;
            }
        }
        return false;
    }

    /** Whether a candidate occupies an id, directly or by providing it. */
    private static boolean occupies(ModCandidate candidate, String id) {
        return candidate.id().equals(id)
                || candidate.metadata().provides().stream()
                        .anyMatch(provided -> provided.id().equals(id));
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
            }
        }
        return index;
    }
}
