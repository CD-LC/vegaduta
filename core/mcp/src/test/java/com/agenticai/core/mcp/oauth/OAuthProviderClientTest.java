package com.agenticai.core.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.mcp.oauth.OAuthProviderClient.RevokeOutcome;
import com.agenticai.core.mcp.oauth.OAuthProviderClient.TokenResult;
import com.agenticai.core.mcp.oauth.OAuthProviderRegistry.Provider;
import com.agenticai.core.mcp.oauth.OAuthProviderTestSupport.ScriptedHttp;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OAuthProviderClientTest {

    private ScriptedHttp http;
    private OAuthProviderClient client;
    private OAuthProviderRegistry registry;

    @BeforeEach
    void setUp() {
        http = new ScriptedHttp();
        client = new OAuthProviderClient(http, com.agenticai.core.json.JsonMappers.plain());
        registry =
                OAuthProviderTestSupport.registry(
                        OAuthProviderTestSupport.props(
                                "google-sheets",
                                "github",
                                "slack",
                                "notion",
                                "linear",
                                "hubspot",
                                "microsoft-graph",
                                "asana",
                                "zoom"));
    }

    private Provider p(String id) {
        return registry.find(id).orElseThrow();
    }

    private static Map<String, String> query(String url) {
        Map<String, String> out = new HashMap<>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            out.put(
                    URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static Map<String, String> form(String body) {
        return body.isEmpty() ? new HashMap<>() : query("https://x.invalid/?" + body);
    }

    // ---------------------------------------------------------------- authorize URL

    @Test
    void googleAuthorizeUrlAsksForOfflineAccessWithPkceAndTheProductScopes() {
        String url = client.buildAuthorizationUrl(p("google-sheets"), "STATE", "CHALLENGE");
        Map<String, String> q = query(url);

        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"));
        assertEquals("code", q.get("response_type"));
        assertEquals("cid-google-sheets", q.get("client_id"));
        assertEquals(
                "https://api.example.com/oauth/mcp/google-sheets/callback", q.get("redirect_uri"));
        assertEquals("STATE", q.get("state"));
        assertEquals("offline", q.get("access_type"));
        assertEquals("consent", q.get("prompt"));
        assertEquals("CHALLENGE", q.get("code_challenge"));
        assertEquals("S256", q.get("code_challenge_method"));
        assertEquals("https://www.googleapis.com/auth/spreadsheets.readonly", q.get("scope"));
        assertFalse(url.contains("secret"), "the client secret never goes into a browser URL");
    }

    @Test
    void slackUsesUserScopeCommaSeparatedAndNoPkce() {
        Map<String, String> q = query(client.buildAuthorizationUrl(p("slack"), "S", "C"));

        assertTrue(q.get("user_scope").contains("channels:read,"));
        assertFalse(q.containsKey("scope"));
        assertFalse(q.containsKey("code_challenge"), "Slack documents no PKCE");
    }

    @Test
    void notionSendsOwnerUserAndNoScopeParameter() {
        Map<String, String> q = query(client.buildAuthorizationUrl(p("notion"), "S", "C"));

        assertEquals("user", q.get("owner"));
        assertFalse(q.containsKey("scope"));
        assertFalse(q.containsKey("code_challenge"));
    }

    @Test
    void microsoftAuthorizeUrlCarriesTheTenantSegmentOfflineAccessAndPkce() {
        String url = client.buildAuthorizationUrl(p("microsoft-graph"), "S", "C");
        Map<String, String> q = query(url);

        assertTrue(
                url.startsWith("https://login.microsoftonline.com/common/oauth2/v2.0/authorize?"));
        assertTrue(q.get("scope").startsWith("offline_access "));
        assertEquals("query", q.get("response_mode"));
        assertEquals("S256", q.get("code_challenge_method"));
    }

    @Test
    void linearScopesAreCommaSeparatedAndAsanaWithNoScopesSendsNone() {
        assertEquals(
                "read", query(client.buildAuthorizationUrl(p("linear"), "S", "C")).get("scope"));
        assertFalse(query(client.buildAuthorizationUrl(p("asana"), "S", "C")).containsKey("scope"));
    }

    // ---------------------------------------------------------------- code exchange

    @Test
    void githubExchangeSendsCredentialsInTheBodyAcceptsJsonAndSurfacesA200ErrorBody() {
        http.respond(
                s ->
                        OAuthProviderTestSupport.json(
                                200,
                                "{\"error\":\"bad_verification_code\",\"error_description\":\"echoes code=SECRET-CODE\"}"));

        McpOAuthException e =
                assertThrows(
                        McpOAuthException.class,
                        () -> client.exchangeCode(p("github"), "SECRET-CODE", "ver"));

        assertEquals("bad_verification_code", e.getOauthErrorCode());
        assertFalse(
                e.getMessage().contains("SECRET-CODE"),
                "never echo a response body or request value");
        ScriptedHttp.Sent sent = http.last();
        assertEquals("POST", sent.method());
        assertEquals("https://github.com/login/oauth/access_token", sent.url());
        assertEquals("application/json", sent.headers().get("Accept"));
        Map<String, String> f = form(sent.body());
        assertEquals("authorization_code", f.get("grant_type"));
        assertEquals("cid-github", f.get("client_id"));
        assertEquals("secret-github", f.get("client_secret"));
        assertEquals("ver", f.get("code_verifier"));
        assertFalse(sent.headers().containsKey("Authorization"));
    }

    @Test
    void githubNonExpiringTokenComesBackWithNoRefreshTokenAndNoExpiry() {
        http.respond(
                s ->
                        OAuthProviderTestSupport.json(
                                200,
                                "{\"access_token\":\"gho_abc\",\"token_type\":\"bearer\",\"scope\":\"repo,read:user\"}"));

        TokenResult t = client.exchangeCode(p("github"), "c", "v");

        assertEquals("gho_abc", t.accessToken());
        assertNull(t.refreshToken());
        assertNull(t.expiresInSeconds());
        assertEquals("repo,read:user", t.scope());
    }

    @Test
    void notionExchangeUsesBasicAuthAndAJsonBodyWithoutPkce() {
        http.respond(
                s ->
                        OAuthProviderTestSupport.json(
                                200,
                                "{\"access_token\":\"ntn_1\",\"refresh_token\":\"ntn_r1\",\"workspace_id\":\"w\"}"));

        TokenResult t = client.exchangeCode(p("notion"), "the-code", "ver");

        ScriptedHttp.Sent sent = http.last();
        assertEquals("https://api.notion.com/v1/oauth/token", sent.url());
        assertEquals(
                "Basic "
                        + Base64.getEncoder()
                                .encodeToString(
                                        "cid-notion:secret-notion"
                                                .getBytes(StandardCharsets.UTF_8)),
                sent.headers().get("Authorization"));
        assertEquals("application/json", sent.headers().get("Content-Type"));
        assertEquals("2022-06-28", sent.headers().get("Notion-Version"));
        assertTrue(sent.body().startsWith("{"));
        assertTrue(sent.body().contains("\"grant_type\":\"authorization_code\""));
        assertTrue(sent.body().contains("\"code\":\"the-code\""));
        assertFalse(
                sent.body().contains("client_secret"), "Basic auth, never the secret in the body");
        assertFalse(sent.body().contains("code_verifier"));
        assertEquals("ntn_r1", t.refreshToken());
        assertNull(t.expiresInSeconds());
    }

    @Test
    void zoomExchangeUsesBasicAuthWithAFormBody() {
        http.respond(
                s ->
                        OAuthProviderTestSupport.json(
                                200,
                                "{\"access_token\":\"z1\",\"refresh_token\":\"zr1\",\"expires_in\":3599}"));

        TokenResult t = client.exchangeCode(p("zoom"), "c", "v");

        ScriptedHttp.Sent sent = http.last();
        assertTrue(sent.headers().get("Authorization").startsWith("Basic "));
        assertEquals("application/x-www-form-urlencoded", sent.headers().get("Content-Type"));
        assertFalse(form(sent.body()).containsKey("client_secret"));
        assertEquals(3599, t.expiresInSeconds());
    }

    @Test
    void slackUserTokenIsReadFromAuthedUserOnTheInitialExchange() {
        http.respond(
                s ->
                        OAuthProviderTestSupport.json(
                                200,
                                "{\"ok\":true,\"app_id\":\"A1\",\"authed_user\":{\"id\":\"U1\",\"access_token\":\"xoxp-user\",\"scope\":\"chat:write\"}}"));

        TokenResult t = client.exchangeCode(p("slack"), "c", "v");

        assertEquals("xoxp-user", t.accessToken());
        assertEquals("chat:write", t.scope());
        assertNull(t.refreshToken());
        assertNull(t.expiresInSeconds());
    }

    @Test
    void slackOkFalseIsAnErrorEvenOnHttp200() {
        http.respond(
                s ->
                        OAuthProviderTestSupport.json(
                                200, "{\"ok\":false,\"error\":\"invalid_code\"}"));

        McpOAuthException e =
                assertThrows(
                        McpOAuthException.class, () -> client.exchangeCode(p("slack"), "c", "v"));

        assertEquals("invalid_code", e.getOauthErrorCode());
    }

    @Test
    void aNon2xxTokenResponseIsAnErrorWithTheProviderCode() {
        http.respond(s -> OAuthProviderTestSupport.json(400, "{\"error\":\"invalid_grant\"}"));

        McpOAuthException e =
                assertThrows(
                        McpOAuthException.class, () -> client.refresh(p("google-sheets"), "r"));

        assertEquals("invalid_grant", e.getOauthErrorCode());
        assertTrue(e.getMessage().contains("HTTP 400"));
    }

    @Test
    void aTokenResponseWithoutAnAccessTokenIsRejected() {
        http.respond(s -> OAuthProviderTestSupport.json(200, "{\"token_type\":\"bearer\"}"));

        assertThrows(McpOAuthException.class, () -> client.exchangeCode(p("github"), "c", "v"));
    }

    // ---------------------------------------------------------------- refresh

    @Test
    void refreshSendsTheGrantAndReadsTheRotatedRefreshToken() {
        http.respond(
                s ->
                        OAuthProviderTestSupport.json(
                                200,
                                "{\"access_token\":\"new-at\",\"refresh_token\":\"new-rt\",\"expires_in\":86399}"));

        TokenResult t = client.refresh(p("linear"), "old-rt");

        Map<String, String> f = form(http.last().body());
        assertEquals("refresh_token", f.get("grant_type"));
        assertEquals("old-rt", f.get("refresh_token"));
        assertEquals("cid-linear", f.get("client_id"));
        assertEquals("new-rt", t.refreshToken());
        assertEquals(86399, t.expiresInSeconds());
        assertFalse(f.containsKey("code_verifier"));
    }

    // ---------------------------------------------------------------- revoke

    @Test
    void googleRevokePostsTheTokenAsAForm() {
        http.respond(s -> OAuthProviderTestSupport.json(200, "{}"));

        assertEquals(RevokeOutcome.REVOKED, client.revoke(p("google-sheets"), "the-refresh-token"));

        ScriptedHttp.Sent sent = http.last();
        assertEquals("POST", sent.method());
        assertEquals("https://oauth2.googleapis.com/revoke", sent.url());
        assertEquals("the-refresh-token", form(sent.body()).get("token"));
    }

    @Test
    void githubRevokeIsADeleteWithBasicAuthAndAJsonBody() {
        http.respond(s -> OAuthProviderTestSupport.json(204, ""));

        assertEquals(RevokeOutcome.REVOKED, client.revoke(p("github"), "gho_abc"));

        ScriptedHttp.Sent sent = http.last();
        assertEquals("DELETE", sent.method());
        assertEquals("https://api.github.com/applications/cid-github/token", sent.url());
        assertTrue(sent.headers().get("Authorization").startsWith("Basic "));
        assertTrue(sent.body().contains("\"access_token\":\"gho_abc\""));
        assertEquals("VegaDuta", sent.headers().get("User-Agent"));
    }

    @Test
    void hubspotRevokeIsADeleteWithTheRefreshTokenInThePath() {
        http.respond(s -> OAuthProviderTestSupport.json(204, ""));

        assertEquals(RevokeOutcome.REVOKED, client.revoke(p("hubspot"), "rt/with+chars"));

        ScriptedHttp.Sent sent = http.last();
        assertEquals("DELETE", sent.method());
        assertEquals(
                "https://api.hubapi.com/oauth/v1/refresh-tokens/rt%2Fwith%2Bchars", sent.url());
        assertNull(sent.body());
    }

    @Test
    void linearAndAsanaRevokeSendTheClientCredentialsInTheForm() {
        http.respond(s -> OAuthProviderTestSupport.json(200, "{}"));

        assertEquals(RevokeOutcome.REVOKED, client.revoke(p("linear"), "tok"));
        Map<String, String> f = form(http.last().body());
        assertEquals("tok", f.get("token"));
        assertEquals("cid-linear", f.get("client_id"));
        assertEquals("secret-linear", f.get("client_secret"));

        assertEquals(RevokeOutcome.REVOKED, client.revoke(p("asana"), "tok2"));
        assertEquals("https://app.asana.com/-/oauth_revoke", http.last().url());
    }

    @Test
    void zoomAndNotionRevokeUseBasicAuth() {
        http.respond(s -> OAuthProviderTestSupport.json(200, "{\"status\":\"success\"}"));

        assertEquals(RevokeOutcome.REVOKED, client.revoke(p("zoom"), "z"));
        assertTrue(http.last().headers().get("Authorization").startsWith("Basic "));
        assertFalse(form(http.last().body()).containsKey("client_secret"));

        assertEquals(RevokeOutcome.REVOKED, client.revoke(p("notion"), "n"));
        assertEquals("https://api.notion.com/v1/oauth/revoke", http.last().url());
        assertEquals("application/json", http.last().headers().get("Content-Type"));
    }

    @Test
    void aProviderWithNoRevocationEndpointReportsNotSupportedAndMakesNoCall() {
        assertEquals(RevokeOutcome.NOT_SUPPORTED, client.revoke(p("microsoft-graph"), "tok"));
        assertTrue(http.sent().isEmpty());
    }

    @Test
    void aMissingTokenCannotBeRevoked() {
        assertEquals(RevokeOutcome.NOT_SUPPORTED, client.revoke(p("google-sheets"), null));
        assertEquals(RevokeOutcome.NOT_SUPPORTED, client.revoke(p("google-sheets"), " "));
        assertTrue(http.sent().isEmpty());
    }

    @Test
    void revokeFailuresNeverThrowAndAreReportedAsFailed() {
        http.respond(s -> OAuthProviderTestSupport.json(500, "boom"));
        assertEquals(RevokeOutcome.FAILED, client.revoke(p("google-sheets"), "t"));

        http.respond(
                s ->
                        OAuthProviderTestSupport.json(
                                200, "{\"ok\":false,\"error\":\"invalid_auth\"}"));
        assertEquals(RevokeOutcome.FAILED, client.revoke(p("slack"), "t"));

        http.respond(
                s -> {
                    throw new McpOAuthException(
                            "Could not reach https://api.hubapi.com/oauth/v1/refresh-tokens/SECRET");
                });
        assertEquals(RevokeOutcome.FAILED, client.revoke(p("hubspot"), "SECRET"));
    }
}
