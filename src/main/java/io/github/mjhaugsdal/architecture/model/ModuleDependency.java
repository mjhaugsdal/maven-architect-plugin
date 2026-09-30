package io.github.mjhaugsdal.architecture.model;

/** A declared direct dependency from one reactor module to another. */
public record ModuleDependency(ModuleId from, ModuleId to, String scope)
        implements Comparable<ModuleDependency> {
    @Override
    public int compareTo(ModuleDependency other) {
        int result = from.compareTo(other.from);
        if (result == 0) result = to.compareTo(other.to);
        return result == 0 ? scope.compareTo(other.scope) : result;
    }
}
