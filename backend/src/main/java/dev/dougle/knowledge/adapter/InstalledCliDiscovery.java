package dev.dougle.knowledge.adapter;

import dev.dougle.knowledge.application.CliDiscovery;
import dev.dougle.knowledge.application.CuratorModel.Provider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class InstalledCliDiscovery implements CliDiscovery {
    private final Path home;
    private final List<Path> environment;

    public InstalledCliDiscovery() {
        this(Path.of(System.getProperty("user.home")),
                java.util.stream.Stream.concat(java.util.Arrays.stream(System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator))
                        .filter(value -> !value.isBlank()).map(Path::of).filter(Path::isAbsolute),
                        java.util.stream.Stream.of(Path.of("/opt/homebrew/bin"), Path.of("/usr/local/bin"))).toList());
    }

    InstalledCliDiscovery(Path home, List<Path> environment) {
        this.home = home;
        this.environment = environment;
    }

    @Override public List<Candidate> find(Provider provider) {
        var directories = new ArrayList<Path>();
        for (String suffix : List.of(".local/bin", "bin", ".npm-global/bin", ".volta/bin", ".asdf/shims", ".bun/bin", "Library/pnpm", ".local/share/pnpm")) {
            directories.add(home.resolve(suffix));
        }
        for (String suffix : List.of(".nvm/versions/node", ".local/share/fnm/node-versions", "Library/Application Support/fnm/node-versions", ".asdf/installs/nodejs", ".local/share/mise/installs/node")) {
            for (Path version : children(home.resolve(suffix), 64)) {
                directories.add(version.resolve(suffix.contains("fnm") ? "installation/bin" : "bin"));
            }
        }
        directories.addAll(environment);
        String name = provider.name().toLowerCase(Locale.ROOT);
        var candidates = new LinkedHashMap<Path, Candidate>();
        for (Path directory : directories.stream().filter(Path::isAbsolute).distinct().limit(256).toList()) {
            add(candidates, directory.resolve(name), name);
            for (Path file : children(directory, 2048)) {
                String label = file.getFileName().toString();
                if (label.matches("(?:" + name + "[-_.][A-Za-z0-9._-]+|[A-Za-z0-9._-]+[-_.]" + name + ")")) {
                    add(candidates, file, label);
                }
            }
        }
        for (String profile : List.of(".zshrc", ".zprofile", ".bashrc", ".bash_profile", ".profile")) {
            Path path = home.resolve(profile);
            try {
                if (!Files.isRegularFile(path) || Files.size(path) > 131072) continue;
                String content;
                try (var input = Files.newInputStream(path)) {
                    byte[] bytes = input.readNBytes(131073);
                    if (bytes.length > 131072) continue;
                    content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                }
                var aliases = java.util.regex.Pattern.compile("(?m)^\\s*alias\\s+([A-Za-z0-9][A-Za-z0-9._-]*)=(['\"])([^\\r\\n]+)\\2\\s*$").matcher(content);
                while (aliases.find()) {
                    String alias = ShellAliasLaunch.register(name, aliases.group(1), aliases.group(3), home, directories);
                    if (alias != null) candidates.put(Path.of(alias), new Candidate(aliases.group(1), alias));
                    else addForwarder(candidates, directories, name, aliases.group(1), aliases.group(3));
                }
                var functions = java.util.regex.Pattern.compile("(?m)^(?:function\\s+)?([A-Za-z0-9][A-Za-z0-9._-]*)\\s*\\(\\)\\s*\\{\\s*(?:command\\s+|exec\\s+)?([^;{}\\r\\n]+)\\s+\"\\$@\"\\s*;?\\s*\\}\\s*$").matcher(content);
                while (functions.find()) addForwarder(candidates, directories, name, functions.group(1), functions.group(2));
            } catch (java.io.IOException | SecurityException exception) { }
        }
        return List.copyOf(candidates.values()).stream().limit(32).toList();
    }

    private void addForwarder(LinkedHashMap<Path, Candidate> candidates, List<Path> directories,
            String provider, String label, String target) {
        target = target.strip();
        if ((target.startsWith("\"") && target.endsWith("\"")) || (target.startsWith("'") && target.endsWith("'"))) {
            target = target.substring(1, target.length() - 1);
        }
        if (target.startsWith("~/")) target = home.resolve(target.substring(2)).toString();
        if (target.startsWith("$HOME/")) target = home.resolve(target.substring(6)).toString();
        if (target.isBlank() || target.matches(".*[;$`|&<>\\\\{}\\r\\n].*")) return;
        try {
            Path path = Path.of(target);
            if (!path.getFileName().toString().matches(provider + "(?:[-_.][A-Za-z0-9._-]+)?")) return;
            if (path.isAbsolute()) add(candidates, path, label);
            else if (!target.contains("/") && !target.contains(" ")) {
                for (Path directory : directories) {
                    if (directory.isAbsolute()) add(candidates, directory.resolve(target), label);
                }
            }
        } catch (java.nio.file.InvalidPathException exception) { }
    }

    private List<Path> children(Path directory, int limit) {
        if (!Files.isDirectory(directory)) return List.of();
        try (var stream = Files.list(directory)) {
            return stream.limit(limit).sorted().toList();
        } catch (java.io.IOException | SecurityException exception) { return List.of(); }
    }

    private void add(LinkedHashMap<Path, Candidate> candidates, Path path, String name) {
        try {
            if (!Files.isRegularFile(path) || !Files.isExecutable(path)) return;
            Path real = path.toRealPath();
            if (real.toString().contains(java.io.File.pathSeparator)
                    || real.toString().codePoints().anyMatch(Character::isISOControl)) return;
            candidates.putIfAbsent(real, new Candidate(name, path.toString()));
        } catch (java.io.IOException | SecurityException exception) { }
    }
}
