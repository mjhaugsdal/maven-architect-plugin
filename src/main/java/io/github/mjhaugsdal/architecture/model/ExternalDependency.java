package io.github.mjhaugsdal.architecture.model;

/** A declared direct dependency from a reactor module to an artifact outside the reactor. */
public record ExternalDependency(
        ModuleId from, String groupId, String artifactId, String version, String scope)
        implements Comparable<ExternalDependency> {
    public String label() {
        return groupId + ":" + artifactId + (version == null || version.isBlank() ? "" : ":" + version);
    }

    @Override
    public int compareTo(ExternalDependency other) {
        int result = from.compareTo(other.from);
        if (result == 0) result = groupId.compareTo(other.groupId);
        if (result == 0) result = artifactId.compareTo(other.artifactId);
        if (result == 0) result = compareNullable(version, other.version);
        return result == 0 ? scope.compareTo(other.scope) : result;
    }

    private static int compareNullable(String first, String second) {
        if (first == null) return second == null ? 0 : -1;
        return second == null ? 1 : first.compareTo(second);
    }
}
