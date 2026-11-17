package io.github.mjhaugsdal.architecture.writer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persists nonempty UTF-8 output without touching unchanged files. */
public final class TextFileWriter {
    public boolean write(Path destination, String content) throws IOException {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Cannot write empty output");
        }
        Path absolute = destination.toAbsolutePath().normalize();
        if (Files.exists(absolute) && content.equals(Files.readString(absolute, StandardCharsets.UTF_8))) {
            return false;
        }
        Files.createDirectories(absolute.getParent());
        Files.writeString(absolute, content, StandardCharsets.UTF_8);
        return true;
    }
}
