package io.github.mjhaugsdal.architecture.generator;

import io.github.mjhaugsdal.architecture.model.ArchitectureGraph;
import io.github.mjhaugsdal.architecture.model.ArchitectureModule;
import io.github.mjhaugsdal.architecture.model.ArtifactDependency;
import io.github.mjhaugsdal.architecture.model.ModuleId;
import io.github.mjhaugsdal.architecture.model.ModuleRelationship;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/** Serializes the complete architecture graph using the stable JSON schema. */
public final class JsonGenerator {
    public String generate(ArchitectureGraph graph) {
        StringBuilder output = new StringBuilder("{\n  \"schemaVersion\": 1,\n  \"nodes\": [");
        boolean first = true;
        for (ArchitectureModule module : graph.modules()) {
            output.append(first ? "\n" : ",\n");
            first = false;
            output.append("    {\"id\": ");
            string(output, moduleId(module.id()));
            output.append(", \"kind\": \"module\", \"groupId\": ");
            string(output, module.id().groupId());
            output.append(", \"artifactId\": ");
            string(output, module.id().artifactId());
            output.append(", \"version\": ");
            string(output, module.id().version());
            output.append(", \"packaging\": ");
            string(output, module.packaging());
            output.append(", \"name\": ");
            string(output, module.name());
            output.append(", \"parent\": ");
            string(output, module.parent() == null ? null : moduleId(module.parent()));
            output.append('}');
        }

        Map<String, ArtifactDependency> externalNodes = new TreeMap<>();
        for (ArtifactDependency dependency : graph.artifactDependencies()) {
            if (dependency.reactorTarget() == null) {
                externalNodes.put(externalId(dependency), dependency);
            }
        }
        for (Map.Entry<String, ArtifactDependency> entry : externalNodes.entrySet()) {
            ArtifactDependency dependency = entry.getValue();
            output.append(first ? "\n" : ",\n");
            first = false;
            output.append("    {\"id\": ");
            string(output, entry.getKey());
            output.append(", \"kind\": \"external\", \"groupId\": ");
            string(output, dependency.groupId());
            output.append(", \"artifactId\": ");
            string(output, dependency.artifactId());
            output.append(", \"version\": ");
            string(output, dependency.version());
            output.append(", \"type\": ");
            string(output, dependency.type());
            output.append(", \"classifier\": ");
            string(output, dependency.classifier());
            output.append('}');
        }
        output.append(first ? "],\n  \"edges\": [" : "\n  ],\n  \"edges\": [");

        first = true;
        for (ModuleRelationship relationship : graph.relationships()) {
            output.append(first ? "\n" : ",\n");
            first = false;
            output.append("    {\"from\": ");
            string(output, moduleId(relationship.from()));
            output.append(", \"to\": ");
            string(output, moduleId(relationship.to()));
            output.append(", \"kind\": ");
            string(output, relationship.type().name().toLowerCase(java.util.Locale.ROOT));
            output.append('}');
        }
        for (ArtifactDependency dependency : graph.artifactDependencies()) {
            output.append(first ? "\n" : ",\n");
            first = false;
            output.append("    {\"from\": ");
            string(output, moduleId(dependency.from()));
            output.append(", \"to\": ");
            string(output, dependency.reactorTarget() == null
                    ? externalId(dependency) : moduleId(dependency.reactorTarget()));
            output.append(", \"kind\": \"dependency\", \"scope\": ");
            string(output, dependency.scope());
            output.append(", \"version\": ");
            string(output, dependency.version());
            output.append(", \"type\": ");
            string(output, dependency.type());
            output.append(", \"classifier\": ");
            string(output, dependency.classifier());
            output.append('}');
        }
        output.append(first ? "]\n}\n" : "\n  ]\n}\n");
        return output.toString();
    }

    public static String moduleId(ModuleId id) {
        return "module:" + encode(id.groupId()) + ":" + encode(id.artifactId()) + ":" + encode(id.version());
    }

    private static String externalId(ArtifactDependency dependency) {
        return "external:" + encode(dependency.groupId()) + ":" + encode(dependency.artifactId()) + ":"
                + encode(dependency.version()) + ":" + encode(dependency.type()) + ":"
                + encode(dependency.classifier());
    }

    private static String encode(String value) {
        return value == null ? "~" : URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20").replace("~", "%7E");
    }

    private static void string(StringBuilder output, String value) {
        if (value == null) {
            output.append("null");
            return;
        }
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20 || Character.isSurrogate(character)) {
                        output.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }
}
