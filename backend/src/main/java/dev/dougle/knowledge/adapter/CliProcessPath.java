package dev.dougle.knowledge.adapter;

import java.io.File;
import java.nio.file.Path;

final class CliProcessPath {
    private CliProcessPath() {}
    static void includeCommandDirectory(ProcessBuilder builder) {
        Path command = Path.of(ShellAliasLaunch.executable(builder.command().getFirst()));
        if (!command.isAbsolute()) return;
        String parent = command.getParent().toString();
        if (parent.contains(File.pathSeparator)) return;
        String existing = builder.environment().getOrDefault("PATH", "/usr/bin:/bin");
        var directories = new java.util.LinkedHashSet<String>();
        directories.add(parent);
        try {
            Path resolved = command.toRealPath().getParent();
            if (!resolved.toString().contains(File.pathSeparator)) directories.add(resolved.toString());
            for (int depth = 0; depth < 8 && resolved != null; depth++, resolved = resolved.getParent()) {
                Path bin = resolved.resolve("bin");
                if (!bin.toString().contains(File.pathSeparator)
                        && java.nio.file.Files.isRegularFile(bin.resolve("node"))
                        && java.nio.file.Files.isExecutable(bin.resolve("node"))) directories.add(bin.toString());
            }
        } catch (java.io.IOException | SecurityException exception) { }
        for (String directory : existing.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!directory.isBlank() && Path.of(directory).isAbsolute()) directories.add(directory);
        }
        for (String directory : java.util.List.of("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin")) {
            if (java.nio.file.Files.isDirectory(Path.of(directory))) directories.add(directory);
        }
        builder.environment().put("PATH", String.join(File.pathSeparator, directories));
    }
}
