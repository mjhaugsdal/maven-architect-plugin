package io.github.mjhaugsdal.architecture.generator;

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

/** Converts a Maven-independent architecture graph to a Mermaid flowchart. */
public final class MermaidGenerator {
    public String generate(ArchitectureGraph graph, boolean includeHierarchy, boolean includeDependencies) {
        return generate(graph, includeHierarchy, includeDependencies, Map.of());
    }

    public String generate(ArchitectureGraph graph, boolean includeHierarchy, boolean includeDependencies,
            Map<ModuleId, String> groups) {
        StringBuilder output = new StringBuilder("flowchart TD\n");
        Map<ModuleId, String> nodes = new HashMap<>();
        Map<ModuleId, String> declarations = new LinkedHashMap<>();
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
            declarations.put(module.id(), node + "[\"" + escape(label) + "\"]\n");
        }
        appendNodes(output, declarations, groups, "    ", "G");
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
                String edge = "    " + nodes.get(dependency.from()) + " -.-> " + nodes.get(dependency.to()) + "\n";
                if (edges.add(edge)) {
                    output.append(edge);
                }
            }
        }
        return output.toString();
    }

    static void appendNodes(StringBuilder output, Map<ModuleId, String> declarations,
            Map<ModuleId, String> groups, String indent, String groupPrefix) {
        Map<String, List<String>> grouped = new TreeMap<>();
        for (Map.Entry<ModuleId, String> entry : declarations.entrySet()) {
            String label = groups.get(entry.getKey());
            if (label == null) {
                output.append(indent).append(entry.getValue());
            } else {
                grouped.computeIfAbsent(label, key -> new ArrayList<>()).add(entry.getValue());
            }
        }
        int index = 0;
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            output.append(indent).append("subgraph ").append(groupPrefix).append(index++)
                    .append("[\"").append(escape(entry.getKey())).append("\"]\n");
            for (String declaration : entry.getValue()) {
                output.append(indent).append("    ").append(declaration);
            }
            output.append(indent).append("end\n");
        }
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
