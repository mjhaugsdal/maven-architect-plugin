package io.github.mjhaugsdal.architecture.mojo;

import io.github.mjhaugsdal.architecture.analyzer.CycleAnalysis;
import io.github.mjhaugsdal.architecture.analyzer.DependencyCycleAnalyzer;
import io.github.mjhaugsdal.architecture.generator.CircularDependencyDiagramGenerator;
import io.github.mjhaugsdal.architecture.generator.CircularDependencyReportGenerator;
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
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/** Reports cyclic dependencies in the selected Maven reactor. */
@Mojo(name = "cycles", aggregator = true, threadSafe = true)
public final class CircularDependencyMojo extends AbstractMojo {
    @Component
    private ArtifactHandlerManager artifactHandlerManager;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** Also write a Mermaid diagram containing only cyclic components. */
    @Parameter(defaultValue = "false", property = "architecture.includeCycleDiagram")
    private boolean includeDiagram;

    /** Destination for the optional Mermaid diagram. */
    @Parameter(defaultValue = "${project.build.directory}/architecture/circular-dependencies.mmd",
            property = "architecture.cycleDiagramFile")
    private File diagramFile;

    @Override
    public void execute() throws MojoExecutionException {
        if (session == null || session.getProjects() == null || session.getProjects().isEmpty()) {
            throw new MojoExecutionException("No Maven reactor projects are available to analyze");
        }
        if (!project.equals(session.getProjects().get(0))) {
            return;
        }
        if (includeDiagram && (diagramFile == null || diagramFile.isDirectory())) {
            throw new MojoExecutionException("cycleDiagramFile must point to a writable file, not a directory");
        }
        try {
            ArchitectureGraph graph = new ProjectScanner(artifactHandlerManager)
                    .scanBuildOrder(session.getProjects());
            CycleAnalysis analysis = new DependencyCycleAnalyzer().analyze(graph);
            getLog().info(new CircularDependencyReportGenerator().generate(analysis));
            if (includeDiagram) {
                String diagram = new CircularDependencyDiagramGenerator().generate(graph, analysis);
                new DiagramWriter().write(diagramFile.toPath(), diagram);
                getLog().info("Mermaid circular dependency diagram: " + diagramFile.getAbsolutePath());
            }
        } catch (IllegalArgumentException | IOException exception) {
            throw new MojoExecutionException("Could not analyze circular dependencies: " + exception.getMessage(),
                    exception);
        }
    }
}
