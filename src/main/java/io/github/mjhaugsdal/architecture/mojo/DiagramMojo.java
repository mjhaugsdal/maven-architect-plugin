package io.github.mjhaugsdal.architecture.mojo;

import io.github.mjhaugsdal.architecture.generator.BuildLevelGenerator;
import io.github.mjhaugsdal.architecture.generator.MermaidGenerator;
import io.github.mjhaugsdal.architecture.model.ArchitectureGraph;
import io.github.mjhaugsdal.architecture.scanner.ProjectScanner;
import io.github.mjhaugsdal.architecture.writer.DiagramWriter;
import java.io.File;
import java.io.IOException;
import java.util.List;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/** Generates a Mermaid diagram of the currently selected Maven reactor. */
@Mojo(name = "diagram", aggregator = true, defaultPhase = LifecyclePhase.NONE, threadSafe = true)
public final class DiagramMojo extends AbstractMojo {
    private static final List<String> DEPENDENCY_SCOPES =
            List.of("compile", "runtime", "provided", "test", "system");

    @Component
    private ArtifactHandlerManager artifactHandlerManager;

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

    /** Include dependencies on artifacts outside the selected reactor in full diagrams. */
    @Parameter(defaultValue = "false", property = "architecture.includeExternalDependencies")
    private boolean includeExternalDependencies;

    /** Optionally show only dependencies in this scope in a full diagram. */
    @Parameter(property = "architecture.dependencyScope")
    private String dependencyScope;

    @Override
    public void execute() throws MojoExecutionException {
        if (session == null || session.getProjects() == null || session.getProjects().isEmpty()) {
            throw new MojoExecutionException("No Maven reactor projects are available to analyze");
        }
        if (!project.equals(session.getProjects().get(0))) {
            return;
        }
        if (dependencyScope != null && !dependencyScope.isBlank()
                && !DEPENDENCY_SCOPES.contains(dependencyScope.trim())) {
            throw new MojoExecutionException("dependencyScope must be one of " + DEPENDENCY_SCOPES
                    + ", not '" + dependencyScope + "'");
        }
        boolean buildLevels = "build-levels".equals(diagramType);
        if (!buildLevels && !"full".equals(diagramType)) {
            throw new MojoExecutionException("diagramType must be 'build-levels' or 'full', not '" + diagramType + "'");
        }
        if (outputFile == null || outputFile.isDirectory()) {
            throw new MojoExecutionException("outputFile must point to a writable file, not a directory");
        }
        try {
            ProjectScanner scanner = new ProjectScanner(artifactHandlerManager);
            String diagram;
            if (buildLevels) {
                diagram = new BuildLevelGenerator().generate(scanner.scanBuildOrder(session.getProjects()));
            } else {
                String selectedScope = dependencyScope == null || dependencyScope.isBlank()
                        ? null : dependencyScope.trim();
                ArchitectureGraph graph = scanner.scan(session.getProjects(), includeTestDependencies, selectedScope,
                        includeExternalDependencies);
                diagram = new MermaidGenerator().generate(graph, includeModuleHierarchy, includeDependencies);
            }
            new DiagramWriter().write(outputFile.toPath(), diagram);
            getLog().info("Mermaid architecture diagram: " + outputFile.getAbsolutePath());
        } catch (IllegalArgumentException | IOException exception) {
            throw new MojoExecutionException("Could not generate architecture diagram: " + exception.getMessage(),
                    exception);
        }
    }
}
