package com.agenticai.core.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.mcp.oauth.OAuthProviderClient.RevokeOutcome;
import com.agenticai.core.mcp.oauth.OAuthProviderRegistry.Provider;
import com.agenticai.core.security.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class OAuthProviderControllerTest {

    private static final String RETURN_URL = "https://app.example.com/tools";

    private OAuthProviderService service;
    private OAuthProviderMcpLinker linker;
    private OAuthProviderController controller;
    private OAuthProviderRegistry registry;

    @BeforeEach
    void setUp() {
        service = mock(OAuthProviderService.class);
        linker = mock(OAuthProviderMcpLinker.class);
        controller = new OAuthProviderController(service, linker);
        registry = OAuthProviderTestSupport.registry(OAuthProviderTestSupport.props("github", "zoom"));
        when(service.returnUrlFor(anyString(), any())).thenReturn(RETURN_URL);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private Provider provider(String id) {
        return registry.find(id).orElseThrow();
    }

    private static String location(ResponseEntity<Void> response) {
        assertEquals(302, response.getStatusCode().value());
        return response.getHeaders().getLocation().toString();
    }

    // ------------------------------------------------------------------ callback

    @Test
    void aDeclinedConsentRedirectsBackWithAFixedReasonCode() {
        String loc = location(controller.callback("github", null, "STATE", "access_denied"));

        assertEquals(RETURN_URL + "?mcp_oauth=error&provider=github&reason=consent_declined", loc);
        verify(service, never()).completeAuthorization(anyString(), anyString(), anyString());
    }

    @Test
    void aCallbackMissingTheCodeOrStateIsRejectedBeforeAnyWork() {
        assertTrue(location(controller.callback("github", null, "S", null)).contains("reason=missing_code_or_state"));
        assertTrue(location(controller.callback("github", "c", null, null)).contains("reason=missing_code_or_state"));
        verify(service, never()).completeAuthorization(anyString(), anyString(), anyString());
    }

    @Test
    void stateProblemsMapToTheirOwnReasonCodesAndNeverLeakProviderText() {
        doThrow(new OAuthStateException(OAuthStateException.EXPIRED, "This connection attempt expired"))
            .when(service).completeAuthorization(eq("github"), anyString(), anyString());
        assertTrue(location(controller.callback("github", "c", "S", null)).endsWith("reason=expired_state"));

        doThrow(new OAuthStateException(OAuthStateException.REPLAYED, "already used"))
            .when(service).completeAuthorization(eq("github"), anyString(), anyString());
        assertTrue(location(controller.callback("github", "c", "S", null)).endsWith("reason=replayed_state"));
    }

    @Test
    void anExchangeFailureAndAnUnconfiguredProviderAreDistinguished() {
        doThrow(new McpOAuthException("The provider rejected the authorization code (HTTP 400, invalid_grant)."))
            .when(service).completeAuthorization(eq("github"), anyString(), anyString());
        assertTrue(location(controller.callback("github", "c", "S", null)).endsWith("reason=exchange_failed"));

        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "not configured"))
            .when(service).completeAuthorization(eq("github"), anyString(), anyString());
        assertTrue(location(controller.callback("github", "c", "S", null)).endsWith("reason=not_configured"));
    }

    @Test
    void aSuccessfulConnectRegistersTheProvidersMcpServerUnderTheStatesTenantAndUser() {
        when(service.completeAuthorization("github", "code", "STATE"))
            .thenReturn(new OAuthProviderService.CompletedConnection("tenant_a", "user-1", "github", RETURN_URL + "?tab=oauth"));
        Provider github = provider("github");
        when(service.provider("github")).thenReturn(Optional.of(github));
        List<String> seen = new ArrayList<>();
        when(linker.registerServerFor(github)).thenAnswer(inv -> {
            seen.add(TenantContext.current().map(c -> c.getTenantId() + "/" + c.getSubject()).orElse("<none>"));
            return UUID.randomUUID();
        });

        String loc = location(controller.callback("github", "code", "STATE", null));

        assertEquals(RETURN_URL + "?tab=oauth&mcp_oauth=connected&provider=github&mcp=registered", loc);
        assertEquals(List.of("tenant_a/user-1"), seen);
        assertTrue(TenantContext.current().isEmpty(), "the callback thread keeps no tenant afterwards");
    }

    @Test
    void aFailedMcpRegistrationStillReportsTheConnectionAndFlagsTheServer() {
        when(service.completeAuthorization("github", "code", "STATE"))
            .thenReturn(new OAuthProviderService.CompletedConnection("tenant_a", "user-1", "github", RETURN_URL));
        Provider github = provider("github");
        when(service.provider("github")).thenReturn(Optional.of(github));
        when(linker.registerServerFor(github)).thenThrow(new IllegalStateException("plan limit"));

        String loc = location(controller.callback("github", "code", "STATE", null));

        assertEquals(RETURN_URL + "?mcp_oauth=connected&provider=github&mcp=failed", loc);
        assertTrue(TenantContext.current().isEmpty());
    }

    @Test
    void aProviderWithoutAnMcpServerRegistersNothing() {
        when(service.completeAuthorization("zoom", "code", "STATE"))
            .thenReturn(new OAuthProviderService.CompletedConnection("tenant_a", "user-1", "zoom", RETURN_URL));
        when(service.provider("zoom")).thenReturn(Optional.of(provider("zoom")));

        String loc = location(controller.callback("zoom", "code", "STATE", null));

        assertEquals(RETURN_URL + "?mcp_oauth=connected&provider=zoom", loc);
        verify(linker, never()).registerServerFor(any());
    }

    @Test
    void theAttackerControlledProviderPathSegmentIsReducedToTheIdAlphabetBeforeItIsEchoed() {
        String loc = location(controller.callback("<script>alert(1)</script>\r\nX-Evil: 1", null, "S", "access_denied"));

        assertFalse(loc.contains("<"));
        assertFalse(loc.contains("\n"));
        assertTrue(loc.contains("provider=_script_alert_1___script_"), loc);
    }

    // ------------------------------------------------------------------ management

    @Test
    void providersWrapsTheListWithTheFeatureFlag() {
        List<OAuthProviderService.ProviderView> views = List.of();
        when(service.featureEnabled()).thenReturn(false);
        when(service.listForCurrentTenant()).thenReturn(views);

        OAuthProviderController.ProvidersResponse r = controller.providers();

        assertFalse(r.enabled());
        assertEquals(views, r.providers());
    }

    @Test
    void authorizeReturnsTheConsentUrlAndPassesTheRequestedReturnUrl() {
        when(service.beginAuthorization("github", "https://app.example.com/x"))
            .thenReturn(new OAuthProviderService.AuthorizationStart("https://github.com/login/oauth/authorize?x=1", "github"));

        OAuthProviderController.AuthorizeResponse r =
            controller.authorize("github", new OAuthProviderController.AuthorizeRequest("https://app.example.com/x"));

        assertEquals("https://github.com/login/oauth/authorize?x=1", r.url());
        assertEquals("github", r.provider());
    }

    @Test
    void authorizeToleratesAMissingBody() {
        when(service.beginAuthorization("github", null))
            .thenReturn(new OAuthProviderService.AuthorizationStart("https://u", "github"));

        assertEquals("https://u", controller.authorize("github", null).url());
    }

    @Test
    void disconnectDropsTheLiveMcpConnectionsOnlyWhenSomethingWasConnected() {
        Provider github = provider("github");
        when(service.provider("github")).thenReturn(Optional.of(github));
        when(service.disconnect("github")).thenReturn(new OAuthProviderService.DisconnectResult(true, RevokeOutcome.REVOKED));

        OAuthProviderController.DisconnectResponse r = controller.disconnect("github");

        assertTrue(r.disconnected());
        assertEquals("REVOKED", r.providerRevocation());
        verify(linker).dropLiveConnections(github);

        when(service.provider("zoom")).thenReturn(Optional.of(provider("zoom")));
        when(service.disconnect("zoom")).thenReturn(new OAuthProviderService.DisconnectResult(false, RevokeOutcome.NOT_SUPPORTED));
        assertFalse(controller.disconnect("zoom").disconnected());
        verify(linker, never()).dropLiveConnections(provider("zoom"));
    }

    @Test
    void anUnknownProviderIs404OnDisconnectAndAttach() {
        when(service.provider("nope")).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND,
            assertThrows(ResponseStatusException.class, () -> controller.disconnect("nope")).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
            assertThrows(ResponseStatusException.class, () -> controller.attach("nope", UUID.randomUUID())).getStatusCode());
    }

    @Test
    void attachDelegatesToTheLinkerAndAnswers204() {
        Provider github = provider("github");
        when(service.provider("github")).thenReturn(Optional.of(github));
        UUID id = UUID.randomUUID();

        ResponseEntity<Void> r = controller.attach("github", id);

        assertEquals(204, r.getStatusCode().value());
        verify(linker).attach(github, id);
    }
}
