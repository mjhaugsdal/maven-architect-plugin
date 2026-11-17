package io.github.mjhaugsdal.architecture.scanner;

import io.github.mjhaugsdal.architecture.model.ArtifactIdentity;
import io.github.mjhaugsdal.architecture.model.ModuleId;
import io.github.mjhaugsdal.architecture.model.ResolvedDependencyVersion;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.DefaultDependencyResolutionRequest;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.ArtifactType;
import org.eclipse.aether.artifact.ArtifactTypeRegistry;
import org.eclipse.aether.graph.DependencyNode;

/** Resolves direct dependency versions while retaining Maven's conflict-winner evidence. */
public final class MavenDependencyResolver {
    private static final String VERBOSE_CONFLICTS = "aether.conflictResolver.verbose";

    private final ProjectDependenciesResolver resolver;
    private final ArtifactHandlerManager artifactHandlerManager;
    private final ArtifactTypeRegistry artifactTypeRegistry;

    public MavenDependencyResolver(
            ProjectDependenciesResolver resolver, ArtifactHandlerManager artifactHandlerManager) {
        this(resolver, artifactHandlerManager, null);
    }

    public MavenDependencyResolver(
            ProjectDependenciesResolver resolver,
            ArtifactHandlerManager artifactHandlerManager,
            ArtifactTypeRegistry artifactTypeRegistry) {
        this.resolver = resolver;
        this.artifactHandlerManager = artifactHandlerManager;
        this.artifactTypeRegistry = artifactTypeRegistry;
    }

    public Map<ModuleId, List<ResolvedDependencyVersion>> resolve(
            List<MavenProject> projects, RepositorySystemSession repositorySession, Consumer<String> warning) {
        Map<ModuleId, List<ResolvedDependencyVersion>> versions = new HashMap<>();
        for (MavenProject project : projects) {
            ModuleId projectId = new ModuleId(project.getGroupId(), project.getArtifactId(), project.getVersion());
            DependencyResolutionResult result = resolve(project, repositorySession, warning);
            if (result == null || result.getDependencyGraph() == null) {
                continue;
            }
            List<ResolvedDependencyVersion> projectVersions = new ArrayList<>();
            for (Dependency dependency : project.getDependencies()) {
                ArtifactIdentity identity = artifactIdentity(dependency);
                List<DependencyNode> candidates = result.getDependencyGraph().getChildren().stream()
                        .filter(node -> matches(identity, node.getArtifact()))
                        .filter(node -> matchesRequestedVersion(dependency.getVersion(), node))
                        .toList();
                if (candidates.size() != 1) {
                    continue;
                }
                DependencyNode node = candidates.get(0);
                projectVersions.add(resolvedVersion(identity, dependency.getScope(), dependency.getVersion(), node, true));
            }
            addTransitiveVersions(result.getDependencyGraph(), 0, projectVersions);
            versions.put(projectId, List.copyOf(projectVersions));
        }
        return Map.copyOf(versions);
    }

    private DependencyResolutionResult resolve(
            MavenProject project, RepositorySystemSession repositorySession, Consumer<String> warning) {
        try {
            DefaultRepositorySystemSession resolutionSession = new DefaultRepositorySystemSession(repositorySession);
            resolutionSession.setConfigProperty(VERBOSE_CONFLICTS, true);
            return resolver.resolve(new DefaultDependencyResolutionRequest(project, resolutionSession));
        } catch (DependencyResolutionException exception) {
            warning.accept("Could not fully resolve dependencies for " + project.getGroupId() + ":"
                    + project.getArtifactId() + ":" + project.getVersion() + ": " + exception.getMessage());
            return exception.getResult();
        } catch (RuntimeException exception) {
            warning.accept("Could not resolve dependencies for " + project.getGroupId() + ":"
                    + project.getArtifactId() + ":" + project.getVersion() + ": " + exception.getMessage());
            return null;
        }
    }

    private ArtifactIdentity artifactIdentity(Dependency dependency) {
        String type = dependency.getType() == null || dependency.getType().isBlank() ? "jar" : dependency.getType();
        ArtifactHandler handler = artifactHandlerManager.getArtifactHandler(type);
        ArtifactType artifactType = artifactTypeRegistry == null ? null : artifactTypeRegistry.get(type);
        String extension = artifactType != null ? artifactType.getExtension()
                : handler == null ? type : handler.getExtension();
        String classifier = dependency.getClassifier();
        if (classifier == null || classifier.isBlank()) {
            classifier = artifactType != null ? artifactType.getClassifier()
                    : handler == null ? null : handler.getClassifier();
        }
        return new ArtifactIdentity(dependency.getGroupId(), dependency.getArtifactId(), type, extension,
                classifier);
    }

    private boolean matches(ArtifactIdentity identity, Artifact artifact) {
        return identity.groupId().equals(artifact.getGroupId())
                && identity.artifactId().equals(artifact.getArtifactId())
                && identity.extension().equals(artifact.getExtension())
                && identity.classifier().equals(artifact.getClassifier());
    }

    private boolean matchesRequestedVersion(String requestedVersion, DependencyNode node) {
        if (requestedVersion == null || requestedVersion.isBlank()) {
            return false;
        }
        return requestedVersion.equals(node.getArtifact().getVersion())
                || node.getVersionConstraint() != null
                        && requestedVersion.equals(node.getVersionConstraint().toString());
    }

    private ResolvedDependencyVersion resolvedVersion(
            ArtifactIdentity identity, String scope, String requestedVersion, DependencyNode node, boolean direct) {
        Object winnerValue = node.getData().get("conflict.winner");
        DependencyNode winner = winnerValue instanceof DependencyNode winnerNode ? winnerNode : null;
        String selectedVersion = winner == null
                ? node.getArtifact().getVersion()
                : winner.getArtifact().getVersion();
        boolean conflict = winner != null
                && !winner.getArtifact().getVersion().equals(node.getArtifact().getVersion());
        if (!isRange(requestedVersion) && !requestedVersion.equals(selectedVersion)) {
            conflict = true;
        }
        boolean managed = (node.getManagedBits() & DependencyNode.MANAGED_VERSION) != 0;
        return new ResolvedDependencyVersion(identity, scope, requestedVersion, selectedVersion,
                managed, conflict, direct);
    }

    private void addTransitiveVersions(
            DependencyNode parent, int depth, List<ResolvedDependencyVersion> versions) {
        for (DependencyNode child : parent.getChildren()) {
            int childDepth = depth + 1;
            if (childDepth > 1 && child.getArtifact() != null && child.getDependency() != null) {
                Artifact artifact = child.getArtifact();
                String requestedVersion = child.getVersionConstraint() == null
                        ? child.getDependency().getArtifact().getVersion()
                        : child.getVersionConstraint().toString();
                ArtifactIdentity identity = new ArtifactIdentity(artifact.getGroupId(), artifact.getArtifactId(),
                        artifact.getExtension(), artifact.getExtension(), artifact.getClassifier());
                versions.add(resolvedVersion(identity, child.getDependency().getScope(), requestedVersion,
                        child, false));
            }
            addTransitiveVersions(child, childDepth, versions);
        }
    }

    private boolean isRange(String version) {
        return version.startsWith("[") || version.startsWith("(");
    }
}
