package io.github.mjhaugsdal.architecture.analyzer;

import io.github.mjhaugsdal.architecture.model.ArchitectureGraph;
import io.github.mjhaugsdal.architecture.model.ModuleDependency;
import io.github.mjhaugsdal.architecture.model.ModuleId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Finds all cyclic strongly connected components in an architecture graph. */
public final class DependencyCycleAnalyzer {
    private final DependencyPathFinder pathFinder = new DependencyPathFinder();

    public CycleAnalysis analyze(ArchitectureGraph graph) {
        Map<ModuleId, Set<ModuleId>> outgoing = new HashMap<>();
        for (var module : graph.modules()) {
            outgoing.put(module.id(), new TreeSet<>());
        }
        for (ModuleDependency dependency : graph.dependencies()) {
            outgoing.get(dependency.from()).add(dependency.to());
        }

        Map<ModuleId, Integer> indexes = new HashMap<>();
        Map<ModuleId, Integer> lowLinks = new HashMap<>();
        Set<ModuleId> onStack = new HashSet<>();
        List<ModuleId> stack = new ArrayList<>();
        List<DependencyCycle> cycles = new ArrayList<>();
        int[] nextIndex = {0};
        for (ModuleId id : new TreeSet<>(outgoing.keySet())) {
            if (!indexes.containsKey(id)) {
                connect(id, outgoing, indexes, lowLinks, onStack, stack, cycles, nextIndex);
            }
        }
        cycles.sort((first, second) -> first.modules().get(0).compareTo(second.modules().get(0)));
        return new CycleAnalysis(cycles);
    }

    private void connect(ModuleId id, Map<ModuleId, Set<ModuleId>> outgoing,
            Map<ModuleId, Integer> indexes, Map<ModuleId, Integer> lowLinks, Set<ModuleId> onStack,
            List<ModuleId> stack, List<DependencyCycle> cycles, int[] nextIndex) {
        indexes.put(id, nextIndex[0]);
        lowLinks.put(id, nextIndex[0]++);
        stack.add(id);
        onStack.add(id);

        for (ModuleId neighbor : outgoing.get(id)) {
            if (!indexes.containsKey(neighbor)) {
                connect(neighbor, outgoing, indexes, lowLinks, onStack, stack, cycles, nextIndex);
                lowLinks.put(id, Math.min(lowLinks.get(id), lowLinks.get(neighbor)));
            } else if (onStack.contains(neighbor)) {
                lowLinks.put(id, Math.min(lowLinks.get(id), indexes.get(neighbor)));
            }
        }

        if (lowLinks.get(id).equals(indexes.get(id))) {
            Set<ModuleId> component = new TreeSet<>();
            ModuleId member;
            do {
                member = stack.remove(stack.size() - 1);
                onStack.remove(member);
                component.add(member);
            } while (!member.equals(id));
            if (component.size() > 1 || outgoing.get(id).contains(id)) {
                cycles.add(new DependencyCycle(List.copyOf(component), representativePath(component, outgoing)));
            }
        }
    }

    private List<ModuleId> representativePath(Set<ModuleId> component, Map<ModuleId, Set<ModuleId>> outgoing) {
        ModuleId start = component.iterator().next();
        List<ModuleId> cycle = new ArrayList<>();
        cycle.add(start);
        ModuleId current = start;
        for (ModuleId target : component) {
            if (!target.equals(start)) {
                current = appendPath(cycle, outgoing, current, target, component);
            }
        }
        if (component.size() == 1) {
            cycle.add(start);
        } else {
            appendPath(cycle, outgoing, current, start, component);
        }
        return List.copyOf(cycle);
    }

    private ModuleId appendPath(List<ModuleId> cycle, Map<ModuleId, Set<ModuleId>> outgoing, ModuleId from,
            ModuleId to, Set<ModuleId> component) {
        List<ModuleId> path = pathFinder.findPath(outgoing, from, to, component)
                .orElseThrow(() -> new IllegalStateException("A cyclic component must be strongly connected"));
        cycle.addAll(path.subList(1, path.size()));
        return to;
    }
}
