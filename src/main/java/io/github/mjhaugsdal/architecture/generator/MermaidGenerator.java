package io.github.mjhaugsdal.architecture.generator;

import io.github.mjhaugsdal.architecture.model.ArchitectureGraph;
import io.github.mjhaugsdal.architecture.model.ArchitectureModule;
import io.github.mjhaugsdal.architecture.model.ModuleDependency;
import io.github.mjhaugsdal.architecture.model.ModuleId;
import io.github.mjhaugsdal.architecture.model.ModuleRelationship;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
        if (includeDependencies) {
            for (ModuleId module : graph.externalModules()) {
                labelCounts.merge(externalDisplayName(module), 1, Integer::sum);
            }
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
        if (includeDependencies) {
            for (ModuleId module : graph.externalModules()) {
                String node = "n" + index++;
                nodes.put(module, node);
                String label = externalDisplayName(module);
                if (labelCounts.get(label) > 1) {
                    label += " (" + module.groupId() + ":" + module.artifactId() + ":" + module.version() + ")";
                }
                output.append("    ").append(node).append("[\"").append(escape(label)).append("\"]\n");
            }
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
                String edge = "    " + nodes.get(dependency.from()) + " -.-> " + nodes.get(dependency.to()) + "\n";
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

    private static String externalDisplayName(ModuleId module) {
        return module.groupId() + ":" + module.artifactId() + ":" + module.version();
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
