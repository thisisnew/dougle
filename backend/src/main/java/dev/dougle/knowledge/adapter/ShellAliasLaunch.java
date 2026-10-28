package dev.dougle.knowledge.adapter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class ShellAliasLaunch {
    private static final String PREFIX = "dougle-alias-";
    private static final Map<String, Plan> PLANS = new ConcurrentHashMap<>();
    private static final Set<String> CLAUDE_ENV = Set.of("CLAUDE_CONFIG_DIR", "ANTHROPIC_BASE_URL",
            "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX",
            "AWS_PROFILE", "AWS_REGION", "AWS_DEFAULT_REGION", "ANTHROPIC_VERTEX_PROJECT_ID", "CLOUD_ML_REGION");
    private static final Set<String> CODEX_ENV = Set.of("OPENAI_API_KEY", "OPENAI_BASE_URL");
    private record Plan(String executable, Map<String, String> environment) {
        @Override public String toString() { return "ShellAliasLaunch"; }
    }

    static String register(String provider, String label, String body, Path home, List<Path> directories) {
        try {
            List<String> tokens = tokens(body);
            if (tokens.isEmpty()) return null;
            int position = tokens.getFirst().equals("env") ? 1 : 0;
            var environment = new LinkedHashMap<String, String>();
            Set<String> allowed = provider.equals("claude") ? CLAUDE_ENV : CODEX_ENV;
            while (position < tokens.size() && tokens.get(position).contains("=")) {
                String assignment = tokens.get(position++);
                int separator = assignment.indexOf('=');
                String name = assignment.substring(0, separator);
                if (!allowed.contains(name)) return null;
                String value = assignment.substring(separator + 1);
                if (name.equals("CLAUDE_CONFIG_DIR")) {
                    value = expandHome(value, home);
                    if (!Path.of(value).isAbsolute()) return null;
                }
                if (value.contains("$")) return null;
                environment.put(name, value);
            }
            if (environment.isEmpty() || position != tokens.size() - 1) return null;
            String target = expandHome(tokens.get(position), home);
            if (target.contains("$")) return null;
            Path path = Path.of(target);
            if (!path.getFileName().toString().matches(provider + "(?:[-_.][A-Za-z0-9._-]+)?")) return null;
            List<Path> choices = path.isAbsolute() ? List.of(path)
                    : directories.stream().filter(Path::isAbsolute).map(directory -> directory.resolve(path)).toList();
            for (Path executable : choices) {
                if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) continue;
                String identity = home + "\n" + provider + "\n" + label + "\n" + executable + "\n" + environment;
                String key = PREFIX + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(identity.getBytes(StandardCharsets.UTF_8)));
                if (PLANS.size() >= 1024 && !PLANS.containsKey(key)) return null;
                PLANS.putIfAbsent(key, new Plan(executable.toString(), Map.copyOf(environment)));
                return key;
            }
        } catch (IllegalArgumentException | java.security.NoSuchAlgorithmException ignored) { }
        return null;
    }

    static boolean known(String command) { return PLANS.containsKey(command); }

    static String executable(String command) {
        Plan plan = PLANS.get(command);
        if (plan != null) return plan.executable();
        if (command.startsWith(PREFIX)) throw new IllegalArgumentException("Alias configuration changed; reconnect required");
        return command;
    }

    static void apply(ProcessBuilder builder) {
        String command = builder.command().getFirst();
        String executable = executable(command);
        Plan plan = PLANS.get(command);
        if (plan == null) return;
        var arguments = new ArrayList<>(builder.command());
        arguments.set(0, executable);
        builder.command(arguments);
        builder.environment().putAll(plan.environment());
    }

    private static String expandHome(String value, Path home) {
        for (String prefix : List.of("~/", "$HOME/", "${HOME}/")) {
            if (value.startsWith(prefix)) return home.resolve(value.substring(prefix.length())).toString();
        }
        return value;
    }

    private static List<String> tokens(String body) {
        if (body.length() > 8192 || body.codePoints().anyMatch(Character::isISOControl)
                || body.matches("(?s).*[`\\\\;|&<>].*") || body.contains("$(")) return List.of();
        var result = new ArrayList<String>();
        var token = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (char character : body.toCharArray()) {
            if (quote != 0) {
                if (character == quote) quote = 0;
                else token.append(character);
            } else if (character == '\'' || character == '"') {
                quote = character;
                started = true;
            } else if (Character.isWhitespace(character)) {
                if (started) { result.add(token.toString()); token.setLength(0); started = false; }
            } else { token.append(character); started = true; }
        }
        if (quote != 0) return List.of();
        if (started) result.add(token.toString());
        return result;
    }
}
