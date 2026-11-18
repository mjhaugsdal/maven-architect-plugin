package io.github.mjhaugsdal.architecture.model;

/** Resolver evidence for one requested artifact version. */
public record ResolvedDependencyVersion(
        ArtifactIdentity artifact,
        String scope,
        String requestedVersion,
        String selectedVersion,
        boolean managed,
        boolean conflict,
        boolean direct) {
}
