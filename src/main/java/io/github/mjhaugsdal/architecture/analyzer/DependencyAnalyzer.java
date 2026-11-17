package io.github.mjhaugsdal.architecture.analyzer;

import io.github.mjhaugsdal.architecture.model.ArchitectureGraph;
import io.github.mjhaugsdal.architecture.model.ArchitectureModule;
import io.github.mjhaugsdal.architecture.model.ModuleDependency;
import io.github.mjhaugsdal.architecture.model.ModuleId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Analyzes directed reactor dependencies without changing the graph's direct edges. */
public final class DependencyAnalyzer {
    /** Maximum number of distinct simple paths retained for any source and target pair. */
    public static final int MAX_PATHS_PER_PAIR = 100;

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
        List<ModuleId> path = new ArrayList<>();
        Set<ModuleId> inPath = new HashSet<>();
        path.add(source);
        inPath.add(source);
        collectPaths(source, target, adjacency, ancestors, path, inPath, paths);
        return paths;
    }

    private void collectPaths(ModuleId current, ModuleId target, Map<ModuleId, Set<ModuleId>> adjacency,
            Set<ModuleId> ancestors, List<ModuleId> path, Set<ModuleId> inPath,
            List<List<ModuleId>> paths) {
        if (current.equals(target)) {
            paths.add(List.copyOf(path));
            return;
        }
        for (ModuleId next : adjacency.get(current)) {
            if (paths.size() >= MAX_PATHS_PER_PAIR) {
                return;
            }
            if (ancestors.contains(next) && inPath.add(next)) {
                path.add(next);
                collectPaths(next, target, adjacency, ancestors, path, inPath, paths);
                path.remove(path.size() - 1);
                inPath.remove(next);
            }
        }
    }
}
