package dev.knowledgeforge.knowledge.adapter;

import java.io.File;
import java.nio.file.Path;

final class CliProcessPath {
    private CliProcessPath() {}
    static void includeCommandDirectory(ProcessBuilder builder) {
        Path command = Path.of(builder.command().getFirst());
        if (!command.isAbsolute()) return;
        String parent = command.getParent().toString();
        if (parent.contains(File.pathSeparator)) return;
        String existing = builder.environment().getOrDefault("PATH", "/usr/bin:/bin");
        builder.environment().put("PATH", parent + File.pathSeparator + existing);
    }
}
