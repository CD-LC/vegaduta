package com.agenticai.core.mcp.oauth;

/**
 * The SSRF guard (or the https-only rule) refused a URL. A distinct type - not a
 * message substring - so discovery can tell "refused" (fatal, must surface) from
 * "unreachable/unparseable" (fall through to the well-known fallbacks).
 */
public class McpOAuthBlockedException extends McpOAuthException {

    public McpOAuthBlockedException(String message) {
        super(message);
    }
}
