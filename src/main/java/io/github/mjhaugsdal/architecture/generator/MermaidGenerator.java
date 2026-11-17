package io.github.mjhaugsdal.architecture.generator;

import io.github.mjhaugsdal.architecture.model.ArchitectureGraph;
import io.github.mjhaugsdal.architecture.model.ArchitectureModule;
import io.github.mjhaugsdal.architecture.model.ExternalDependency;
import io.github.mjhaugsdal.architecture.model.ModuleDependency;
import io.github.mjhaugsdal.architecture.model.ModuleId;
import io.github.mjhaugsdal.architecture.model.ModuleRelationship;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Converts a Maven-independent architecture graph to a Mermaid flowchart. */
public final class MermaidGenerator {
    public String generate(ArchitectureGraph graph, boolean includeHierarchy, boolean includeDependencies) {
        return generate(graph, includeHierarchy, includeDependencies, false);
    }

    public String generate(
            ArchitectureGraph graph,
            boolean includeHierarchy,
            boolean includeDependencies,
            boolean includeSelectedVersions) {
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
        Map<String, String> externalNodeLabels = new TreeMap<>();
        Map<String, String> externalNodes = new HashMap<>();
        if (includeDependencies) {
            for (ExternalDependency dependency : graph.externalDependencies()) {
                String key = externalNodeKey(dependency);
                externalNodeLabels.putIfAbsent(key, externalNodeLabel(dependency, includeSelectedVersions));
            }
        }
        for (Map.Entry<String, String> externalNode : externalNodeLabels.entrySet()) {
            String node = "x" + externalNodes.size();
            externalNodes.put(externalNode.getKey(), node);
            output.append("    ").append(node).append("([\"")
                    .append(escape(externalNode.getValue())).append("\"])\n");
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
                String label = dependencyLabel(dependency.artifact(), dependency.requestedVersion(),
                        dependency.selectedVersion(), dependency.managed(), dependency.conflict(),
                        includeSelectedVersions);
                String edge = "    " + nodes.get(dependency.from()) + " -.->|"
                        + escape(label) + "| " + nodes.get(dependency.to()) + "\n";
                if (edges.add(edge)) {
                    output.append(edge);
                }
            }
            for (ExternalDependency dependency : graph.externalDependencies()) {
                String label = dependencyLabel(dependency.artifact(), dependency.requestedVersion(),
                        dependency.selectedVersion(), dependency.managed(), dependency.conflict(),
                        includeSelectedVersions);
                String edge = "    " + nodes.get(dependency.from()) + " -.->|"
                        + escape(label) + "| " + externalNodes.get(externalNodeKey(dependency))
                        + "\n";
                if (edges.add(edge)) {
                    output.append(edge);
                }
            }
        }
        return output.toString();
    }

    private String externalNodeKey(ExternalDependency dependency) {
        return dependency.label() + "\u0000" + dependency.selectedVersion();
    }

    private String externalNodeLabel(ExternalDependency dependency, boolean includeSelectedVersions) {
        String label = dependency.label();
        if (includeSelectedVersions) {
            label += " (selected " + (dependency.selectedVersion() == null
                    ? "unavailable" : dependency.selectedVersion()) + ")";
        }
        return label;
    }

    private String dependencyLabel(
            io.github.mjhaugsdal.architecture.model.ArtifactIdentity artifact,
            String requestedVersion,
            String selectedVersion,
            boolean managed,
            boolean conflict,
            boolean includeSelectedVersions) {
        List<String> label = new ArrayList<>();
            String type = artifact.type();
            if (!artifact.extension().equals(type)) {
                type += "[" + artifact.extension() + "]";
            }
            if (!"jar".equals(type) || !artifact.classifier().isBlank()) {
                label.add(type + (artifact.classifier().isBlank() ? "" : ":" + artifact.classifier()));
        }
        label.add(versionDescription(requestedVersion, selectedVersion, managed, conflict, includeSelectedVersions));
        return String.join(", ", label);
    }

    private String versionDescription(
            String requestedVersion,
            String selectedVersion,
            boolean managed,
            boolean conflict,
            boolean includeSelectedVersions) {
        List<String> label = new ArrayList<>();
        label.add("requested " + (requestedVersion == null ? "unknown" : requestedVersion));
        if (includeSelectedVersions) {
            label.add("selected " + (selectedVersion == null ? "unavailable" : selectedVersion));
        }
        if (managed) {
            label.add("managed");
        }
        if (conflict) {
            label.add("CONFLICT");
        }
        return String.join("; ", label);
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
                    || codePoint == '`' || codePoint == '\\' || codePoint == '|') {
                escaped.append('#').append(codePoint).append(';');
            } else {
                escaped.appendCodePoint(codePoint);
            }
        });
        return escaped.toString();
    }
}
