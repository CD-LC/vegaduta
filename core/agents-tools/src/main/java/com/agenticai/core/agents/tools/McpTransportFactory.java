package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.attestation.AgentAttestationService;
import com.agenticai.core.mcp.McpServerType;
import com.agenticai.core.mcp.atlassian.AtlassianOAuthService;
import com.agenticai.core.mcp.dto.McpDynamicConfig;
import com.agenticai.core.mcp.oauth.McpOAuthTokenSource;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.net.GuardedHttpClientBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Builds the MCP client transport per transport kind: STDIO (env-isolated subprocess with the
 * Connection Doctor's stderr capture), SSE and streamable-HTTP (redirects disabled, headers applied
 * per request, origin pinned). Routing headers and the provider token source are setter-injected on
 * the manager, so they arrive as suppliers and are read at build time.
 */
final class McpTransportFactory {

    private static final Logger log = LoggerFactory.getLogger(McpTransportFactory.class);
    private static final ObjectMapper JSON_MAPPER = com.agenticai.core.json.JsonMappers.plain();

    /**
     * Longest a remote MCP connection may sit silent between two packets: an SSE stream that gets
     * no event and no keep-alive for this long, or a reply that stalls this long, is treated as
     * dead. Far above the client's own 60 s request timeout.
     */
    static final Duration REMOTE_SOCKET_TIMEOUT = Duration.ofMinutes(15);

    private final AtlassianOAuthService atlassianOAuthService;
    private final ObjectProvider<AgentAttestationService> agentAttestation;
    private final Supplier<McpRoutingHeaders> routing;
    private final Supplier<McpOAuthTokenSource> providerTokens;

    /**
     * Heap ceiling on a single remote-MCP response body (default 32 MiB): a registered remote
     * server can stream an arbitrarily large {@code tools/call} result and the SDK materialises
     * replies in memory - the tool-output truncation runs only AFTER full receipt, so without a
     * transport cap a hostile server could exhaust the heap. Settable via {@code
     * app.mcp.max-response-bytes} ({@code <= 0} restores unlimited); overrides in tests via {@link
     * #setClientBuilders} bypass it entirely.
     */
    private volatile long maxResponseBytes = 32L * 1024 * 1024;

    /**
     * Where the SDK transports get their {@code HttpClient}: the SSRF-guarded client, so the
     * address a connection goes to is the one the guard resolved and validated. Replaceable in
     * tests only.
     */
    private Supplier<HttpClient.Builder> clientBuilders =
            () -> new GuardedHttpClientBuilder(REMOTE_SOCKET_TIMEOUT)
                    .maxResponseBytes(maxResponseBytes);

    McpTransportFactory(
            AtlassianOAuthService atlassianOAuthService,
            ObjectProvider<AgentAttestationService> agentAttestation,
            Supplier<McpRoutingHeaders> routing,
            Supplier<McpOAuthTokenSource> providerTokens) {
        this.atlassianOAuthService = atlassianOAuthService;
        this.agentAttestation = agentAttestation;
        this.routing = routing;
        this.providerTokens = providerTokens;
    }

    /** Test seam: the client builder handed to the SDK transports. */
    void setClientBuilders(Supplier<HttpClient.Builder> clientBuilders) {
        this.clientBuilders = clientBuilders;
    }

    /** See {@link #maxResponseBytes}' javadoc. */
    void setMaxResponseBytes(long maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }

    /** SSE or streamable-HTTP transport for a remote registration (see the manager's history). */
    McpClientTransport buildRemoteTransport(McpDynamicConfig config) {
        return buildRemoteTransport(config, remoteRequestHeaders(config));
    }

    /**
     * Headers are applied PER REQUEST via {@code httpRequestCustomizer}. Tenant/subject are
     * captured HERE (connect/test thread, TenantContext present) because the SDK invokes the
     * customizer on a transport thread that has none.
     */
    McpRemoteHeaders.PerRequestHeaders remoteRequestHeaders(McpDynamicConfig config) {
        return new McpRemoteHeaders.PerRequestHeaders(
                config,
                atlassianOAuthService,
                agentAttestation::getIfAvailable,
                TenantContext.current().orElse(null),
                providerTokens.get());
    }

    McpClientTransport buildRemoteTransport(
            McpDynamicConfig config, McpRemoteHeaders.PerRequestHeaders requestHeaders) {
        String url = config.url();
        McpRoutingHeaders routingHeaders = routing.get();
        if (config.type() == McpServerType.SSE) {
            McpRoutingHeaders.warnSseDeprecated(config.name());
            return HttpClientSseClientTransport.builder(url)
                    .messageEndpointValidator(McpOriginPinning.sseEndpointValidator(url))
                    .httpRequestCustomizer(
                            McpOriginPinning.requestCustomizer(
                                    url, routingHeaders.wrap(requestHeaders)))
                    // SEC-026 / MCP07: the SDK would build its OWN java.net.http.HttpClient, which
                    // resolves the host again at connect time (a DNS-rebinding window after
                    // McpRemoteUrlGuard#checkResolved). Hand it the guarded client instead: every
                    // connect, and every reconnect of the long-lived stream, resolves through
                    // SsrfGuard and goes to the address it validated.
                    .clientBuilder(clientBuilders.get())
                    // Pentest M4: never follow redirects.
                    .customizeClient(cb -> cb.followRedirects(HttpClient.Redirect.NEVER))
                    .build();
        }
        return HttpClientStreamableHttpTransport.builder(url)
                .httpRequestCustomizer(
                        McpOriginPinning.requestCustomizer(
                                url, routingHeaders.wrap(requestHeaders)))
                .clientBuilder(clientBuilders.get())
                .customizeClient(cb -> cb.followRedirects(HttpClient.Redirect.NEVER))
                .build();
    }

    /** Static env-derived (or OAuth-minted) headers, used by the pre-probe. */
    Map<String, String> remoteHeaders(McpDynamicConfig config) {
        return McpRemoteHeaders.build(config, atlassianOAuthService, providerTokens.get());
    }

    /**
     * Builds the stdio transport AND wires the Connection Doctor's stderr capture: the SDK hands
     * each stderr line of the child to the transport's {@code stdErrorHandler}.
     */
    McpClientTransport buildStdioTransport(
            ServerParameters params,
            String serverName,
            StderrTailBuffer stderrTail,
            Map<String, String> secretValues) {
        McpClientTransport transport = constructStdioTransport(params);
        if (transport instanceof StdioClientTransport stdioTransport) {
            stdioTransport.setStdErrorHandler(
                    line -> {
                        // The tail deliberately stays RAW: it is only persisted/returned through
                        // diagnose() -> redactSecrets, and the self-healer needs unredacted text.
                        stderrTail.add(line);
                        // Keep the SDK's default (log every stderr line), but REDACTED.
                        log.info(
                                "MCP server '{}' STDERR: {}",
                                serverName,
                                McpSecretRedactor.redactSecrets(line, secretValues));
                    });
        }
        return transport;
    }

    // L2 hardening (2026-08-14): no raw-StdioClientTransport fallback - failing closed is strictly
    // safer than leaking core's whole process env into the spawned child (see
    // EnvIsolatedStdioClientTransport). Callers treat this method as fallible.
    McpClientTransport constructStdioTransport(ServerParameters params) {
        try {
            Class<?> jacksonAdapter =
                    Class.forName("io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper");
            Object mcpJsonMapper =
                    jacksonAdapter.getConstructor(ObjectMapper.class).newInstance(JSON_MAPPER);
            return new EnvIsolatedStdioClientTransport(
                    params, (io.modelcontextprotocol.json.McpJsonMapper) mcpJsonMapper);
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError e) {
            throw new IllegalStateException(
                    "EnvIsolatedStdioClientTransport construction failed - the bundled MCP SDK no longer exposes the "
                            + "(ServerParameters, McpJsonMapper) constructor this class's env isolation depends on. Refusing "
                            + "to fall back to an un-isolated StdioClientTransport.",
                    e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "StdioClientTransport(ServerParameters, McpJsonMapper) construction failed", e);
        }
    }
}
