package io.github.mjhaugsdal.architecture.analyzer;

import io.github.mjhaugsdal.architecture.model.ArchitectureGraph;
import io.github.mjhaugsdal.architecture.model.ModuleDependency;
import io.github.mjhaugsdal.architecture.model.ModuleId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Finds deterministic dependency paths through an architecture graph. */
public final class DependencyPathFinder {
    public Optional<List<ModuleId>> findPath(ArchitectureGraph graph, ModuleId from, ModuleId to) {
        Map<ModuleId, Set<ModuleId>> outgoing = new HashMap<>();
        for (ModuleId id : graph.modules().stream().map(module -> module.id()).toList()) {
            outgoing.put(id, new TreeSet<>());
        }
        for (ModuleDependency dependency : graph.dependencies()) {
            outgoing.get(dependency.from()).add(dependency.to());
        }
        return findPath(outgoing, from, to, outgoing.keySet());
    }

    Optional<List<ModuleId>> findPath(Map<ModuleId, Set<ModuleId>> outgoing, ModuleId from, ModuleId to,
            Set<ModuleId> allowed) {
        if (!allowed.contains(from) || !allowed.contains(to)) {
            return Optional.empty();
        }
        Map<ModuleId, ModuleId> previous = new HashMap<>();
        Set<ModuleId> visited = new HashSet<>();
        ArrayDeque<ModuleId> pending = new ArrayDeque<>();
        visited.add(from);
        pending.add(from);
        while (!pending.isEmpty()) {
            ModuleId current = pending.remove();
            if (current.equals(to)) {
                List<ModuleId> path = new ArrayList<>();
                for (ModuleId id = to; id != null; id = previous.get(id)) {
                    path.add(id);
                    if (id.equals(from)) {
                        break;
                    }
                }
                Collections.reverse(path);
                return Optional.of(List.copyOf(path));
            }
            for (ModuleId next : outgoing.getOrDefault(current, Set.of())) {
                if (allowed.contains(next) && visited.add(next)) {
                    previous.put(next, current);
                    pending.add(next);
                }
            }
        }
        return Optional.empty();
    }
}
