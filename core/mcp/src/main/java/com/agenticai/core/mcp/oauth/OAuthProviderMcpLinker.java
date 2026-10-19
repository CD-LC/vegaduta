package com.agenticai.core.mcp.oauth;

import com.agenticai.core.mcp.McpConnectDispatcher;
import com.agenticai.core.mcp.McpDynamicConnector;
import com.agenticai.core.mcp.McpRegistryService;
import com.agenticai.core.mcp.McpServerRegistrationNotFoundException;
import com.agenticai.core.mcp.McpServerType;
import com.agenticai.core.mcp.dto.McpServerRegistrationRequest;
import com.agenticai.core.mcp.dto.McpServerRegistrationResponse;
import com.agenticai.core.mcp.oauth.OAuthProviderRegistry.Provider;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Catalog glue between a provider OAuth connection and the remote MCP registry:
 * a registration whose {@code oauthProvider} marker is {@code oauth:<id>} is
 * connected with the tenant's provider token as its bearer (the generalisation of
 * the Atlassian marker, resolved in {@code McpRemoteHeaders} via
 * {@link McpOAuthTokenSource}). Three entry points:
 *
 * <ul>
 *   <li>{@link #registerServerFor}: after a successful connect, upsert the provider's
 *       own MCP server (catalog entry {@code <id>-oauth}) and connect it - the same
 *       "register then dispatch AFTER commit" order {@code AtlassianOAuthController}
 *       uses.</li>
 *   <li>{@link #attach}: point an EXISTING remote registration at a connected
 *       provider. Only when the registration's URL is on the provider's own MCP
 *       host, so a bearer token can never be aimed at an arbitrary server.</li>
 *   <li>{@link #dropLiveConnections}: on disconnect, stop using the token.</li>
 * </ul>
 *
 * <p>Must run with the tenant's {@code TenantContext} set (every call writes
 * tenant-schema rows).
 */
@Component
public class OAuthProviderMcpLinker {

    private static final Logger log = LoggerFactory.getLogger(OAuthProviderMcpLinker.class);

    private final McpRegistryService registryService;
    private final ObjectProvider<McpDynamicConnector> connector;
    private final McpConnectDispatcher connectDispatcher;
    private final OAuthProviderService providerService;

    public OAuthProviderMcpLinker(
        McpRegistryService registryService,
        ObjectProvider<McpDynamicConnector> connector,
        McpConnectDispatcher connectDispatcher,
        OAuthProviderService providerService
    ) {
        this.registryService = registryService;
        this.connector = connector;
        this.connectDispatcher = connectDispatcher;
        this.providerService = providerService;
    }

    /** @return the registration id, or null when the provider has no remote MCP server configured */
    public UUID registerServerFor(Provider p) {
        if (!p.hasMcpServer()) {
            return null;
        }
        McpServerRegistrationResponse registration = registryService.register(new McpServerRegistrationRequest(
            p.mcpServerName(),
            McpServerType.STREAMABLE_HTTP,
            p.spec().name() + " via your OAuth connection",
            true,
            null,
            null,
            Map.of(),
            p.mcpUrl()
        ));
        registryService.setOAuthProvider(registration.id(), OAuthProviderService.markerFor(p.id()));
        dispatchConnect(registration.id());
        return registration.id();
    }

    /**
     * @throws McpServerRegistrationNotFoundException unknown registration (404)
     * @throws IllegalArgumentException the registration is not a remote server on this provider's MCP host (400)
     * @throws ResponseStatusException 409 when the tenant has not connected the provider
     */
    public void attach(Provider p, UUID registrationId) {
        McpServerRegistrationResponse target = registryService.listAll().stream()
            .filter(r -> r.id().equals(registrationId))
            .findFirst()
            .orElseThrow(() -> new McpServerRegistrationNotFoundException(registrationId));
        if (target.url() == null || !p.allowsMcpServerUrl(target.url())) {
            throw new IllegalArgumentException(
                "Only a remote MCP server on " + p.spec().name() + "'s own MCP host can use this connection.");
        }
        if (!providerService.listForCurrentTenant().stream().anyMatch(v -> v.id().equals(p.id()) && v.connected())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Connect " + p.spec().name() + " first.");
        }
        registryService.setOAuthProvider(registrationId, OAuthProviderService.markerFor(p.id()));
        dispatchConnect(registrationId);
    }

    /**
     * Stops every live MCP connection that was using this provider's token and
     * deletes the registration this platform created for it. Registrations the
     * tenant attached by hand stay (their connect now fails with "not connected").
     *
     * @return how many registrations were touched
     */
    public int dropLiveConnections(Provider p) {
        List<McpServerRegistrationResponse> marked = registryService.listByOAuthProvider(OAuthProviderService.markerFor(p.id()));
        McpDynamicConnector c = connector.getIfAvailable();
        for (McpServerRegistrationResponse r : marked) {
            try {
                if (c != null) {
                    c.disconnect(r.id());
                }
                if (p.mcpServerName().equals(r.name())) {
                    registryService.delete(r.id());
                }
            } catch (RuntimeException e) {
                log.warn("Could not clean up MCP registration {} while disconnecting OAuth provider {}: {}", r.id(), p.id(), e.toString());
            }
        }
        return marked.size();
    }

    private void dispatchConnect(UUID registrationId) {
        McpDynamicConnector c = connector.getIfAvailable();
        if (c != null) {
            connectDispatcher.dispatch(c, registrationId);
        }
    }
}
