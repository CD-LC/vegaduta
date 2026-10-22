package com.agenticai.core.google;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Request-mapping and error-translation tests for {@link GoogleOAuthClient}'s raw-HttpClient calls
 * against Google's OAuth endpoints, on the injected stub transport {@link HttpStubs} documents
 * (there is no injectable transport seam in main code - see that class's javadoc).
 *
 * <p>The regressions worth catching: a dropped form field on the token exchange (Google 400s with
 * an unhelpful {@code invalid_request}), missing percent-encoding of a code/token containing {@code
 * +}/{@code /} (both appear in real Google authorization codes), losing {@code
 * access_type=offline}/{@code prompt=consent} from the authorization URL (silently breaks
 * reconnect-after-disconnect - no refresh token comes back), and an HTTP failure surfacing as
 * anything other than {@link GoogleApiException} with the upstream status and body preserved.
 */
class GoogleOAuthClientTest {

    private GoogleOAuthClient client;
    private HttpStubs.RecordingHttpClient http;

    @BeforeEach
    void setUp() {
        client = new GoogleOAuthClient(new ObjectMapper());
        http = HttpStubs.install(client);
    }

    // ---- authorization URL (pure string building, no HTTP) ------------------

    @Test
    void authorizationUrlCarriesOfflineConsentAndPercentEncodedParams() {
        String url =
                client.buildAuthorizationUrl(
                        "cid.apps.googleusercontent.com",
                        "https://api.example.com/oauth/google/callback",
                        "scope-a scope-b",
                        "st+ate/value");

        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"));
        assertTrue(url.contains("client_id=cid.apps.googleusercontent.com"));
        assertTrue(
                url.contains(
                        "redirect_uri=https%3A%2F%2Fapi.example.com%2Foauth%2Fgoogle%2Fcallback"));
        assertTrue(url.contains("response_type=code"));
        // Both are load-bearing: without them Google only issues a refresh
        // token on the very first consent, breaking every reconnect.
        assertTrue(url.contains("access_type=offline"));
        assertTrue(url.contains("prompt=consent"));
        assertTrue(url.contains("include_granted_scopes=true"));
        assertTrue(url.contains("scope=scope-a+scope-b"));
        // EncryptionService base64 can contain '+' and '/' - they must be
        // percent-encoded or Google hands back a corrupted state.
        assertTrue(url.contains("state=st%2Bate%2Fvalue"));
    }

    // ---- authorization-code exchange ----------------------------------------

    @Test
    void exchangeCodePostsTheExactFormGoogleExpects() {
        http.enqueue(
                200,
                "{\"access_token\":\"at-1\",\"refresh_token\":\"rt-1\",\"expires_in\":3599,\"scope\":\"a b\"}");

        GoogleOAuthClient.TokenResponse tokens =
                client.exchangeCode("cid", "sec", "https://api.example.com/cb", "4/0Ab+code");

        HttpRequest request = http.onlyRequest();
        assertEquals("https://oauth2.googleapis.com/token", request.uri().toString());
        assertEquals("POST", request.method());
        assertEquals(
                "application/x-www-form-urlencoded",
                request.headers().firstValue("Content-Type").orElse(null));
        // Real Google codes contain '/' and '+' - unencoded they corrupt the form.
        assertEquals(
                "code=4%2F0Ab%2Bcode&client_id=cid&client_secret=sec"
                        + "&redirect_uri=https%3A%2F%2Fapi.example.com%2Fcb&grant_type=authorization_code",
                HttpStubs.bodyOf(request));
        assertEquals("at-1", tokens.accessToken());
        assertEquals("rt-1", tokens.refreshToken());
        assertEquals(3599, tokens.expiresInSeconds());
        assertEquals("a b", tokens.scope());
    }

    @Test
    void pkceChallengeAndVerifierAreSentOnlyWhenGiven() {
        String plain =
                client.buildAuthorizationUrl("cid", "http://localhost:8080/cb", "s", "st", null);
        assertFalse(plain.contains("code_challenge"), "web mode must not change");

        String url =
                client.buildAuthorizationUrl(
                        "cid", "http://localhost:8080/cb", "s", "st", null, "CHAL");
        assertTrue(url.contains("&code_challenge=CHAL&code_challenge_method=S256"));

        http.enqueue(200, "{\"access_token\":\"at\",\"refresh_token\":\"rt\",\"expires_in\":3599}");
        client.exchangeCode("cid", "sec", "http://localhost:8080/cb", "code", "VER");
        assertTrue(HttpStubs.bodyOf(http.onlyRequest()).contains("&code_verifier=VER&"));
    }

    @Test
    void refreshGrantResponseWithoutRefreshTokenMapsToNullNotEmpty() {
        // Google never returns refresh_token on the refresh grant - the record
        // contract is null (GoogleOAuthService#connect branches on it).
        http.enqueue(200, "{\"access_token\":\"at-1\",\"expires_in\":3600}");

        GoogleOAuthClient.TokenResponse tokens = client.refreshAccessToken("cid", "sec", "rt-1");

        assertNull(tokens.refreshToken());
        assertEquals("", tokens.scope());
        assertEquals(3600, tokens.expiresInSeconds());
    }

    @Test
    void refreshAccessTokenSendsTheRefreshGrantForm() {
        http.enqueue(200, "{\"access_token\":\"at-9\"}");

        client.refreshAccessToken("cid", "sec", "rt/with+chars");

        assertEquals(
                "refresh_token=rt%2Fwith%2Bchars&client_id=cid&client_secret=sec&grant_type=refresh_token",
                HttpStubs.bodyOf(http.onlyRequest()));
    }

    // ---- error translation --------------------------------------------------

    @Test
    void rejectedAuthorizationCodeKeepsStatusAndBodyInTheMessage() {
        http.enqueue(400, "{\"error\":\"invalid_grant\"}");

        GoogleApiException ex =
                assertThrows(
                        GoogleApiException.class,
                        () -> client.exchangeCode("cid", "sec", "https://cb", "bad-code"));

        assertTrue(ex.getMessage().contains("Google rejected the authorization code"));
        assertTrue(ex.getMessage().contains("(400)"));
        assertTrue(
                ex.getMessage().contains("invalid_grant"),
                "upstream body must survive into the message");
    }

    @Test
    void rejectedRefreshTokenNamesTheFixReconnect() {
        // The classic failure: tenant revoked access in their Google account,
        // our stored refresh token is dead. The message the tool layer relays
        // must point at reconnecting, not just echo a 400.
        http.enqueue(400, "{\"error\":\"invalid_grant\"}");

        GoogleApiException ex =
                assertThrows(
                        GoogleApiException.class,
                        () -> client.refreshAccessToken("cid", "sec", "dead-rt"));

        assertTrue(ex.getMessage().contains("the tenant likely needs to reconnect"));
        assertTrue(ex.getMessage().contains("(400)"));
    }

    @Test
    void tokenResponseWithoutAccessTokenIsAnError() {
        http.enqueue(200, "{\"token_type\":\"Bearer\"}");

        GoogleApiException ex =
                assertThrows(
                        GoogleApiException.class,
                        () -> client.refreshAccessToken("cid", "sec", "rt-1"));

        assertTrue(ex.getMessage().contains("no access_token"));
    }

    @Test
    void networkFailureBecomesCouldNotReachNotARawIoException() {
        http.enqueueNetworkFailure("connection refused");

        GoogleApiException ex =
                assertThrows(
                        GoogleApiException.class,
                        () -> client.exchangeCode("cid", "sec", "https://cb", "code"));

        assertTrue(ex.getMessage().contains("Could not reach Google's OAuth token endpoint"));
        assertTrue(ex.getMessage().contains("connection refused"));
    }

    // ---- userinfo -----------------------------------------------------------

    @Test
    void fetchEmailSendsBearerTokenAndParsesEmail() {
        http.enqueue(200, "{\"email\":\"user@example.com\",\"verified_email\":true}");

        String email = client.fetchEmail("at-1");

        HttpRequest request = http.onlyRequest();
        assertEquals("https://www.googleapis.com/oauth2/v2/userinfo", request.uri().toString());
        assertEquals("Bearer at-1", request.headers().firstValue("Authorization").orElse(null));
        assertEquals("user@example.com", email);
    }

    @Test
    void userinfoFailureAndMissingEmailBothTranslateToGoogleApiException() {
        http.enqueue(401, "{\"error\":\"invalid_token\"}");
        GoogleApiException httpError =
                assertThrows(GoogleApiException.class, () -> client.fetchEmail("at-1"));
        assertTrue(httpError.getMessage().contains("(401)"));

        http.enqueue(200, "{}");
        GoogleApiException missingField =
                assertThrows(GoogleApiException.class, () -> client.fetchEmail("at-1"));
        assertTrue(missingField.getMessage().contains("no email"));
    }

    // ---- revoke -------------------------------------------------------------

    @Test
    void revokePostsTheTokenAsAQueryParamAndIgnoresNonSuccessStatuses() {
        // revoke() deliberately never checks the status code - Google returns
        // 400 for an already-revoked token, and disconnect must proceed anyway.
        http.enqueue(400, "{\"error\":\"invalid_token\"}");

        assertDoesNotThrow(() -> client.revoke("rt+1"));

        HttpRequest request = http.onlyRequest();
        assertEquals("https://oauth2.googleapis.com/revoke?token=rt%2B1", request.uri().toString());
        assertEquals("POST", request.method());
    }

    @Test
    void revokeNetworkFailureThrowsForTheCallerToLog() {
        // GoogleOAuthService#disconnect catches and logs this - the client
        // itself still reports it rather than swallowing silently.
        http.enqueueNetworkFailure("dns failure");

        GoogleApiException ex = assertThrows(GoogleApiException.class, () -> client.revoke("rt-1"));

        assertTrue(ex.getMessage().contains("Could not reach Google's revoke endpoint"));
    }
}
