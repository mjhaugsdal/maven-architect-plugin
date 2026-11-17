package io.github.mjhaugsdal.architecture.mojo;

import io.github.mjhaugsdal.architecture.generator.BuildLevelGenerator;
import io.github.mjhaugsdal.architecture.generator.MermaidGenerator;
import io.github.mjhaugsdal.architecture.model.ArchitectureGraph;
import io.github.mjhaugsdal.architecture.model.ModuleId;
import io.github.mjhaugsdal.architecture.model.ResolvedDependencyVersion;
import io.github.mjhaugsdal.architecture.scanner.MavenDependencyResolver;
import io.github.mjhaugsdal.architecture.scanner.ProjectScanner;
import io.github.mjhaugsdal.architecture.writer.DiagramWriter;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectDependenciesResolver;

/** Generates a Mermaid diagram of the currently selected Maven reactor. */
@Mojo(name = "diagram", aggregator = true, defaultPhase = LifecyclePhase.NONE, threadSafe = true)
public final class DiagramMojo extends AbstractMojo {
    @Component
    private ArtifactHandlerManager artifactHandlerManager;

    @Component
    private ProjectDependenciesResolver projectDependenciesResolver;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** Destination for the Mermaid diagram; override with -Darchitecture.outputFile. */
    @Parameter(defaultValue = "${project.build.directory}/architecture/architecture.mmd",
            property = "architecture.outputFile")
    private File outputFile;

    /**
     * {@code build-levels} (default) groups modules into levels that can be built in parallel;
     * {@code full} renders aggregation, inheritance and dependency edges.
     */
    @Parameter(defaultValue = "build-levels", property = "architecture.diagramType")
    private String diagramType;

    /** Include edges for aggregator modules and inherited parent POMs. */
    @Parameter(defaultValue = "true", property = "architecture.includeModuleHierarchy")
    private boolean includeModuleHierarchy;

    /** Include direct dependencies between reactor projects. */
    @Parameter(defaultValue = "true", property = "architecture.includeDependencies")
    private boolean includeDependencies;

    /** Include declared test-scope dependencies in addition to compile and runtime. */
    @Parameter(defaultValue = "false", property = "architecture.includeTestDependencies")
    private boolean includeTestDependencies;

    /** Include dependencies outside the selected reactor in full diagrams. */
    @Parameter(defaultValue = "false", property = "architecture.includeExternalDependencies")
    private boolean includeExternalDependencies;

    /** Resolve dependencies to show Maven-selected versions and mediated conflicts. */
    @Parameter(defaultValue = "false", property = "architecture.resolveDependencyVersions")
    private boolean resolveDependencyVersions;

    @Override
    public void execute() throws MojoExecutionException {
        if (session == null || session.getProjects() == null || session.getProjects().isEmpty()) {
            throw new MojoExecutionException("No Maven reactor projects are available to analyze");
        }
        boolean buildLevels = "build-levels".equals(diagramType);
        if (!buildLevels && !"full".equals(diagramType)) {
            throw new MojoExecutionException("diagramType must be 'build-levels' or 'full', not '" + diagramType + "'");
        }
        if (outputFile == null || outputFile.isDirectory()) {
            throw new MojoExecutionException("outputFile must point to a writable file, not a directory");
        }
        try {
            var repositorySession = session.getRepositorySession();
            ProjectScanner scanner = new ProjectScanner(
                    artifactHandlerManager, repositorySession.getArtifactTypeRegistry());
            String diagram;
            if (buildLevels) {
                diagram = new BuildLevelGenerator().generate(scanner.scanBuildOrder(session.getProjects()));
            } else {
                Map<ModuleId, List<ResolvedDependencyVersion>> resolvedVersions = resolveDependencyVersions
                        ? new MavenDependencyResolver(projectDependenciesResolver, artifactHandlerManager,
                                repositorySession.getArtifactTypeRegistry())
                                .resolve(session.getProjects(), repositorySession, message -> getLog().warn(message))
                        : Map.of();
                ArchitectureGraph graph = scanner.scan(session.getProjects(), includeTestDependencies,
                        includeExternalDependencies, resolvedVersions);
                diagram = new MermaidGenerator().generate(
                        graph, includeModuleHierarchy, includeDependencies, resolveDependencyVersions);
            }
            new DiagramWriter().write(outputFile.toPath(), diagram);
            getLog().info("Mermaid architecture diagram: " + outputFile.getAbsolutePath());
        } catch (IllegalArgumentException | IOException exception) {
            throw new MojoExecutionException("Could not generate architecture diagram: " + exception.getMessage(),
                    exception);
        }
    }
}
