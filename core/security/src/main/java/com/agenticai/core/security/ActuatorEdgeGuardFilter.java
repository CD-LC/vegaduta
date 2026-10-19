package com.agenticai.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Keeps {@code /actuator/**} (other than health and info) off the internet
 * without depending on whatever sits in front of core (security audit
 * 2026-09-18).
 *
 * <p>{@code /actuator/prometheus} is {@code permitAll()} because a Prometheus
 * server scrapes with no JWT, and "not publicly reachable" was delegated to the
 * Caddy route that blocks {@code /actuator/*}. That held on the compose stack.
 * Production moved to Azure Container Apps behind Cloudflare, where there is no
 * Caddy - and {@code api.example.com/actuator/prometheus} served 358 KB of
 * internals (every route, a tenant name, Kafka client ids, the security filter
 * chain layout) to anyone. An edge rule can close that again, but an edge rule
 * is exactly the kind of control that silently stops existing on the next
 * platform move.
 *
 * <p>The test used here is one an outside caller cannot pass: every path from
 * the internet to core goes through a proxy that ADDS a forwarding header
 * (Cloudflare, the Container Apps ingress, Caddy), and a client cannot make a
 * proxy omit it. The in-network scrape ({@code core:8080} from the Prometheus
 * container) carries none. So: forwarded request + non-public actuator path =
 * 404, unless it presents the optional scrape token - which exists for a future
 * scraper that has to come through the ingress.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class ActuatorEdgeGuardFilter extends OncePerRequestFilter {

    private static final String[] FORWARDING_HEADERS = {
        "X-Forwarded-For", "CF-Connecting-IP", "CF-Ray", "X-Envoy-External-Address", "Forwarded", "Via",
    };

    private final String scrapeToken;

    public ActuatorEdgeGuardFilter(@Value("${app.actuator.scrape-token:}") String scrapeToken) {
        this.scrapeToken = scrapeToken == null ? "" : scrapeToken.trim();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        PathClass c = classify(request.getRequestURI());
        return c == PathClass.NOT_ACTUATOR || c == PathClass.PUBLIC_ACTUATOR;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        if (classify(request.getRequestURI()) == PathClass.MALFORMED) {
            // Double-encoded / undecodable path aimed at the actuator (SEC-042): refuse, never guess the route.
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        if (arrivedThroughAProxy(request) && !presentsScrapeToken(request)) {
            // 404, not 403: there is nothing here as far as the internet is concerned.
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        chain.doFilter(request, response);
    }

    /** How the guard sees a request path. */
    enum PathClass { NOT_ACTUATOR, PUBLIC_ACTUATOR, GUARDED, MALFORMED }

    /**
     * SEC-042: classify on the path the container / Spring MVC will actually route, not the raw request
     * URI. The raw URI is percent-decoded ONCE, {@code ;path-parameters} are stripped from every segment,
     * backslashes count as separators, empty and {@code .} segments are dropped, {@code ..} is resolved and
     * the comparison is case-insensitive. So {@code /%61ctuator/prometheus}, {@code //actuator/env},
     * {@code /./actuator}, {@code /actuator;jsessionid=x/prometheus}, {@code /actuator/health/..;/prometheus}
     * and {@code /ACTUATOR/prometheus} are all guarded.
     *
     * <p>The path is never decoded twice for routing; instead a once-decoded path that still carries an
     * encoded separator ({@code %2F %2E %5C %25}) next to an actuator fragment, or that would decode AGAIN
     * to an actuator path, is {@link PathClass#MALFORMED} (400). An undecodable path that mentions the
     * actuator is MALFORMED too.
     */
    static PathClass classify(String rawUri) {
        if (rawUri == null || rawUri.isEmpty()) {
            return PathClass.NOT_ACTUATOR;
        }
        String once = percentDecode(rawUri);
        if (once == null) {
            return rawUri.toLowerCase(Locale.ROOT).contains("ctuator") ? PathClass.MALFORMED : PathClass.NOT_ACTUATOR;
        }
        String normalised = normalise(once);
        if (once.indexOf('%') >= 0) {
            String lower = once.toLowerCase(Locale.ROOT);
            boolean encodedSeparator = lower.contains("%2f") || lower.contains("%2e")
                || lower.contains("%5c") || lower.contains("%25");
            String twice = percentDecode(once);
            boolean hidesActuator = twice == null
                ? lower.contains("ctuator")
                : isActuator(normalise(twice));
            if (hidesActuator || (encodedSeparator && (lower.contains("ctuator") || isActuator(normalised)))) {
                return PathClass.MALFORMED;
            }
        }
        if (!isActuator(normalised)) {
            return PathClass.NOT_ACTUATOR;
        }
        return normalised.equals("/actuator/health") || normalised.startsWith("/actuator/health/")
            || normalised.equals("/actuator/info") ? PathClass.PUBLIC_ACTUATOR : PathClass.GUARDED;
    }

    private static boolean isActuator(String normalisedLowerPath) {
        return normalisedLowerPath.startsWith("/actuator");
    }

    /** Strict single percent-decode (no '+' to space); null when malformed. */
    private static String percentDecode(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        byte[] in = s.getBytes(StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(in.length);
        for (int i = 0; i < in.length; i++) {
            byte b = in[i];
            if (b == '%') {
                if (i + 2 >= in.length) {
                    return null;
                }
                int hi = Character.digit((char) in[i + 1], 16);
                int lo = Character.digit((char) in[i + 2], 16);
                if (hi < 0 || lo < 0) {
                    return null;
                }
                out.write((hi << 4) | lo);
                i += 2;
            } else {
                out.write(b);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Lower-cased; ;params stripped; //, ., .. resolved; backslash treated as '/'. Always starts with '/'. */
    private static String normalise(String decoded) {
        String p = decoded.replace('\\', '/');
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        java.util.ArrayDeque<String> out = new java.util.ArrayDeque<>();
        for (String seg : p.split("/", -1)) {
            int semi = seg.indexOf(';');
            if (semi >= 0) {
                seg = seg.substring(0, semi);
            }
            if (seg.isEmpty() || seg.equals(".")) {
                continue;
            }
            if (seg.equals("..")) {
                out.pollLast();
                continue;
            }
            out.addLast(seg);
        }
        return ("/" + String.join("/", out)).toLowerCase(Locale.ROOT);
    }

    private static boolean arrivedThroughAProxy(HttpServletRequest request) {
        for (String header : FORWARDING_HEADERS) {
            if (request.getHeader(header) != null) {
                return true;
            }
        }
        return false;
    }

    private boolean presentsScrapeToken(HttpServletRequest request) {
        if (scrapeToken.isEmpty()) {
            return false;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        return MessageDigest.isEqual(
            authorization.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8),
            scrapeToken.getBytes(StandardCharsets.UTF_8)
        );
    }
}
