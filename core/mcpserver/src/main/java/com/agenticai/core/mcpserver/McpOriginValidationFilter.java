package com.agenticai.core.mcpserver;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Origin validation for the {@code /mcp} endpoint, required by the MCP Streamable HTTP transport
 * ("Servers MUST validate the Origin header on all incoming connections to prevent DNS rebinding
 * attacks. If the Origin header is present and invalid, servers MUST respond with HTTP 403").
 *
 * <p>Rules: no {@code Origin} header (every non-browser MCP client: Claude Desktop, Cursor, SDKs,
 * curl) passes; a present Origin passes only when it is in the configured allow-list
 * ({@code app.mcpserver.origin.allowed}, exact scheme+host+port match, no wildcards). The request's
 * own {@code Host} is deliberately NOT treated as "same origin": under DNS rebinding the attacker's
 * page and the rebound Host are the same hostname, which is the attack.
 *
 * <p>Modes ({@code app.mcpserver.origin.mode}): {@code enforce} (default) rejects with 403 and a
 * JSON-RPC error body without an id; {@code report} logs the would-be rejection and lets the
 * request through, for a migration window; {@code off} disables the check.
 */
class McpOriginValidationFilter extends OncePerRequestFilter {

    enum Mode {
        ENFORCE,
        REPORT,
        OFF;

        static Mode parse(String raw) {
            if (raw == null) {
                return ENFORCE;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "off", "false" -> OFF;
                case "report", "warn" -> REPORT;
                default -> ENFORCE;
            };
        }
    }

    private static final Logger log = LoggerFactory.getLogger(McpOriginValidationFilter.class);

    private final Mode mode;
    private final Set<String> allowed;

    McpOriginValidationFilter(Mode mode, String allowedCsv) {
        this.mode = mode;
        this.allowed = allowedCsv == null ? Set.of() : Arrays.stream(allowedCsv.split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).map(McpOriginValidationFilter::normalize)
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (mode == Mode.OFF || origin == null || isAllowed(origin)) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("Inbound MCP request with disallowed Origin '{}' ({})", sanitize(origin),
            mode == Mode.ENFORCE ? "rejected" : "report-only");
        if (mode == Mode.REPORT) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"Forbidden: Origin not allowed.\"}}");
    }

    boolean isAllowed(String origin) {
        return allowed.contains(normalize(origin));
    }

    /** Scheme and host are case-insensitive, a trailing slash is not part of an origin. */
    static String normalize(String origin) {
        String s = origin.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.toLowerCase(Locale.ROOT);
    }

    private static String sanitize(String value) {
        String v = value.length() > 200 ? value.substring(0, 200) : value;
        return v.replaceAll("[\\r\\n]", "_");
    }
}
