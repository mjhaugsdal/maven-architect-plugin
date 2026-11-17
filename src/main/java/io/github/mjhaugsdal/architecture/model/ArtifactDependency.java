package io.github.mjhaugsdal.architecture.model;

import java.util.Comparator;

/** A direct dependency declaration, including coordinates not represented in the reactor. */
public record ArtifactDependency(
        ModuleId from,
        ModuleId reactorTarget,
        String groupId,
        String artifactId,
        String version,
        String type,
        String classifier,
        String scope) implements Comparable<ArtifactDependency> {
    private static final Comparator<String> STRINGS = Comparator.nullsFirst(String::compareTo);
    private static final Comparator<ModuleId> MODULE_IDS = Comparator.nullsFirst(Comparator.naturalOrder());

    @Override
    public int compareTo(ArtifactDependency other) {
        int result = from.compareTo(other.from);
        if (result == 0) result = STRINGS.compare(groupId, other.groupId);
        if (result == 0) result = STRINGS.compare(artifactId, other.artifactId);
        if (result == 0) result = STRINGS.compare(version, other.version);
        if (result == 0) result = STRINGS.compare(type, other.type);
        if (result == 0) result = STRINGS.compare(classifier, other.classifier);
        if (result == 0) result = STRINGS.compare(scope, other.scope);
        if (result == 0) result = MODULE_IDS.compare(reactorTarget, other.reactorTarget);
        return result;
    }
}
