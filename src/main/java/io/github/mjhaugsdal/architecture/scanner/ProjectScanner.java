package io.github.mjhaugsdal.architecture.scanner;

import io.github.mjhaugsdal.architecture.model.ArchitectureGraph;
import io.github.mjhaugsdal.architecture.model.ArchitectureModule;
import io.github.mjhaugsdal.architecture.model.ExternalDependency;
import io.github.mjhaugsdal.architecture.model.ModuleDependency;
import io.github.mjhaugsdal.architecture.model.ModuleId;
import io.github.mjhaugsdal.architecture.model.ModuleRelationship;
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

/** Reads the effective Maven reactor model without parsing POM files. */
public final class ProjectScanner {
    private final ArtifactHandlerManager artifactHandlerManager;

    public ProjectScanner(ArtifactHandlerManager artifactHandlerManager) {
        this.artifactHandlerManager = artifactHandlerManager;
    }

    public ArchitectureGraph scan(List<MavenProject> projects, boolean includeTestDependencies) {
        return scan(projects, includeTestDependencies, null, false);
    }

    public ArchitectureGraph scan(List<MavenProject> projects, boolean includeTestDependencies, String dependencyScope,
            boolean includeExternalDependencies) {
        return scan(projects, includeTestDependencies, false, dependencyScope, includeExternalDependencies);
    }

    /**
     * Scans the reactor keeping every declared dependency between reactor projects, whatever its scope or
     * classifier, because Maven orders the build on all of them.
     */
    public ArchitectureGraph scanBuildOrder(List<MavenProject> projects) {
        return scan(projects, true, true, null, false);
    }

    private ArchitectureGraph scan(List<MavenProject> projects, boolean includeTestDependencies, boolean allScopes,
            String dependencyScope, boolean includeExternalDependencies) {
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
                boolean includedScope = dependencyScope == null
                        ? "compile".equals(scope) || "runtime".equals(scope)
                                || (includeTestDependencies && "test".equals(scope))
                        : dependencyScope.equals(scope);
                if (!allScopes && !includedScope) {
                    continue;
                }
                if (!allScopes && dependency.getClassifier() != null && !dependency.getClassifier().isBlank()) {
                    continue;
                }
                if (dependency.getGroupId() == null || dependency.getArtifactId() == null) {
                    continue;
                }
                ModuleId target = dependency.getVersion() == null ? null : resolveTarget(dependency, reactor);
                MavenProject targetProject = target == null ? null : reactor.get(target);
                if (targetProject != null && (allScopes || matchesType(dependency, targetProject))) {
                    graph.addDependency(new ModuleDependency(source, target, scope));
                } else if (targetProject == null && includeExternalDependencies) {
                    graph.addExternalDependency(new ExternalDependency(source, dependency.getGroupId(),
                            dependency.getArtifactId(), dependency.getVersion(), scope));
                }
            }
        }
        return graph;
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

    private boolean matchesType(Dependency dependency, MavenProject target) {
        String type = dependency.getType() == null || dependency.getType().isBlank() ? "jar" : dependency.getType();
        ArtifactHandler dependencyHandler = artifactHandlerManager.getArtifactHandler(type);
        return dependencyHandler != null && target.getArtifact() != null
                && target.getArtifact().getArtifactHandler() != null
                && dependencyHandler.getExtension().equals(target.getArtifact().getArtifactHandler().getExtension());
    }

    private ModuleId id(String group, String artifact, String version) {
        return new ModuleId(group, artifact, version);
    }
}
