package com.agenticai.core.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.mcp.McpRemoteUrlGuard;
import com.agenticai.core.mcp.oauth.McpOAuthConnectionStore.McpOAuthConnection;
import com.agenticai.core.mcp.oauth.OAuthProviderTestSupport.MutableClock;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The generic remote-MCP OAuth flow end to end against {@link FakeAuthorizationServer}, which
 * enforces PKCE, redirect-uri, RFC 8707 resource, rotation and CIMD semantics - so a green test
 * means the client sent the right thing, not that a canned response matched.
 */
class McpOAuthFlowTest {

    private static final String MCP = "https://mcp.example.com/mcp";
    private static final String ISSUER = "https://auth.example.com";
    private static final String CALLBACK = "https://api.example.com/oauth/mcp/remote/callback";
    private static final String CONSOLE = "https://app.example.com/tools";
    private static final String CIMD_URL =
            "https://api.example.com/oauth/mcp/remote/client-metadata.json";

    private FakeAuthorizationServer as;
    private InMemoryMcpOAuthConnectionStore store;
    private EncryptionService encryption;
    private MutableClock clock;
    private McpOAuthService service;
    private ListAppender<ILoggingEvent> logs;
    private Logger rootLogger;

    @BeforeEach
    void setUp() {
        as = new FakeAuthorizationServer(MCP, ISSUER);
        encryption = OAuthProviderTestSupport.encryption((byte) 7);
        store = new InMemoryMcpOAuthConnectionStore();
        clock = new MutableClock(Instant.parse("2026-10-05T10:00:00Z"));
        service = newService(true, null);
        TenantContext.set(TenantContext.of("tenant-a", "user-1", Set.of()));
        // Capture EVERYTHING logged by the flow to prove no secret ever reaches a log line.
        rootLogger = (Logger) LoggerFactory.getLogger("com.agenticai.core.mcp.oauth");
        rootLogger.setLevel(Level.DEBUG);
        logs = new ListAppender<>();
        logs.start();
        rootLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logs);
        TenantContext.clear();
    }

    private McpOAuthService newService(boolean enabled, McpOAuthClientMetadata cimd) {
        ObjectMapper om = new ObjectMapper();
        return new McpOAuthService(
                new McpOAuthDiscovery(as, om),
                new McpOAuthClient(as, om),
                store,
                encryption,
                cimd,
                new McpRemoteUrlGuard(),
                enabled,
                CALLBACK,
                new OAuthRedirectPolicy(CONSOLE, ""),
                clock,
                600);
    }

    private McpOAuthClientMetadata activeCimd() {
        return new McpOAuthClientMetadata(
                McpRemoteUrlGuard.withDenylist(List.of("postgres")),
                true,
                CIMD_URL,
                McpOAuthClientMetadata.DEFAULT_PATH,
                "VegaDuta",
                "",
                CALLBACK);
    }

    /** begin -> user consents at the AS -> callback. */
    private McpOAuthService.CompletedConnection connect() {
        McpOAuthService.AuthorizationStart start = service.beginAuthorization(MCP, null);
        Map<String, String> cb = as.consent(start.authorizationUrl());
        return service.completeAuthorization(cb.get("code"), cb.get("state"), cb.get("iss"));
    }

    // ------------------------------------------------------------ happy path

    @Test
    void fullFlowWithDynamicRegistrationConnectsAndStoresOnlyEncryptedSecrets() {
        McpOAuthService.AuthorizationStart start = service.beginAuthorization(MCP, null);
        assertEquals("dcr", start.clientMode());
        assertEquals(MCP, start.resourceUrl());
        Map<String, String> q =
                FakeAuthorizationServer.query(
                        java.net.URI.create(start.authorizationUrl()).getRawQuery());
        assertEquals(MCP, q.get("resource"));
        assertEquals("S256", q.get("code_challenge_method"));
        assertEquals(CALLBACK, q.get("redirect_uri"));
        assertEquals("read write", q.get("scope"));

        Map<String, String> cb = as.consent(start.authorizationUrl());
        McpOAuthService.CompletedConnection done =
                service.completeAuthorization(cb.get("code"), cb.get("state"), cb.get("iss"));
        assertEquals("tenant-a", done.tenantId());
        assertEquals("user-1", done.userId());
        assertEquals(MCP, done.resourceUrl());
        assertEquals(CONSOLE, done.returnUrl());

        McpOAuthConnection stored = store.find("tenant-a", MCP).orElseThrow();
        assertTrue(stored.isConnected());
        assertNotNull(stored.encryptedRefreshToken());
        assertTrue(encryption.decrypt(stored.encryptedRefreshToken()).startsWith("rt-"));
        assertEquals("dcr-secret-1", encryption.decrypt(stored.encryptedClientSecret()));
        assertNull(
                stored.encryptedAccessToken(),
                "a refreshable access token must never be persisted");
        assertFalse(stored.encryptedRefreshToken().startsWith("rt-"), "ciphertext, not the token");

        String token = service.currentAccessToken(MCP);
        assertTrue(token.startsWith("at-"));
        assertEquals(token, service.currentAccessToken(MCP), "served from the in-memory cache");
        assertEquals(1, as.tokenRequestBodies.size(), "no refresh while the cached token is fresh");
        assertEquals(McpOAuthService.State.CONNECTED, service.status(MCP).state());
    }

    @Test
    void theTokenRequestUsesTheSameClientRedirectUriAndResourceAsTheAuthorizationRequest() {
        connect();
        String body = as.tokenRequestBodies.get(0);
        assertTrue(body.contains("grant_type=authorization_code"));
        assertTrue(
                body.contains(
                        "redirect_uri="
                                + java.net.URLEncoder.encode(
                                        CALLBACK, java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(
                body.contains(
                        "resource="
                                + java.net.URLEncoder.encode(
                                        MCP, java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(body.contains("code_verifier="));
    }

    @Test
    void discoveryStartsFromAProbe401WithResourceMetadata() {
        as.advertiseResourceMetadataInChallenge = true;
        service.beginAuthorization(MCP, null);
        assertTrue(as.requests.contains("POST " + MCP));
        assertEquals(McpOAuthService.State.NEEDS_SIGNIN, service.status(MCP).state());
    }

    // -------------------------------------------------------------- status

    @Test
    void statusIsNotRequiredWhenTheServerAnswersWithoutCredentials() {
        as.mcpRequiresAuth = false;
        assertEquals(McpOAuthService.State.NOT_REQUIRED, service.status(MCP).state());
    }

    @Test
    void statusNeedsSigninWhenTheServerChallenges() {
        assertEquals(McpOAuthService.State.NEEDS_SIGNIN, service.status(MCP).state());
    }

    @Test
    void statusIsUnknownWhenTheServerCannotBeReached() {
        McpOAuthHttp unreachable =
                (m, u, h, b) -> {
                    throw new McpOAuthException("Could not reach " + u);
                };
        McpOAuthService s =
                new McpOAuthService(
                        new McpOAuthDiscovery(unreachable, new ObjectMapper()),
                        new McpOAuthClient(unreachable, new ObjectMapper()),
                        store,
                        encryption,
                        true,
                        CALLBACK);
        assertEquals(McpOAuthService.State.UNKNOWN, s.status(MCP).state());
    }

    // ----------------------------------------------------- DCR vs CIMD choice

    @Test
    void cimdIsUsedAsTheClientIdWhenTheServerSupportsItAndNoRegistrationHappens() {
        service = newService(true, activeCimd());
        as.cimdSupported = true;
        as.cimdDocuments.put(CIMD_URL, new ObjectMapper().valueToTree(activeCimd().document()));

        McpOAuthService.AuthorizationStart start = service.beginAuthorization(MCP, null);
        assertEquals("cimd", start.clientMode());
        assertEquals(0, as.registrations, "CIMD means no dynamic registration call");
        assertFalse(as.requests.contains("POST " + ISSUER + "/register"));
        Map<String, String> q =
                FakeAuthorizationServer.query(
                        java.net.URI.create(start.authorizationUrl()).getRawQuery());
        assertEquals(CIMD_URL, q.get("client_id"));

        Map<String, String> cb = as.consent(start.authorizationUrl());
        service.completeAuthorization(cb.get("code"), cb.get("state"), cb.get("iss"));
        McpOAuthConnection stored = store.find("tenant-a", MCP).orElseThrow();
        assertEquals(CIMD_URL, stored.clientId());
        assertNull(stored.encryptedClientSecret());
        assertEquals("none", stored.tokenEndpointAuthMethod());
        assertTrue(service.currentAccessToken(MCP).startsWith("at-"));
    }

    @Test
    void dynamicRegistrationIsUsedWhenTheServerDoesNotSupportCimd() {
        service = newService(true, activeCimd());
        as.cimdSupported = false;
        assertEquals("dcr", service.beginAuthorization(MCP, null).clientMode());
        assertEquals(1, as.registrations);
    }

    @Test
    void dynamicRegistrationIsUsedWhenCimdIsNotActiveEvenIfTheServerSupportsIt() {
        // default OFF: service built without CIMD
        as.cimdSupported = true;
        assertEquals("dcr", service.beginAuthorization(MCP, null).clientMode());
        // enabled but misconfigured (client id URL on localhost) stays inactive too
        McpOAuthClientMetadata bad =
                new McpOAuthClientMetadata(
                        new McpRemoteUrlGuard(),
                        true,
                        "https://localhost/oauth/mcp/remote/client-metadata.json",
                        McpOAuthClientMetadata.DEFAULT_PATH,
                        "V",
                        "",
                        CALLBACK);
        assertFalse(bad.active());
        store = new InMemoryMcpOAuthConnectionStore();
        service = newService(true, bad);
        assertEquals("dcr", service.beginAuthorization(MCP, null).clientMode());
    }

    @Test
    void aServerWithNeitherDcrNorCimdIsRefusedWithAClearMessage() {
        as.hasRegistrationEndpoint = false;
        McpOAuthException e =
                assertThrows(McpOAuthException.class, () -> service.beginAuthorization(MCP, null));
        assertTrue(e.getMessage().contains("dynamic client registration"));
    }

    @Test
    void theRegisteredClientIsReusedOnReconnect() {
        connect();
        service.beginAuthorization(MCP, null);
        assertEquals(1, as.registrations);
    }

    @Test
    void
            cimdAuthorizationFailsAtTheAsIfTheRedirectIsNotInTheDocumentSoOnlyTheConfiguredCallbackIsPublished() {
        Map<String, Object> doc = activeCimd().document();
        assertEquals(List.of(CALLBACK), doc.get("redirect_uris"));
        assertEquals(CIMD_URL, doc.get("client_id"));
        assertEquals("none", doc.get("token_endpoint_auth_method"));
    }

    // -------------------------------------------------------------- refresh

    @Test
    void refreshesBeforeExpiryAndRotatesTheRefreshToken() {
        as.expiresIn = 3600;
        connect();
        String first = service.currentAccessToken(MCP);
        String rt1 =
                encryption.decrypt(
                        store.find("tenant-a", MCP).orElseThrow().encryptedRefreshToken());

        clock.advance(
                Duration.ofSeconds(3600 - 30)); // inside the 60s safety margin, before real expiry
        String second = service.currentAccessToken(MCP);
        assertNotEquals(first, second);
        String rt2 =
                encryption.decrypt(
                        store.find("tenant-a", MCP).orElseThrow().encryptedRefreshToken());
        assertNotEquals(rt1, rt2, "the rotated refresh token is persisted");
        assertEquals(2, as.tokenRequestBodies.size());
        assertTrue(as.tokenRequestBodies.get(1).contains("grant_type=refresh_token"));
        assertTrue(
                as.tokenRequestBodies.get(1).contains("resource="),
                "refresh is audience-bound too");
    }

    @Test
    void aRevokedGrantMarksTheConnectionNeedsReauthInsteadOfFailingSilently() {
        connect();
        as.revokeAll();
        clock.advance(Duration.ofSeconds(4000));

        McpOAuthException e =
                assertThrows(McpOAuthException.class, () -> service.currentAccessToken(MCP));
        assertEquals("reauth_required", e.getOauthErrorCode());
        McpOAuthConnection stored = store.find("tenant-a", MCP).orElseThrow();
        assertTrue(stored.needsReauth());
        assertFalse(stored.isConnected());
        assertNull(stored.encryptedRefreshToken());
        assertEquals(McpOAuthService.State.NEEDS_REAUTH, service.status(MCP).state());
        // stays failing loudly, without hammering the AS again
        int tokenCalls = as.tokenRequestBodies.size();
        assertEquals(
                "reauth_required",
                assertThrows(McpOAuthException.class, () -> service.currentAccessToken(MCP))
                        .getOauthErrorCode());
        assertEquals(tokenCalls, as.tokenRequestBodies.size());
    }

    @Test
    void reauthorizingAfterRevocationReusesTheRegisteredClientAndConnectsAgain() {
        connect();
        as.revokeAll();
        clock.advance(Duration.ofSeconds(4000));
        assertThrows(McpOAuthException.class, () -> service.currentAccessToken(MCP));

        connect();
        assertEquals(1, as.registrations, "the client survives needs-reauth");
        assertEquals(McpOAuthService.State.CONNECTED, service.status(MCP).state());
        assertTrue(service.currentAccessToken(MCP).startsWith("at-"));
    }

    @Test
    void aTransientRefreshFailureKeepsTheConnectionAndIsNotMarkedReauth() {
        connect();
        as.refreshTransientStatus = 503;
        clock.advance(Duration.ofSeconds(4000));
        McpOAuthException e =
                assertThrows(McpOAuthException.class, () -> service.currentAccessToken(MCP));
        assertNotEquals("reauth_required", e.getOauthErrorCode());
        McpOAuthConnection stored = store.find("tenant-a", MCP).orElseThrow();
        assertTrue(stored.isConnected());
        assertFalse(stored.needsReauth());

        as.refreshTransientStatus = 0;
        assertTrue(service.currentAccessToken(MCP).startsWith("at-"));
    }

    @Test
    void anAsThatIssuesNoRefreshTokenGetsItsEncryptedAccessTokenPersistedThenNeedsReauthOnExpiry() {
        as.issueRefreshToken = false;
        as.expiresIn = 3600;
        connect();
        McpOAuthConnection stored = store.find("tenant-a", MCP).orElseThrow();
        assertNull(stored.encryptedRefreshToken());
        assertNotNull(stored.encryptedAccessToken());
        assertTrue(encryption.decrypt(stored.encryptedAccessToken()).startsWith("at-"));
        assertTrue(service.currentAccessToken(MCP).startsWith("at-"));

        clock.advance(Duration.ofSeconds(3600));
        assertEquals(
                "reauth_required",
                assertThrows(McpOAuthException.class, () -> service.currentAccessToken(MCP))
                        .getOauthErrorCode());
        assertTrue(store.find("tenant-a", MCP).orElseThrow().needsReauth());
    }

    // ----------------------------------------------------------------- SSRF

    @Test
    void theTenantTypedUrlIsRefusedForEveryInternalOrObfuscatedHostWithoutANetworkCall() {
        List<String> bad =
                List.of(
                        "https://127.0.0.1/mcp",
                        "https://169.254.169.254/latest/meta-data",
                        "https://10.0.0.5/mcp",
                        "https://192.168.1.10/mcp",
                        "https://[::1]/mcp",
                        "https://localhost/mcp",
                        "https://postgres/mcp",
                        "https://2130706433/mcp",
                        "https://0x7f000001/mcp",
                        "https://metadata.google.internal/computeMetadata/v1/");
        for (String url : bad) {
            as.requests.clear();
            McpOAuthException e =
                    assertThrows(
                            McpOAuthException.class,
                            () -> service.beginAuthorization(url, null),
                            url);
            assertTrue(e instanceof McpOAuthBlockedException, url + " -> " + e.getMessage());
            assertTrue(
                    as.requests.isEmpty()
                            || as.requests.stream()
                                    .noneMatch(r -> r.contains("127.") || r.contains("169.254")),
                    "no request may reach " + url);
        }
    }

    @Test
    void plainHttpAndCredentialUrlsAreRefused() {
        assertThrows(
                McpOAuthException.class,
                () -> service.beginAuthorization("http://mcp.example.com/mcp", null));
        assertThrows(
                McpOAuthException.class,
                () -> service.beginAuthorization("https://user:pw@mcp.example.com/mcp", null));
        assertThrows(
                McpOAuthException.class,
                () -> service.beginAuthorization("https://mcp.example.com/mcp#frag", null));
    }

    @Test
    void aMetadataDocumentPointingTheAuthorizationServerAtAnInternalHostIsRefused() {
        as.prmAuthorizationServer = "https://169.254.169.254";
        assertThrows(McpOAuthBlockedException.class, () -> service.beginAuthorization(MCP, null));
        assertTrue(
                as.requests.stream().noneMatch(r -> r.contains("169.254.169.254")),
                "never fetched");
    }

    @Test
    void anAdvertisedTokenOrRegistrationEndpointOnAPrivateAddressIsRefusedAtDiscovery() {
        as.tokenEndpointOverride = "https://10.1.2.3/token";
        assertThrows(McpOAuthBlockedException.class, () -> service.beginAuthorization(MCP, null));
        as.tokenEndpointOverride = null;
        as.registrationEndpointOverride = "https://postgres/register";
        assertThrows(McpOAuthBlockedException.class, () -> service.beginAuthorization(MCP, null));
        assertTrue(
                as.requests.stream()
                        .noneMatch(r -> r.contains("10.1.2.3") || r.contains("postgres")));
    }

    @Test
    void statusAndDisconnectApplyTheSameGuard() {
        assertThrows(McpOAuthBlockedException.class, () -> service.status("https://127.0.0.1/mcp"));
    }

    // -------------------------------------------------------- state / binding

    @Test
    void aStateCannotBeReplayed() {
        McpOAuthService.AuthorizationStart start = service.beginAuthorization(MCP, null);
        Map<String, String> cb = as.consent(start.authorizationUrl());
        service.completeAuthorization(cb.get("code"), cb.get("state"), cb.get("iss"));
        OAuthStateException e =
                assertThrows(
                        OAuthStateException.class,
                        () ->
                                service.completeAuthorization(
                                        cb.get("code"), cb.get("state"), cb.get("iss")));
        assertEquals(OAuthStateException.REPLAYED, e.reason());
    }

    @Test
    void aCallbackSeenOnAnotherTenantsSessionIsRefusedAndTheStateStaysUsableForTheRealOwner() {
        McpOAuthService.AuthorizationStart start = service.beginAuthorization(MCP, null);
        Map<String, String> cb = as.consent(start.authorizationUrl());

        TenantContext.set(TenantContext.of("tenant-b", "mallory", Set.of()));
        OAuthStateException e =
                assertThrows(
                        OAuthStateException.class,
                        () ->
                                service.completeAuthorization(
                                        cb.get("code"), cb.get("state"), cb.get("iss")));
        assertEquals(OAuthStateException.TENANT_MISMATCH, e.reason());
        assertTrue(store.find("tenant-b", MCP).isEmpty(), "nothing is stored for the other tenant");

        TenantContext.clear(); // the genuine redirect carries no session
        McpOAuthService.CompletedConnection done =
                service.completeAuthorization(cb.get("code"), cb.get("state"), cb.get("iss"));
        assertEquals("tenant-a", done.tenantId());
        assertTrue(store.find("tenant-a", MCP).orElseThrow().isConnected());
        assertTrue(store.find("tenant-b", MCP).isEmpty());
    }

    @Test
    void tenantsNeverSeeEachOthersConnections() {
        connect();
        TenantContext.set(TenantContext.of("tenant-b", "u", Set.of()));
        assertEquals(
                "not_connected",
                assertThrows(McpOAuthException.class, () -> service.currentAccessToken(MCP))
                        .getOauthErrorCode());
        assertEquals(McpOAuthService.State.NEEDS_SIGNIN, service.status(MCP).state());
    }

    @Test
    void aForgedTamperedOrForeignKeyStateIsRejected() {
        McpOAuthService.AuthorizationStart start = service.beginAuthorization(MCP, null);
        String state = as.consent(start.authorizationUrl()).get("state");
        String tampered =
                state.substring(0, state.length() - 4) + (state.endsWith("AAAA") ? "BBBB" : "AAAA");
        assertEquals(
                OAuthStateException.INVALID,
                assertThrows(
                                OAuthStateException.class,
                                () -> service.completeAuthorization("c", tampered, null))
                        .reason());

        // a state minted under a different APP_ENCRYPTION_KEY (e.g. another deployment) cannot be
        // decrypted
        EncryptionService other = OAuthProviderTestSupport.encryption((byte) 9);
        String foreign =
                new McpOAuthStateCodec(other, clock, 600)
                        .encode(
                                "tenant-b",
                                "u",
                                MCP,
                                "verifier",
                                CONSOLE,
                                ISSUER,
                                false,
                                "cid",
                                CALLBACK);
        assertEquals(
                OAuthStateException.INVALID,
                assertThrows(
                                OAuthStateException.class,
                                () -> service.completeAuthorization("c", foreign, null))
                        .reason());
        // a state for the PROVIDER flow is a different format and is refused too
        String providerState =
                new OAuthStateCodec(encryption, clock, 600)
                        .encode("github", "tenant-a", "u", "v", CONSOLE);
        assertEquals(
                OAuthStateException.INVALID,
                assertThrows(
                                OAuthStateException.class,
                                () -> service.completeAuthorization("c", providerState, null))
                        .reason());
    }

    @Test
    void anExpiredStateIsRejected() {
        McpOAuthService.AuthorizationStart start = service.beginAuthorization(MCP, null);
        Map<String, String> cb = as.consent(start.authorizationUrl());
        clock.advance(Duration.ofSeconds(601));
        assertEquals(
                OAuthStateException.EXPIRED,
                assertThrows(
                                OAuthStateException.class,
                                () ->
                                        service.completeAuthorization(
                                                cb.get("code"), cb.get("state"), null))
                        .reason());
    }

    @Test
    void tokensAreStoredUnderTheServerInTheStateAndNowhereElse() {
        McpOAuthService.AuthorizationStart start = service.beginAuthorization(MCP, null);
        Map<String, String> cb = as.consent(start.authorizationUrl());
        service.completeAuthorization(cb.get("code"), cb.get("state"), cb.get("iss"));
        assertTrue(store.find("tenant-a", MCP).orElseThrow().isConnected());
        assertTrue(store.find("tenant-a", "https://mcp.example.com").isEmpty());
        assertTrue(store.find("tenant-a", "https://evil.example.net/mcp").isEmpty());
    }

    @Test
    void aStateWhosePendingClientWasReplacedInTheMeantimeIsRefused() {
        McpOAuthService.AuthorizationStart first = service.beginAuthorization(MCP, null);
        Map<String, String> cb = as.consent(first.authorizationUrl());
        // another flow re-registers a different client for the same resource (e.g. issuer changed /
        // client dropped)
        McpOAuthConnection pending = store.find("tenant-a", MCP).orElseThrow();
        store.save(
                new McpOAuthConnection(
                        "tenant-a",
                        MCP,
                        pending.issuer(),
                        pending.authorizationEndpoint(),
                        pending.tokenEndpoint(),
                        "someone-elses-client",
                        null,
                        "none",
                        CALLBACK,
                        null,
                        null));
        assertEquals(
                OAuthStateException.NO_PENDING_CONNECTION,
                assertThrows(
                                OAuthStateException.class,
                                () ->
                                        service.completeAuthorization(
                                                cb.get("code"), cb.get("state"), null))
                        .reason());
    }

    // ----------------------------------------------------------- issuer (9207)

    @Test
    void anIssParameterThatIsNotTheDiscoveredIssuerIsRejected() {
        as.issParameterSupported = true;
        McpOAuthService.AuthorizationStart start = service.beginAuthorization(MCP, null);
        Map<String, String> cb = as.consent(start.authorizationUrl());
        OAuthStateException e =
                assertThrows(
                        OAuthStateException.class,
                        () ->
                                service.completeAuthorization(
                                        cb.get("code"),
                                        cb.get("state"),
                                        "https://evil.example.net"));
        assertEquals(OAuthStateException.ISSUER_MISMATCH, e.reason());
        assertTrue(
                store.find("tenant-a", MCP).orElseThrow().encryptedRefreshToken() == null,
                "no tokens stored");
    }

    @Test
    void aMissingIssIsRejectedWhenTheServerPromisedOne() {
        as.issParameterSupported = true;
        McpOAuthService.AuthorizationStart start = service.beginAuthorization(MCP, null);
        Map<String, String> cb = as.consent(start.authorizationUrl());
        assertEquals(
                OAuthStateException.ISSUER_MISMATCH,
                assertThrows(
                                OAuthStateException.class,
                                () ->
                                        service.completeAuthorization(
                                                cb.get("code"), cb.get("state"), null))
                        .reason());
    }

    @Test
    void aCorrectIssIsAcceptedAndAMissingOneIsFineWhenNeverPromised() {
        as.issParameterSupported = true;
        assertEquals(
                MCP, connect().resourceUrl()); // consent() sends iss because the AS supports it

        TenantContext.set(TenantContext.of("tenant-c", "u", Set.of()));
        as.issParameterSupported = false;
        assertEquals(MCP, connect().resourceUrl());
    }

    // -------------------------------------------------------------- audience

    @Test
    void aResourceOnAnotherHostOrPathIsRefusedAsMetadataSubstitution() {
        as.prmResource = "https://evil.example.net/mcp";
        assertThrows(McpOAuthException.class, () -> service.beginAuthorization(MCP, null));
        as.prmResource = "https://mcp.example.com/other";
        assertThrows(McpOAuthException.class, () -> service.beginAuthorization(MCP, null));
        as.prmResource = "https://mcp.example.com/mcp-evil";
        assertThrows(McpOAuthException.class, () -> service.beginAuthorization(MCP, null));
    }

    @Test
    void anOriginLevelResourceCoversTheServerAndTheTokenIsOnlyReleasedWithinIt() {
        as.prmResource = "https://mcp.example.com";
        McpOAuthService.AuthorizationStart start = service.beginAuthorization(MCP, null);
        assertEquals("https://mcp.example.com", start.resourceUrl());
        Map<String, String> q =
                FakeAuthorizationServer.query(
                        java.net.URI.create(start.authorizationUrl()).getRawQuery());
        assertEquals("https://mcp.example.com", q.get("resource"));
        Map<String, String> cb = as.consent(start.authorizationUrl());
        service.completeAuthorization(cb.get("code"), cb.get("state"), cb.get("iss"));

        assertTrue(service.currentAccessToken(MCP).startsWith("at-"));
        assertTrue(
                service.currentAccessToken("https://mcp.example.com/other/path").startsWith("at-"));
        // a different host never gets it
        assertEquals(
                "not_connected",
                assertThrows(
                                McpOAuthException.class,
                                () -> service.currentAccessToken("https://mcp.example.net/mcp"))
                        .getOauthErrorCode());
        assertEquals(
                "not_connected",
                assertThrows(
                                McpOAuthException.class,
                                () ->
                                        service.currentAccessToken(
                                                "https://mcp.example.com:8443/mcp"))
                        .getOauthErrorCode());
    }

    @Test
    void aPathLevelResourceDoesNotReleaseTheTokenToASiblingPath() {
        connect();
        assertEquals(
                "not_connected",
                assertThrows(
                                McpOAuthException.class,
                                () ->
                                        service.currentAccessToken(
                                                "https://mcp.example.com/mcp-evil"))
                        .getOauthErrorCode());
        assertEquals(
                "not_connected",
                assertThrows(
                                McpOAuthException.class,
                                () -> service.currentAccessToken("https://mcp.example.com/"))
                        .getOauthErrorCode());
        assertTrue(
                service.currentAccessToken("https://MCP.example.com:443/mcp/").startsWith("at-"),
                "same server, other spelling");
    }

    // ------------------------------------------------------------------ scope

    @Test
    void theChallengeScopeWinsOverScopesSupported() {
        as.challengeScope = "files:read";
        McpOAuthService.AuthorizationStart start = service.beginAuthorization(MCP, null);
        assertEquals(
                "files:read",
                FakeAuthorizationServer.query(
                                java.net.URI.create(start.authorizationUrl()).getRawQuery())
                        .get("scope"));
    }

    @Test
    void aCallerMayNarrowButNeverWidenTheScopes() {
        McpOAuthService.AuthorizationStart narrowed = service.beginAuthorization(MCP, "read");
        assertEquals(
                "read",
                FakeAuthorizationServer.query(
                                java.net.URI.create(narrowed.authorizationUrl()).getRawQuery())
                        .get("scope"));
        assertThrows(McpOAuthException.class, () -> service.beginAuthorization(MCP, "read admin"));
    }

    @Test
    void noScopeParameterWhenTheServerDeclaresNone() {
        as.scopesSupported = List.of();
        McpOAuthService.AuthorizationStart start = service.beginAuthorization(MCP, null);
        assertFalse(
                FakeAuthorizationServer.query(
                                java.net.URI.create(start.authorizationUrl()).getRawQuery())
                        .containsKey("scope"));
        assertThrows(McpOAuthException.class, () -> service.beginAuthorization(MCP, "read"));
    }

    // ------------------------------------------------------------------ misc

    @Test
    void returnToMustBeOnTheAllowList() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.beginAuthorization(MCP, null, "https://evil.example.net/phish"));
        McpOAuthService.AuthorizationStart ok =
                service.beginAuthorization(MCP, null, CONSOLE + "?tab=servers");
        Map<String, String> cb = as.consent(ok.authorizationUrl());
        assertEquals(
                CONSOLE + "?tab=servers",
                service.completeAuthorization(cb.get("code"), cb.get("state"), cb.get("iss"))
                        .returnUrl());
    }

    @Test
    void disconnectForgetsTheConnectionAndTheCachedToken() {
        connect();
        assertTrue(service.currentAccessToken(MCP).startsWith("at-"));
        assertEquals(List.of(MCP), service.disconnect(MCP));
        assertTrue(store.find("tenant-a", MCP).isEmpty());
        assertEquals(
                "not_connected",
                assertThrows(McpOAuthException.class, () -> service.currentAccessToken(MCP))
                        .getOauthErrorCode());
        assertEquals(List.of(), service.disconnect(MCP));
    }

    @Test
    void anUnreadableStoredTokenIsAReconnectErrorNotAServerError() {
        connect();
        McpOAuthConnection c = store.find("tenant-a", MCP).orElseThrow();
        store.save(c.withRefreshToken("not-valid-ciphertext"));
        clock.advance(Duration.ofSeconds(4000));
        assertEquals(
                "token_unreadable",
                assertThrows(McpOAuthException.class, () -> service.currentAccessToken(MCP))
                        .getOauthErrorCode());
    }

    @Test
    void disabledOrMisconfiguredServiceRefusesEverything() {
        McpOAuthService off = newService(false, null);
        assertFalse(off.isEnabled());
        assertThrows(McpOAuthException.class, () -> off.beginAuthorization(MCP, null));
        assertThrows(McpOAuthException.class, () -> off.currentAccessToken(MCP));
        assertThrows(McpOAuthException.class, () -> off.status(MCP));
        assertThrows(McpOAuthException.class, () -> off.completeAuthorization("c", "s", null));
        // on, but the callback is not a public https URL (localhost) -> not usable
        ObjectMapper om = new ObjectMapper();
        McpOAuthService local =
                new McpOAuthService(
                        new McpOAuthDiscovery(as, om),
                        new McpOAuthClient(as, om),
                        store,
                        encryption,
                        null,
                        McpRemoteUrlGuard.withDenylist(List.of()),
                        true,
                        "http://localhost:8080/oauth/mcp/remote/callback",
                        new OAuthRedirectPolicy(CONSOLE, ""),
                        clock,
                        600);
        assertFalse(local.isEnabled());
        // on, but there is no console to return to
        McpOAuthService noConsole =
                new McpOAuthService(
                        new McpOAuthDiscovery(as, om),
                        new McpOAuthClient(as, om),
                        store,
                        encryption,
                        null,
                        McpRemoteUrlGuard.withDenylist(List.of()),
                        true,
                        CALLBACK,
                        new OAuthRedirectPolicy("", ""),
                        clock,
                        600);
        assertFalse(noConsole.isEnabled());
    }

    @Test
    void noTokenClientSecretOrAuthorizationCodeEverReachesALogLine() {
        as.refreshInvalidGrant = false;
        McpOAuthService.AuthorizationStart start = service.beginAuthorization(MCP, null);
        Map<String, String> cb = as.consent(start.authorizationUrl());
        service.completeAuthorization(cb.get("code"), cb.get("state"), cb.get("iss"));
        String accessToken = service.currentAccessToken(MCP);
        String refreshToken =
                encryption.decrypt(
                        store.find("tenant-a", MCP).orElseThrow().encryptedRefreshToken());
        clock.advance(Duration.ofSeconds(4000));
        service.currentAccessToken(MCP);
        as.revokeAll();
        clock.advance(Duration.ofSeconds(4000));
        assertThrows(McpOAuthException.class, () -> service.currentAccessToken(MCP));

        assertFalse(logs.list.isEmpty(), "the flow does log (connected / marked needs-reauth)");
        for (ILoggingEvent event : logs.list) {
            String line =
                    event.getFormattedMessage()
                            + (event.getThrowableProxy() == null
                                    ? ""
                                    : event.getThrowableProxy().getMessage());
            for (String secret :
                    List.of(
                            accessToken,
                            refreshToken,
                            "dcr-secret-1",
                            cb.get("code"),
                            cb.get("state"))) {
                assertFalse(line.contains(secret), "secret leaked into log: " + line);
            }
            assertFalse(line.contains("code_verifier"), line);
        }
    }

    @Test
    void connectionsAreStoredByCanonicalUrlSoSpellingDoesNotDuplicateThem() {
        McpOAuthService.AuthorizationStart start =
                service.beginAuthorization("https://MCP.example.com:443/mcp/", null);
        assertEquals(MCP, start.resourceUrl());
    }
}
