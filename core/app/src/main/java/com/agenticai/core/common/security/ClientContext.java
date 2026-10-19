package com.agenticai.core.common.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/**
 * Client context: metadata extracted from a request for fraud detection analysis.
 * Captures IP, User-Agent, TLS fingerprint, authentication state, etc.
 */
public class ClientContext {
    public final UUID tenantId;
    public final InetAddress ip;
    public final String userAgent;
    public final String tlsFingerprint; // TLS JA3 fingerprint
    public final UUID userId; // null for unauthenticated requests
    public final String sessionId; // Session identifier for rate limiting
    public final long requestTimestamp;

    public ClientContext(UUID tenantId, InetAddress ip, String userAgent, String tlsFingerprint,
                        UUID userId, String sessionId) {
        this.tenantId = tenantId;
        this.ip = ip;
        this.userAgent = userAgent != null ? userAgent : "unknown";
        this.tlsFingerprint = tlsFingerprint;
        this.userId = userId;
        this.sessionId = sessionId != null ? sessionId : ip.getHostAddress();
        this.requestTimestamp = System.currentTimeMillis();
    }

    /**
     * Extract client context from an HTTP request.
     * Handles X-Forwarded-For, X-Real-IP, and direct client IP.
     */
    public static ClientContext fromRequest(HttpServletRequest req, UUID tenantId, UUID userId) {
        InetAddress ip = extractClientIp(req);
        String userAgent = req.getHeader("User-Agent");
        String tlsFingerprint = extractTlsJa3(req);
        String sessionId = req.getSession(false) != null ? req.getSession(false).getId() : null;

        return new ClientContext(tenantId, ip, userAgent, tlsFingerprint, userId, sessionId);
    }

    private static InetAddress extractClientIp(HttpServletRequest req) {
        // Check X-Forwarded-For header (behind proxy)
        String xForwardedFor = req.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            String clientIp = xForwardedFor.split(",")[0].trim();
            try {
                return InetAddress.getByName(clientIp);
            } catch (UnknownHostException e) {
                // Fall through to other methods
            }
        }

        // Check X-Real-IP header (Nginx)
        String xRealIp = req.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            try {
                return InetAddress.getByName(xRealIp);
            } catch (UnknownHostException e) {
                // Fall through
            }
        }

        // Fall back to remote address
        try {
            return InetAddress.getByName(req.getRemoteAddr());
        } catch (UnknownHostException e) {
            // Should not happen, but fallback to localhost
            try {
                return InetAddress.getByName("127.0.0.1");
            } catch (UnknownHostException ex) {
                throw new RuntimeException("Failed to parse client IP", ex);
            }
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
        return "ClientContext{" +
                "tenantId=" + tenantId +
                ", ip=" + ip +
                ", userId=" + userId +
                ", userAgent='" + (userAgent.length() > 50 ? userAgent.substring(0, 50) + "..." : userAgent) + '\'' +
                '}';
    }
}
