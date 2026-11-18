package io.github.mjhaugsdal.architecture.model;

/** Maven artifact identity independent of its requested or selected version. */
public record ArtifactIdentity(String groupId, String artifactId, String type, String extension, String classifier)
        implements Comparable<ArtifactIdentity> {
    public ArtifactIdentity {
        if (groupId == null || groupId.isBlank() || artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException("Artifact identity must include groupId and artifactId");
        }
        type = type == null || type.isBlank() ? "jar" : type;
        extension = extension == null || extension.isBlank() ? type : extension;
        classifier = classifier == null ? "" : classifier;
    }

    public String label() {
        return groupId + ":" + artifactId + ":" + type
                + (extension.equals(type) ? "" : "[" + extension + "]")
                + (classifier.isBlank() ? "" : ":" + classifier);
    }

    @Override
    public int compareTo(ArtifactIdentity other) {
        int result = groupId.compareTo(other.groupId);
        if (result == 0) result = artifactId.compareTo(other.artifactId);
        if (result == 0) result = type.compareTo(other.type);
        if (result == 0) result = extension.compareTo(other.extension);
        return result == 0 ? classifier.compareTo(other.classifier) : result;
    }
}
