package com.agenticai.core.google;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * First tests for {@link GoogleOAuthService} - the OAuth heart of the
 * module: state minting/verification, code-for-refresh-token exchange,
 * credential encryption at rest, on-demand access-token refresh, and the
 * error surface each failure mode presents to callers (the agent tool layer
 * catches these messages verbatim, so they must be actionable sentences,
 * not raw stacks).
 *
 * <p>DB-free: repository, OAuth HTTP client, and {@link EncryptionService}
 * are Mockito mocks. Encryption is stubbed as a visibly reversible wrapper
 * ("ENC(...)") so tests can assert both directions of the round trip - that
 * the refresh token is stored as ciphertext and that the ciphertext is
 * decrypted before being sent back to Google - without a real AES key.
 *
 * <p>{@code TenantSchemaResolver.currentSchemaName()} is exercised through
 * its unregistered-static fallback (no {@code TenantSchemaResolver} bean is
 * ever constructed in this module's test fork), which resolves from the
 * {@link TenantContext} thread-local - set/cleared around each test.
 */
class GoogleOAuthServiceTest {

    private static final String CLIENT_ID = "cid.apps.googleusercontent.com";
    private static final String CLIENT_SECRET = "cs-secret";
    private static final String REDIRECT_URI = "https://api.example.com/oauth/google/callback";

    private GoogleCredentialRepository repository;
    private GoogleOAuthClient client;
    private EncryptionService encryptionService;
    private GoogleOAuthService service;

    @BeforeEach
    void setUp() {
        repository = mock(GoogleCredentialRepository.class);
        client = mock(GoogleOAuthClient.class);
        encryptionService = mock(EncryptionService.class);
        service = new GoogleOAuthService(repository, client, encryptionService, CLIENT_ID, CLIENT_SECRET, REDIRECT_URI);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // ---- configuration gate -------------------------------------------------

    @Test
    void blankAnyOfTheThreeOauthPropertiesMeansNotConfigured() {
        assertTrue(service.isConfigured());
        assertFalse(new GoogleOAuthService(repository, client, encryptionService, "", CLIENT_SECRET, REDIRECT_URI).isConfigured());
        assertFalse(new GoogleOAuthService(repository, client, encryptionService, CLIENT_ID, "", REDIRECT_URI).isConfigured());
        assertFalse(new GoogleOAuthService(repository, client, encryptionService, CLIENT_ID, CLIENT_SECRET, " ").isConfigured());
    }

    @Test
    void authorizeUrlOnAnUnconfiguredDeploymentIsATypedNotConfiguredError() {
        GoogleOAuthService unconfigured =
            new GoogleOAuthService(repository, client, encryptionService, "", "", "");

        GoogleOAuthNotConfiguredException ex =
            assertThrows(GoogleOAuthNotConfiguredException.class, unconfigured::authorizeUrl);

        // The message is what an admin sees in the console - it must say what
        // is wrong ("not configured"), not stack-trace noise.
        assertTrue(ex.getMessage().contains("not configured"));
    }

    // ---- loopback (personal) mode --------------------------------------------

    private GoogleOAuthService loopback(String redirect) {
        return new GoogleOAuthService(repository, client, encryptionService, CLIENT_ID, CLIENT_SECRET, redirect,
            new com.agenticai.core.security.oauth.OAuthStateBinding(false, java.time.Clock.systemUTC()), "loopback");
    }

    @Test
    void loopbackModeNeedsALoopbackRedirectAndSaysWhenItIsNot() {
        assertTrue(loopback("http://localhost:8080/oauth/google/callback").isConfigured());
        assertTrue(loopback("http://127.0.0.1:18080/oauth/google/callback").isConfigured());
        GoogleOAuthService domain = loopback("https://api.example.com/oauth/google/callback");
        assertFalse(domain.isConfigured());
        assertTrue(domain.redirectNotLoopback());
        // Web mode (every non-personal shape) is untouched by the new switch.
        assertTrue(service.isConfigured());
        assertFalse(service.redirectNotLoopback());
    }

    @Test
    void loopbackAuthorizeSendsAnS256ChallengeAndTheExchangeSendsTheMatchingVerifier() throws Exception {
        GoogleOAuthService svc = loopback("http://localhost:8080/oauth/google/callback");
        TenantContext.set(TenantContext.of("acme", "test-user", Set.of()));
        when(encryptionService.encrypt(anyString())).thenReturn("OPAQUE-STATE");
        when(client.buildAuthorizationUrl(anyString(), anyString(), anyString(), anyString(), any(), anyString()))
            .thenReturn("https://auth.url");

        svc.authorizeUrl();

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(encryptionService).encrypt(payload.capture());
        String nonce = payload.getValue().split("\\|")[1].substring(2);
        ArgumentCaptor<String> challenge = ArgumentCaptor.forClass(String.class);
        verify(client).buildAuthorizationUrl(
            eq(CLIENT_ID), eq("http://localhost:8080/oauth/google/callback"), eq(GoogleScopes.REQUESTED),
            eq("OPAQUE-STATE"), any(), challenge.capture());

        java.lang.reflect.Method take = GoogleOAuthService.class.getDeclaredMethod("takePkceVerifier", String.class);
        take.setAccessible(true);
        String verifier = (String) take.invoke(svc, nonce);
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest), challenge.getValue());
        assertNull(take.invoke(svc, nonce), "a verifier is single use");
    }

    // ---- state encode/decode ------------------------------------------------

    @Test
    void authorizeUrlEncryptsCurrentTenantSchemaAndTimestampIntoState() {
        // "Acme Co" exercises the sanitizer on the way into the state payload:
        // lowercased, non [a-z0-9_] replaced - the schema Google round-trips
        // back must be the exact string Hibernate would resolve for this tenant.
        TenantContext.set(TenantContext.of("Acme Co", "test-user", Set.of()));
        when(encryptionService.encrypt(anyString())).thenReturn("OPAQUE-STATE");
        when(client.buildAuthorizationUrl(anyString(), anyString(), anyString(), anyString())).thenReturn("https://auth.url");

        String url = service.authorizeUrl();

        assertEquals("https://auth.url", url);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(encryptionService).encrypt(payload.capture());
        String[] parts = payload.getValue().split("\\|");
        assertEquals(3, parts.length, "state payload must be schema|n=<binding nonce>|epochSeconds");
        assertEquals("acme_co", parts[0]);
        assertTrue(parts[1].startsWith("n=") && parts[1].length() == 45, "SEC-018: a 256-bit binding nonce");
        long issuedAt = Long.parseLong(parts[2]);
        assertTrue(Math.abs(Instant.now().getEpochSecond() - issuedAt) < 5, "timestamp must be 'now'");
        // The full requested scope set and the *encrypted* state (never the
        // plaintext schema) are what reach the authorization URL.
        verify(client).buildAuthorizationUrl(CLIENT_ID, REDIRECT_URI, GoogleScopes.REQUESTED, "OPAQUE-STATE");
    }

    @Test
    void decodeStateReturnsTheSchemaForAFreshState() {
        when(encryptionService.decrypt("good-state"))
            .thenReturn("acme_co|" + Instant.now().getEpochSecond());

        assertEquals("acme_co", service.decodeState("good-state"));
    }

    @Test
    void tamperedStateFailsClosedWithARetryableMessage() {
        // GCM decrypt of a tampered value throws - decodeState must translate
        // that into the typed state exception, not leak the crypto exception.
        when(encryptionService.decrypt("tampered")).thenThrow(new IllegalStateException("tag mismatch"));

        GoogleOAuthStateException ex =
            assertThrows(GoogleOAuthStateException.class, () -> service.decodeState("tampered"));

        assertTrue(ex.getMessage().contains("try connecting again"));
    }

    @Test
    void stateWithoutSeparatorOrWithNonNumericTimestampIsMalformed() {
        when(encryptionService.decrypt("no-separator")).thenReturn("acme_co_and_no_pipe");
        assertThrows(GoogleOAuthStateException.class, () -> service.decodeState("no-separator"));

        when(encryptionService.decrypt("bad-timestamp")).thenReturn("acme_co|notanumber");
        assertThrows(GoogleOAuthStateException.class, () -> service.decodeState("bad-timestamp"));
    }

    @Test
    void stateOlderThanTenMinutesIsExpired() {
        long elevenMinutesAgo = Instant.now().getEpochSecond() - 660;
        when(encryptionService.decrypt("stale")).thenReturn("acme_co|" + elevenMinutesAgo);

        GoogleOAuthStateException ex =
            assertThrows(GoogleOAuthStateException.class, () -> service.decodeState("stale"));

        assertTrue(ex.getMessage().contains("expired"));
    }

    // ---- connect (code exchange + credential storage) -----------------------

    @Test
    void connectStoresTheRefreshTokenAsCiphertextNeverPlaintext() {
        when(client.exchangeCode(CLIENT_ID, CLIENT_SECRET, REDIRECT_URI, "auth-code"))
            .thenReturn(new GoogleOAuthClient.TokenResponse("at-1", "rt-secret", 3599, "scopeA scopeB"));
        when(client.fetchEmail("at-1")).thenReturn("user@example.com");
        when(encryptionService.encrypt("rt-secret")).thenReturn("ENC(rt-secret)");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());

        String email = service.connect("auth-code");

        assertEquals("user@example.com", email);
        ArgumentCaptor<GoogleCredential> saved = ArgumentCaptor.forClass(GoogleCredential.class);
        verify(repository).save(saved.capture());
        GoogleCredential credential = saved.getValue();
        assertEquals("user@example.com", credential.getConnectedEmail());
        assertEquals("ENC(rt-secret)", credential.getEncryptedRefreshToken(),
            "the stored value must be the EncryptionService output, not the raw token");
        assertEquals("scopeA scopeB", credential.getGrantedScopes());
        assertEquals(GoogleCredentialStatus.ACTIVE, credential.getStatus());
    }

    @Test
    void connectReusesTheExistingRowOnReconnectAndReactivatesIt() {
        GoogleCredential existing = new GoogleCredential("old@example.com", "ENC(old-rt)", "oldScope");
        existing.revoke(); // a previously disconnected tenant reconnecting
        when(client.exchangeCode(any(), any(), any(), eq("auth-code")))
            .thenReturn(new GoogleOAuthClient.TokenResponse("at-1", "rt-new", 3599, "newScope"));
        when(client.fetchEmail("at-1")).thenReturn("new@example.com");
        when(encryptionService.encrypt("rt-new")).thenReturn("ENC(rt-new)");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(existing));

        service.connect("auth-code");

        ArgumentCaptor<GoogleCredential> saved = ArgumentCaptor.forClass(GoogleCredential.class);
        verify(repository).save(saved.capture());
        assertSame(existing, saved.getValue(), "reconnect must update the one row, not insert a second");
        assertEquals("new@example.com", existing.getConnectedEmail());
        assertEquals("ENC(rt-new)", existing.getEncryptedRefreshToken());
        assertEquals(GoogleCredentialStatus.ACTIVE, existing.getStatus());
    }

    @Test
    void connectWithoutARefreshTokenTellsTheUserHowToFixIt() {
        // Google omits refresh_token on a repeat consent - nothing usable to
        // store, and the message must carry the concrete recovery step.
        when(client.exchangeCode(any(), any(), any(), eq("auth-code")))
            .thenReturn(new GoogleOAuthClient.TokenResponse("at-1", null, 3599, "scopeA"));

        GoogleApiException ex = assertThrows(GoogleApiException.class, () -> service.connect("auth-code"));

        assertTrue(ex.getMessage().contains("did not return a refresh token"));
        assertTrue(ex.getMessage().contains("myaccount.google.com/permissions"),
            "the recovery instruction (revoke + retry) must reach the user");
        verify(repository, never()).save(any());
    }

    // ---- currentAccessToken (the path every tool call takes) ----------------

    @Test
    void currentAccessTokenDecryptsStoredTokenAndExchangesItForAFreshAccessToken() {
        GoogleCredential credential = new GoogleCredential("user@example.com", "ENC(rt-secret)", "scopeA");
        when(repository.findFirstByStatusOrderByCreatedAtAsc(GoogleCredentialStatus.ACTIVE))
            .thenReturn(Optional.of(credential));
        when(encryptionService.decrypt("ENC(rt-secret)")).thenReturn("rt-secret");
        when(client.refreshAccessToken(CLIENT_ID, CLIENT_SECRET, "rt-secret"))
            .thenReturn(new GoogleOAuthClient.TokenResponse("fresh-at", null, 3599, "scopeA"));

        assertEquals("fresh-at", service.currentAccessToken());
    }

    @Test
    void currentAccessTokenWithoutAnActiveConnectionIsACleanActionableError() {
        when(repository.findFirstByStatusOrderByCreatedAtAsc(GoogleCredentialStatus.ACTIVE))
            .thenReturn(Optional.empty());

        GoogleNotConnectedException ex =
            assertThrows(GoogleNotConnectedException.class, () -> service.currentAccessToken());

        // This message is what the agent tool layer relays to the model/user -
        // it must name the fix (connect on the Integrations page), not be a
        // bare NoSuchElementException.
        assertTrue(ex.getMessage().contains("No Google account connected"));
        assertTrue(ex.getMessage().contains("Integrations page"));
        verify(client, never()).refreshAccessToken(any(), any(), any());
    }

    @Test
    void revokedCredentialIsInvisibleToStatusAndActiveCredential() {
        // status()/activeCredential() query by ACTIVE only - a revoked row
        // must read as "not connected", which these mocks make explicit.
        when(repository.findFirstByStatusOrderByCreatedAtAsc(GoogleCredentialStatus.ACTIVE))
            .thenReturn(Optional.empty());

        assertTrue(service.status().isEmpty());
        assertTrue(service.activeCredential().isEmpty());
    }

    // ---- disconnect ---------------------------------------------------------

    @Test
    void disconnectRevokesRemotelyAndMarksTheRowRevoked() {
        GoogleCredential credential = new GoogleCredential("user@example.com", "ENC(rt-secret)", "scopeA");
        when(repository.findFirstByStatusOrderByCreatedAtAsc(GoogleCredentialStatus.ACTIVE))
            .thenReturn(Optional.of(credential));
        when(encryptionService.decrypt("ENC(rt-secret)")).thenReturn("rt-secret");

        service.disconnect();

        verify(client).revoke("rt-secret"); // decrypted before hitting Google's revoke endpoint
        assertEquals(GoogleCredentialStatus.REVOKED, credential.getStatus());
        verify(repository).save(credential);
    }

    @Test
    void disconnectStillMarksRevokedLocallyWhenGoogleRevokeIsUnreachable() {
        // Best-effort posture: a Google outage must not leave the tenant stuck
        // "connected" with no way to disconnect.
        GoogleCredential credential = new GoogleCredential("user@example.com", "ENC(rt-secret)", "scopeA");
        when(repository.findFirstByStatusOrderByCreatedAtAsc(GoogleCredentialStatus.ACTIVE))
            .thenReturn(Optional.of(credential));
        when(encryptionService.decrypt("ENC(rt-secret)")).thenReturn("rt-secret");
        org.mockito.Mockito.doThrow(new GoogleApiException("Could not reach Google's revoke endpoint"))
            .when(client).revoke("rt-secret");

        service.disconnect();

        assertEquals(GoogleCredentialStatus.REVOKED, credential.getStatus());
        verify(repository).save(credential);
    }

    @Test
    void disconnectWithNoActiveConnectionIsANoOp() {
        when(repository.findFirstByStatusOrderByCreatedAtAsc(GoogleCredentialStatus.ACTIVE))
            .thenReturn(Optional.empty());

        service.disconnect();

        verify(repository, never()).save(any());
        verify(client, never()).revoke(any());
    }

    // ---- scope-set + incremental-consent state ------------------------------

    @Test
    void requestedNeverBundlesYoutubeReadonlyWithDriveFile() {
        // Google's scope-combination rule rejects drive.file+youtube.readonly
        // in ONE authorization request (Error 400 invalid_request) - regression
        // guard against re-bundling the scope that broke every connect.
        assertFalse(GoogleScopes.REQUESTED.contains(GoogleScopes.YOUTUBE_READONLY));
        assertTrue(GoogleScopes.REQUESTED.contains(GoogleScopes.DRIVE_FILE));
    }

    @Test
    void decodeConnectStateReadsThePurposeAndSubjectMarkers() {
        when(encryptionService.decrypt("purpose-state"))
            .thenReturn("acme_co|u=member-1|p=" + GoogleOAuthService.PURPOSE_YOUTUBE_READONLY
                + "|" + Instant.now().getEpochSecond());

        GoogleOAuthService.DecodedState decoded = service.decodeConnectState("purpose-state");

        assertEquals("acme_co", decoded.schema());
        assertEquals("member-1", decoded.subjectKey());
        assertEquals(GoogleOAuthService.PURPOSE_YOUTUBE_READONLY, decoded.purpose());
    }

    @Test
    void decodeConnectStateReadsAPurposeOnlyStateAsTenantWideIncremental() {
        when(encryptionService.decrypt("upload-state"))
            .thenReturn("acme_co|p=" + GoogleOAuthService.PURPOSE_YOUTUBE_UPLOAD
                + "|" + Instant.now().getEpochSecond());

        GoogleOAuthService.DecodedState decoded = service.decodeConnectState("upload-state");

        assertEquals("acme_co", decoded.schema());
        assertNull(decoded.subjectKey());
        assertEquals(GoogleOAuthService.PURPOSE_YOUTUBE_UPLOAD, decoded.purpose());
    }

    @Test
    void decodeConnectStateLeavesPurposeNullOnABaseConnect() {
        when(encryptionService.decrypt("base-state"))
            .thenReturn("acme_co|u=member-1|" + Instant.now().getEpochSecond());

        GoogleOAuthService.DecodedState decoded = service.decodeConnectState("base-state");

        assertEquals("acme_co", decoded.schema());
        assertEquals("member-1", decoded.subjectKey());
        assertNull(decoded.purpose());
    }

    @Test
    void chainedYoutubeReadonlyUrlCarriesPurposeSubjectAndLoginHint() {
        when(encryptionService.encrypt(anyString())).thenReturn("OPAQUE-STATE");
        when(client.buildAuthorizationUrl(anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn("https://auth.url/yt");

        String url = service.chainedYoutubeReadonlyAuthorizeUrl("acme_co", "member-1", "user@example.com");

        assertEquals("https://auth.url/yt", url);
        verify(client).buildAuthorizationUrl(
            CLIENT_ID, REDIRECT_URI, GoogleScopes.YOUTUBE_READONLY, "OPAQUE-STATE", "user@example.com");
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(encryptionService).encrypt(payload.capture());
        String[] parts = payload.getValue().split("\\|");
        assertEquals("acme_co", parts[0]);
        assertEquals("u=member-1", parts[1]);
        assertEquals("p=" + GoogleOAuthService.PURPOSE_YOUTUBE_READONLY, parts[2]);
    }

    // ---- incremental connect guards ------------------------------------------

    @Test
    void incrementalConnectRequiresAnExistingCredentialRow() {
        // The connection was disconnected between consent mint and callback -
        // the incremental grant must not create a fresh row holding ONLY the
        // incremental scope.
        when(client.exchangeCode(any(), any(), any(), eq("auth-code")))
            .thenReturn(new GoogleOAuthClient.TokenResponse("at-1", "rt-new", 3599, "scopeA scopeB"));
        when(client.fetchEmail("at-1")).thenReturn("user@example.com");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());

        assertThrows(GoogleNotConnectedException.class,
            () -> service.connect("auth-code", null, GoogleOAuthService.PURPOSE_YOUTUBE_READONLY));
        verify(repository, never()).save(any());
    }

    @Test
    void incrementalConnectRefusesADifferentGoogleAccount() {
        // login_hint is a hint, not enforcement - if the user switched
        // accounts at the consent screen anyway, saving would repoint the
        // credential at an account whose grant doesn't cover the base scopes.
        GoogleCredential existing = new GoogleCredential("owner@example.com", "ENC(old-rt)", "scopeA");
        when(client.exchangeCode(any(), any(), any(), eq("auth-code")))
            .thenReturn(new GoogleOAuthClient.TokenResponse("at-1", "rt-new", 3599, "scopeB"));
        when(client.fetchEmail("at-1")).thenReturn("someone-else@example.com");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(existing));

        GoogleApiException ex = assertThrows(GoogleApiException.class,
            () -> service.connect("auth-code", null, GoogleOAuthService.PURPOSE_YOUTUBE_READONLY));

        assertTrue(ex.getMessage().contains("different Google account"));
        verify(repository, never()).save(any());
        assertEquals("owner@example.com", existing.getConnectedEmail());
    }

    @Test
    void incrementalConnectStoresTheUnionScopeOnTheSameRow() {
        GoogleCredential existing = new GoogleCredential("owner@example.com", "ENC(old-rt)", "scopeA");
        when(client.exchangeCode(any(), any(), any(), eq("auth-code")))
            .thenReturn(new GoogleOAuthClient.TokenResponse("at-1", "rt-new", 3599, "scopeA scopeB"));
        when(client.fetchEmail("at-1")).thenReturn("Owner@Example.com"); // case-insensitive match
        when(encryptionService.encrypt("rt-new")).thenReturn("ENC(rt-new)");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(existing));

        GoogleCredential saved = service.connect("auth-code", null, GoogleOAuthService.PURPOSE_YOUTUBE_READONLY);

        assertSame(existing, saved, "incremental consent must update the existing row in place");
        assertEquals("scopeA scopeB", saved.getGrantedScopes());
        assertEquals("ENC(rt-new)", saved.getEncryptedRefreshToken());
    }

    // ---- SEC-018: state bound to the connect session that minted it ----------

    private final com.agenticai.core.security.oauth.OAuthStateBinding binding =
        new com.agenticai.core.security.oauth.OAuthStateBinding(false, java.time.Clock.systemUTC());

    /** Mint a real authorize URL as {@code user} in {@code tenant}; returns [decryptedPayload, cookieValue]. */
    private String[] mintAs(GoogleOAuthService svc, String tenant, String user, boolean personal) {
        TenantContext.set(TenantContext.of(tenant, user, Set.of()));
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        when(encryptionService.encrypt(payload.capture())).thenReturn("OPAQUE");
        when(client.buildAuthorizationUrl(anyString(), anyString(), anyString(), anyString())).thenReturn("https://auth.url");
        GoogleOAuthService.AuthorizeRedirect r = personal ? svc.personalAuthorizeRedirect() : svc.authorizeRedirect();
        TenantContext.clear();
        return new String[] {payload.getValue(), r.bindingCookie().getValue()};
    }

    private GoogleOAuthService boundService() {
        return new GoogleOAuthService(repository, client, encryptionService, CLIENT_ID, CLIENT_SECRET, REDIRECT_URI, binding);
    }

    @Test
    void sec018_validBoundStateWithItsCookieIsAccepted() {
        GoogleOAuthService svc = boundService();
        String[] minted = mintAs(svc, "tenant_a", "admin-a", false);
        when(encryptionService.decrypt("cb-state")).thenReturn(minted[0]);

        GoogleOAuthService.DecodedState decoded = svc.decodeConnectState("cb-state");
        svc.verifyBinding(decoded, minted[1]);

        assertEquals("tenant_a", decoded.schema());
    }

    @Test
    void sec018_replayedStateIsRejected() {
        GoogleOAuthService svc = boundService();
        String[] minted = mintAs(svc, "tenant_a", "admin-a", false);
        when(encryptionService.decrypt("cb-state")).thenReturn(minted[0]);
        GoogleOAuthService.DecodedState decoded = svc.decodeConnectState("cb-state");
        svc.verifyBinding(decoded, minted[1]);

        assertThrows(GoogleOAuthStateException.class, () -> svc.verifyBinding(svc.decodeConnectState("cb-state"), minted[1]));
    }

    @Test
    void sec018_stateWithAnotherBrowsersCookieIsRejected() {
        GoogleOAuthService svc = boundService();
        String[] attacker = mintAs(svc, "tenant_attacker", "attacker", false);
        String[] victim = mintAs(svc, "tenant_victim", "victim", false);
        when(encryptionService.decrypt("attacker-state")).thenReturn(attacker[0]);

        assertThrows(GoogleOAuthStateException.class,
            () -> svc.verifyBinding(svc.decodeConnectState("attacker-state"), victim[1]));
    }

    @Test
    void sec018_stateMintedBeforeTheFixCarriesNoNonceAndIsRejected() {
        GoogleOAuthService svc = boundService();
        when(encryptionService.decrypt("legacy")).thenReturn("acme_co|" + Instant.now().getEpochSecond());

        assertThrows(GoogleOAuthStateException.class, () -> svc.verifyBinding(svc.decodeConnectState("legacy"), null));
    }

    @Test
    void sec018_stateOfTenantACannotBeRedirectedIntoAnotherTenantOrUser() {
        GoogleOAuthService svc = boundService();
        String[] minted = mintAs(svc, "tenant_a", "member-a", true);
        String nonceSegment = minted[0].split("\\|")[2];
        // Even a (hypothetically) re-encrypted state that swaps tenant/subject but reuses A's nonce is refused.
        when(encryptionService.decrypt("swapped-tenant"))
            .thenReturn("tenant_b|u=member-a|" + nonceSegment + "|" + Instant.now().getEpochSecond());

        assertThrows(GoogleOAuthStateException.class,
            () -> svc.verifyBinding(svc.decodeConnectState("swapped-tenant"), minted[1]));
        verify(repository, never()).save(any());
    }
}
