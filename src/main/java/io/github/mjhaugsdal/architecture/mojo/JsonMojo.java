package io.github.mjhaugsdal.architecture.mojo;

import io.github.mjhaugsdal.architecture.generator.JsonGenerator;
import io.github.mjhaugsdal.architecture.scanner.ProjectScanner;
import io.github.mjhaugsdal.architecture.writer.TextFileWriter;
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

/** Exports the complete selected reactor architecture graph as versioned JSON. */
@Mojo(name = "json", aggregator = true, defaultPhase = LifecyclePhase.NONE, threadSafe = true)
public final class JsonMojo extends AbstractMojo {
    @Component
    private ArtifactHandlerManager artifactHandlerManager;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** Destination for the JSON graph; override with -Darchitecture.jsonOutputFile. */
    @Parameter(defaultValue = "${project.build.directory}/architecture/architecture.json",
            property = "architecture.jsonOutputFile")
    private File outputFile;

    @Override
    public void execute() throws MojoExecutionException {
        if (session == null || session.getProjects() == null || session.getProjects().isEmpty()) {
            throw new MojoExecutionException("No Maven reactor projects are available to analyze");
        }
        if (!project.equals(session.getProjects().get(0))) {
            return;
        }
        if (outputFile == null || outputFile.isDirectory()) {
            throw new MojoExecutionException("jsonOutputFile must point to a writable file, not a directory");
        }
        try {
            String json = new JsonGenerator().generate(
                    new ProjectScanner(artifactHandlerManager).scanComplete(session.getProjects()));
            new TextFileWriter().write(outputFile.toPath(), json);
            getLog().info("JSON architecture graph: " + outputFile.getAbsolutePath());
        } catch (IllegalArgumentException | IOException exception) {
            throw new MojoExecutionException("Could not export architecture graph: " + exception.getMessage(),
                    exception);
        }
    }
}
