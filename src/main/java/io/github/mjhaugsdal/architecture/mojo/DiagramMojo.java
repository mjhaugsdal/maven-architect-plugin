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

    /** Include direct dependencies whose artifacts are outside the selected reactor. */
    @Parameter(defaultValue = "false", property = "architecture.includeExternalDependencies")
    private boolean includeExternalDependencies;

    @Parameter(defaultValue = "false", property = "architecture.verbose")
    private boolean verbose;

    @Override
    public void execute() throws MojoExecutionException {
        if (session == null || session.getProjects() == null || session.getProjects().isEmpty()) {
            throw new MojoExecutionException("No Maven reactor projects are available to analyze");
        }
        if (!project.equals(session.getProjects().get(0))) {
            return;
        }
        boolean buildLevels = validateParameters();
        try {
            ProjectScanner scanner = new ProjectScanner(artifactHandlerManager);
            ArchitectureGraph graph;
            String diagram;
            if (buildLevels) {
                graph = scanner.scanBuildOrder(session.getProjects());
                diagram = new BuildLevelGenerator().generate(graph);
            } else {
                graph = scanner.scan(session.getProjects(), includeTestDependencies, includeExternalDependencies);
                diagram = new MermaidGenerator().generate(graph, includeModuleHierarchy, includeDependencies);
            }
            boolean written = new DiagramWriter().write(outputFile.toPath(), diagram);
            if (verbose) {
                getLog().info(summary(diagram, written));
            } else {
                getLog().info("Mermaid architecture diagram: " + outputFile.getAbsolutePath());
            }
        } catch (IllegalArgumentException | IOException exception) {
            throw new MojoExecutionException("Could not generate architecture diagram: " + exception.getMessage(),
                    exception);
        }
    }

    private boolean validateParameters() throws MojoExecutionException {
        boolean buildLevels = "build-levels".equals(diagramType);
        if (!buildLevels && !"full".equals(diagramType)) {
            throw new MojoExecutionException("Invalid architecture.diagramType '" + diagramType
                    + "'; valid values are 'build-levels' and 'full'");
        }
        if (buildLevels && (!includeModuleHierarchy || !includeDependencies || includeTestDependencies)) {
            throw new MojoExecutionException("architecture.includeModuleHierarchy, "
                    + "architecture.includeDependencies, and architecture.includeTestDependencies only apply to "
                    + "diagramType='full'; use diagramType='full' or reset these conflicting settings");
        }
        if (outputFile == null || outputFile.isDirectory()) {
            throw new MojoExecutionException("architecture.outputFile must point to a writable file, not a directory");
        }
        return buildLevels;
    }

    private String summary(String diagram, boolean written) {
        long renderedProjects = diagram.lines().filter(line -> line.matches("\\s*n\\d+\\[.*")).count();
        long edges = diagram.lines().filter(line -> line.contains(" -->") || line.contains(" -.-> ")).count();
        String filters = "reactor=selected";
        if ("build-levels".equals(diagramType)) {
            filters += ", dependencies=all scopes and types, standalone aggregators=omitted";
        } else {
            String scopes = includeTestDependencies ? "compile/runtime/test" : "compile/runtime";
            filters += ", hierarchy=" + (includeModuleHierarchy ? "included" : "excluded")
                    + ", dependencies=" + (includeDependencies ? scopes : "excluded")
                    + ", external dependencies="
                    + (includeDependencies && includeExternalDependencies ? "included" : "excluded");
        }
        return "Architecture diagram summary: " + session.getProjects().size() + " selected projects, "
                + renderedProjects + " rendered projects, " + edges + " edges; filters: " + filters
                + "; output: " + outputFile.getAbsolutePath() + (written ? " (written)" : " (unchanged)");
    }
}
