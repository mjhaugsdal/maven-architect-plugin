package io.github.mjhaugsdal.architecture.generator;

import io.github.mjhaugsdal.architecture.analyzer.CycleAnalysis;
import io.github.mjhaugsdal.architecture.model.ArchitectureGraph;
import io.github.mjhaugsdal.architecture.model.ArchitectureModule;
import io.github.mjhaugsdal.architecture.model.ModuleDependency;
import io.github.mjhaugsdal.architecture.model.ModuleId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Renders only cyclic dependency components as a Mermaid flowchart. */
public final class CircularDependencyDiagramGenerator {
    public String generate(ArchitectureGraph graph, CycleAnalysis analysis) {
        Map<ModuleId, Integer> componentByModule = new HashMap<>();
        Set<ModuleId> cyclicModules = new TreeSet<>();
        for (int component = 0; component < analysis.cycles().size(); component++) {
            for (ModuleId id : analysis.cycles().get(component).modules()) {
                componentByModule.put(id, component);
                cyclicModules.add(id);
            }
        }

        Map<ModuleId, ArchitectureModule> modules = new TreeMap<>();
        for (ArchitectureModule module : graph.modules()) {
            if (cyclicModules.contains(module.id())) {
                modules.put(module.id(), module);
            }
        }
        Map<String, Integer> labelCounts = new HashMap<>();
        for (ArchitectureModule module : modules.values()) {
            labelCounts.merge(MermaidGenerator.displayName(module), 1, Integer::sum);
        }
        StringBuilder output = new StringBuilder("flowchart TD\n");
        Map<ModuleId, String> nodes = new HashMap<>();
        int node = 0;
        for (int component = 0; component < analysis.cycles().size(); component++) {
            output.append("    subgraph C").append(component).append("[\"Cycle ").append(component + 1)
                    .append("\"]\n");
            for (ModuleId id : analysis.cycles().get(component).modules()) {
                ArchitectureModule module = modules.get(id);
                String label = MermaidGenerator.displayName(module);
                if (labelCounts.get(label) > 1) {
                    label += " (" + module.id().groupId() + ":" + module.id().artifactId() + ":"
                            + module.id().version() + ")";
                }
                String nodeId = "n" + node++;
                nodes.put(module.id(), nodeId);
                output.append("        ").append(nodeId).append("[\"").append(MermaidGenerator.escape(label))
                        .append("\"]\n");
            }
            output.append("    end\n");
        }
        Set<String> edges = new HashSet<>();
        for (ModuleDependency dependency : graph.dependencies()) {
            Integer component = componentByModule.get(dependency.from());
            if (component != null && component.equals(componentByModule.get(dependency.to()))) {
                String edge = nodes.get(dependency.from()) + " -.-> " + nodes.get(dependency.to());
                if (edges.add(edge)) {
                    output.append("    ").append(edge).append('\n');
                }
            }
        }
        return output.toString();
    }
}
