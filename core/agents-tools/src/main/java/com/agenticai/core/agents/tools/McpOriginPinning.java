package com.agenticai.core.agents.tools;

import com.agenticai.core.mcp.McpRemoteUrlGuard;
import io.modelcontextprotocol.client.transport.InvalidSseMessageEndpointException;
import io.modelcontextprotocol.client.transport.SseMessageEndpointValidator;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import java.net.URI;
import java.util.Optional;

/**
 * Keeps a remote MCP connection on the origin that was SSRF-checked (security review SEC-026).
 *
 * <p>The legacy SSE transport learns where to POST messages from the server's {@code endpoint}
 * event. MCP SDK 0.18.2's {@code Utils.resolveUri} refuses an absolute endpoint on another origin
 * but not a scheme-relative one, so an endpoint of {@code //core:8080/x} resolved against the base
 * switched the connection to the compose network - carrying the registration's Authorization header
 * with it. Two independent hooks close that:
 *
 * <ul>
 *   <li>{@link #sseEndpointValidator} is installed as the SSE transport's {@code
 *       messageEndpointValidator}: the advertised endpoint is resolved and must be same-origin
 *       (scheme, host, effective port) with the registered URL, or the connect fails.
 *   <li>{@link #requestCustomizer} wraps the per-request header customizer on BOTH transports and
 *       refuses to build any request whose URI is not same-origin with the registered URL - a
 *       backstop that does not depend on how the SDK resolves the endpoint.
 * </ul>
 *
 * The registered URL itself passed {@link McpRemoteUrlGuard#checkResolved} immediately before
 * connect, so "same origin as the base" is the validation the endpoint needs.
 *
 * <p><b>The socket is pinned too.</b> The SDK would build its own {@code java.net.http.HttpClient},
 * which resolves the host again at connect. {@link McpTransportFactory} instead hands both
 * transports a {@code GuardedHttpClientBuilder} (their {@code clientBuilder(..)} hook), whose
 * client resolves every connect, and every reconnect of the SSE stream, through {@code SsrfGuard}
 * and connects to the address it validated: a DNS answer that flips to an internal address after
 * {@code checkResolved} has nothing to connect to. Redirects are NEVER on both transports, so there
 * is no redirect hop to re-validate.
 */
final class McpOriginPinning {

    private McpOriginPinning() {}

    /**
     * The SSE {@code endpoint}-event validator for a connection registered at {@code
     * registeredUrl}.
     */
    static SseMessageEndpointValidator sseEndpointValidator(String registeredUrl) {
        return (baseUri, messageEndpoint) -> {
            URI resolved;
            try {
                resolved =
                        baseUri.resolve(
                                URI.create(messageEndpoint == null ? "" : messageEndpoint.strip()));
            } catch (IllegalArgumentException e) {
                throw new InvalidSseMessageEndpointException(
                        "The MCP server sent an invalid message endpoint.", messageEndpoint);
            }
            Optional<String> refused = McpRemoteUrlGuard.checkSameOrigin(registeredUrl, resolved);
            if (refused.isPresent()) {
                throw new InvalidSseMessageEndpointException(refused.get(), messageEndpoint);
            }
        };
    }

    /**
     * Wraps {@code delegate} so no request leaves for an origin other than {@code registeredUrl}'s.
     */
    static McpSyncHttpClientRequestCustomizer requestCustomizer(
            String registeredUrl, McpSyncHttpClientRequestCustomizer delegate) {
        return (builder, method, endpoint, body, context) -> {
            Optional<String> refused = McpRemoteUrlGuard.checkSameOrigin(registeredUrl, endpoint);
            if (refused.isPresent()) {
                throw new IllegalStateException(refused.get());
            }
            delegate.customize(builder, method, endpoint, body, context);
        };
    }
}
