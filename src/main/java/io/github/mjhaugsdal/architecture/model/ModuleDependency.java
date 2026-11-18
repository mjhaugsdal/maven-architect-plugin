package io.github.mjhaugsdal.architecture.model;

/** A declared direct dependency from one reactor module to another. */
public record ModuleDependency(
        ModuleId from,
        ModuleId to,
        String scope,
        ArtifactIdentity artifact,
        String requestedVersion,
        String selectedVersion,
        boolean managed,
        boolean conflict)
        implements Comparable<ModuleDependency> {
    public ModuleDependency(ModuleId from, ModuleId to, String scope) {
        this(from, to, scope, new ArtifactIdentity(to.groupId(), to.artifactId(), "jar", "jar", ""),
                to.version(), null, false, false);
    }

    @Override
    public int compareTo(ModuleDependency other) {
        int result = from.compareTo(other.from);
        if (result == 0) result = to.compareTo(other.to);
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
