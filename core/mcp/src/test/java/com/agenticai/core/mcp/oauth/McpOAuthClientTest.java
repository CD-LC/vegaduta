package com.agenticai.core.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.mcp.oauth.McpOAuthDiscovery.AuthServerMetadata;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class McpOAuthClientTest {

    private static final String ISSUER = "https://auth.example.com";
    private static final AuthServerMetadata AS =
            new AuthServerMetadata(
                    ISSUER,
                    ISSUER + "/authorize",
                    ISSUER + "/token",
                    ISSUER + "/register",
                    List.of(),
                    List.of("none", "client_secret_basic"));

    private final FakeMcpOAuthHttp http = new FakeMcpOAuthHttp();
    private final McpOAuthClient client =
            new McpOAuthClient(http, com.agenticai.core.json.JsonMappers.plain());

    @Test
    void pkceChallengeIsS256OfVerifier() {
        // RFC 7636 appendix B test vector
        assertEquals(
                "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                McpOAuthPkce.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        McpOAuthPkce.Pair pair = McpOAuthPkce.generate();
        assertEquals(McpOAuthPkce.challengeFor(pair.verifier()), pair.challenge());
        assertFalse(pair.verifier().contains("="));
    }

    @Test
    void registerTakesGrantedAuthMethodFromResponse() {
        http.on(
                "POST",
                ISSUER + "/register",
                201,
                "{\"client_id\":\"cid\",\"token_endpoint_auth_method\":\"client_secret_post\",\"client_secret\":\"sec\"}");
        McpOAuthClient.Registration reg =
                client.register(AS, "https://api.example.com/cb", "VegaDuta");
        assertEquals("cid", reg.clientId());
        assertEquals("sec", reg.clientSecret());
        assertEquals("client_secret_post", reg.tokenEndpointAuthMethod());
        // server supports "none", so a public client was requested
        assertTrue(http.sent.get(0).body().contains("\"token_endpoint_auth_method\":\"none\""));
        assertTrue(http.sent.get(0).body().contains("https://api.example.com/cb"));
    }

    @Test
    void registrationStatesTheApplicationTypeSoOidcServersDoNotDefaultItToNative() {
        http.on("POST", ISSUER + "/register", 201, "{\"client_id\":\"cid\"}");
        client.register(AS, "https://api.example.com/cb", "VegaDuta");
        assertTrue(http.sent.get(0).body().contains("\"application_type\":\"web\""));
    }

    @Test
    void registerFailsClearlyWithoutRegistrationEndpoint() {
        AuthServerMetadata noDcr =
                new AuthServerMetadata(
                        ISSUER,
                        ISSUER + "/authorize",
                        ISSUER + "/token",
                        null,
                        List.of(),
                        List.of());
        assertThrows(
                McpOAuthException.class, () -> client.register(noDcr, "https://x/cb", "VegaDuta"));
    }

    @Test
    void authorizationUrlCarriesPkceResourceAndScope() {
        String url =
                client.buildAuthorizationUrl(
                        AS,
                        "cid",
                        "https://api.example.com/cb",
                        "st",
                        "chal",
                        "https://mcp.example.com/mcp",
                        "read write");
        assertTrue(url.startsWith(ISSUER + "/authorize?response_type=code&client_id=cid"));
        assertTrue(url.contains("&code_challenge=chal&code_challenge_method=S256"));
        assertTrue(url.contains("&resource=https%3A%2F%2Fmcp.example.com%2Fmcp"));
        assertTrue(url.contains("&scope=read+write"));
        assertFalse(
                client.buildAuthorizationUrl(AS, "cid", "r", "s", "c", "res", null)
                        .contains("scope="));
    }

    @Test
    void exchangeUsesBasicAuthWhenGranted() {
        http.on(
                "POST",
                ISSUER + "/token",
                200,
                "{\"access_token\":\"at\",\"refresh_token\":\"rt\",\"expires_in\":120,\"scope\":\"read\"}");
        McpOAuthClient.TokenResponse t =
                client.exchangeCode(
                        AS,
                        "cid",
                        "sec",
                        "client_secret_basic",
                        "https://x/cb",
                        "code1",
                        "ver1",
                        "https://mcp.example.com/mcp");
        assertEquals("at", t.accessToken());
        assertEquals("rt", t.refreshToken());
        assertEquals(120, t.expiresInSeconds());
        FakeMcpOAuthHttp.Sent sent = http.sent.get(0);
        assertEquals(
                "Basic "
                        + Base64.getEncoder()
                                .encodeToString("cid:sec".getBytes(StandardCharsets.UTF_8)),
                sent.headers().get("Authorization"));
        assertTrue(sent.body().contains("grant_type=authorization_code"));
        assertTrue(sent.body().contains("code_verifier=ver1"));
        assertFalse(sent.body().contains("client_secret"));
    }

    @Test
    void publicClientSendsOnlyClientIdAndRefreshWorks() {
        http.on("POST", ISSUER + "/token", 200, "{\"access_token\":\"at2\"}");
        McpOAuthClient.TokenResponse t =
                client.refresh(AS, "cid", null, "none", "rt", "https://mcp.example.com/mcp");
        assertEquals("at2", t.accessToken());
        assertNull(t.refreshToken());
        FakeMcpOAuthHttp.Sent sent = http.sent.get(0);
        assertTrue(sent.body().contains("grant_type=refresh_token"));
        assertTrue(sent.body().contains("client_id=cid"));
        assertFalse(sent.headers().containsKey("Authorization"));
    }

    @Test
    void tokenErrorSurfacesOauthErrorCodeWithoutLeakingBody() {
        http.on(
                "POST",
                ISSUER + "/token",
                400,
                "{\"error\":\"invalid_grant\",\"error_description\":\"secret-echo\"}");
        McpOAuthException e =
                assertThrows(
                        McpOAuthException.class,
                        () -> client.refresh(AS, "cid", null, "none", "rt", "res"));
        assertEquals("invalid_grant", e.getOauthErrorCode());
        assertFalse(e.getMessage().contains("secret-echo"));
    }
}
