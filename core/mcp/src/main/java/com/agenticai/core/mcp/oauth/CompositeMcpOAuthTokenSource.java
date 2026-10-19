package com.agenticai.core.mcp.oauth;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The single {@link McpOAuthTokenSource} the remote-MCP connect path injects: routes a registration's
 * {@code oauthProvider} marker to the right owner.
 *
 * <ul>
 *   <li>{@code oauth:<id>} - the provider registry ({@link OAuthProviderService}), exactly as before;</li>
 *   <li>{@link McpOAuthService#MARKER} - the generic spec-discovered flow ({@link McpOAuthService}), which
 *       releases a token only to a registration URL covered by the resource the tenant connected.</li>
 * </ul>
 *
 * {@code @Primary}: {@link OAuthProviderService} is itself a source, and the connection manager's setter takes
 * one - without this the second source would make that injection ambiguous. A registration carrying neither
 * marker is never handled here, so with the generic flow switched off (nothing is ever stamped with
 * {@link McpOAuthService#MARKER}) behaviour is identical to the provider service alone.
 */
@Component
@Primary
public class CompositeMcpOAuthTokenSource implements McpOAuthTokenSource {

    private final OAuthProviderService providers;
    private final McpOAuthService generic;

    public CompositeMcpOAuthTokenSource(OAuthProviderService providers, McpOAuthService generic) {
        this.providers = providers;
        this.generic = generic;
    }

    @Override
    public boolean handles(String oauthProviderMarker) {
        return McpOAuthService.MARKER.equals(oauthProviderMarker) || providers.handles(oauthProviderMarker);
    }

    @Override
    public String accessTokenFor(String oauthProviderMarker, String serverUrl) {
        if (McpOAuthService.MARKER.equals(oauthProviderMarker)) {
            return generic.currentAccessToken(serverUrl);
        }
        return providers.accessTokenFor(oauthProviderMarker, serverUrl);
    }
}
