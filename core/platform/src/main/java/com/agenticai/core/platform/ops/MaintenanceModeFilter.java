package com.agenticai.core.platform.ops;

import com.agenticai.core.json.JsonMappers;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Enforces platform maintenance mode: while it is on, every {@code /api/**} request answers {@code
 * 503 {"error":"maintenance","message":...}} with a {@code Retry-After}, except
 *
 * <ul>
 *   <li>{@code /api/platform/**} - the console that turns it back off;
 *   <li>{@code /api/public/status} - how every UI learns maintenance is on;
 *   <li>{@code /api/system/status/live} - the logged-out liveness probe;
 *   <li>{@code /api/billing/webhook/**} - payment-provider callbacks, which must never be lost to a
 *       maintenance window (the {@code /webhook/**} payment and alert callbacks are outside {@code
 *       /api/**} and never touched);
 *   <li>CORS preflights ({@code OPTIONS});
 *   <li>callers holding {@code ROLE_platform-admin}, so operators can verify the platform before
 *       reopening it.
 * </ul>
 *
 * Actuator health is outside {@code /api/**} and therefore never blocked, so readiness probes do
 * not cycle replicas during a window.
 *
 * <p><b>Ordering.</b> A plain {@code Filter} bean, registered by Spring Boot at {@link
 * Ordered#LOWEST_PRECEDENCE}, i.e. AFTER Spring Security's filter chain (order -100): the JWT has
 * been authenticated, so the platform-admin bypass can read the {@link SecurityContextHolder}. The
 * flip side: a request Spring Security already rejected (401/403) never reaches this filter, which
 * is fine.
 *
 * <p><b>Cost.</b> One volatile read of {@link PlatformBroadcastService}'s cached snapshot per
 * request (refreshed from the DB at most every 10 s per replica).
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class MaintenanceModeFilter extends OncePerRequestFilter {

    static final String PLATFORM_ADMIN = "ROLE_platform-admin";
    static final String RETRY_AFTER_SECONDS = "120";
    private static final ObjectMapper JSON = JsonMappers.plain();
    private static final List<String> EXEMPT_PREFIXES =
            List.of("/api/platform/", "/api/billing/webhook/");
    private static final List<String> EXEMPT_EXACT =
            List.of(
                    "/api/platform",
                    "/api/public/status",
                    "/api/system/status/live",
                    "/api/billing/webhook");

    private final PlatformBroadcastService broadcast;

    public MaintenanceModeFilter(PlatformBroadcastService broadcast) {
        this.broadcast = broadcast;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isBlockable(request) || !broadcast.maintenanceActive() || isPlatformAdmin()) {
            chain.doFilter(request, response);
            return;
        }
        PlatformBroadcastService.MaintenanceState state = broadcast.maintenance();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "maintenance");
        body.put(
                "message",
                state.message() == null || state.message().isBlank()
                        ? "The platform is undergoing maintenance. Please try again shortly."
                        : state.message());
        body.put("endsAt", state.endsAt() == null ? null : state.endsAt().toString());
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setHeader("Retry-After", RETRY_AFTER_SECONDS);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(JSON.writeValueAsString(body));
    }

    /**
     * A {@code /api/**} request that maintenance applies to at all (method + path only; no role
     * check).
     */
    static boolean isBlockable(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        if (!path.startsWith("/api/")) {
            return false;
        }
        String normalized = path.replaceAll("/+$", "");
        if (EXEMPT_EXACT.contains(normalized)) {
            return false;
        }
        for (String prefix : EXEMPT_PREFIXES) {
            if (path.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    static boolean isPlatformAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getAuthorities() == null) {
            return false;
        }
        for (GrantedAuthority a : auth.getAuthorities()) {
            if (PLATFORM_ADMIN.equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
