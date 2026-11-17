package io.github.mjhaugsdal.architecture.analyzer;

import io.github.mjhaugsdal.architecture.model.ArchitectureGraph;
import io.github.mjhaugsdal.architecture.model.ModuleDependency;
import io.github.mjhaugsdal.architecture.model.ModuleId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Computes module dependency and coupling metrics from a reactor architecture graph. */
public final class ArchitectureAnalyzer {
    /**
     * Analyzes unique directed module dependency pairs. Multiple dependency declarations between the same
     * modules count as one edge, regardless of scope.
     */
    public ArchitectureAnalysis analyze(ArchitectureGraph graph) {
        Map<ModuleId, Set<ModuleId>> dependencies = new TreeMap<>();
        Map<ModuleId, Set<ModuleId>> dependents = new TreeMap<>();
        for (var module : graph.modules()) {
            dependencies.put(module.id(), new TreeSet<>());
            dependents.put(module.id(), new TreeSet<>());
        }
        for (ModuleDependency dependency : graph.dependencies()) {
            dependencies.get(dependency.from()).add(dependency.to());
            dependents.get(dependency.to()).add(dependency.from());
        }

        Map<ModuleId, Integer> depths = dependencyDepths(dependencies);
        Map<ModuleId, ModuleMetrics> metrics = new TreeMap<>();
        for (ModuleId id : dependencies.keySet()) {
            int fanIn = dependents.get(id).size();
            int fanOut = dependencies.get(id).size();
            metrics.put(id, new ModuleMetrics(id, fanIn, fanOut, fanIn + fanOut, depths.get(id)));
        }
        int edgeCount = dependencies.values().stream().mapToInt(Set::size).sum();
        return new ArchitectureAnalysis(metrics.size(), edgeCount, metrics);
    }

    private Map<ModuleId, Integer> dependencyDepths(Map<ModuleId, Set<ModuleId>> dependencies) {
        Map<ModuleId, Set<ModuleId>> reverse = new TreeMap<>();
        dependencies.keySet().forEach(id -> reverse.put(id, new TreeSet<>()));
        dependencies.forEach((source, targets) ->
                targets.forEach(target -> reverse.get(target).add(source)));

        List<ModuleId> finishOrder = finishOrder(dependencies);
        Map<ModuleId, Integer> componentByModule = new HashMap<>();
        List<Set<ModuleId>> components = new ArrayList<>();
        for (int i = finishOrder.size() - 1; i >= 0; i--) {
            ModuleId start = finishOrder.get(i);
            if (componentByModule.containsKey(start)) {
                continue;
            }
            int component = components.size();
            Set<ModuleId> members = new TreeSet<>();
            Deque<ModuleId> pending = new ArrayDeque<>();
            pending.push(start);
            componentByModule.put(start, component);
            while (!pending.isEmpty()) {
                ModuleId current = pending.pop();
                members.add(current);
                for (ModuleId neighbor : reverse.get(current)) {
                    if (componentByModule.putIfAbsent(neighbor, component) == null) {
                        pending.push(neighbor);
                    }
                }
            }
            components.add(members);
        }

        List<Set<Integer>> componentDependencies = new ArrayList<>();
        List<Set<Integer>> componentDependents = new ArrayList<>();
        int[] remainingDependencies = new int[components.size()];
        int[] componentDepths = new int[components.size()];
        for (int i = 0; i < components.size(); i++) {
            componentDependencies.add(new HashSet<>());
            componentDependents.add(new HashSet<>());
        }
        dependencies.forEach((source, targets) -> {
            int sourceComponent = componentByModule.get(source);
            for (ModuleId target : targets) {
                int targetComponent = componentByModule.get(target);
                if (sourceComponent != targetComponent
                        && componentDependencies.get(sourceComponent).add(targetComponent)) {
                    componentDependents.get(targetComponent).add(sourceComponent);
                    remainingDependencies[sourceComponent]++;
                }
            }
        });

        Deque<Integer> ready = new ArrayDeque<>();
        for (int i = 0; i < remainingDependencies.length; i++) {
            if (remainingDependencies[i] == 0) {
                ready.add(i);
            }
        }
        while (!ready.isEmpty()) {
            int dependency = ready.remove();
            for (int dependent : componentDependents.get(dependency)) {
                componentDepths[dependent] = Math.max(componentDepths[dependent], componentDepths[dependency] + 1);
                if (--remainingDependencies[dependent] == 0) {
                    ready.add(dependent);
                }
            }
        }

        Map<ModuleId, Integer> depths = new TreeMap<>();
        componentByModule.forEach((module, component) -> depths.put(module, componentDepths[component]));
        return depths;
    }

    private List<ModuleId> finishOrder(Map<ModuleId, Set<ModuleId>> dependencies) {
        Set<ModuleId> visited = new HashSet<>();
        List<ModuleId> order = new ArrayList<>(dependencies.size());
        for (ModuleId start : dependencies.keySet()) {
            if (!visited.add(start)) {
                continue;
            }
            Deque<Visit> pending = new ArrayDeque<>();
            pending.push(new Visit(start, dependencies.get(start).iterator()));
            while (!pending.isEmpty()) {
                Visit visit = pending.peek();
                if (visit.neighbors().hasNext()) {
                    ModuleId neighbor = visit.neighbors().next();
                    if (visited.add(neighbor)) {
                        pending.push(new Visit(neighbor, dependencies.get(neighbor).iterator()));
                    }
                } else {
                    order.add(pending.pop().module());
                }
            }
        }
        return order;
    }

    private record Visit(ModuleId module, java.util.Iterator<ModuleId> neighbors) {}
}
