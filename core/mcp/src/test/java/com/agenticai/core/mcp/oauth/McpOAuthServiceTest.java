package com.agenticai.core.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.mcp.oauth.McpOAuthConnectionStore.McpOAuthConnection;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpOAuthServiceTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String MCP = "https://mcp.example.com/mcp";
    private static final String ISSUER = "https://auth.example.com";
    private static final String CB = "https://api.example.com/oauth/mcp/remote/callback";
    private static final String PRM =
            "https://mcp.example.com/.well-known/oauth-protected-resource/mcp";
    private static final String AS_DOC =
            "{\"issuer\":\""
                    + ISSUER
                    + "\",\"authorization_endpoint\":\""
                    + ISSUER
                    + "/authorize\",\"token_endpoint\":\""
                    + ISSUER
                    + "/token\",\"registration_endpoint\":\""
                    + ISSUER
                    + "/register\",\"code_challenge_methods_supported\":[\"S256\"]}";

    private FakeMcpOAuthHttp http;
    private InMemoryMcpOAuthConnectionStore store;
    private EncryptionService encryption;
    private McpOAuthService service;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        encryption = new EncryptionService(KEY, "");
        Method init = EncryptionService.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(encryption);
        http =
                new FakeMcpOAuthHttp()
                        .on(
                                "GET",
                                PRM,
                                200,
                                "{\"resource\":\""
                                        + MCP
                                        + "\",\"authorization_servers\":[\""
                                        + ISSUER
                                        + "\"],\"scopes_supported\":[\"read\"]}")
                        .on("GET", ISSUER + "/.well-known/oauth-authorization-server", 200, AS_DOC)
                        .on(
                                "POST",
                                ISSUER + "/register",
                                201,
                                "{\"client_id\":\"cid\",\"client_secret\":\"client-secret-plain\",\"token_endpoint_auth_method\":\"client_secret_basic\"}");
        store = new InMemoryMcpOAuthConnectionStore();
        service = newService(true);
        TenantContext.set(TenantContext.of("tenant-a", "sub", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private McpOAuthService newService(boolean enabled) {
        ObjectMapper om = new ObjectMapper();
        return new McpOAuthService(
                new McpOAuthDiscovery(http, om),
                new McpOAuthClient(http, om),
                store,
                encryption,
                enabled,
                CB);
    }

    private static String stateFrom(String authorizationUrl) {
        for (String pair : URI.create(authorizationUrl).getRawQuery().split("&")) {
            if (pair.startsWith("state=")) {
                return URLDecoder.decode(pair.substring(6), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("no state in " + authorizationUrl);
    }

    private String connect(String refreshToken) {
        McpOAuthService.AuthorizationStart start = service.beginAuthorization(MCP, null);
        http.on(
                "POST",
                ISSUER + "/token",
                200,
                "{\"access_token\":\"at-1\",\"refresh_token\":\""
                        + refreshToken
                        + "\",\"expires_in\":3600}");
        service.completeAuthorization("code-1", stateFrom(start.authorizationUrl()));
        return start.authorizationUrl();
    }

    @Test
    void disabledServiceRefusesEverything() {
        McpOAuthService off = newService(false);
        assertFalse(off.isEnabled());
        assertThrows(McpOAuthException.class, () -> off.beginAuthorization(MCP, null));
        assertThrows(McpOAuthException.class, () -> off.currentAccessToken(MCP));
    }

    @Test
    void fullFlowStoresOnlyEncryptedSecretsAndCachesAccessToken() {
        String url = connect("rt-plain");
        assertTrue(url.contains("scope=read"));
        assertTrue(url.contains("code_challenge_method=S256"));

        McpOAuthConnection stored = store.find("tenant-a", MCP).orElseThrow();
        assertNotEquals("rt-plain", stored.encryptedRefreshToken());
        assertEquals("rt-plain", encryption.decrypt(stored.encryptedRefreshToken()));
        assertNotEquals("client-secret-plain", stored.encryptedClientSecret());
        assertEquals("client-secret-plain", encryption.decrypt(stored.encryptedClientSecret()));

        // access token from the exchange is cached: no refresh call needed
        assertEquals("at-1", service.currentAccessToken(MCP));
        assertEquals(
                0,
                http.sent.stream()
                        .filter(
                                s ->
                                        s.body() != null
                                                && s.body().contains("grant_type=refresh_token"))
                        .count());
    }

    @Test
    void refreshesWhenCacheExpiredAndPersistsRotatedRefreshToken() {
        // expires_in 30 < safety margin (60s), so the cached entry is immediately stale
        McpOAuthService.AuthorizationStart start = service.beginAuthorization(MCP, null);
        http.on(
                "POST",
                ISSUER + "/token",
                200,
                "{\"access_token\":\"at-1\",\"refresh_token\":\"rt-1\",\"expires_in\":30}");
        service.completeAuthorization("c", stateFrom(start.authorizationUrl()));

        http.on(
                "POST",
                ISSUER + "/token",
                200,
                "{\"access_token\":\"at-2\",\"refresh_token\":\"rt-2\",\"expires_in\":30}");
        assertEquals("at-2", service.currentAccessToken(MCP));
        assertEquals(
                "rt-2",
                encryption.decrypt(
                        store.find("tenant-a", MCP).orElseThrow().encryptedRefreshToken()));
    }

    @Test
    void invalidGrantClearsRefreshTokenAndRequiresReconnect() {
        McpOAuthService.AuthorizationStart start = service.beginAuthorization(MCP, null);
        http.on(
                "POST",
                ISSUER + "/token",
                200,
                "{\"access_token\":\"at-1\",\"refresh_token\":\"rt-1\",\"expires_in\":1}");
        service.completeAuthorization("c", stateFrom(start.authorizationUrl()));

        http.on("POST", ISSUER + "/token", 400, "{\"error\":\"invalid_grant\"}");
        McpOAuthException e =
                assertThrows(McpOAuthException.class, () -> service.currentAccessToken(MCP));
        assertEquals("reauth_required", e.getOauthErrorCode());
        assertFalse(store.find("tenant-a", MCP).orElseThrow().isConnected());
        McpOAuthException again =
                assertThrows(McpOAuthException.class, () -> service.currentAccessToken(MCP));
        assertEquals("reauth_required", again.getOauthErrorCode());
        assertTrue(store.find("tenant-a", MCP).orElseThrow().needsReauth());
    }

    @Test
    void transientRefreshFailureKeepsTheRefreshToken() {
        McpOAuthService.AuthorizationStart start = service.beginAuthorization(MCP, null);
        http.on(
                "POST",
                ISSUER + "/token",
                200,
                "{\"access_token\":\"at-1\",\"refresh_token\":\"rt-1\",\"expires_in\":1}");
        service.completeAuthorization("c", stateFrom(start.authorizationUrl()));

        http.on("POST", ISSUER + "/token", 503, "oops");
        assertThrows(McpOAuthException.class, () -> service.currentAccessToken(MCP));
        assertTrue(store.find("tenant-a", MCP).orElseThrow().isConnected());
    }

    @Test
    void secondConnectReusesRegisteredClient() {
        connect("rt-1");
        service.beginAuthorization(MCP, null);
        assertEquals(1, http.count("POST", ISSUER + "/register"));
    }

    @Test
    void tamperedStateIsRejected() {
        assertThrows(
                OAuthStateException.class,
                () -> service.completeAuthorization("c", "not-a-valid-blob"));
        assertThrows(OAuthStateException.class, () -> service.completeAuthorization("c", null));
    }

    @Test
    void tenantsDoNotShareConnections() {
        connect("rt-1");
        TenantContext.set(TenantContext.of("tenant-b", "sub", Set.of()));
        McpOAuthException e =
                assertThrows(McpOAuthException.class, () -> service.currentAccessToken(MCP));
        assertEquals("not_connected", e.getOauthErrorCode());
    }

    @Test
    void withoutARefreshTokenTheEncryptedAccessTokenIsPersistedUntilItExpires() {
        McpOAuthService.AuthorizationStart start = service.beginAuthorization(MCP, null);
        http.on(
                "POST",
                ISSUER + "/token",
                200,
                "{\"access_token\":\"at-only\",\"expires_in\":3600}");
        service.completeAuthorization("c", stateFrom(start.authorizationUrl()));
        McpOAuthConnection stored = store.find("tenant-a", MCP).orElseThrow();
        assertTrue(stored.isConnected());
        assertEquals(null, stored.encryptedRefreshToken());
        assertNotEquals("at-only", stored.encryptedAccessToken());
        assertEquals("at-only", service.currentAccessToken(MCP));
    }

    @Test
    void disconnectForgetsTheConnection() {
        connect("rt-1");
        service.disconnect(MCP);
        assertTrue(store.find("tenant-a", MCP).isEmpty());
    }

    @Test
    void stateIsSingleUse() {
        McpOAuthService.AuthorizationStart start = service.beginAuthorization(MCP, null);
        http.on(
                "POST",
                ISSUER + "/token",
                200,
                "{\"access_token\":\"at\",\"refresh_token\":\"rt\",\"expires_in\":3600}");
        String state = stateFrom(start.authorizationUrl());
        service.completeAuthorization("c", state);
        McpOAuthException e =
                assertThrows(
                        McpOAuthException.class, () -> service.completeAuthorization("c", state));
        assertTrue(e.getMessage().contains("already used"));
    }
}
