package io.github.mjhaugsdal.architecture.model;

/** Identity for an artifact dependency that is not represented by a selected reactor project. */
public record ExternalArtifact(String groupId, String artifactId, String version, String type, String classifier)
        implements Comparable<ExternalArtifact> {
    public ExternalArtifact {
        if (groupId == null || groupId.isBlank() || artifactId == null || artifactId.isBlank()
                || version == null || version.isBlank() || type == null || type.isBlank()) {
            throw new IllegalArgumentException("External artifact coordinates must be complete");
        }
        classifier = classifier == null ? "" : classifier;
    }

    @Override
    public int compareTo(ExternalArtifact other) {
        int result = groupId.compareTo(other.groupId);
        if (result == 0) result = artifactId.compareTo(other.artifactId);
        if (result == 0) result = version.compareTo(other.version);
        if (result == 0) result = type.compareTo(other.type);
        return result == 0 ? classifier.compareTo(other.classifier) : result;
    }
}
