package dev.knowledgeforge.sources.application;

import java.util.regex.Pattern;

public record SourceAddress(String kind, String location) {
    private static final Pattern REPOSITORY = Pattern.compile(
            "^https://github\\.com(?::443)?(/[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+/?)(?:\\?tab=readme-ov-file)?(?:#readme)?$",
            Pattern.CASE_INSENSITIVE);

    public static SourceAddress resolve(String kind, String location) {
        String address = location.strip();
        if (!"URL".equals(kind) && !"GITHUB".equals(kind)) return new SourceAddress(kind, address);
        var repository = REPOSITORY.matcher(address);
        if (repository.matches() && !repository.group(1).matches(".*/\\.{1,2}/?")) {
            return new SourceAddress("GITHUB", "https://github.com" + repository.group(1));
        }
        return new SourceAddress("URL", address);
    }
}
