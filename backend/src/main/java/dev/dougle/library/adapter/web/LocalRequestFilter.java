package dev.dougle.library.adapter.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class LocalRequestFilter extends OncePerRequestFilter {
    private final dev.dougle.desktop.DesktopSession desktop;

    public LocalRequestFilter(dev.dougle.desktop.DesktopSession desktop) {
        this.desktop = desktop;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store");
        if (desktop.enabled()) {
            response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; "
                    + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; "
                    + "connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
            response.setHeader("Referrer-Policy", "same-origin");
        }
        String host = request.getServerName();
        String origin = request.getHeader("Origin");
        boolean localHost = Set.of("localhost", "127.0.0.1", "[::1]").contains(host);
        boolean trustedOrigin = origin == null || Set.of(
                "http://127.0.0.1:" + request.getServerPort(),
                "http://localhost:" + request.getServerPort()).contains(origin)
                || (!desktop.enabled() && Set.of("http://127.0.0.1:5173", "http://localhost:5173").contains(origin == null ? "" : origin));
        String path = request.getServletPath();
        if (path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
        boolean staticUi = Set.of("GET", "HEAD").contains(request.getMethod())
                && (path.equals("/") || path.equals("/index.html") || path.matches("/assets/[A-Za-z0-9_-]+\\.(js|css|woff2?|png|svg|ico)"));
        boolean authenticated = !desktop.enabled() || staticUi || desktop.authenticates(request.getHeader("X-Forge-Session"));
        boolean mutation = !Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod());
        if (!authenticated || !localHost || !trustedOrigin || (mutation && !"1".equals(request.getHeader("X-Forge-Client")))) {
            response.setStatus(403);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"status\":403,\"detail\":\"허용되지 않은 로컬 요청입니다.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
