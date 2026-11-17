package io.github.mjhaugsdal.architecture.mojo;

import io.github.mjhaugsdal.architecture.generator.BuildLevelGenerator;
import io.github.mjhaugsdal.architecture.generator.MermaidGenerator;
import io.github.mjhaugsdal.architecture.model.ArchitectureGraph;
import io.github.mjhaugsdal.architecture.scanner.ProjectScanner;
import io.github.mjhaugsdal.architecture.writer.DiagramWriter;
import java.io.File;
import java.io.IOException;
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
     * {@code full} renders aggregation, inheritance and dependency edges; {@code dependency} renders dependencies only.
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

    /** Include declared dependencies on artifacts outside the selected reactor. */
    @Parameter(defaultValue = "false", property = "architecture.includeExternalDependencies")
    private boolean includeExternalDependencies;

    @Override
    public void execute() throws MojoExecutionException {
        if (session == null || session.getProjects() == null || session.getProjects().isEmpty()) {
            throw new MojoExecutionException("No Maven reactor projects are available to analyze");
        }
        if (!project.equals(session.getProjects().get(0))) {
            return;
        }
        boolean buildLevels = "build-levels".equals(diagramType);
        boolean dependencyOnly = "dependency".equals(diagramType);
        if (!buildLevels && !dependencyOnly && !"full".equals(diagramType)) {
            throw new MojoExecutionException(
                    "diagramType must be 'build-levels', 'full' or 'dependency', not '" + diagramType + "'");
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
                ArchitectureGraph graph = scanner.scan(session.getProjects(), includeTestDependencies,
                        includeExternalDependencies);
                diagram = new MermaidGenerator().generate(graph, !dependencyOnly && includeModuleHierarchy,
                        dependencyOnly || includeDependencies);
            }
            new DiagramWriter().write(outputFile.toPath(), diagram);
            getLog().info("Mermaid architecture diagram: " + outputFile.getAbsolutePath());
        } catch (IllegalArgumentException | IOException exception) {
            throw new MojoExecutionException("Could not generate architecture diagram: " + exception.getMessage(),
                    exception);
        }
    }
}
