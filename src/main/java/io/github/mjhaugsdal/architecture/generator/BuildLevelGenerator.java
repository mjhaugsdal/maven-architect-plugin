package io.github.mjhaugsdal.architecture.generator;

import io.github.mjhaugsdal.architecture.analyzer.DependencyCycleFinder;
import io.github.mjhaugsdal.architecture.model.ArchitectureGraph;
import io.github.mjhaugsdal.architecture.model.ArchitectureModule;
import io.github.mjhaugsdal.architecture.model.ModuleDependency;
import io.github.mjhaugsdal.architecture.model.ModuleId;
import io.github.mjhaugsdal.architecture.model.ModuleRelationship;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Renders a simplified Mermaid diagram that groups reactor modules into build levels. Modules in the same level
 * do not depend on each other and can be built in parallel; each level only needs the levels above it.
 */
public final class BuildLevelGenerator {
    public String generate(ArchitectureGraph graph) {
        Map<ModuleId, ArchitectureModule> modules = new LinkedHashMap<>();
        Set<ModuleId> connected = new HashSet<>();
        for (ModuleDependency dependency : graph.dependencies()) {
            if (!dependency.from().equals(dependency.to())) {
                connected.add(dependency.from());
                connected.add(dependency.to());
            }
        }
        Set<ModuleId> aggregators = new HashSet<>();
        for (ModuleRelationship relationship : graph.relationships()) {
            if (relationship.type() == ModuleRelationship.Type.AGGREGATION) {
                aggregators.add(relationship.from());
            }
        }
        for (ArchitectureModule module : graph.modules()) {
            if (!aggregators.contains(module.id()) || connected.contains(module.id())) {
                modules.put(module.id(), module);
            }
        }

        Map<ModuleId, Set<ModuleId>> upstream = new HashMap<>();
        for (ModuleId id : modules.keySet()) {
            upstream.put(id, new HashSet<>());
        }
        for (ModuleDependency dependency : graph.dependencies()) {
            if (!dependency.from().equals(dependency.to()) && modules.containsKey(dependency.from())
                    && modules.containsKey(dependency.to())) {
                upstream.get(dependency.from()).add(dependency.to());
            }
        }

        new DependencyCycleFinder().findCycle(upstream).ifPresent(cycle -> {
            String path = cycle.stream()
                    .map(id -> id.groupId() + ":" + id.artifactId() + ":" + id.version())
                    .collect(java.util.stream.Collectors.joining(" -> "));
            throw new IllegalArgumentException("Dependency cycle detected: " + path);
        });

        Map<ModuleId, Integer> levels = new HashMap<>();
        for (ModuleId id : modules.keySet()) {
            level(id, upstream, levels);
        }
        Map<Integer, List<ArchitectureModule>> byLevel = new TreeMap<>();
        for (ArchitectureModule module : modules.values()) {
            byLevel.computeIfAbsent(levels.get(module.id()), key -> new ArrayList<>()).add(module);
        }

        Map<String, Integer> labelCounts = new HashMap<>();
        for (ArchitectureModule module : modules.values()) {
            labelCounts.merge(MermaidGenerator.displayName(module), 1, Integer::sum);
        }
        StringBuilder output = new StringBuilder("flowchart TD\n");
        int node = 0;
        for (Map.Entry<Integer, List<ArchitectureModule>> entry : byLevel.entrySet()) {
            int level = entry.getKey();
            output.append("    subgraph L").append(level).append("[\"Level ").append(level).append("\"]\n");
            output.append("        direction LR\n");
            for (ArchitectureModule module : entry.getValue()) {
                String label = MermaidGenerator.displayName(module);
                if (labelCounts.get(label) > 1) {
                    label += " (" + module.id().groupId() + ":" + module.id().artifactId() + ":"
                            + module.id().version() + ")";
                }
                output.append("        n").append(node++).append("[\"")
                        .append(MermaidGenerator.escape(label)).append("\"]\n");
            }
            output.append("    end\n");
        }
        for (int level = 1; level < byLevel.size(); level++) {
            output.append("    L").append(level - 1).append(" --> L").append(level).append('\n');
        }
        return output.toString();
    }

    private int level(ModuleId id, Map<ModuleId, Set<ModuleId>> upstream, Map<ModuleId, Integer> levels) {
        Integer known = levels.get(id);
        if (known != null) {
            return known;
        }
        int level = 0;
        for (ModuleId dependency : upstream.get(id)) {
            level = Math.max(level, level(dependency, upstream, levels) + 1);
        }
        levels.put(id, level);
        return level;
    }
}
