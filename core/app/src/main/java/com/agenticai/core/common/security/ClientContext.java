package com.agenticai.core.common.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Client context: metadata extracted from a request for fraud detection analysis. Captures IP,
 * User-Agent, TLS fingerprint, authentication state, etc.
 */
public class ClientContext {
    public final UUID tenantId;
    public final InetAddress ip;
    public final String userAgent;
    public final String tlsFingerprint; // TLS JA3 fingerprint
    public final UUID userId; // null for unauthenticated requests
    public final String sessionId; // Session identifier for rate limiting
    public final long requestTimestamp;

    public ClientContext(
            UUID tenantId,
            InetAddress ip,
            String userAgent,
            String tlsFingerprint,
            UUID userId,
            String sessionId) {
        this.tenantId = tenantId;
        this.ip = ip;
        this.userAgent = userAgent != null ? userAgent : "unknown";
        this.tlsFingerprint = tlsFingerprint;
        this.userId = userId;
        this.sessionId = sessionId != null ? sessionId : ip.getHostAddress();
        this.requestTimestamp = System.currentTimeMillis();
    }

    /**
     * Extract client context from an HTTP request. The client IP is the container-resolved peer
     * address; forwarding headers are not trusted.
     */
    public static ClientContext fromRequest(HttpServletRequest req, UUID tenantId, UUID userId) {
        InetAddress ip = extractClientIp(req);
        String userAgent = req.getHeader("User-Agent");
        String tlsFingerprint = extractTlsJa3(req);
        String sessionId = req.getSession(false) != null ? req.getSession(false).getId() : null;

        return new ClientContext(tenantId, ip, userAgent, tlsFingerprint, userId, sessionId);
    }

    /**
     * The container-resolved peer address only. {@code server.forward-headers-strategy=native} with
     * the Tomcat remote-ip valve (application.yml) already replaces {@code getRemoteAddr()} with
     * the real client address when, and only when, the direct peer is a trusted proxy, so
     * X-Forwarded-For / X-Real-IP are never read here: they are client-controlled. The value is
     * parsed as a literal IP, never resolved as a hostname (no DNS lookup).
     */
    static InetAddress extractClientIp(HttpServletRequest req) {
        InetAddress ip = parseLiteralIp(req.getRemoteAddr());
        return ip != null ? ip : InetAddress.getLoopbackAddress();
    }

    private static final Pattern IPV4 =
            Pattern.compile("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$");
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9a-fA-F:.]+$");

    /**
     * Parse an IPv4/IPv6 literal without ever touching DNS; null for anything else (hostnames,
     * junk, null). {@code InetAddress.getByName} only skips the resolver for literals, so the
     * syntax is checked first.
     */
    static InetAddress parseLiteralIp(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
        }
        int zone = s.indexOf('%');
        if (zone >= 0) {
            s = s.substring(0, zone);
        }
        boolean v6 = s.indexOf(':') >= 0;
        if (s.isEmpty()
                || s.length() > 45
                || !(v6 ? IPV6_CHARS.matcher(s).matches() : IPV4.matcher(s).matches())) {
            return null;
        }
        try {
            return InetAddress.getByName(s);
        } catch (java.net.UnknownHostException e) {
            return null;
        }
    }

    private static String extractTlsJa3(HttpServletRequest req) {
        // TLS JA3 fingerprint typically provided by a WAF or load balancer
        // Example: Cloudflare provides this in CF-JA3 header
        String ja3 = req.getHeader("CF-JA3");
        if (ja3 != null && !ja3.isEmpty()) {
            return ja3;
        }

        ja3 = req.getHeader("X-TLS-JA3");
        if (ja3 != null && !ja3.isEmpty()) {
            return ja3;
        }

        // Fallback: compute a simple fingerprint from User-Agent + Accept headers
        String userAgent = req.getHeader("User-Agent");
        String accept = req.getHeader("Accept");
        if (userAgent != null && accept != null) {
            return Integer.toHexString((userAgent + accept).hashCode());
        }

        return null;
    }

    @Override
    public String toString() {
        return "ClientContext{"
                + "tenantId="
                + tenantId
                + ", ip="
                + ip
                + ", userId="
                + userId
                + ", userAgent='"
                + (userAgent.length() > 50 ? userAgent.substring(0, 50) + "..." : userAgent)
                + '\''
                + '}';
    }
}
