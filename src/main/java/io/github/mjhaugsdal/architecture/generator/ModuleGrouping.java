package io.github.mjhaugsdal.architecture.generator;

import io.github.mjhaugsdal.architecture.model.ArchitectureGraph;
import io.github.mjhaugsdal.architecture.model.ArchitectureModule;
import io.github.mjhaugsdal.architecture.model.ModuleId;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Projects module metadata onto display groups without changing graph identity. */
public final class ModuleGrouping {
    public Map<ModuleId, String> project(ArchitectureGraph graph, String strategy, Path reactorDirectory,
            Map<String, List<String>> customGroups) {
        if (strategy == null
                || !Set.of("none", "parent", "artifactId", "groupId", "directory", "custom").contains(strategy)) {
            throw new IllegalArgumentException("groupBy must be 'none', 'parent', 'artifactId', 'groupId', "
                    + "'directory' or 'custom', not '" + strategy + "'");
        }
        Map<String, String> assignments = new HashMap<>();
        if ("custom".equals(strategy) && customGroups != null) {
            for (Map.Entry<String, List<String>> entry : customGroups.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                    throw new IllegalArgumentException("Custom groups require a nonblank label and module coordinates");
                }
                for (String selector : entry.getValue()) {
                    if (selector == null || !selector.matches("[^:\\s]+:[^:\\s]+(:[^:\\s]+)?")) {
                        throw new IllegalArgumentException("Custom group modules must use groupId:artifactId[:version]");
                    }
                    String previous = assignments.putIfAbsent(selector, entry.getKey());
                    if (previous != null && !previous.equals(entry.getKey())) {
                        throw new IllegalArgumentException("Overlapping custom groups for " + selector);
                    }
                }
            }
        }
        for (Map.Entry<String, String> entry : assignments.entrySet()) {
            int versionSeparator = entry.getKey().lastIndexOf(':');
            String broadMatch = assignments.get(entry.getKey().substring(0, versionSeparator));
            if (broadMatch != null && !broadMatch.equals(entry.getValue())) {
                throw new IllegalArgumentException("Overlapping custom groups for " + entry.getKey());
            }
        }
        Map<ModuleId, String> groups = new HashMap<>();
        for (ArchitectureModule module : graph.modules()) {
            ModuleId id = module.id();
            String label = switch (strategy) {
                case "parent" -> module.parent() == null ? null : coordinates(module.parent());
                case "artifactId" -> id.artifactId();
                case "groupId" -> id.groupId();
                case "directory" -> directory(module.directory(), reactorDirectory);
                case "custom" -> {
                    String shortMatch = assignments.get(id.groupId() + ":" + id.artifactId());
                    String exactMatch = assignments.get(coordinates(id));
                    yield exactMatch == null ? shortMatch : exactMatch;
                }
                default -> null;
            };
            if (label != null) {
                groups.put(id, label);
            }
        }
        return Map.copyOf(groups);
    }

    private String coordinates(ModuleId id) {
        return id.groupId() + ":" + id.artifactId() + ":" + id.version();
    }

    private String directory(String directory, Path reactorDirectory) {
        if (directory == null || directory.isBlank()) {
            return null;
        }
        Path root = reactorDirectory.toAbsolutePath().normalize();
        Path path = Path.of(directory).toAbsolutePath().normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            return null;
        }
        return root.relativize(path).getName(0).toString();
    }
}
