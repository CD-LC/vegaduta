package com.agenticai.core.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class CompositeMcpOAuthTokenSourceTest {

    private final OAuthProviderService providers = mock(OAuthProviderService.class);
    private final McpOAuthService generic = mock(McpOAuthService.class);
    private final CompositeMcpOAuthTokenSource source = new CompositeMcpOAuthTokenSource(providers, generic);

    @Test
    void routesTheGenericMarkerToTheGenericServiceWithTheRegistrationUrl() {
        when(generic.currentAccessToken("https://mcp.example.com/mcp")).thenReturn("at-generic");
        assertTrue(source.handles(McpOAuthService.MARKER));
        assertEquals("at-generic", source.accessTokenFor(McpOAuthService.MARKER, "https://mcp.example.com/mcp"));
        verify(providers, never()).accessTokenFor(anyString(), anyString());
    }

    @Test
    void routesProviderMarkersToTheProviderRegistryExactlyAsBefore() {
        when(providers.handles("oauth:github")).thenReturn(true);
        when(providers.accessTokenFor("oauth:github", "https://api.githubcopilot.com/mcp/")).thenReturn("at-gh");
        assertTrue(source.handles("oauth:github"));
        assertEquals("at-gh", source.accessTokenFor("oauth:github", "https://api.githubcopilot.com/mcp/"));
        verify(generic, never()).currentAccessToken(anyString());
    }

    @Test
    void ownsNeitherAtlassianNorUnknownMarkers() {
        assertFalse(source.handles("ATLASSIAN"));
        assertFalse(source.handles(null));
        assertFalse(source.handles("something-else"));
    }
}
