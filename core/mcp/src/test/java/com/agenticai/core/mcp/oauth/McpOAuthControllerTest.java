package com.agenticai.core.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.mcp.McpRemoteUrlGuard;
import com.agenticai.core.mcp.oauth.OAuthProviderTestSupport.MutableClock;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * REST face of the generic flow, through real Spring MVC request mapping (standalone MockMvc): route resolution
 * next to {@link OAuthProviderController}, the flag-off 404s, the error-to-status mapping and the callback's
 * redirect contract.
 */
class McpOAuthControllerTest {

    private static final String MCP = "https://mcp.example.com/mcp";
    private static final String ISSUER = "https://auth.example.com";
    private static final String CALLBACK = "https://api.example.com/oauth/mcp/remote/callback";
    private static final String CONSOLE = "https://app.example.com/tools";
    private static final String CIMD_URL = "https://api.example.com/oauth/mcp/remote/client-metadata.json";

    private FakeAuthorizationServer as;
    private EncryptionService encryption;
    private McpOAuthRegistrationLinker linker;
    private OAuthProviderService providerService;
    private OAuthProviderMcpLinker providerLinker;

    @BeforeEach
    void setUp() {
        as = new FakeAuthorizationServer(MCP, ISSUER);
        encryption = OAuthProviderTestSupport.encryption((byte) 3);
        linker = mock(McpOAuthRegistrationLinker.class);
        providerService = mock(OAuthProviderService.class);
        providerLinker = mock(OAuthProviderMcpLinker.class);
        when(providerService.returnUrlFor(anyString(), any())).thenReturn(CONSOLE);
        TenantContext.set(TenantContext.of("tenant-a", "user-1", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private McpOAuthClientMetadata cimd(boolean enabled) {
        return new McpOAuthClientMetadata(new McpRemoteUrlGuard(), enabled, CIMD_URL,
            McpOAuthClientMetadata.DEFAULT_PATH, "VegaDuta", "", CALLBACK);
    }

    private McpOAuthService service(boolean enabled, McpOAuthClientMetadata cimd) {
        ObjectMapper om = new ObjectMapper();
        return new McpOAuthService(new McpOAuthDiscovery(as, om), new McpOAuthClient(as, om),
            new InMemoryMcpOAuthConnectionStore(), encryption, cimd, new McpRemoteUrlGuard(), enabled, CALLBACK,
            new OAuthRedirectPolicy(CONSOLE, ""), new MutableClock(Instant.parse("2026-10-05T10:00:00Z")), 600);
    }

    private MockMvc mvc(McpOAuthService service, McpOAuthClientMetadata cimd) {
        return MockMvcBuilders
            .standaloneSetup(new McpOAuthController(service, linker, cimd), new OAuthProviderController(providerService, providerLinker))
            .addPlaceholderValue("app.mcp.oauth.cimd.path", McpOAuthClientMetadata.DEFAULT_PATH)
            .build();
    }

    // -------------------------------------------------------------- flag off

    @Test
    void everythingButTheCapabilityProbeIs404WhileTheFlagIsOff() throws Exception {
        McpOAuthClientMetadata cimd = cimd(true);
        MockMvc mvc = mvc(service(false, cimd), cimd);

        mvc.perform(get("/api/mcp/oauth/remote")).andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.cimd").value(false));
        mvc.perform(post("/api/mcp/oauth/remote/authorize").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + MCP + "\"}"))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/mcp/oauth/remote/status").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + MCP + "\"}"))
            .andExpect(status().isNotFound());
        mvc.perform(delete("/api/mcp/oauth/remote").param("url", MCP)).andExpect(status().isNotFound());
        mvc.perform(get("/oauth/mcp/remote/callback").param("code", "c").param("state", "s")).andExpect(status().isNotFound());
        mvc.perform(get(McpOAuthClientMetadata.DEFAULT_PATH)).andExpect(status().isNotFound());
        assertTrue(as.requests.isEmpty(), "flag off: not a single outbound request");
        verify(linker, never()).link(anyString());
    }

    @Test
    void theClientMetadataDocumentIs404UnlessCimdItselfIsAlsoOn() throws Exception {
        McpOAuthClientMetadata cimd = cimd(false);
        MockMvc mvc = mvc(service(true, cimd), cimd);
        mvc.perform(get(McpOAuthClientMetadata.DEFAULT_PATH)).andExpect(status().isNotFound());
        mvc.perform(get("/api/mcp/oauth/remote")).andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.cimd").value(false));
    }

    // ------------------------------------------------------------------ CIMD

    @Test
    void theClientMetadataDocumentIsServedAsPublicJsonWhenActive() throws Exception {
        McpOAuthClientMetadata cimd = cimd(true);
        MockMvc mvc = mvc(service(true, cimd), cimd);
        TenantContext.clear(); // public route: no session
        mvc.perform(get(McpOAuthClientMetadata.DEFAULT_PATH))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/json"))
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("public")))
            .andExpect(jsonPath("$.client_id").value(CIMD_URL))
            .andExpect(jsonPath("$.redirect_uris[0]").value(CALLBACK))
            .andExpect(jsonPath("$.redirect_uris.length()").value(1))
            .andExpect(jsonPath("$.token_endpoint_auth_method").value("none"))
            .andExpect(jsonPath("$.client_secret").doesNotExist());
    }

    // ----------------------------------------------------------- route table

    @Test
    void remoteRoutesAreNotSwallowedByTheProviderControllersVariableRoutes() throws Exception {
        McpOAuthClientMetadata cimd = cimd(false);
        McpOAuthService svc = service(true, cimd);
        MockMvc mvc = mvc(svc, cimd);

        // /oauth/mcp/remote/callback is ours...
        TenantContext.clear();
        MvcResult ours = mvc.perform(get("/oauth/mcp/remote/callback").param("error", "access_denied")).andReturn();
        assertTrue(ours.getResponse().getHeader("Location").contains("provider=remote"));
        verify(providerService, never()).completeAuthorization(anyString(), anyString(), anyString());
        // ...while another id still reaches the provider controller
        MvcResult theirs = mvc.perform(get("/oauth/mcp/github/callback").param("error", "access_denied")).andReturn();
        assertTrue(theirs.getResponse().getHeader("Location").contains("provider=github"));

        // DELETE /api/mcp/oauth/remote is ours; DELETE /api/mcp/oauth/github is the provider's
        TenantContext.set(TenantContext.of("tenant-a", "user-1", Set.of()));
        mvc.perform(delete("/api/mcp/oauth/remote").param("url", MCP)).andExpect(status().isOk());
        verify(providerService, never()).disconnect("remote");
    }

    // ------------------------------------------------------ authorize / status

    @Test
    void authorizeReturnsTheConsentUrl() throws Exception {
        McpOAuthClientMetadata cimd = cimd(false);
        MockMvc mvc = mvc(service(true, cimd), cimd);
        String body = mvc.perform(post("/api/mcp/oauth/remote/authorize").contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"" + MCP + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.resource").value(MCP))
            .andExpect(jsonPath("$.issuer").value(ISSUER))
            .andExpect(jsonPath("$.clientMode").value("dcr"))
            .andReturn().getResponse().getContentAsString();
        assertTrue(body.contains(ISSUER + "/authorize?response_type=code"));
    }

    @Test
    void authorizeMapsErrorsToTheRightStatus() throws Exception {
        McpOAuthClientMetadata cimd = cimd(false);
        MockMvc mvc = mvc(service(true, cimd), cimd);
        // SSRF refusal -> 400
        mvc.perform(post("/api/mcp/oauth/remote/authorize").contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"https://169.254.169.254/latest\"}")).andExpect(status().isBadRequest());
        // not an allow-listed return URL -> 400
        mvc.perform(post("/api/mcp/oauth/remote/authorize").contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"" + MCP + "\",\"returnTo\":\"https://evil.example.net/\"}")).andExpect(status().isBadRequest());
        // server publishes no OAuth metadata -> 422
        as.prmResource = "https://evil.example.net/mcp";
        mvc.perform(post("/api/mcp/oauth/remote/authorize").contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"" + MCP + "\"}")).andExpect(status().isUnprocessableEntity());
        // missing url -> 400
        mvc.perform(post("/api/mcp/oauth/remote/status").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void statusReportsTheFourConsoleStates() throws Exception {
        McpOAuthClientMetadata cimd = cimd(false);
        McpOAuthService svc = service(true, cimd);
        MockMvc mvc = mvc(svc, cimd);
        String req = "{\"url\":\"" + MCP + "\"}";

        mvc.perform(post("/api/mcp/oauth/remote/status").contentType(MediaType.APPLICATION_JSON).content(req))
            .andExpect(jsonPath("$.state").value("needs_signin"));
        as.mcpRequiresAuth = false;
        mvc.perform(post("/api/mcp/oauth/remote/status").contentType(MediaType.APPLICATION_JSON).content(req))
            .andExpect(jsonPath("$.state").value("not_required"));
        as.mcpRequiresAuth = true;

        completeFlow(svc);
        mvc.perform(post("/api/mcp/oauth/remote/status").contentType(MediaType.APPLICATION_JSON).content(req))
            .andExpect(jsonPath("$.state").value("connected")).andExpect(jsonPath("$.resource").value(MCP));
    }

    private McpOAuthService.CompletedConnection completeFlow(McpOAuthService svc) {
        McpOAuthService.AuthorizationStart start = svc.beginAuthorization(MCP, null);
        Map<String, String> cb = as.consent(start.authorizationUrl());
        return svc.completeAuthorization(cb.get("code"), cb.get("state"), cb.get("iss"));
    }

    // -------------------------------------------------------------- callback

    @Test
    void theCallbackConnectsLinksRegistrationsAndRedirectsToTheConsole() throws Exception {
        McpOAuthClientMetadata cimd = cimd(false);
        McpOAuthService svc = service(true, cimd);
        MockMvc mvc = mvc(svc, cimd);
        when(linker.link(MCP)).thenReturn(1);

        Map<String, String> cb = as.consent(svc.beginAuthorization(MCP, null).authorizationUrl());
        TenantContext.clear(); // the AS redirect carries no session
        MvcResult result = mvc.perform(get("/oauth/mcp/remote/callback")
                .param("code", cb.get("code")).param("state", cb.get("state")))
            .andExpect(status().isFound()).andReturn();
        String location = result.getResponse().getHeader("Location");
        assertEquals(CONSOLE + "?mcp_oauth=connected&provider=remote&mcp=registered&server="
            + java.net.URLEncoder.encode(MCP, java.nio.charset.StandardCharsets.UTF_8), location);
        verify(linker).link(MCP);
        assertTrue(TenantContext.current().isEmpty(), "the callback restores the (empty) context");
    }

    @Test
    void callbackFailuresAreFixedReasonCodesAndNeverLinkAnything() throws Exception {
        McpOAuthClientMetadata cimd = cimd(false);
        McpOAuthService svc = service(true, cimd);
        MockMvc mvc = mvc(svc, cimd);
        TenantContext.clear();

        assertTrue(location(mvc, "/oauth/mcp/remote/callback?error=access_denied&error_description=secret-text")
            .endsWith("mcp_oauth=error&provider=remote&reason=consent_declined"));
        assertTrue(location(mvc, "/oauth/mcp/remote/callback?code=c").contains("reason=missing_code_or_state"));
        assertTrue(location(mvc, "/oauth/mcp/remote/callback?code=c&state=garbage").contains("reason=invalid_state"));

        TenantContext.set(TenantContext.of("tenant-a", "user-1", Set.of()));
        as.issParameterSupported = true;
        Map<String, String> cb = as.consent(svc.beginAuthorization(MCP, null).authorizationUrl());
        TenantContext.clear();
        // wrong issuer -> issuer_mismatch
        assertTrue(location(mvc, "/oauth/mcp/remote/callback?code=" + cb.get("code") + "&state="
            + java.net.URLEncoder.encode(cb.get("state"), java.nio.charset.StandardCharsets.UTF_8) + "&iss=https%3A%2F%2Fevil.example.net")
            .contains("reason=issuer_mismatch"));
        // the state was consumed by that attempt: replay is reported as such
        assertTrue(location(mvc, "/oauth/mcp/remote/callback?code=" + cb.get("code") + "&state="
            + java.net.URLEncoder.encode(cb.get("state"), java.nio.charset.StandardCharsets.UTF_8) + "&iss=" + java.net.URLEncoder.encode(ISSUER, java.nio.charset.StandardCharsets.UTF_8))
            .contains("reason=replayed_state"));
        verify(linker, never()).link(anyString());
    }

    @Test
    void aCallbackOnAnotherTenantsSessionIsRejectedWithTenantMismatch() throws Exception {
        McpOAuthClientMetadata cimd = cimd(false);
        McpOAuthService svc = service(true, cimd);
        MockMvc mvc = mvc(svc, cimd);
        Map<String, String> cb = as.consent(svc.beginAuthorization(MCP, null).authorizationUrl());
        TenantContext.set(TenantContext.of("tenant-b", "mallory", Set.of()));
        String loc = location(mvc, "/oauth/mcp/remote/callback?code=" + cb.get("code") + "&state="
            + java.net.URLEncoder.encode(cb.get("state"), java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(loc.contains("reason=tenant_mismatch"), loc);
        verify(linker, never()).link(anyString());
    }

    @Test
    void disconnectDropsLiveConnectionsOnlyWhenSomethingWasConnected() throws Exception {
        McpOAuthClientMetadata cimd = cimd(false);
        McpOAuthService svc = service(true, cimd);
        MockMvc mvc = mvc(svc, cimd);
        when(linker.unlink(anyList())).thenReturn(2);

        mvc.perform(delete("/api/mcp/oauth/remote").param("url", MCP))
            .andExpect(jsonPath("$.disconnected").value(false)).andExpect(jsonPath("$.liveConnectionsDropped").value(0));
        verify(linker, never()).unlink(anyList());

        completeFlow(svc);
        mvc.perform(delete("/api/mcp/oauth/remote").param("url", MCP))
            .andExpect(jsonPath("$.disconnected").value(true)).andExpect(jsonPath("$.liveConnectionsDropped").value(2));
        verify(linker).unlink(List.of(MCP));
        assertFalse(svc.disconnect(MCP).size() > 0);
    }

    private static String location(MockMvc mvc, String url) throws Exception {
        MvcResult r = mvc.perform(get(URI.create(url))).andExpect(status().isFound()).andReturn();
        return r.getResponse().getHeader("Location");
    }
}
