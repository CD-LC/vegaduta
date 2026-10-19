package com.agenticai.core.mcp.oauth;

import com.agenticai.core.mcp.McpConnectDispatcher;
import com.agenticai.core.mcp.McpDynamicConnector;
import com.agenticai.core.mcp.McpRegistryService;
import com.agenticai.core.mcp.dto.McpServerRegistrationResponse;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Glue between a generic remote-MCP OAuth connection ({@link McpOAuthService}) and the tenant's MCP
 * registrations - the sibling of {@link OAuthProviderMcpLinker}, which does the same for the provider registry.
 *
 * <ul>
 *   <li>{@link #link}: after a successful connect, every remote registration whose URL the connected
 *       resource covers is stamped with {@link McpOAuthService#MARKER} and (re)connected - AFTER the
 *       stamping transaction committed, same order as the Atlassian and provider callbacks. A registration
 *       already owned by another OAuth flow is never taken over.</li>
 *   <li>{@link #unlink}: on disconnect, stop using the token (drop the live connections). The registrations
 *       stay, so the tenant sees them fail with "not connected" and can reconnect.</li>
 * </ul>
 *
 * <p>Must run with the tenant's {@code TenantContext} set (every call touches tenant-schema rows).
 */
@Component
public class McpOAuthRegistrationLinker {

    private static final Logger log = LoggerFactory.getLogger(McpOAuthRegistrationLinker.class);

    private final McpRegistryService registryService;
    private final ObjectProvider<McpDynamicConnector> connector;
    private final McpConnectDispatcher connectDispatcher;

    public McpOAuthRegistrationLinker(
        McpRegistryService registryService,
        ObjectProvider<McpDynamicConnector> connector,
        McpConnectDispatcher connectDispatcher
    ) {
        this.registryService = registryService;
        this.connector = connector;
        this.connectDispatcher = connectDispatcher;
    }

    /** @return how many registrations now use the connection (and were sent to connect) */
    public int link(String resourceUrl) {
        List<UUID> ids = registryService.stampOAuthMarkerOnRemote(
            url -> McpOAuthUrls.covers(resourceUrl, url), McpOAuthService.MARKER);
        McpDynamicConnector c = connector.getIfAvailable();
        if (c != null) {
            for (UUID id : ids) {
                connectDispatcher.dispatch(c, id);
            }
        }
        return ids.size();
    }

    /** @return how many live connections were dropped */
    public int unlink(List<String> resourceUrls) {
        McpDynamicConnector c = connector.getIfAvailable();
        int dropped = 0;
        for (McpServerRegistrationResponse r : registryService.listByOAuthProvider(McpOAuthService.MARKER)) {
            boolean covered = r.url() != null && resourceUrls.stream().anyMatch(res -> McpOAuthUrls.covers(res, r.url()));
            if (!covered || c == null) {
                continue;
            }
            try {
                c.disconnect(r.id());
                dropped++;
            } catch (RuntimeException e) {
                log.warn("Could not drop the live MCP connection {} while disconnecting OAuth: {}", r.id(), e.toString());
            }
        }
        return dropped;
    }
}
