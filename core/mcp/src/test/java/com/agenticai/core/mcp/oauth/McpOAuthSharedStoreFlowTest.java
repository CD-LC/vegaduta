package com.agenticai.core.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.mcp.McpRemoteUrlGuard;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Two {@link McpOAuthService} instances over ONE {@link InMemoryMcpOAuthSharedStore} and ONE
 * {@link InMemoryMcpOAuthConnectionStore} stand in for two replicas (the shared store is what a
 * Valkey-backed {@link RedisMcpOAuthSharedStore} gives the real multi-replica deployment): a state
 * consumed on one "replica" must be rejected on the other, and a token cached on one must serve the
 * other without a second token-endpoint call.
 */
class McpOAuthSharedStoreFlowTest {

    private static final String MCP = "https://mcp.example.com/mcp";
    private static final String ISSUER = "https://auth.example.com";
    private static final String CB = "https://api.example.com/oauth/mcp/remote/callback";
    private static final String CONSOLE = "https://app.example.com/tools";
    private static final String PRM =
            "https://mcp.example.com/.well-known/oauth-protected-resource/mcp";
    private static final String AS_DOC =
            "{\"issuer\":\"" + ISSUER + "\",\"authorization_endpoint\":\"" + ISSUER
                + "/authorize\",\"token_endpoint\":\"" + ISSUER
                + "/token\",\"registration_endpoint\":\"" + ISSUER
                + "/register\",\"code_challenge_methods_supported\":[\"S256\"]}";

    private FakeMcpOAuthHttp http;
    private InMemoryMcpOAuthConnectionStore store;
    private EncryptionService encryption;
    private InMemoryMcpOAuthSharedStore shared;
    private OAuthProviderTestSupport.MutableClock clock;
    private McpOAuthService replicaA;
    private McpOAuthService replicaB;

    @BeforeEach
    void setUp() {
        encryption = OAuthProviderTestSupport.encryption((byte) 4);
        clock = new OAuthProviderTestSupport.MutableClock(Instant.parse("2026-10-05T10:00:00Z"));
        http = new FakeMcpOAuthHttp()
            .on("GET", PRM, 200,
                "{\"resource\":\"" + MCP + "\",\"authorization_servers\":[\"" + ISSUER
                    + "\"],\"scopes_supported\":[\"read\"]}")
            .on("GET", ISSUER + "/.well-known/oauth-authorization-server", 200, AS_DOC)
            .on("POST", ISSUER + "/register", 201,
                "{\"client_id\":\"cid\",\"client_secret\":\"client-secret-plain\",\"token_endpoint_auth_method\":\"client_secret_basic\"}");
        store = new InMemoryMcpOAuthConnectionStore();
        shared = new InMemoryMcpOAuthSharedStore(clock);
        replicaA = newService();
        replicaB = newService();
        TenantContext.set(TenantContext.of("tenant-a", "sub", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private McpOAuthService newService() {
        ObjectMapper om = new ObjectMapper();
        return new McpOAuthService(new McpOAuthDiscovery(http, om), new McpOAuthClient(http, om), store,
            encryption, null, new McpRemoteUrlGuard(), true, CB, new OAuthRedirectPolicy(CONSOLE, ""), clock,
            600, shared);
    }

    private static String stateFrom(String authorizationUrl) {
        for (String pair : URI.create(authorizationUrl).getRawQuery().split("&")) {
            if (pair.startsWith("state=")) {
                return URLDecoder.decode(pair.substring(6), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("no state in " + authorizationUrl);
    }

    @Test
    void aStateConsumedOnOneReplicaIsRejectedOnTheOther() {
        McpOAuthService.AuthorizationStart start = replicaA.beginAuthorization(MCP, null);
        http.on("POST", ISSUER + "/token", 200,
            "{\"access_token\":\"at-1\",\"refresh_token\":\"rt-1\",\"expires_in\":3600}");
        String state = stateFrom(start.authorizationUrl());
        replicaA.completeAuthorization("code-1", state);

        OAuthStateException replay = assertThrows(OAuthStateException.class,
            () -> replicaB.completeAuthorization("code-1", state));
        assertEquals(OAuthStateException.REPLAYED, replay.reason());
    }

    @Test
    void anAccessTokenCachedOnOneReplicaServesTheOther() {
        McpOAuthService.AuthorizationStart start = replicaA.beginAuthorization(MCP, null);
        http.on("POST", ISSUER + "/token", 200,
            "{\"access_token\":\"at-1\",\"refresh_token\":\"rt-1\",\"expires_in\":3600}");
        replicaA.completeAuthorization("code-1", stateFrom(start.authorizationUrl()));
        assertEquals(1, http.count("POST", ISSUER + "/token"));

        // The other replica must serve the cached token without calling the token endpoint again.
        assertEquals("at-1", replicaB.currentAccessToken(MCP));
        assertEquals(1, http.count("POST", ISSUER + "/token"));
    }

    @Test
    void aDisconnectOnOneReplicaDropsTheSharedCachedToken() {
        McpOAuthService.AuthorizationStart start = replicaA.beginAuthorization(MCP, null);
        http.on("POST", ISSUER + "/token", 200,
            "{\"access_token\":\"at-1\",\"refresh_token\":\"rt-1\",\"expires_in\":3600}");
        replicaA.completeAuthorization("code-1", stateFrom(start.authorizationUrl()));
        assertEquals("at-1", shared.token("tenant-a", MCP).orElseThrow().accessToken());

        replicaB.disconnect(MCP);
        assertEquals(java.util.Optional.empty(), shared.token("tenant-a", MCP));
    }
}
