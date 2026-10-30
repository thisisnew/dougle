package dev.dougle.search.adapter;

import dev.dougle.search.application.SearchIndex.Hit;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

final class NamedCodeFocus {
    private static final Pattern NAME = Pattern.compile("(?<![A-Za-z0-9_$])[A-Z][A-Za-z0-9_$]*(?![A-Za-z0-9_$])");

    static Set<String> targets(String question, Collection<Hit> candidates) {
        var targets = new LinkedHashSet<String>();
        var names = NAME.matcher(question);
        while (names.find()) {
            String name = names.group();
            var declaration = Pattern.compile("\\b(?:class|interface|enum|record)\\s+" + Pattern.quote(name) + "(?![A-Za-z0-9_$])");
            if (candidates.stream().filter(NamedCodeFocus::javaSource).anyMatch(hit ->
                    hit.path().replace('\\', '/').matches("(?:.*/)?" + Pattern.quote(name) + "\\.java")
                            || declaration.matcher(hit.snippet()).find())) targets.add(name);
        }
        return Set.copyOf(targets);
    }

    static boolean allows(Hit hit, Set<String> targets) {
        if (targets.isEmpty() || !javaSource(hit) || hit.score() >= 0.5f) return true;
        String evidence = hit.path() + "\n" + hit.snippet();
        return targets.stream().anyMatch(name -> Pattern.compile("(?<![A-Za-z0-9_$])" + Pattern.quote(name)
                + "(?![A-Za-z0-9_$])").matcher(evidence).find());
    }

    private static boolean javaSource(Hit hit) {
        return hit.path().toLowerCase(java.util.Locale.ROOT).endsWith(".java");
    }
}
