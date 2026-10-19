package com.agenticai.core.mcp.oauth;

import java.util.Map;

/**
 * The single egress seam for every outbound call the generic MCP OAuth flow
 * makes (discovery documents, registration, token endpoint). The production
 * implementation ({@link JdkMcpOAuthHttp}) SSRF-guards every URL; tests
 * substitute a canned fake.
 */
public interface McpOAuthHttp {

    /**
     * @param method  "GET", "POST" or "DELETE" (provider token revocation)
     * @param headers request headers (may be empty, never null)
     * @param body    request body, or null for none
     * @throws McpOAuthException if the URL is blocked by the SSRF guard or the call fails at the transport level
     */
    Response send(String method, String url, Map<String, String> headers, String body);

    /**
     * Cheap, DNS-free pre-flight of a URL the flow is about to be asked to talk to (an endpoint advertised by a
     * metadata document). Lets discovery refuse a private / loopback / link-local / numeric-obfuscated /
     * platform-internal endpoint up front instead of at token time. {@link #send} still enforces the full,
     * resolving check on every request; this is the early, user-facing half.
     *
     * @return empty when acceptable, otherwise the reason it is refused
     */
    default java.util.Optional<String> checkUrl(String url) {
        return java.util.Optional.empty();
    }

    /** @param headers response headers, names lower-cased, first value only */
    record Response(int status, Map<String, String> headers, String body) {
        public String header(String lowerCaseName) {
            return headers == null ? null : headers.get(lowerCaseName);
        }
    }
}
