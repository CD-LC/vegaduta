package com.agenticai.core.agents.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Streamable HTTP routing headers from MCP revision 2026-07-28: every POST mirrors the JSON-RPC
 * {@code method} into {@code Mcp-Method} and, for {@code tools/call}, {@code prompts/get} and
 * {@code resources/read}, the target into {@code Mcp-Name}, so gateways can route without parsing
 * the body. A value that is not plain visible ASCII (or that looks like the sentinel) is carried as
 * {@code =?base64?<UTF-8 base64>?=}.
 *
 * <p>Off by default ({@code app.mcp.client.routing-headers.enabled}): this client still speaks the
 * handshake-based protocol (SDK 0.18, revision 2025-11-25 semantics), where the headers are neither
 * required nor defined, so sending them is a courtesy to modern gateways rather than compliance
 * with the new revision. See docs/architecture/mcp-spec-alignment.md.
 */
@Component
class McpRoutingHeaders {

    private static final Logger log = LoggerFactory.getLogger(McpRoutingHeaders.class);
    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();
    private static final Pattern SAFE = Pattern.compile("^[\\x21-\\x7E]([\\x20-\\x7E\\t]*[\\x21-\\x7E])?$");

    static final McpRoutingHeaders OFF = new McpRoutingHeaders(false);

    private final boolean enabled;

    McpRoutingHeaders(@Value("${app.mcp.client.routing-headers.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    /** {@code delegate} unchanged when disabled; otherwise the same headers plus the routing ones. */
    McpSyncHttpClientRequestCustomizer wrap(McpSyncHttpClientRequestCustomizer delegate) {
        if (!enabled) {
            return delegate;
        }
        return (builder, method, endpoint, body, context) -> {
            delegate.customize(builder, method, endpoint, body, context);
            mirror(builder, body);
        };
    }

    static void mirror(HttpRequest.Builder builder, String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        try {
            JsonNode message = MAPPER.readTree(body);
            String method = message.path("method").asText(null);
            if (method == null) {
                return; // a response to a server request: no method to mirror
            }
            builder.setHeader("Mcp-Method", method);
            String name = switch (method) {
                case "tools/call", "prompts/get" -> message.path("params").path("name").asText(null);
                case "resources/read" -> message.path("params").path("uri").asText(null);
                default -> null;
            };
            if (name != null) {
                builder.setHeader("Mcp-Name", encode(name));
            }
        } catch (IOException e) {
            log.debug("Not mirroring routing headers: request body is not JSON");
        }
    }

    static String encode(String value) {
        boolean sentinel = value.startsWith("=?base64?") && value.endsWith("?=");
        if (SAFE.matcher(value).matches() && !sentinel) {
            return value;
        }
        return "=?base64?" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    /** HTTP+SSE is deprecated since MCP 2025-03-26; one line per connect so operators can find stragglers. */
    static void warnSseDeprecated(String serverName) {
        log.warn("MCP server '{}' uses the deprecated HTTP+SSE transport; re-register it as STREAMABLE_HTTP "
            + "(see docs/architecture/mcp-spec-alignment.md for the deprecation timeline)", serverName);
    }
}
