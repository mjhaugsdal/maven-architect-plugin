package io.github.mjhaugsdal.architecture.analyzer;

import io.github.mjhaugsdal.architecture.model.ArchitectureGraph;
import io.github.mjhaugsdal.architecture.model.ArchitectureModule;
import io.github.mjhaugsdal.architecture.model.ModuleDependency;
import io.github.mjhaugsdal.architecture.model.ModuleId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Analyzes directed reactor dependencies without changing the graph's direct edges. */
public final class DependencyAnalyzer {
    /** Maximum number of distinct simple paths retained for any source and target pair. */
    public static final int MAX_PATHS_PER_PAIR = 100;
    /** Maximum number of candidate paths explored for any source and target pair. */
    public static final int MAX_PATH_SEARCH_STATES_PER_PAIR = 10_000;

    public DependencyAnalysis analyze(ArchitectureGraph graph) {
        return analyze(graph, false);
    }

    /**
     * Includes compile and runtime dependencies by default; test dependencies are included only when requested.
     * Provided, system, and other scopes are excluded.
     */
    public DependencyAnalysis analyze(ArchitectureGraph graph, boolean includeTestDependencies) {
        Map<ModuleId, Set<ModuleId>> adjacency = new TreeMap<>();
        for (ArchitectureModule module : graph.modules()) {
            adjacency.put(module.id(), new TreeSet<>());
        }

        Set<ModuleDependency> directDependencies = new TreeSet<>();
        for (ModuleDependency dependency : graph.dependencies()) {
            if (includedScope(dependency.scope(), includeTestDependencies)) {
                directDependencies.add(dependency);
                adjacency.get(dependency.from()).add(dependency.to());
            }
        }

        Map<ModuleId, Set<ModuleId>> reverse = reverseAdjacency(adjacency);
        Map<ModuleId, Set<ModuleId>> reachability = new TreeMap<>();
        Map<ModuleId, Map<ModuleId, List<List<ModuleId>>>> paths = new TreeMap<>();
        Map<ModuleId, Set<ModuleId>> ancestorsByTarget = new TreeMap<>();
        for (ModuleId source : adjacency.keySet()) {
            Set<ModuleId> reachable = reachableFrom(source, adjacency);
            reachability.put(source, reachable);
            Map<ModuleId, List<List<ModuleId>>> sourcePaths = new TreeMap<>();
            for (ModuleId target : reachable) {
                Set<ModuleId> ancestors = ancestorsByTarget.computeIfAbsent(target,
                        ignored -> ancestorsOf(target, reverse));
                sourcePaths.put(target, pathsBetween(source, target, adjacency, ancestors));
            }
            paths.put(source, sourcePaths);
        }
        return new DependencyAnalysis(directDependencies, reachability, paths);
    }

    private boolean includedScope(String scope, boolean includeTestDependencies) {
        return "compile".equals(scope) || "runtime".equals(scope)
                || (includeTestDependencies && "test".equals(scope));
    }

    private Set<ModuleId> reachableFrom(ModuleId source, Map<ModuleId, Set<ModuleId>> adjacency) {
        Set<ModuleId> reachable = new TreeSet<>();
        Deque<ModuleId> pending = new ArrayDeque<>(adjacency.get(source));
        while (!pending.isEmpty()) {
            ModuleId target = pending.removeFirst();
            if (!target.equals(source) && reachable.add(target)) {
                pending.addAll(adjacency.get(target));
            }
        }
        return reachable;
    }

    private Map<ModuleId, Set<ModuleId>> reverseAdjacency(Map<ModuleId, Set<ModuleId>> adjacency) {
        Map<ModuleId, Set<ModuleId>> reverse = new TreeMap<>();
        adjacency.keySet().forEach(id -> reverse.put(id, new TreeSet<>()));
        adjacency.forEach((from, targets) -> targets.forEach(to -> reverse.get(to).add(from)));
        return reverse;
    }

    private Set<ModuleId> ancestorsOf(ModuleId target, Map<ModuleId, Set<ModuleId>> reverse) {
        Set<ModuleId> ancestors = new HashSet<>();
        Deque<ModuleId> pending = new ArrayDeque<>();
        pending.add(target);
        while (!pending.isEmpty()) {
            ModuleId current = pending.removeFirst();
            if (ancestors.add(current)) {
                pending.addAll(reverse.get(current));
            }
        }
        return ancestors;
    }

    private List<List<ModuleId>> pathsBetween(ModuleId source, ModuleId target,
            Map<ModuleId, Set<ModuleId>> adjacency, Set<ModuleId> ancestors) {
        List<List<ModuleId>> paths = new ArrayList<>();
        List<ModuleId> shortestPath = shortestPath(source, target, adjacency, ancestors);
        if (shortestPath == null) {
            return paths;
        }
        paths.add(shortestPath);
        collectAlternatePaths(source, target, adjacency, ancestors, shortestPath, paths);
        return paths;
    }

    private void collectAlternatePaths(ModuleId source, ModuleId target,
            Map<ModuleId, Set<ModuleId>> adjacency, Set<ModuleId> ancestors,
            List<ModuleId> shortestPath, List<List<ModuleId>> paths) {
        int[] exploredStates = {0};
        Set<ModuleId> shortestPrefix = new HashSet<>();
        shortestPrefix.add(source);
        for (int index = 0; index < shortestPath.size() - 1
                && paths.size() < MAX_PATHS_PER_PAIR
                && exploredStates[0] < MAX_PATH_SEARCH_STATES_PER_PAIR; index++) {
            List<ModuleId> prefix = null;
            ModuleId expectedNext = shortestPath.get(index + 1);
            for (ModuleId next : adjacency.get(shortestPath.get(index))) {
                if (paths.size() >= MAX_PATHS_PER_PAIR
                        || exploredStates[0] >= MAX_PATH_SEARCH_STATES_PER_PAIR) {
                    return;
                }
                if (!next.equals(expectedNext) && ancestors.contains(next) && !shortestPrefix.contains(next)) {
                    if (prefix == null) {
                        prefix = new ArrayList<>(shortestPath.subList(0, index + 1));
                    }
                    List<ModuleId> path = new ArrayList<>(prefix);
                    Set<ModuleId> inPath = new HashSet<>(shortestPrefix);
                    exploredStates[0]++;
                    path.add(next);
                    inPath.add(next);
                    if (next.equals(target)) {
                        paths.add(List.copyOf(path));
                    } else {
                        collectPathsFrom(next, target, adjacency, ancestors,
                                path, inPath, paths, exploredStates);
                    }
                    path.remove(path.size() - 1);
                    inPath.remove(next);
                }
            }
            shortestPrefix.add(expectedNext);
        }
    }

    private void collectPathsFrom(ModuleId current, ModuleId target,
            Map<ModuleId, Set<ModuleId>> adjacency, Set<ModuleId> ancestors,
            List<ModuleId> path, Set<ModuleId> inPath, List<List<ModuleId>> paths,
            int[] exploredStates) {
        int startingPathSize = path.size();
        Deque<Iterator<ModuleId>> pending = new ArrayDeque<>();
        pending.push(adjacency.get(current).iterator());
        while (!pending.isEmpty() && paths.size() < MAX_PATHS_PER_PAIR
                && exploredStates[0] < MAX_PATH_SEARCH_STATES_PER_PAIR) {
            Iterator<ModuleId> neighbors = pending.peek();
            if (!neighbors.hasNext()) {
                pending.pop();
                if (path.size() > startingPathSize) {
                    inPath.remove(path.remove(path.size() - 1));
                }
                continue;
            }
            ModuleId next = neighbors.next();
            if (!ancestors.contains(next) || !inPath.add(next)) {
                continue;
            }
            exploredStates[0]++;
            path.add(next);
            if (next.equals(target)) {
                paths.add(List.copyOf(path));
                path.remove(path.size() - 1);
                inPath.remove(next);
            } else {
                pending.push(adjacency.get(next).iterator());
            }
        }
        while (path.size() > startingPathSize) {
            inPath.remove(path.remove(path.size() - 1));
        }
    }

    private List<ModuleId> shortestPath(ModuleId source, ModuleId target,
            Map<ModuleId, Set<ModuleId>> adjacency, Set<ModuleId> ancestors) {
        Map<ModuleId, ModuleId> previous = new TreeMap<>();
        Set<ModuleId> visited = new HashSet<>();
        Deque<ModuleId> pending = new ArrayDeque<>();
        visited.add(source);
        pending.add(source);
        while (!pending.isEmpty()) {
            ModuleId current = pending.removeFirst();
            if (current.equals(target)) {
                List<ModuleId> path = new ArrayList<>();
                for (ModuleId module = target; module != null; module = previous.get(module)) {
                    path.add(module);
                }
                java.util.Collections.reverse(path);
                return path;
            }
            for (ModuleId next : adjacency.get(current)) {
                if (ancestors.contains(next) && visited.add(next)) {
                    previous.put(next, current);
                    pending.addLast(next);
                }
            }
        }
        return null;
    }
}
