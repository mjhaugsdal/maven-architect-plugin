package io.github.mjhaugsdal.architecture.scanner;

import io.github.mjhaugsdal.architecture.model.ArchitectureGraph;
import io.github.mjhaugsdal.architecture.model.ArchitectureModule;
import io.github.mjhaugsdal.architecture.model.ArtifactIdentity;
import io.github.mjhaugsdal.architecture.model.ExternalDependency;
import io.github.mjhaugsdal.architecture.model.ModuleDependency;
import io.github.mjhaugsdal.architecture.model.ModuleId;
import io.github.mjhaugsdal.architecture.model.ModuleRelationship;
import io.github.mjhaugsdal.architecture.model.ResolvedDependencyVersion;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.artifact.ArtifactType;
import org.eclipse.aether.artifact.ArtifactTypeRegistry;

/** Reads the effective Maven reactor model without parsing POM files. */
public final class ProjectScanner {
    private final ArtifactHandlerManager artifactHandlerManager;
    private final ArtifactTypeRegistry artifactTypeRegistry;

    public ProjectScanner(ArtifactHandlerManager artifactHandlerManager) {
        this(artifactHandlerManager, null);
    }

    public ProjectScanner(
            ArtifactHandlerManager artifactHandlerManager, ArtifactTypeRegistry artifactTypeRegistry) {
        this.artifactHandlerManager = artifactHandlerManager;
        this.artifactTypeRegistry = artifactTypeRegistry;
    }

    public ArchitectureGraph scan(List<MavenProject> projects, boolean includeTestDependencies) {
        return scan(projects, includeTestDependencies, false, false, Map.of());
    }

    public ArchitectureGraph scan(
            List<MavenProject> projects,
            boolean includeTestDependencies,
            boolean includeExternalDependencies,
            Map<ModuleId, List<ResolvedDependencyVersion>> resolvedVersions) {
        return scan(projects, includeTestDependencies, false, includeExternalDependencies, resolvedVersions);
    }

    /**
     * Scans the reactor keeping every declared dependency between reactor projects, whatever its scope or
     * classifier, because Maven orders the build on all of them.
     */
    public ArchitectureGraph scanBuildOrder(List<MavenProject> projects) {
        return scan(projects, true, true, false, Map.of());
    }

    private ArchitectureGraph scan(
            List<MavenProject> projects,
            boolean includeTestDependencies,
            boolean allScopes,
            boolean includeExternalDependencies,
            Map<ModuleId, List<ResolvedDependencyVersion>> resolvedVersions) {
        if (projects == null || projects.isEmpty()) {
            throw new IllegalArgumentException("No projects are available in the Maven reactor");
        }
        ArchitectureGraph graph = new ArchitectureGraph();
        Map<ModuleId, MavenProject> reactor = new HashMap<>();
        Map<Path, ModuleId> directories = new HashMap<>();
        for (MavenProject project : projects) {
            if (project == null) {
                throw new IllegalArgumentException("The Maven reactor contains a null project");
            }
            ModuleId id = id(project.getGroupId(), project.getArtifactId(), project.getVersion());
            if (project.getBasedir() == null) {
                throw new IllegalArgumentException("No project directory is available for " + id);
            }
            ModuleId parent = project.getParent() == null ? null
                    : id(project.getParent().getGroupId(), project.getParent().getArtifactId(),
                            project.getParent().getVersion());
            Path directory = project.getBasedir().toPath().toAbsolutePath().normalize();
            graph.addModule(new ArchitectureModule(id, project.getPackaging(), project.getName(),
                    directory.toString(), parent));
            reactor.put(id, project);
            directories.put(directory, id);
        }
        for (Map.Entry<ModuleId, MavenProject> entry : reactor.entrySet()) {
            ModuleId source = entry.getKey();
            MavenProject project = entry.getValue();
            for (String module : project.getModules()) {
                ModuleId child = directories.get(project.getBasedir().toPath().resolve(module).toAbsolutePath().normalize());
                if (child != null && !child.equals(source)) {
                    graph.addRelationship(new ModuleRelationship(source, child, ModuleRelationship.Type.AGGREGATION));
                }
            }
            MavenProject parent = project.getParent();
            if (parent != null) {
                ModuleId parentId = id(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
                if (reactor.containsKey(parentId) && !parentId.equals(source)) {
                    graph.addRelationship(new ModuleRelationship(parentId, source, ModuleRelationship.Type.INHERITANCE));
                }
            }
            for (Dependency dependency : project.getDependencies()) {
                String scope = dependency.getScope() == null || dependency.getScope().isBlank()
                        ? "compile" : dependency.getScope();
                if (!allScopes && !("compile".equals(scope) || "runtime".equals(scope)
                        || (includeTestDependencies && "test".equals(scope)))) {
                    continue;
                }
                if (dependency.getGroupId() == null || dependency.getArtifactId() == null
                        || dependency.getVersion() == null) {
                    continue;
                }
                ArtifactIdentity artifact = artifactIdentity(dependency);
                ResolvedDependencyVersion resolution = findResolution(
                        resolvedVersions.get(source), artifact, dependency.getVersion());
                String selectedVersion = resolution == null ? null : resolution.selectedVersion();
                boolean conflict = resolution != null && resolution.conflict();
                boolean managed = isManaged(project, dependency) || resolution != null && resolution.managed();
                ModuleId target = selectedVersion == null
                        ? resolveTarget(dependency, reactor)
                        : findTarget(artifact, selectedVersion, reactor);
                MavenProject targetProject = target == null ? null : reactor.get(target);
                boolean targetMatches = targetProject != null && matchesArtifact(dependency, targetProject);
                if (targetProject != null && (allScopes || targetMatches)) {
                    graph.addDependency(new ModuleDependency(source, target, scope, artifact, dependency.getVersion(),
                            selectedVersion, managed, conflict));
                } else if (includeExternalDependencies) {
                    graph.addExternalDependency(new ExternalDependency(source, artifact, scope, dependency.getVersion(),
                            selectedVersion, managed, conflict));
                }
            }
            if (!allScopes) {
                addResolvedTransitives(graph, source, resolvedVersions.get(source), includeTestDependencies,
                        includeExternalDependencies, reactor);
            }
        }
        return graph;
    }

    private void addResolvedTransitives(
            ArchitectureGraph graph,
            ModuleId source,
            List<ResolvedDependencyVersion> versions,
            boolean includeTestDependencies,
            boolean includeExternalDependencies,
            Map<ModuleId, MavenProject> reactor) {
        if (versions == null) {
            return;
        }
        for (ResolvedDependencyVersion version : versions) {
            if (version.direct() || !("compile".equals(version.scope()) || "runtime".equals(version.scope())
                    || (includeTestDependencies && "test".equals(version.scope())))) {
                continue;
            }
            String targetVersion = version.selectedVersion();
            ModuleId target = targetVersion == null
                    ? null
                    : findTarget(version.artifact(), targetVersion, reactor);
            MavenProject targetProject = target == null ? null : reactor.get(target);
            if (targetProject != null) {
                graph.addDependency(new ModuleDependency(source, target, version.scope(), version.artifact(),
                        version.requestedVersion(), version.selectedVersion(), version.managed(), version.conflict()));
            } else if (includeExternalDependencies) {
                graph.addExternalDependency(new ExternalDependency(source, version.artifact(), version.scope(),
                        version.requestedVersion(), version.selectedVersion(), version.managed(), version.conflict()));
            }
        }
    }

    private ModuleId findTarget(
            ArtifactIdentity artifact, String version, Map<ModuleId, MavenProject> reactor) {
        ModuleId id = new ModuleId(artifact.groupId(), artifact.artifactId(), version);
        MavenProject project = reactor.get(id);
        return project != null && matchesArtifact(artifact, project) ? id : null;
    }

    private ModuleId resolveTarget(Dependency dependency, Map<ModuleId, MavenProject> reactor) {
        String groupId = dependency.getGroupId();
        String artifactId = dependency.getArtifactId();
        String version = dependency.getVersion();
        ModuleId exact = id(groupId, artifactId, version);
        if (reactor.containsKey(exact)) {
            return exact;
        }
        if (!(version.startsWith("[") || version.startsWith("("))) {
            return null;
        }
        try {
            VersionRange range = VersionRange.createFromVersionSpec(version);
            ModuleId match = null;
            for (ModuleId candidate : reactor.keySet()) {
                if (candidate.groupId().equals(groupId) && candidate.artifactId().equals(artifactId)
                        && range.containsVersion(new DefaultArtifactVersion(candidate.version()))) {
                    if (!matchesArtifact(dependency, reactor.get(candidate))) {
                        continue;
                    }
                    if (match != null) {
                        return null;
                    }
                    match = candidate;
                }
            }
            return match;
        } catch (InvalidVersionSpecificationException e) {
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

    private boolean matchesArtifact(Dependency dependency, MavenProject target) {
        ArtifactIdentity identity = artifactIdentity(dependency);
        return matchesArtifact(identity, target);
    }

    private boolean matchesArtifact(ArtifactIdentity identity, MavenProject target) {
        if (target.getArtifact() == null) {
            return false;
        }
        if (matchesArtifact(identity, target.getArtifact())) {
            return true;
        }
        if (target.getAttachedArtifacts().stream().anyMatch(artifact -> matchesArtifact(identity, artifact))) {
            return true;
        }
        return !identity.classifier().isBlank()
                && target.getArtifact().getArtifactHandler() != null
                && identity.extension().equals(target.getArtifact().getArtifactHandler().getExtension());
    }

    private boolean matchesArtifact(ArtifactIdentity identity, org.apache.maven.artifact.Artifact artifact) {
        return artifact.getArtifactHandler() != null
                && identity.extension().equals(artifact.getArtifactHandler().getExtension())
                && identity.classifier().equals(artifact.getClassifier() == null ? "" : artifact.getClassifier());
    }

    private ResolvedDependencyVersion findResolution(
            List<ResolvedDependencyVersion> resolutions, ArtifactIdentity artifact, String requestedVersion) {
        if (resolutions == null) {
            return null;
        }
        ResolvedDependencyVersion match = null;
        for (ResolvedDependencyVersion resolution : resolutions) {
            if (resolution.direct() && resolution.artifact().equals(artifact)
                    && resolution.requestedVersion().equals(requestedVersion)) {
                if (match != null && (!java.util.Objects.equals(match.selectedVersion(), resolution.selectedVersion())
                        || match.conflict() != resolution.conflict())) {
                    return null;
                }
                match = resolution;
            }
        }
        return match;
    }

    private boolean isManaged(MavenProject project, Dependency dependency) {
        MavenProject current = project;
        while (current != null) {
            if (current.getOriginalModel() != null) {
                for (Dependency declaration : current.getOriginalModel().getDependencies()) {
                    if (sameArtifact(declaration, dependency)) {
                        return declaration.getVersion() == null || declaration.getVersion().isBlank();
                    }
                }
            }
            current = current.getParent();
        }
        return false;
    }

    private boolean sameArtifact(Dependency first, Dependency second) {
        return java.util.Objects.equals(first.getGroupId(), second.getGroupId())
                && java.util.Objects.equals(first.getArtifactId(), second.getArtifactId())
                && normalize(first.getType(), "jar").equals(normalize(second.getType(), "jar"))
                && normalize(first.getClassifier(), "").equals(normalize(second.getClassifier(), ""));
    }

    private String normalize(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private ModuleId id(String group, String artifact, String version) {
        return new ModuleId(group, artifact, version);
    }
}
