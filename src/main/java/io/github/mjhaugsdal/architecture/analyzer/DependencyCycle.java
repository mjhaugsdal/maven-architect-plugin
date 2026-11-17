package io.github.mjhaugsdal.architecture.analyzer;

import io.github.mjhaugsdal.architecture.model.ModuleId;
import java.util.List;

/** A strongly connected module cluster and one representative closed dependency path. */
public record DependencyCycle(List<ModuleId> modules, List<ModuleId> path) {
    public DependencyCycle {
        modules = List.copyOf(modules);
        path = List.copyOf(path);
    }
}
