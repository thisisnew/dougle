package dev.knowledgeforge.knowledge.adapter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;

final class CodexProfile {
    private CodexProfile() {}

    static Path prepare(String location) throws IOException {
        Path home = Path.of(location);
        if (!home.isAbsolute()) throw new IOException("Absolute profile required");
        requireDedicated(home.getParent().toRealPath().resolve(home.getFileName()));
        if (!Files.exists(home, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(home, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        return validate(home, false);
    }

    static void configure(ProcessBuilder builder, Path profile, Path directory) throws IOException {
        String isolatedHome = directory.toRealPath().toString();
        builder.environment().keySet().retainAll(Set.of("PATH", "USER", "LOGNAME", "LANG", "LC_ALL"));
        CliProcessPath.includeCommandDirectory(builder);
        builder.environment().put("HOME", isolatedHome);
        builder.environment().put("XDG_CONFIG_HOME", isolatedHome);
        builder.environment().put("TMPDIR", isolatedHome);
        builder.environment().put("CODEX_HOME", profile.toString());
    }

    static Path validate(Path home, boolean requireAuthentication) throws IOException {
        if (!home.isAbsolute() || Files.isSymbolicLink(home) || !Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Dedicated absolute profile required");
        }
        Path profile = home.toRealPath();
        requireDedicated(profile);
        if (!PosixFilePermissions.fromString("rwx------").containsAll(Files.getPosixFilePermissions(profile))) {
            throw new IOException("Private profile required");
        }
        Path auth = profile.resolve("auth.json");
        if ((requireAuthentication || Files.exists(auth, LinkOption.NOFOLLOW_LINKS))
                && (!Files.isRegularFile(auth, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(auth)
                || !PosixFilePermissions.fromString("rw-------").containsAll(Files.getPosixFilePermissions(auth)))) {
            throw new IOException("Private file login required");
        }
        Set<String> files = Set.of("auth.json", ".forge-stream.lock", ".sqlite-maintenance.lock", "version.json", "models_cache.json", ".last_migration", ".sandbox_migration", "installation_id");
        try (var entries = Files.list(profile)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (name.equals("log") && Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    try (var logs = Files.list(entry)) {
                        for (Path log : logs.toList()) {
                            if (!log.getFileName().toString().equals("codex-login.log")
                                    || !Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS)
                                    || !PosixFilePermissions.fromString("rw-------").containsAll(Files.getPosixFilePermissions(log))) {
                                throw new IOException("Unexpected login log");
                            }
                        }
                    }
                    continue;
                }
                if (Set.of("tmp", "skills").contains(name) && Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    if (name.equals("skills")) {
                        try (var skills = Files.list(entry)) {
                            for (Path skill : skills.toList()) {
                                if (!skill.getFileName().toString().equals(".system")
                                        || !Files.isDirectory(skill, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Custom skills forbidden");
                            }
                        }
                    }
                    continue;
                }
                if ((!files.contains(name) && !name.matches("(?:state|logs|goals|memories|queue)_[0-9]+\\.sqlite(?:-shm|-wal)?"))
                        || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Unexpected profile content");
                }
            }
        }
        return profile;
    }

    private static void requireDedicated(Path profile) throws IOException {
        for (String normal : List.of(Path.of(System.getProperty("user.home"), ".codex").toString(),
                System.getenv().getOrDefault("CODEX_HOME", Path.of(System.getProperty("user.home"), ".codex").toString()))) {
            Path usual = Path.of(normal).toAbsolutePath().normalize();
            if (Files.exists(usual)) usual = usual.toRealPath();
            if (profile.startsWith(usual) || usual.startsWith(profile)) throw new IOException("Shared profile forbidden");
        }
    }

}
