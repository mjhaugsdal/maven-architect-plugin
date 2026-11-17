package io.github.mjhaugsdal.architecture.analyzer;

import io.github.mjhaugsdal.architecture.model.ModuleDependency;
import io.github.mjhaugsdal.architecture.model.ModuleId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Direct dependency edges, transitive reachability, and bounded module paths. */
public final class DependencyAnalysis {
    private final Set<ModuleDependency> directDependencies;
    private final Map<ModuleId, Set<ModuleId>> reachableDependencies;
    private final Map<ModuleId, Map<ModuleId, List<List<ModuleId>>>> paths;

    DependencyAnalysis(Set<ModuleDependency> directDependencies,
            Map<ModuleId, Set<ModuleId>> reachableDependencies,
            Map<ModuleId, Map<ModuleId, List<List<ModuleId>>>> paths) {
        this.directDependencies = Collections.unmodifiableSet(new TreeSet<>(directDependencies));
        Map<ModuleId, Set<ModuleId>> reachabilityCopy = new TreeMap<>();
        reachableDependencies.forEach((source, targets) ->
                reachabilityCopy.put(source, Collections.unmodifiableSet(new TreeSet<>(targets))));
        this.reachableDependencies = Collections.unmodifiableMap(reachabilityCopy);

        Map<ModuleId, Map<ModuleId, List<List<ModuleId>>>> pathsCopy = new TreeMap<>();
        paths.forEach((source, targetPaths) -> {
            Map<ModuleId, List<List<ModuleId>>> targetCopy = new TreeMap<>();
            targetPaths.forEach((target, targetPathsList) -> {
                List<List<ModuleId>> pathCopies = new ArrayList<>();
                targetPathsList.forEach(path -> pathCopies.add(List.copyOf(path)));
                targetCopy.put(target, List.copyOf(pathCopies));
            });
            pathsCopy.put(source, Collections.unmodifiableMap(targetCopy));
        });
        this.paths = Collections.unmodifiableMap(pathsCopy);
    }

    /** Returns the selected declared edges without inferring transitive edges. */
    public Set<ModuleDependency> directDependencies() {
        return directDependencies;
    }

    /** Returns each source module's deduplicated reachable targets, excluding the source itself. */
    public Map<ModuleId, Set<ModuleId>> reachableDependencies() {
        return reachableDependencies;
    }

    /** Returns the reported directed paths from {@code from} to {@code to}. */
    public List<List<ModuleId>> paths(ModuleId from, ModuleId to) {
        return paths.getOrDefault(from, Map.of()).getOrDefault(to, List.of());
    }
}
