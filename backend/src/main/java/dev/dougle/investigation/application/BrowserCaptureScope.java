package dev.dougle.investigation.application;

import java.net.URI;
import java.util.List;
import java.util.Locale;

public record BrowserCaptureScope(String url, List<String> allowedGetPaths) {
    public BrowserCaptureScope {
        URI target = validateUrl(url);
        if (allowedGetPaths == null || allowedGetPaths.isEmpty() || allowedGetPaths.size() > 32) {
            throw new IllegalArgumentException("Approve between 1 and 32 exact GET paths");
        }
        allowedGetPaths = allowedGetPaths.stream().map(BrowserCaptureScope::validatePath).distinct().sorted().toList();
        url = target.toString();
        if (!allowedGetPaths.contains(target.getRawPath())) throw new IllegalArgumentException("Navigation path must be approved");
    }

    public String origin() { return "https://" + URI.create(url).getHost().toLowerCase(Locale.ROOT); }

    public boolean allows(String method, String requested, boolean document) {
        try {
            URI candidate = validateUrl(requested);
            return "GET".equals(method) && origin().equals("https://" + candidate.getHost().toLowerCase(Locale.ROOT))
                    && allowedGetPaths.contains(candidate.getRawPath()) && (!document || url.equals(candidate.toString()));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public String observationPath(String requested) {
        try {
            if (requested == null || requested.length() > 2048) return "[redacted]";
            URI original = URI.create(requested);
            if (original.getRawUserInfo() != null || original.getHost() == null || original.getPort() != -1) return "[redacted]";
            URI candidate = validateUrl(original.getScheme() + "://" + original.getHost() + original.getRawPath());
            return origin().equals("https://" + candidate.getHost().toLowerCase(Locale.ROOT))
                    ? candidate.getRawPath() : "[outside-approved-scope]";
        } catch (IllegalArgumentException invalid) {
            return "[redacted]";
        }
    }

    public String candidatePath(String method, String requested) {
        String path = observationPath(requested);
        return "GET".equals(method) && path.startsWith("/") && path.length() <= 160 && !allowedGetPaths.contains(path)
                ? path : null;
    }

    private static URI validateUrl(String value) {
        if (value == null || value.length() > 2048) throw new IllegalArgumentException("Invalid capture URL");
        URI uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getPort() != -1
                || !uri.getHost().matches("[a-zA-Z0-9.-]+") || uri.getHost().endsWith(".")) {
            throw new IllegalArgumentException("Public HTTPS URL without port, query, credentials or fragment required");
        }
        validatePath(uri.getRawPath());
        return uri;
    }

    private static String validatePath(String path) {
        if (path == null || path.length() > 512 || !path.matches("/[a-zA-Z0-9/_.,~-]*")
                || path.contains("//") || !URI.create(path).normalize().toString().equals(path)
                || path.endsWith("/..") || path.endsWith("/.")) {
            throw new IllegalArgumentException("Exact unencoded absolute path required");
        }
        return path;
    }
}
