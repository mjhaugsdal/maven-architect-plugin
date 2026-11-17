package io.github.mjhaugsdal.architecture.analyzer;

import io.github.mjhaugsdal.architecture.model.ModuleId;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Immutable dependency metrics for an architecture graph. */
public record ArchitectureAnalysis(int moduleCount, int dependencyEdgeCount, Map<ModuleId, ModuleMetrics> modules) {
    public ArchitectureAnalysis {
        modules = Collections.unmodifiableMap(new TreeMap<>(modules));
    }

    /**
     * Returns modules ordered by coupling score descending, then by module identity ascending.
     *
     * @param limit maximum number of modules to return
     * @return the highest-coupling module metrics
     */
    public List<ModuleMetrics> highestCouplingModules(int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("Limit must not be negative");
        }
        return modules.values().stream()
                .sorted(Comparator.comparingInt(ModuleMetrics::couplingScore).reversed()
                        .thenComparing(ModuleMetrics::module))
                .limit(limit)
                .toList();
    }
}
