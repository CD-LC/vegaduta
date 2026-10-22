package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.attestation.AgentAttestationService;
import com.agenticai.core.mcp.McpServerType;
import com.agenticai.core.mcp.atlassian.AtlassianOAuthService;
import com.agenticai.core.mcp.dto.McpDynamicConfig;
import com.agenticai.core.mcp.oauth.McpOAuthTokenSource;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import java.net.http.HttpClient;
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

    private final AtlassianOAuthService atlassianOAuthService;
    private final ObjectProvider<AgentAttestationService> agentAttestation;
    private final Supplier<McpRoutingHeaders> routing;
    private final Supplier<McpOAuthTokenSource> providerTokens;

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
                    // Pentest M4: the SDK builds its OWN HttpClient - never follow redirects.
                    .customizeClient(cb -> cb.followRedirects(HttpClient.Redirect.NEVER))
                    .build();
        }
        return HttpClientStreamableHttpTransport.builder(url)
                .httpRequestCustomizer(
                        McpOriginPinning.requestCustomizer(
                                url, routingHeaders.wrap(requestHeaders)))
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
