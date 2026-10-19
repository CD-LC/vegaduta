package com.agenticai.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Keeps users whose only tenant role is {@code end-customer} out of console writes.
 *
 * <p>The realm defines {@code end-customer} as having "no access to any admin endpoint", and its
 * default permission bundle is empty, but every {@code /api/**} write without a specific rule falls
 * through to {@code anyRequest().authenticated()} and so was open to it. This filter denies those
 * users every mutating {@code /api/**} request except talking to an agent (chat, voice) and
 * managing their own account and notifications.
 *
 * <p>It is a filter, not a matcher rule, because Spring Security decides on the FIRST matching
 * rule: a leading "deny end-customer on writes" rule could not fall through to the rules after it
 * for everyone else. A user who also holds a builder or admin role is not affected; the role, not
 * per-user permission grants, decides.
 *
 * <p>It runs just before the {@code AuthorizationFilter}, after exception translation, so a denial
 * is the chain's standard 403 (and its {@code authz.denied} audit entry).
 */
public class EndCustomerWriteGuardFilter extends OncePerRequestFilter {

    static final String END_CUSTOMER = "ROLE_end-customer";

    /** Any of these roles means the user is not just an end customer. */
    static final Set<String> CONSOLE_ROLES =
            Set.of(
                    "ROLE_tenant-admin",
                    "ROLE_agent-builder",
                    "ROLE_delegated-user",
                    "ROLE_platform-admin");

    private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");

    /** Writes an end customer may make: talk to an agent, manage their own account. */
    static final List<RequestMatcher> ALLOWED =
            List.of(
                    post("/api/agents/*/chat"),
                    post("/api/agents/*/chat/attachments"),
                    post("/api/agents/*/chat/sessions/*/cancel"),
                    post("/api/agents/*/agui/run"),
                    post("/api/agents/*/turns/feedback"),
                    post("/api/voice/agents/*/token"),
                    post("/api/voice/agents/*/turn"),
                    post("/api/account/avatar"),
                    matcher(HttpMethod.PUT, "/api/account/phone"),
                    post("/api/notifications/read-all"),
                    post("/api/notifications/*/read"),
                    matcher(HttpMethod.DELETE, "/api/channels/google/personal/connection"));

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !WRITES.contains(request.getMethod())
                || !pathWithinApplication(request).startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isOnlyEndCustomer(SecurityContextHolder.getContext().getAuthentication())
                && ALLOWED.stream().noneMatch(m -> m.matches(request))) {
            throw new AccessDeniedException(
                    "end-customer accounts cannot make this change; ask a workspace admin");
        }
        chain.doFilter(request, response);
    }

    static boolean isOnlyEndCustomer(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        Set<String> roles =
                authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(java.util.stream.Collectors.toSet());
        return roles.contains(END_CUSTOMER) && roles.stream().noneMatch(CONSOLE_ROLES::contains);
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context)
                ? uri.substring(context.length())
                : uri;
    }

    private static RequestMatcher post(String pattern) {
        return matcher(HttpMethod.POST, pattern);
    }

    private static RequestMatcher matcher(HttpMethod method, String pattern) {
        return PathPatternRequestMatcher.withDefaults().matcher(method, pattern);
    }
}
