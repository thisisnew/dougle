package dev.knowledgeforge.library.adapter.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class LocalRequestFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store");
        String host = request.getServerName();
        String origin = request.getHeader("Origin");
        boolean localHost = Set.of("localhost", "127.0.0.1", "[::1]").contains(host);
        boolean trustedOrigin = origin == null || Set.of(
                "http://127.0.0.1:5173", "http://localhost:5173",
                "http://127.0.0.1:" + request.getServerPort(),
                "http://localhost:" + request.getServerPort()).contains(origin);
        boolean mutation = !Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod());
        if (!localHost || !trustedOrigin || (mutation && !"1".equals(request.getHeader("X-Forge-Client")))) {
            response.setStatus(403);
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"status\":403,\"detail\":\"허용되지 않은 로컬 요청입니다.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
