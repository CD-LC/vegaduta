package com.agenticai.core.mcp.atlassian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.mcp.McpRegistryService;
import com.agenticai.core.security.TenantContext;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Covers the two riskiest pieces of {@link AtlassianOAuthService}: the
 * encrypted {@code state} value's security properties (round-trips what it
 * carries, rejects tamper/expiry - the SAME CSRF/replay contract {@code
 * XOAuthService}/{@code GoogleOAuthService} already rely on, just with no
 * existing test file for either to mirror), and the token-cache's
 * revoke-only-on-{@code invalid_grant} logic (a real correctness risk a plan
 * review specifically flagged - a naive "revoke on any refresh failure"
 * would force every tenant to reconnect over a transient Atlassian outage).
 */
class AtlassianOAuthServiceTest {

    private static final String TEST_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String REDIRECT_URI = "https://api.staging.example.com/oauth/atlassian/callback";

    private AtlassianCredentialRepository repository;
    private AtlassianOAuthClient client;
    private AtlassianPlatformClientService platformClientService;
    private McpRegistryService mcpRegistryService;
    private EncryptionService encryptionService;
    private AtlassianOAuthService service;
    private com.agenticai.core.security.oauth.OAuthStateBinding stateBinding;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        repository = mock(AtlassianCredentialRepository.class);
        client = mock(AtlassianOAuthClient.class);
        platformClientService = mock(AtlassianPlatformClientService.class);
        mcpRegistryService = mock(McpRegistryService.class);
        encryptionService = new EncryptionService(TEST_KEY, "");
        // init() is package-private (@PostConstruct, fired by Spring in the
        // real app) - this test lives in a different package, same
        // reflection-for-testing-internals idiom DynamicMcpConnectionManagerTest
        // already uses for its own private-method access.
        Method init = EncryptionService.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(encryptionService);
        stateBinding = new com.agenticai.core.security.oauth.OAuthStateBinding(false);
        service = new AtlassianOAuthService(repository, client, platformClientService, mcpRegistryService, encryptionService, REDIRECT_URI, stateBinding);

        TenantContext.set(TenantContext.of("tenant-abc", "test-subject", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void decodeStateRoundTripsTheTenantAndVerifierAuthorizeUrlEncoded() {
        when(platformClientService.ensureRegistered(REDIRECT_URI))
            .thenReturn(new AtlassianPlatformClientService.ResolvedClient("client-id", "secret", "client_secret_basic"));
        when(client.newPkcePair()).thenReturn(new AtlassianOAuthClient.PkcePair("verifier-abc", "challenge-abc"));
        ArgumentCaptor<String> stateCaptor = ArgumentCaptor.forClass(String.class);
        when(client.buildAuthorizationUrl(eq("client-id"), eq(REDIRECT_URI), stateCaptor.capture(), eq("challenge-abc")))
            .thenReturn("https://mcp.atlassian.com/v1/authorize?client_id=client-id");

        service.authorizeUrl();

        AtlassianOAuthService.DecodedState decoded = service.decodeState(stateCaptor.getValue());
        assertEquals("tenant-abc", decoded.tenantId());
        assertEquals("verifier-abc", decoded.codeVerifier());
    }

    @Test
    void decodeStateRejectsATamperedValue() {
        String tampered = encryptionService.encrypt("tenant-abc|verifier|nonce|" + Instant.now().getEpochSecond()) + "x";
        assertThrows(AtlassianOAuthStateException.class, () -> service.decodeState(tampered));
    }

    @Test
    void decodeStateRejectsAnExpiredValue() {
        long elevenMinutesAgo = Instant.now().getEpochSecond() - 660;
        String expired = encryptionService.encrypt("tenant-abc|verifier|nonce|" + elevenMinutesAgo);
        assertThrows(AtlassianOAuthStateException.class, () -> service.decodeState(expired));
    }

    @Test
    void decodeStateRejectsAMalformedDecryptedPayload() {
        String malformed = encryptionService.encrypt("only-one-part");
        assertThrows(AtlassianOAuthStateException.class, () -> service.decodeState(malformed));
    }

    @Test
    void currentAccessTokenThrowsNotConnectedWhenNoCredentialExists() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        assertThrows(AtlassianNotConnectedException.class, () -> service.currentAccessToken());
    }

    @Test
    void currentAccessTokenCachesAndDoesNotRefreshTwiceInARow() {
        AtlassianCredential credential = new AtlassianCredential(encryptionService.encrypt("refresh-token-1"));
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(credential));
        when(platformClientService.ensureRegistered(REDIRECT_URI))
            .thenReturn(new AtlassianPlatformClientService.ResolvedClient("client-id", "secret", "client_secret_basic"));
        when(client.refreshAccessToken(eq("client-id"), eq("secret"), eq("client_secret_basic"), eq("refresh-token-1")))
            .thenReturn(new AtlassianOAuthClient.TokenResponse("access-token-1", null, 3600, ""));

        String first = service.currentAccessToken();
        String second = service.currentAccessToken();

        assertEquals("access-token-1", first);
        assertEquals("access-token-1", second);
        verify(client, times(1)).refreshAccessToken(eq("client-id"), eq("secret"), eq("client_secret_basic"), eq("refresh-token-1"));
    }

    @Test
    void currentAccessTokenRevokesTheCredentialOnInvalidGrant() {
        AtlassianCredential credential = new AtlassianCredential(encryptionService.encrypt("dead-refresh-token"));
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(credential));
        when(platformClientService.ensureRegistered(REDIRECT_URI))
            .thenReturn(new AtlassianPlatformClientService.ResolvedClient("client-id", "secret", "client_secret_basic"));
        when(client.refreshAccessToken(eq("client-id"), eq("secret"), eq("client_secret_basic"), eq("dead-refresh-token")))
            .thenThrow(new AtlassianApiException("rejected (400): {\"error\":\"invalid_grant\"}", null, "invalid_grant"));

        assertThrows(AtlassianNotConnectedException.class, () -> service.currentAccessToken());

        assertEquals(AtlassianCredentialStatus.REVOKED, credential.getStatus());
        verify(repository).save(credential);
    }

    @Test
    void currentAccessTokenPropagatesATransientFailureWithoutRevoking() {
        AtlassianCredential credential = new AtlassianCredential(encryptionService.encrypt("refresh-token-1"));
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(credential));
        when(platformClientService.ensureRegistered(REDIRECT_URI))
            .thenReturn(new AtlassianPlatformClientService.ResolvedClient("client-id", "secret", "client_secret_basic"));
        // No oauthErrorCode - a network-level/5xx failure, not a confirmed
        // invalid_grant. Must propagate as-is, never silently become
        // AtlassianNotConnectedException, and must never revoke - see
        // currentAccessToken's javadoc.
        AtlassianApiException transientFailure = new AtlassianApiException("Could not reach Atlassian's OAuth token endpoint: timeout");
        when(client.refreshAccessToken(eq("client-id"), eq("secret"), eq("client_secret_basic"), eq("refresh-token-1")))
            .thenThrow(transientFailure);

        AtlassianApiException thrown = assertThrows(AtlassianApiException.class, () -> service.currentAccessToken());

        assertEquals(transientFailure, thrown);
        assertEquals(AtlassianCredentialStatus.ACTIVE, credential.getStatus());
        verify(repository, never()).save(credential);
    }

    // ---- SEC-018: state bound to the connect session that minted it ----

    private String mintState() {
        when(platformClientService.ensureRegistered(REDIRECT_URI))
            .thenReturn(new AtlassianPlatformClientService.ResolvedClient("client-id", "secret", "client_secret_basic"));
        when(client.newPkcePair()).thenReturn(new AtlassianOAuthClient.PkcePair("verifier-abc", "challenge-abc"));
        ArgumentCaptor<String> stateCaptor = ArgumentCaptor.forClass(String.class);
        when(client.buildAuthorizationUrl(eq("client-id"), eq(REDIRECT_URI), stateCaptor.capture(), eq("challenge-abc")))
            .thenReturn("https://mcp.atlassian.com/v1/authorize?client_id=client-id");
        AtlassianOAuthService.AuthorizeRedirect redirect = service.authorizeRedirect();
        org.junit.jupiter.api.Assertions.assertNotNull(redirect.bindingCookie());
        return stateCaptor.getValue();
    }

    @Test
    void boundStateVerifiesOnceAndIsRefusedOnReplay() {
        AtlassianOAuthService.DecodedState decoded = service.decodeState(mintState());
        service.verifyBinding(decoded, null);
        assertThrows(AtlassianOAuthStateException.class, () -> service.verifyBinding(decoded, null));
    }

    @Test
    void preBindingThreeFieldStateIsRefused() {
        String legacy = encryptionService.encrypt("tenant-abc|verifier|" + Instant.now().getEpochSecond());
        assertThrows(AtlassianOAuthStateException.class, () -> service.decodeState(legacy));
    }

    @Test
    void stateWithAForeignNonceIsRefused() {
        String forged = encryptionService.encrypt("tenant-abc|verifier|not-issued-here|" + Instant.now().getEpochSecond());
        AtlassianOAuthService.DecodedState decoded = service.decodeState(forged);
        assertThrows(AtlassianOAuthStateException.class, () -> service.verifyBinding(decoded, null));
    }

    @Test
    void mismatchedBrowserCookieIsRefused() {
        AtlassianOAuthService.DecodedState decoded = service.decodeState(mintState());
        assertThrows(AtlassianOAuthStateException.class, () -> service.verifyBinding(decoded, "some-other-browser"));
    }
}
