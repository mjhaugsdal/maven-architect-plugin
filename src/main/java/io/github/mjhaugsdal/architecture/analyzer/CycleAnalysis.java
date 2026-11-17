package io.github.mjhaugsdal.architecture.analyzer;

import io.github.mjhaugsdal.architecture.model.ModuleId;
import java.util.List;

/** The cyclic dependency components found in an architecture graph. */
public record CycleAnalysis(List<DependencyCycle> cycles) {
    public CycleAnalysis {
        cycles = List.copyOf(cycles);
    }

    public boolean hasCycles() {
        return !cycles.isEmpty();
    }

    public String diagnosticMessage() {
        if (!hasCycles()) {
            return "No circular dependencies found.";
        }
        StringBuilder message = new StringBuilder("Dependency cycle detected:");
        for (int index = 0; index < cycles.size(); index++) {
            DependencyCycle cycle = cycles.get(index);
            message.append("\n  Cycle ").append(index + 1).append(": modules [");
            message.append(String.join(", ", cycle.modules().stream().map(CycleAnalysis::coordinates).toList()));
            message.append("]; path ");
            message.append(String.join(" -> ", cycle.path().stream().map(CycleAnalysis::coordinates).toList()));
        }
        return message.toString();
    }

    private static String coordinates(ModuleId id) {
        return id.groupId() + ":" + id.artifactId() + ":" + id.version();
    }
}
