package io.github.mjhaugsdal.architecture.model;

/** A declared dependency from a reactor module to an artifact outside the selected reactor. */
public record ExternalDependency(ModuleId from, ExternalArtifact to, String scope)
        implements Comparable<ExternalDependency> {
    @Override
    public int compareTo(ExternalDependency other) {
        int result = from.compareTo(other.from);
        if (result == 0) result = to.compareTo(other.to);
        return result == 0 ? scope.compareTo(other.scope) : result;
    }
}
