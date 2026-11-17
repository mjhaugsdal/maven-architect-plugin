package io.github.mjhaugsdal.architecture.model;

/** A declared direct dependency from a reactor module to an artifact outside the reactor. */
public record ExternalDependency(
        ModuleId from,
        ArtifactIdentity artifact,
        String scope,
        String requestedVersion,
        String selectedVersion,
        boolean managed,
        boolean conflict)
        implements Comparable<ExternalDependency> {
    public String label() {
        return artifact.label() + (requestedVersion == null || requestedVersion.isBlank()
                ? "" : ":" + requestedVersion);
    }

    @Override
    public int compareTo(ExternalDependency other) {
        int result = from.compareTo(other.from);
        if (result == 0) result = artifact.compareTo(other.artifact);
        if (result == 0) result = compareNullable(requestedVersion, other.requestedVersion);
        if (result == 0) result = compareNullable(selectedVersion, other.selectedVersion);
        if (result == 0) result = scope.compareTo(other.scope);
        if (result == 0) result = Boolean.compare(managed, other.managed);
        return result == 0 ? Boolean.compare(conflict, other.conflict) : result;
    }

    private static int compareNullable(String first, String second) {
        if (first == null) return second == null ? 0 : -1;
        return second == null ? 1 : first.compareTo(second);
    }
}
