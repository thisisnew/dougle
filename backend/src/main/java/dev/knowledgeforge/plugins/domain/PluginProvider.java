package dev.knowledgeforge.plugins.domain;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

public enum PluginProvider {
    NOTION, ATLASSIAN, MYSQL;

    public static Optional<PluginProvider> forWebLink(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return Optional.empty();
        String host = uri.getHost().toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
        if (host.endsWith(".atlassian.net")) return Optional.of(ATLASSIAN);
        if (host.equals("app.notion.com") || host.equals("notion.so") || host.endsWith(".notion.so")
                || host.equals("notion.site") || host.endsWith(".notion.site")
                || ((host.equals("notion.com") || host.equals("www.notion.com")) && uri.getPath().startsWith("/p/"))) {
            return Optional.of(NOTION);
        }
        return Optional.empty();
    }
}
