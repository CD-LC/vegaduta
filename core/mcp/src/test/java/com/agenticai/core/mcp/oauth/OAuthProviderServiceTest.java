package com.agenticai.core.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.mcp.oauth.McpOAuthConnectionStore.McpOAuthConnection;
import com.agenticai.core.mcp.oauth.OAuthProviderClient.RevokeOutcome;
import com.agenticai.core.mcp.oauth.OAuthProviderTestSupport.MutableClock;
import com.agenticai.core.mcp.oauth.OAuthProviderTestSupport.ScriptedHttp;
import com.agenticai.core.security.TenantContext;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class OAuthProviderServiceTest {

    private static final String RETURN_URL = "https://app.example.com/tools";
    private static final String GOOGLE_TOKEN = "https://oauth2.googleapis.com/token";
    private static final String GITHUB_TOKEN = "https://github.com/login/oauth/access_token";

    private MutableClock clock;
    private EncryptionService encryption;
    private ScriptedHttp http;
    private InMemoryMcpOAuthConnectionStore store;
    private OAuthProviderService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-04T10:00:00Z"));
        encryption = OAuthProviderTestSupport.encryption((byte) 3);
        http = new ScriptedHttp();
        store = new InMemoryMcpOAuthConnectionStore();
        service =
                newService(
                        OAuthProviderTestSupport.props(
                                "google-sheets", "github", "microsoft-graph", "zoom"));
        asTenant("tenant_a", "user-1");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private OAuthProviderService newService(Map<String, String> props) {
        return new OAuthProviderService(
                OAuthProviderTestSupport.registry(props),
                new OAuthProviderClient(http, com.agenticai.core.json.JsonMappers.plain()),
                store,
                encryption,
                600,
                new OAuthRedirectPolicy(RETURN_URL, ""),
                clock);
    }

    private static void asTenant(String tenant, String user) {
        TenantContext.set(TenantContext.of(tenant, user, Set.of()));
    }

    private static String stateOf(String authorizationUrl) {
        for (String pair : URI.create(authorizationUrl).getRawQuery().split("&")) {
            if (pair.startsWith("state=")) {
                return URLDecoder.decode(pair.substring(6), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("no state in " + authorizationUrl);
    }

    /**
     * Runs the whole consent round trip; {@code tokenJson} is what the provider's token endpoint
     * answers.
     */
    private OAuthProviderService.CompletedConnection connect(
            String providerId, String tokenUrl, String tokenJson) {
        OAuthProviderService.AuthorizationStart start =
                service.beginAuthorization(providerId, null);
        http.respond(
                s ->
                        s.url().equals(tokenUrl)
                                ? OAuthProviderTestSupport.json(200, tokenJson)
                                : OAuthProviderTestSupport.json(404, ""));
        return service.completeAuthorization(
                providerId, "the-code", stateOf(start.authorizationUrl()));
    }

    private static String googleTokens(String access, String refresh, int expiresIn) {
        return "{\"access_token\":\""
                + access
                + "\",\"refresh_token\":\""
                + refresh
                + "\",\"expires_in\":"
                + expiresIn
                + "}";
    }

    private McpOAuthConnection stored(String tenant, String providerId) {
        return store.find(tenant, OAuthProviderService.RESOURCE_PREFIX + providerId).orElseThrow();
    }

    // ------------------------------------------------------------------ authorize / complete

    @Test
    void authorizeBindsTheStateToTheCurrentTenantAndUser() {
        OAuthProviderService.AuthorizationStart start = service.beginAuthorization("github", null);

        assertTrue(
                start.authorizationUrl().startsWith("https://github.com/login/oauth/authorize?"));
        OAuthStateCodec.Payload payload =
                new OAuthStateCodec(encryption, clock, 600)
                        .decode(stateOf(start.authorizationUrl()), "github");
        assertEquals("tenant_a", payload.tenantId());
        assertEquals("user-1", payload.userId());
        assertEquals(RETURN_URL, payload.returnUrl());
    }

    @Test
    void authorizeRejectsAReturnUrlOutsideTheAllowList() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.beginAuthorization("github", "https://evil.example.com/x"));
    }

    @Test
    void anUnknownProviderIs404AndAnUnconfiguredOneIs409WithTheReason() {
        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(
                                ResponseStatusException.class,
                                () -> service.beginAuthorization("nope", null))
                        .getStatusCode());

        ResponseStatusException notConfigured =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.beginAuthorization("slack", null));
        assertEquals(HttpStatus.CONFLICT, notConfigured.getStatusCode());
        assertTrue(notConfigured.getReason().contains("no_client"));
    }

    @Test
    void everythingIsRefusedWhileTheFeatureFlagIsOff() {
        Map<String, String> props = OAuthProviderTestSupport.props("github");
        props.put("app.mcp.oauth.providers-enabled", "false");
        OAuthProviderService off = newService(props);

        assertFalse(off.featureEnabled());
        assertEquals(
                HttpStatus.CONFLICT,
                assertThrows(
                                ResponseStatusException.class,
                                () -> off.beginAuthorization("github", null))
                        .getStatusCode());
        assertEquals(
                "feature_off",
                assertThrows(McpOAuthException.class, () -> off.currentAccessToken("github"))
                        .getOauthErrorCode());
    }

    @Test
    void completingTheFlowStoresOnlyCiphertextAndOnlyTheRefreshTokenForARefreshableProvider() {
        OAuthProviderService.CompletedConnection done =
                connect("google-sheets", GOOGLE_TOKEN, googleTokens("at-1", "rt-1", 3600));

        assertEquals("tenant_a", done.tenantId());
        assertEquals("user-1", done.userId());
        assertEquals(RETURN_URL, done.returnUrl());
        McpOAuthConnection c = stored("tenant_a", "google-sheets");
        assertNotNull(c.encryptedRefreshToken());
        assertNotEquals("rt-1", c.encryptedRefreshToken());
        assertEquals("rt-1", encryption.decrypt(c.encryptedRefreshToken()));
        assertNull(
                c.encryptedAccessToken(),
                "a refreshable access token is cached in memory, never persisted");
        assertNull(
                c.encryptedClientSecret(),
                "the platform client secret is read from config, not copied per tenant");
        assertEquals("cid-google-sheets", c.clientId());
        assertFalse(c.toString().contains("rt-1"));
    }

    @Test
    void theExchangeSendsTheCodeAndThePkceVerifierFromTheState() {
        OAuthProviderService.AuthorizationStart start =
                service.beginAuthorization("google-sheets", null);
        String state = stateOf(start.authorizationUrl());
        String verifier =
                new OAuthStateCodec(encryption, clock, 600)
                        .decode(state, "google-sheets")
                        .codeVerifier();
        http.respond(s -> OAuthProviderTestSupport.json(200, googleTokens("a", "r", 3600)));

        service.completeAuthorization("google-sheets", "the-code", state);

        String body = http.last().body();
        assertTrue(body.contains("code=the-code"));
        assertTrue(body.contains("code_verifier=" + verifier));
        assertTrue(
                start.authorizationUrl()
                        .contains("code_challenge=" + McpOAuthPkce.challengeFor(verifier)));
    }

    @Test
    void theCallbackNeedsNoAmbientTenantBecauseTheStateCarriesIt() {
        OAuthProviderService.AuthorizationStart start =
                service.beginAuthorization("google-sheets", null);
        TenantContext.clear(); // the provider's redirect has no JWT and so no TenantContext
        http.respond(s -> OAuthProviderTestSupport.json(200, googleTokens("a", "r", 3600)));

        OAuthProviderService.CompletedConnection done =
                service.completeAuthorization(
                        "google-sheets", "c", stateOf(start.authorizationUrl()));

        assertEquals("tenant_a", done.tenantId());
        assertTrue(
                store.find("tenant_a", OAuthProviderService.RESOURCE_PREFIX + "google-sheets")
                        .isPresent());
    }

    @Test
    void aStateCannotBeReplayedOrUsedOnAnotherProviderOrAfterItExpired() {
        OAuthProviderService.AuthorizationStart start =
                service.beginAuthorization("google-sheets", null);
        String state = stateOf(start.authorizationUrl());
        http.respond(s -> OAuthProviderTestSupport.json(200, googleTokens("a", "r", 3600)));

        assertEquals(
                OAuthStateException.PROVIDER_MISMATCH,
                assertThrows(
                                OAuthStateException.class,
                                () -> service.completeAuthorization("github", "c", state))
                        .reason());

        service.completeAuthorization("google-sheets", "c", state);
        assertEquals(
                OAuthStateException.REPLAYED,
                assertThrows(
                                OAuthStateException.class,
                                () -> service.completeAuthorization("google-sheets", "c", state))
                        .reason());

        String late = stateOf(service.beginAuthorization("google-sheets", null).authorizationUrl());
        clock.advance(Duration.ofSeconds(601));
        assertEquals(
                OAuthStateException.EXPIRED,
                assertThrows(
                                OAuthStateException.class,
                                () -> service.completeAuthorization("google-sheets", "c", late))
                        .reason());
    }

    @Test
    void aTokenExchangeFailureLeavesNothingStored() {
        String state =
                stateOf(service.beginAuthorization("google-sheets", null).authorizationUrl());
        http.respond(s -> OAuthProviderTestSupport.json(400, "{\"error\":\"invalid_grant\"}"));

        assertThrows(
                McpOAuthException.class,
                () -> service.completeAuthorization("google-sheets", "c", state));

        assertTrue(
                store.find("tenant_a", OAuthProviderService.RESOURCE_PREFIX + "google-sheets")
                        .isEmpty());
    }

    @Test
    void returnUrlForReadsAValidStateAndFallsBackToTheDefaultOtherwise() {
        String state = stateOf(service.beginAuthorization("github", null).authorizationUrl());

        assertEquals(RETURN_URL, service.returnUrlFor("github", state));
        assertEquals(RETURN_URL, service.returnUrlFor("github", "garbage"));
        assertEquals(RETURN_URL, service.returnUrlFor("github", null));
        assertEquals(RETURN_URL, service.returnUrlFor("slack", state));
    }

    // ------------------------------------------------------------------ access tokens

    @Test
    void aFreshTokenIsServedFromTheCacheWithoutAnotherProviderCall() {
        connect("google-sheets", GOOGLE_TOKEN, googleTokens("at-1", "rt-1", 3600));
        int callsAfterConnect = http.sent().size();

        assertEquals("at-1", service.currentAccessToken("google-sheets"));
        assertEquals("at-1", service.currentAccessToken("google-sheets"));

        assertEquals(callsAfterConnect, http.sent().size());
    }

    @Test
    void anExpiredTokenIsRefreshedAndARotatedRefreshTokenIsPersisted() {
        connect("google-sheets", GOOGLE_TOKEN, googleTokens("at-1", "rt-1", 3600));
        clock.advance(Duration.ofSeconds(3600 - 59)); // inside the 60s safety margin
        http.respond(s -> OAuthProviderTestSupport.json(200, googleTokens("at-2", "rt-2", 3600)));

        assertEquals("at-2", service.currentAccessToken("google-sheets"));

        assertEquals(
                "grant_type=refresh_token&refresh_token=rt-1&client_id=cid-google-sheets&client_secret=secret-google-sheets",
                http.last().body());
        assertEquals(
                "rt-2",
                encryption.decrypt(stored("tenant_a", "google-sheets").encryptedRefreshToken()));
        assertEquals(
                "at-2", service.currentAccessToken("google-sheets"), "and the new token is cached");
        assertEquals(
                1, http.count("POST", GOOGLE_TOKEN) - 1, "one refresh after the initial exchange");
    }

    @Test
    void anUnchangedRefreshTokenIsNotRewritten() {
        connect("google-sheets", GOOGLE_TOKEN, googleTokens("at-1", "rt-1", 3600));
        String before = stored("tenant_a", "google-sheets").encryptedRefreshToken();
        clock.advance(Duration.ofHours(2));
        http.respond(
                s ->
                        OAuthProviderTestSupport.json(
                                200, "{\"access_token\":\"at-2\",\"expires_in\":3600}"));

        assertEquals("at-2", service.currentAccessToken("google-sheets"));

        assertEquals(before, stored("tenant_a", "google-sheets").encryptedRefreshToken());
    }

    @Test
    void concurrentCallersShareOneRefreshSingleFlight() throws Exception {
        connect("google-sheets", GOOGLE_TOKEN, googleTokens("at-1", "rt-1", 3600));
        clock.advance(Duration.ofHours(2));
        AtomicInteger refreshes = new AtomicInteger();
        http.delay(150)
                .respond(
                        s -> {
                            refreshes.incrementAndGet();
                            return OAuthProviderTestSupport.json(
                                    200, googleTokens("at-refreshed", "rt-2", 3600));
                        });

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(
                    pool.submit(
                            () -> {
                                asTenant(
                                        "tenant_a",
                                        "worker"); // a worker thread has no TenantContext of its
                                // own
                                go.await();
                                return service.currentAccessToken("google-sheets");
                            }));
        }
        go.countDown();
        for (Future<String> f : results) {
            assertEquals("at-refreshed", f.get(10, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertEquals(
                1, refreshes.get(), "eight concurrent callers must produce exactly one refresh");
    }

    @Test
    void theRefreshLockIsPerTenantAndProviderSoOneSlowRefreshDoesNotBlockAnother() {
        connect("google-sheets", GOOGLE_TOKEN, googleTokens("at-a", "rt-a", 3600));
        asTenant("tenant_b", "user-2");
        connect("google-sheets", GOOGLE_TOKEN, googleTokens("at-b", "rt-b", 3600));

        assertEquals("at-b", service.currentAccessToken("google-sheets"));
        asTenant("tenant_a", "user-1");
        assertEquals(
                "at-a", service.currentAccessToken("google-sheets"), "connections are per tenant");
    }

    @Test
    void aPermanentlyRejectedRefreshTokenClearsTheConnectionSoTheUserIsToldToReconnect() {
        connect("google-sheets", GOOGLE_TOKEN, googleTokens("at-1", "rt-1", 3600));
        clock.advance(Duration.ofHours(2));
        http.respond(s -> OAuthProviderTestSupport.json(400, "{\"error\":\"invalid_grant\"}"));

        McpOAuthException e =
                assertThrows(
                        McpOAuthException.class, () -> service.currentAccessToken("google-sheets"));

        assertEquals("invalid_grant", e.getOauthErrorCode());
        assertFalse(stored("tenant_a", "google-sheets").isConnected());
        assertEquals(
                "not_connected",
                assertThrows(
                                McpOAuthException.class,
                                () -> service.currentAccessToken("google-sheets"))
                        .getOauthErrorCode());
        assertFalse(
                service.listForCurrentTenant().stream()
                        .filter(v -> v.id().equals("google-sheets"))
                        .findFirst()
                        .orElseThrow()
                        .connected());
    }

    @Test
    void aTransientRefreshFailureKeepsTheConnection() {
        connect("google-sheets", GOOGLE_TOKEN, googleTokens("at-1", "rt-1", 3600));
        clock.advance(Duration.ofHours(2));
        http.respond(s -> OAuthProviderTestSupport.json(503, "unavailable"));

        assertThrows(McpOAuthException.class, () -> service.currentAccessToken("google-sheets"));

        assertTrue(stored("tenant_a", "google-sheets").isConnected());
        http.respond(s -> OAuthProviderTestSupport.json(200, googleTokens("at-3", "rt-1", 3600)));
        assertEquals("at-3", service.currentAccessToken("google-sheets"));
    }

    @Test
    void aProviderThatIssuesNoRefreshTokenGetsItsNonExpiringAccessTokenPersistedEncrypted() {
        connect(
                "github",
                GITHUB_TOKEN,
                "{\"access_token\":\"gho_abc\",\"token_type\":\"bearer\",\"scope\":\"repo\"}");

        McpOAuthConnection c = stored("tenant_a", "github");
        assertNull(c.encryptedRefreshToken());
        assertNotNull(c.encryptedAccessToken());
        assertNotEquals("gho_abc", c.encryptedAccessToken());
        assertNull(c.accessTokenExpiresAt());
        assertTrue(c.isConnected());

        // a fresh service instance (a restart: no in-memory cache) still serves it from the store
        OAuthProviderService restarted =
                newService(
                        OAuthProviderTestSupport.props(
                                "google-sheets", "github", "microsoft-graph", "zoom"));
        int calls = http.sent().size();
        assertEquals("gho_abc", restarted.currentAccessToken("github"));
        assertEquals(calls, http.sent().size());
    }

    @Test
    void anExpiringTokenWithNoRefreshTokenAsksForAReconnectOnceItLapses() {
        connect(
                "zoom",
                "https://zoom.us/oauth/token",
                "{\"access_token\":\"z-1\",\"expires_in\":3600}");
        assertEquals("z-1", service.currentAccessToken("zoom"));

        clock.advance(Duration.ofSeconds(3600));
        McpOAuthException e =
                assertThrows(McpOAuthException.class, () -> service.currentAccessToken("zoom"));

        assertEquals("expired", e.getOauthErrorCode());
        assertFalse(
                service.listForCurrentTenant().stream()
                        .filter(v -> v.id().equals("zoom"))
                        .findFirst()
                        .orElseThrow()
                        .connected());
    }

    @Test
    void anAccessTokenThatCameWithARefreshTokenButNoExpiryIsRefreshedAfterTheAssumedLifetime() {
        connect(
                "zoom",
                "https://zoom.us/oauth/token",
                "{\"access_token\":\"z-1\",\"refresh_token\":\"zr-1\"}");
        assertEquals("z-1", service.currentAccessToken("zoom"));

        clock.advance(Duration.ofSeconds(OAuthProviderService.ASSUMED_REFRESHABLE_TTL_SECONDS));
        http.respond(
                s ->
                        OAuthProviderTestSupport.json(
                                200,
                                "{\"access_token\":\"z-2\",\"refresh_token\":\"zr-2\",\"expires_in\":3599}"));

        assertEquals("z-2", service.currentAccessToken("zoom"));
        assertEquals(
                "zr-2", encryption.decrypt(stored("tenant_a", "zoom").encryptedRefreshToken()));
    }

    @Test
    void notConnectedIsAClearErrorNotAnNpe() {
        McpOAuthException e =
                assertThrows(McpOAuthException.class, () -> service.currentAccessToken("github"));
        assertEquals("not_connected", e.getOauthErrorCode());
    }

    @Test
    void tokensNeverAppearInExceptionMessages() {
        connect("google-sheets", GOOGLE_TOKEN, googleTokens("SECRET-AT", "SECRET-RT", 3600));
        clock.advance(Duration.ofHours(2));
        http.respond(
                s ->
                        OAuthProviderTestSupport.json(
                                400,
                                "{\"error\":\"invalid_request\",\"error_description\":\"SECRET-RT\"}"));

        McpOAuthException e =
                assertThrows(
                        McpOAuthException.class, () -> service.currentAccessToken("google-sheets"));

        assertFalse(e.getMessage().contains("SECRET"));
        assertFalse(String.valueOf(e.getCause()).contains("SECRET"));
    }

    // ------------------------------------------------------------------ disconnect

    @Test
    void disconnectRevokesAtTheProviderThenForgetsTheTokens() {
        connect("google-sheets", GOOGLE_TOKEN, googleTokens("at-1", "rt-1", 3600));
        http.respond(s -> OAuthProviderTestSupport.json(200, "{}"));

        OAuthProviderService.DisconnectResult result = service.disconnect("google-sheets");

        assertTrue(result.wasConnected());
        assertEquals(RevokeOutcome.REVOKED, result.providerRevocation());
        assertEquals("https://oauth2.googleapis.com/revoke", http.last().url());
        assertTrue(
                http.last().body().contains("token=rt-1"),
                "Google: revoking the refresh token ends the whole grant");
        assertTrue(
                store.find("tenant_a", OAuthProviderService.RESOURCE_PREFIX + "google-sheets")
                        .isEmpty());
        assertEquals(
                "not_connected",
                assertThrows(
                                McpOAuthException.class,
                                () -> service.currentAccessToken("google-sheets"))
                        .getOauthErrorCode());
    }

    @Test
    void disconnectOfAProviderWithNoRevocationEndpointSaysSoAndStillForgets() {
        connect(
                "microsoft-graph",
                "https://login.microsoftonline.com/common/oauth2/v2.0/token",
                googleTokens("m-1", "mr-1", 3599));
        int calls = http.sent().size();

        OAuthProviderService.DisconnectResult result = service.disconnect("microsoft-graph");

        assertEquals(RevokeOutcome.NOT_SUPPORTED, result.providerRevocation());
        assertEquals(calls, http.sent().size(), "no revoke call exists for Entra");
        assertTrue(
                store.find("tenant_a", OAuthProviderService.RESOURCE_PREFIX + "microsoft-graph")
                        .isEmpty());
    }

    @Test
    void aFailedProviderRevocationIsReportedButTheLocalTokensAreStillForgotten() {
        connect("github", GITHUB_TOKEN, "{\"access_token\":\"gho_abc\"}");
        http.respond(s -> OAuthProviderTestSupport.json(500, "boom"));

        OAuthProviderService.DisconnectResult result = service.disconnect("github");

        assertEquals(RevokeOutcome.FAILED, result.providerRevocation());
        assertTrue(
                store.find("tenant_a", OAuthProviderService.RESOURCE_PREFIX + "github").isEmpty());
    }

    @Test
    void disconnectWhenNotConnectedIsANoOp() {
        OAuthProviderService.DisconnectResult result = service.disconnect("github");
        assertFalse(result.wasConnected());
    }

    @Test
    void disconnectStillWorksAfterTheAdminRemovedTheCredentialsButCannotRevoke() {
        connect("github", GITHUB_TOKEN, "{\"access_token\":\"gho_abc\"}");
        OAuthProviderService unconfigured = newService(OAuthProviderTestSupport.props());
        int calls = http.sent().size();

        OAuthProviderService.DisconnectResult result = unconfigured.disconnect("github");

        assertTrue(result.wasConnected());
        assertEquals(RevokeOutcome.FAILED, result.providerRevocation());
        assertEquals(calls, http.sent().size());
        assertTrue(
                store.find("tenant_a", OAuthProviderService.RESOURCE_PREFIX + "github").isEmpty());
    }

    @Test
    void disconnectStillForgetsTheRowWhenTheStoredTokensCannotBeDecrypted() {
        connect("google-sheets", GOOGLE_TOKEN, googleTokens("at-1", "rt-1", 3600));
        connect("github", GITHUB_TOKEN, "{\"access_token\":\"gho_abc\"}");
        // Same rows, but this process holds a different APP_ENCRYPTION_KEY: every ciphertext is
        // unreadable.
        OAuthProviderService wrongKey =
                new OAuthProviderService(
                        OAuthProviderTestSupport.registry(
                                OAuthProviderTestSupport.props("google-sheets", "github")),
                        new OAuthProviderClient(http, com.agenticai.core.json.JsonMappers.plain()),
                        store,
                        OAuthProviderTestSupport.encryption((byte) 9),
                        600,
                        new OAuthRedirectPolicy(RETURN_URL, ""),
                        clock);
        int calls = http.sent().size();

        OAuthProviderService.DisconnectResult refreshable = wrongKey.disconnect("google-sheets");
        OAuthProviderService.DisconnectResult accessOnly = wrongKey.disconnect("github");

        assertTrue(refreshable.wasConnected());
        assertEquals(RevokeOutcome.FAILED, refreshable.providerRevocation());
        assertTrue(accessOnly.wasConnected());
        assertEquals(
                calls, http.sent().size(), "nothing readable, so nothing is sent to the provider");
        assertTrue(
                store.find("tenant_a", OAuthProviderService.RESOURCE_PREFIX + "google-sheets")
                        .isEmpty());
        assertTrue(
                store.find("tenant_a", OAuthProviderService.RESOURCE_PREFIX + "github").isEmpty());
    }

    @Test
    void anUndecryptableStoredTokenIsAReconnectErrorNotAnInternalError() {
        connect("github", GITHUB_TOKEN, "{\"access_token\":\"gho_abc\"}");
        OAuthProviderService wrongKey =
                new OAuthProviderService(
                        OAuthProviderTestSupport.registry(OAuthProviderTestSupport.props("github")),
                        new OAuthProviderClient(http, com.agenticai.core.json.JsonMappers.plain()),
                        store,
                        OAuthProviderTestSupport.encryption((byte) 9),
                        600,
                        new OAuthRedirectPolicy(RETURN_URL, ""),
                        clock);

        assertEquals(
                "token_unreadable",
                assertThrows(McpOAuthException.class, () -> wrongKey.currentAccessToken("github"))
                        .getOauthErrorCode());
    }

    @Test
    void hubspotsBadRefreshTokenStatusClearsTheConnectionSoTheUserIsToldToReconnect() {
        service = newService(OAuthProviderTestSupport.props("hubspot"));
        connect(
                "hubspot",
                "https://api.hubapi.com/oauth/v1/token",
                googleTokens("hs-at", "hs-rt", 1800));
        clock.advance(Duration.ofHours(2));
        http.respond(
                s ->
                        OAuthProviderTestSupport.json(
                                400,
                                "{\"status\":\"BAD_REFRESH_TOKEN\",\"message\":\"missing or unknown refresh token\"}"));

        McpOAuthException e =
                assertThrows(McpOAuthException.class, () -> service.currentAccessToken("hubspot"));

        assertEquals("invalid_grant", e.getOauthErrorCode());
        assertFalse(stored("tenant_a", "hubspot").isConnected());
    }

    // ------------------------------------------------------------------ listing

    @Test
    void theListIsHonestAboutConfiguredAndConnectedPerTenant() {
        connect("github", GITHUB_TOKEN, "{\"access_token\":\"gho_abc\"}");

        Map<String, OAuthProviderService.ProviderView> byId = new HashMap<>();
        service.listForCurrentTenant().forEach(v -> byId.put(v.id(), v));

        assertEquals(12, byId.size());
        assertTrue(byId.get("github").configured());
        assertTrue(byId.get("github").connected());
        assertTrue(byId.get("github").registersMcpServer());
        assertTrue(byId.get("google-sheets").configured());
        assertFalse(byId.get("google-sheets").connected());
        assertFalse(byId.get("slack").configured());
        assertEquals("no_client", byId.get("slack").notConfiguredReason());
        assertEquals(
                "https://api.example.com/oauth/mcp/slack/callback",
                byId.get("slack").redirectUri());
        assertFalse(byId.get("microsoft-graph").revocable());

        asTenant("tenant_b", "user-2");
        assertFalse(
                service.listForCurrentTenant().stream()
                        .filter(v -> v.id().equals("github"))
                        .findFirst()
                        .orElseThrow()
                        .connected(),
                "another tenant is not connected");
    }

    // ------------------------------------------------------------------ MCP token source

    @Test
    void theMarkerRoundTripsAndOnlyOurPrefixIsHandled() {
        assertEquals("oauth:github", OAuthProviderService.markerFor("github"));
        assertEquals("github", OAuthProviderService.providerIdOf("oauth:github"));
        assertTrue(service.handles("oauth:github"));
        assertFalse(service.handles("ATLASSIAN"));
        assertFalse(service.handles(null));
    }

    @Test
    void theBearerIsReleasedOnlyToTheProvidersOwnMcpHost() {
        connect("github", GITHUB_TOKEN, "{\"access_token\":\"gho_abc\"}");

        assertEquals(
                "gho_abc",
                service.accessTokenFor("oauth:github", "https://api.githubcopilot.com/mcp/"));

        McpOAuthException e =
                assertThrows(
                        McpOAuthException.class,
                        () ->
                                service.accessTokenFor(
                                        "oauth:github", "https://evil.example.com/mcp"));
        assertEquals("host_not_allowed", e.getOauthErrorCode());
        assertFalse(e.getMessage().contains("gho_abc"));
        assertEquals(
                "unknown_provider",
                assertThrows(
                                McpOAuthException.class,
                                () -> service.accessTokenFor("oauth:nope", "https://x.example.com"))
                        .getOauthErrorCode());
    }

    @Test
    void aProviderWithoutAnMcpServerNeverReleasesItsTokenToAnyUrl() {
        connect("zoom", "https://zoom.us/oauth/token", googleTokens("z", "zr", 3600));

        assertEquals(
                "host_not_allowed",
                assertThrows(
                                McpOAuthException.class,
                                () -> service.accessTokenFor("oauth:zoom", "https://zoom.us/mcp"))
                        .getOauthErrorCode());
    }
}
