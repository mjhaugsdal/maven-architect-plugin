package io.github.mjhaugsdal.architecture.model;

/** A reactor project's full Maven identity. */
public record ModuleId(String groupId, String artifactId, String version) implements Comparable<ModuleId> {
    public ModuleId {
        if (groupId == null || groupId.isBlank() || artifactId == null || artifactId.isBlank()
                || version == null || version.isBlank()) {
            throw new IllegalArgumentException("Module coordinates must include groupId, artifactId and version");
        }
    }

    @Override
    public int compareTo(ModuleId other) {
        int result = groupId.compareTo(other.groupId);
        if (result == 0) result = artifactId.compareTo(other.artifactId);
        return result == 0 ? version.compareTo(other.version) : result;
    }
}
