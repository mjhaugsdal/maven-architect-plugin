package io.github.mjhaugsdal.architecture.writer;

import java.io.IOException;
import java.nio.file.Path;

/** Persists nonempty Mermaid diagrams as UTF-8, without touching unchanged output. */
public final class DiagramWriter {
    public void write(Path destination, String content) throws IOException {
        if (content == null || !content.startsWith("flowchart TD\n") || content.isBlank()) {
            throw new IllegalArgumentException("Cannot write an empty or invalid Mermaid diagram");
        }
        new TextFileWriter().write(destination, content);
    }
}
