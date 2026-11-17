package io.github.mjhaugsdal.architecture.analyzer;

import io.github.mjhaugsdal.architecture.model.ModuleId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Finds an ordered, closed dependency cycle in a module graph. */
public final class DependencyCycleFinder {
    public Optional<List<ModuleId>> findCycle(Map<ModuleId, ? extends Set<ModuleId>> dependencies) {
        Map<ModuleId, Integer> states = new HashMap<>();
        List<ModuleId> path = new ArrayList<>();
        for (ModuleId module : new TreeSet<>(dependencies.keySet())) {
            List<ModuleId> cycle = visit(module, dependencies, states, path);
            if (cycle != null) {
                return Optional.of(cycle);
            }
        }
        return Optional.empty();
    }

    private List<ModuleId> visit(ModuleId module, Map<ModuleId, ? extends Set<ModuleId>> dependencies,
            Map<ModuleId, Integer> states, List<ModuleId> path) {
        Integer state = states.get(module);
        if (state != null && state == 2) {
            return null;
        }
        if (state != null && state == 1) {
            int cycleStart = path.indexOf(module);
            List<ModuleId> cycle = new ArrayList<>(path.subList(cycleStart, path.size()));
            cycle.add(module);
            return cycle;
        }

        states.put(module, 1);
        path.add(module);
        Set<ModuleId> upstream = dependencies.get(module);
        if (upstream != null) {
            for (ModuleId dependency : new TreeSet<>(upstream)) {
                if (dependencies.containsKey(dependency)) {
                    List<ModuleId> cycle = visit(dependency, dependencies, states, path);
                    if (cycle != null) {
                        return cycle;
                    }
                }
            }
        }
        path.remove(path.size() - 1);
        states.put(module, 2);
        return null;
    }
}
