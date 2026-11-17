package io.github.mjhaugsdal.architecture.generator;

import io.github.mjhaugsdal.architecture.model.ArchitectureGraph;
import io.github.mjhaugsdal.architecture.model.ArchitectureModule;
import io.github.mjhaugsdal.architecture.model.ExternalDependency;
import io.github.mjhaugsdal.architecture.model.ModuleDependency;
import io.github.mjhaugsdal.architecture.model.ModuleId;
import io.github.mjhaugsdal.architecture.model.ModuleRelationship;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Converts a Maven-independent architecture graph to a Mermaid flowchart. */
public final class MermaidGenerator {
    public String generate(ArchitectureGraph graph, boolean includeHierarchy, boolean includeDependencies) {
        StringBuilder output = new StringBuilder("flowchart TD\n");
        Map<ModuleId, String> nodes = new HashMap<>();
        Map<String, Integer> labelCounts = new HashMap<>();
        for (ArchitectureModule module : graph.modules()) {
            String label = displayName(module);
            labelCounts.merge(label, 1, Integer::sum);
        }
        int index = 0;
        for (ArchitectureModule module : graph.modules()) {
            String node = "n" + index++;
            nodes.put(module.id(), node);
            String label = displayName(module);
            if (labelCounts.get(label) > 1) {
                label += " (" + module.id().groupId() + ":" + module.id().artifactId() + ":" + module.id().version() + ")";
            }
            output.append("    ").append(node).append("[\"").append(escape(label)).append("\"]\n");
        }
        Map<String, String> externalNodes = new TreeMap<>();
        if (includeDependencies) {
            for (ExternalDependency dependency : graph.externalDependencies()) {
                if (!externalNodes.containsKey(dependency.label())) {
                    externalNodes.put(dependency.label(), "x" + externalNodes.size());
                }
            }
        }
        for (Map.Entry<String, String> externalNode : externalNodes.entrySet()) {
            output.append("    ").append(externalNode.getValue()).append("([\"")
                    .append(escape(externalNode.getKey())).append("\"])\n");
        }
        if (includeHierarchy) {
            for (ModuleRelationship relationship : graph.relationships()) {
                output.append("    ").append(nodes.get(relationship.from()));
                if (relationship.type() == ModuleRelationship.Type.INHERITANCE) {
                    output.append(" -->|inherits| ");
                } else {
                    output.append(" --> ");
                }
                output.append(nodes.get(relationship.to())).append('\n');
            }
        }
        if (includeDependencies) {
            Set<String> edges = new HashSet<>();
            for (ModuleDependency dependency : graph.dependencies()) {
                String edge = "    " + nodes.get(dependency.from()) + " -.->|" + escape(dependency.scope()) + "| "
                        + nodes.get(dependency.to()) + "\n";
                if (edges.add(edge)) {
                    output.append(edge);
                }
            }
            for (ExternalDependency dependency : graph.externalDependencies()) {
                String edge = "    " + nodes.get(dependency.from()) + " -.->|" + escape(dependency.scope()) + "| "
                        + externalNodes.get(dependency.label()) + "\n";
                if (edges.add(edge)) {
                    output.append(edge);
                }
            }
        }
        return output.toString();
    }

    static String displayName(ArchitectureModule module) {
        return module.name() == null || module.name().isBlank() ? module.id().artifactId() : module.name();
    }

    static String escape(String label) {
        StringBuilder escaped = new StringBuilder();
        label.codePoints().forEach(codePoint -> {
            if (codePoint < 32 || codePoint == 127) {
                escaped.append(' ');
            } else if (codePoint == '"' || codePoint == '#' || codePoint == '&'
                    || codePoint == '<' || codePoint == '>' || codePoint == '[' || codePoint == ']'
                    || codePoint == '`' || codePoint == '\\') {
                escaped.append('#').append(codePoint).append(';');
            } else {
                escaped.appendCodePoint(codePoint);
            }
        });
        return escaped.toString();
    }
}
