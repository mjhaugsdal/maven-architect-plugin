package io.github.mjhaugsdal.architecture.model;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Deterministically ordered, de-duplicated reactor architecture. */
public final class ArchitectureGraph {
    private final Map<ModuleId, ArchitectureModule> modules = new TreeMap<>();
    private final Set<ModuleRelationship> relationships = new TreeSet<>();
    private final Set<ModuleDependency> dependencies = new TreeSet<>();

    public void addModule(ArchitectureModule module) {
        if (modules.putIfAbsent(module.id(), module) != null) {
            throw new IllegalArgumentException("Duplicate reactor project: " + module.id());
        }
    }

    public void addRelationship(ModuleRelationship relationship) {
        requireModules(relationship.from(), relationship.to());
        relationships.add(relationship);
    }

    public void addDependency(ModuleDependency dependency) {
        requireModules(dependency.from(), dependency.to());
        dependencies.add(dependency);
    }

    private void requireModules(ModuleId from, ModuleId to) {
        if (!modules.containsKey(from) || !modules.containsKey(to)) {
            throw new IllegalArgumentException("Relationship refers to a project outside the reactor");
        }
    }

    public Collection<ArchitectureModule> modules() {
        return Collections.unmodifiableCollection(modules.values());
    }

    public Set<ModuleRelationship> relationships() {
        return Collections.unmodifiableSet(relationships);
    }

    public Set<ModuleDependency> dependencies() {
        return Collections.unmodifiableSet(dependencies);
    }
}
