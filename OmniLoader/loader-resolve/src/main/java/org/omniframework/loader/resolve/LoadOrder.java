package org.omniframework.loader.resolve;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.diag.OmniIssue;
import org.omniframework.loader.model.Dependency;

/**
 * Sorts a selected mod set into the order it should be initialised (LDR-5's ordering half).
 *
 * <h2>Two kinds of edge, and why the difference matters</h2>
 *
 * <p><b>Explicit edges</b> come from a manifest saying {@code "order": "before"} or
 * {@code "after"}. The author asserted a real requirement, so these are <b>hard</b>: a cycle
 * among them is a genuine contradiction and resolution fails, naming the loop.
 *
 * <p><b>Implied edges</b> come from {@code depends}: a mod is normally initialised after the
 * things it depends on, because that is what authors expect and what every loader in the corpus
 * does. But the canonical model deliberately separates requirement from ordering — two mods may
 * legitimately require each other's <em>presence</em> without either caring who goes first — so
 * implying order from requirement would manufacture cycles that are not real. These edges are
 * therefore <b>soft</b>: honoured when they can be, dropped when they would create a cycle.
 *
 * <p>That asymmetry is the whole design. Without it you either lose the sensible default (and
 * mods initialise before their dependencies) or you break mutual dependencies (and legal mod sets
 * refuse to load).
 *
 * <p>The sort is deterministic — ties break by mod id — because a load order that varies between
 * runs produces bugs that appear and vanish for no visible reason.
 */
final class LoadOrder {

    private final List<ModCandidate> selected;
    private final Map<String, ModCandidate> byId;

    LoadOrder(List<ModCandidate> selected) {
        this.selected = List.copyOf(Objects.requireNonNull(selected, "selected"));
        Map<String, ModCandidate> index = new LinkedHashMap<>();
        for (ModCandidate candidate : selected) {
            index.put(candidate.id(), candidate);
        }
        this.byId = index;
    }

    /**
     * Produces the load order, or empty when explicit ordering contradicts itself.
     *
     * @param issues receives a cycle report on failure, and a note when a soft edge was dropped
     */
    Optional<List<ModCandidate>> sort(IssueCollector issues) {
        Objects.requireNonNull(issues, "issues");

        Map<String, Set<String>> hard = new TreeMap<>();
        Map<String, Set<String>> soft = new TreeMap<>();
        for (ModCandidate candidate : selected) {
            hard.computeIfAbsent(candidate.id(), ignored -> new TreeSet<>());
            soft.computeIfAbsent(candidate.id(), ignored -> new TreeSet<>());
        }
        collectEdges(hard, soft);

        // Explicit contradictions are errors, and are reported before anything is dropped —
        // otherwise the cycle would be silently broken and the author never told.
        Optional<List<String>> hardCycle = findCycle(hard);
        if (hardCycle.isPresent()) {
            issues.add(OmniIssue.error("resolve.ordering-cycle")
                    .at(String.join(" → ", hardCycle.get()))
                    .with(String.join(", ", hardCycle.get()))
                    .remedy("These mods each demand to load before the other: "
                            + String.join(" → ", hardCycle.get())
                            + ". One of their manifests has to give way.")
                    .build());
            return Optional.empty();
        }

        Map<String, Set<String>> combined = merge(hard, soft);
        Optional<List<String>> softCycle = findCycle(combined);
        while (softCycle.isPresent()) {
            // Drop one soft edge from the cycle. Which one is arbitrary but deterministic; the
            // requirement is still enforced, only the initialisation hint is relaxed.
            List<String> cycle = softCycle.get();
            boolean dropped = false;
            for (int i = 0; i < cycle.size() - 1 && !dropped; i++) {
                String from = cycle.get(i);
                String to = cycle.get(i + 1);
                if (soft.getOrDefault(from, Set.of()).contains(to)) {
                    soft.get(from).remove(to);
                    issues.add(OmniIssue.warning("resolve.ordering-relaxed")
                            .at(from)
                            .with(from, to)
                            .remedy(from + " and " + to + " depend on each other, so they "
                                    + "cannot both initialise second. Both still load; the "
                                    + "order between them is not guaranteed.")
                            .build());
                    dropped = true;
                }
            }
            if (!dropped) {
                // Every edge in the cycle is hard — already reported above, so this is
                // unreachable in practice. Failing loudly beats looping forever.
                issues.add(OmniIssue.error("resolve.ordering-cycle")
                        .at(String.join(" → ", cycle))
                        .remedy("Mod load order contains a cycle that cannot be resolved.")
                        .build());
                return Optional.empty();
            }
            combined = merge(hard, soft);
            softCycle = findCycle(combined);
        }
        return Optional.of(topologicalSort(combined));
    }

    /** Builds both edge sets from the selected mods' declarations. */
    private void collectEdges(Map<String, Set<String>> hard, Map<String, Set<String>> soft) {
        for (ModCandidate candidate : selected) {
            String id = candidate.id();
            for (Dependency dependency : candidate.metadata().dependencies()) {
                String other = dependency.modId();
                if (!byId.containsKey(other)) {
                    continue; // ordering against something absent is simply moot
                }
                switch (dependency.order()) {
                    case BEFORE -> hard.get(other).add(id); // other loads after this one
                    case AFTER -> hard.get(id).add(other); // this loads after other
                    case NONE -> {
                        if (dependency.kind() == Dependency.Kind.DEPENDS) {
                            soft.get(id).add(other);
                        }
                    }
                }
            }
        }
    }

    private static Map<String, Set<String>> merge(
            Map<String, Set<String>> hard, Map<String, Set<String>> soft) {
        Map<String, Set<String>> combined = new TreeMap<>();
        hard.forEach((id, deps) -> combined.put(id, new TreeSet<>(deps)));
        soft.forEach((id, deps) -> combined.computeIfAbsent(id, ignored -> new TreeSet<>()).addAll(deps));
        return combined;
    }

    /**
     * Finds one cycle, as a path that returns to its start, or empty when the graph is acyclic.
     *
     * <p>Returning the actual path rather than a boolean is what lets the error say
     * "a → b → c → a" instead of "there is a cycle somewhere".
     */
    private static Optional<List<String>> findCycle(Map<String, Set<String>> edges) {
        Set<String> visited = new LinkedHashSet<>();
        Set<String> onPath = new LinkedHashSet<>();
        Deque<String> path = new ArrayDeque<>();
        for (String node : edges.keySet()) {
            Optional<List<String>> cycle = visit(node, edges, visited, onPath, path);
            if (cycle.isPresent()) {
                return cycle;
            }
        }
        return Optional.empty();
    }

    private static Optional<List<String>> visit(
            String node,
            Map<String, Set<String>> edges,
            Set<String> visited,
            Set<String> onPath,
            Deque<String> path) {
        if (onPath.contains(node)) {
            List<String> cycle = new ArrayList<>();
            boolean collecting = false;
            for (String step : path) {
                if (step.equals(node)) {
                    collecting = true;
                }
                if (collecting) {
                    cycle.add(step);
                }
            }
            cycle.add(node);
            return Optional.of(cycle);
        }
        if (!visited.add(node)) {
            return Optional.empty();
        }
        onPath.add(node);
        path.addLast(node);
        for (String next : edges.getOrDefault(node, Set.of())) {
            Optional<List<String>> cycle = visit(next, edges, visited, onPath, path);
            if (cycle.isPresent()) {
                return cycle;
            }
        }
        path.removeLast();
        onPath.remove(node);
        return Optional.empty();
    }

    /** Kahn's algorithm with a sorted ready-set, so equal-priority mods order by id. */
    private List<ModCandidate> topologicalSort(Map<String, Set<String>> edges) {
        Map<String, Integer> remaining = new TreeMap<>();
        Map<String, Set<String>> dependents = new TreeMap<>();
        for (String id : byId.keySet()) {
            remaining.put(id, edges.getOrDefault(id, Set.of()).size());
            dependents.computeIfAbsent(id, ignored -> new TreeSet<>());
        }
        edges.forEach((id, deps) ->
                deps.forEach(dep -> dependents.computeIfAbsent(dep, ignored -> new TreeSet<>()).add(id)));

        TreeSet<String> ready = new TreeSet<>();
        remaining.forEach((id, count) -> {
            if (count == 0) {
                ready.add(id);
            }
        });

        List<ModCandidate> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.pollFirst();
            ordered.add(byId.get(id));
            for (String dependent : dependents.getOrDefault(id, Set.of())) {
                int left = remaining.merge(dependent, -1, Integer::sum);
                if (left == 0) {
                    ready.add(dependent);
                }
            }
        }
        return ordered;
    }
}
