package io.github.mjhaugsdal.architecture.model;

/** A relationship between two modules, independent of diagram notation. */
public record ModuleRelationship(ModuleId from, ModuleId to, Type type)
        implements Comparable<ModuleRelationship> {
    public enum Type { AGGREGATION, INHERITANCE }

    @Override
    public int compareTo(ModuleRelationship other) {
        int result = from.compareTo(other.from);
        if (result == 0) result = to.compareTo(other.to);
        return result == 0 ? type.compareTo(other.type) : result;
    }
}
