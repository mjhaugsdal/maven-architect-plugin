package io.github.mjhaugsdal.architecture.scanner;

import io.github.mjhaugsdal.architecture.model.ArchitectureGraph;
import io.github.mjhaugsdal.architecture.model.ArchitectureModule;
import io.github.mjhaugsdal.architecture.model.ModuleDependency;
import io.github.mjhaugsdal.architecture.model.ModuleId;
import io.github.mjhaugsdal.architecture.model.ModuleRelationship;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;

/** Reads the effective Maven reactor model without parsing POM files. */
public final class ProjectScanner {
    public ArchitectureGraph scan(List<MavenProject> projects, boolean includeTestDependencies) {
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
                if (!("compile".equals(scope) || "runtime".equals(scope)
                        || (includeTestDependencies && "test".equals(scope)))) {
                    continue;
                }
                if (dependency.getClassifier() != null && !dependency.getClassifier().isBlank()) {
                    continue;
                }
                if (dependency.getGroupId() == null || dependency.getArtifactId() == null
                        || dependency.getVersion() == null) {
                    continue;
                }
                ModuleId target = id(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
                MavenProject targetProject = reactor.get(target);
                if (targetProject != null && matchesType(dependency, targetProject)) {
                    graph.addDependency(new ModuleDependency(source, target, scope));
                }
            }
        }
        return graph;
    }

    private boolean matchesType(Dependency dependency, MavenProject target) {
        String type = dependency.getType() == null || dependency.getType().isBlank() ? "jar" : dependency.getType();
        return type.equals(target.getPackaging()) || ("jar".equals(type) && "maven-plugin".equals(target.getPackaging()));
    }

    private ModuleId id(String group, String artifact, String version) {
        return new ModuleId(group, artifact, version);
    }
}
