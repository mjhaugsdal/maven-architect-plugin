package io.github.mjhaugsdal.architecture.mojo;

import io.github.mjhaugsdal.architecture.generator.MermaidGenerator;
import io.github.mjhaugsdal.architecture.model.ArchitectureGraph;
import io.github.mjhaugsdal.architecture.scanner.ProjectScanner;
import io.github.mjhaugsdal.architecture.writer.DiagramWriter;
import java.io.File;
import java.io.IOException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/** Generates a Mermaid diagram of the currently selected Maven reactor. */
@Mojo(name = "diagram", aggregator = true, defaultPhase = LifecyclePhase.NONE, threadSafe = true)
public final class DiagramMojo extends AbstractMojo {
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** Destination for the Mermaid diagram; override with -Darchitecture.outputFile. */
    @Parameter(defaultValue = "${project.build.directory}/architecture/architecture.mmd",
            property = "architecture.outputFile")
    private File outputFile;

    /** Include edges for aggregator modules and inherited parent POMs. */
    @Parameter(defaultValue = "true", property = "architecture.includeModuleHierarchy")
    private boolean includeModuleHierarchy;

    /** Include direct dependencies between reactor projects. */
    @Parameter(defaultValue = "true", property = "architecture.includeDependencies")
    private boolean includeDependencies;

    /** Include declared test-scope dependencies in addition to compile and runtime. */
    @Parameter(defaultValue = "false", property = "architecture.includeTestDependencies")
    private boolean includeTestDependencies;

    /** Reserved for a later release; external dependency diagrams are not yet supported. */
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
        if (includeExternalDependencies) {
            throw new MojoExecutionException("includeExternalDependencies is not supported in version 0.0.1");
        }
        if (outputFile == null || outputFile.isDirectory()) {
            throw new MojoExecutionException("outputFile must point to a writable file, not a directory");
        }
        try {
            ArchitectureGraph graph = new ProjectScanner().scan(session.getProjects(), includeTestDependencies);
            String diagram = new MermaidGenerator().generate(graph, includeModuleHierarchy, includeDependencies);
            new DiagramWriter().write(outputFile.toPath(), diagram);
            getLog().info("Mermaid architecture diagram: " + outputFile.getAbsolutePath());
        } catch (IllegalArgumentException | IOException exception) {
            throw new MojoExecutionException("Could not generate architecture diagram: " + exception.getMessage(),
                    exception);
        }
    }
}
