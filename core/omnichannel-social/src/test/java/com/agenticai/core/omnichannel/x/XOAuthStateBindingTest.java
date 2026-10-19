package com.agenticai.core.omnichannel.x;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.oauth.OAuthStateBinding;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * SEC-018: an X connect state is single-use and bound to the tenant (and browser) that started it.
 */
class XOAuthStateBindingTest {

    private final XCredentialRepository repository = mock(XCredentialRepository.class);
    private final XOAuthClient client = mock(XOAuthClient.class);
    private final EncryptionService encryption = mock(EncryptionService.class);
    private final OAuthStateBinding binding = new OAuthStateBinding(false, Clock.systemUTC());
    private final XOAuthService service =
            new XOAuthService(
                    repository,
                    client,
                    mock(XApiClient.class),
                    encryption,
                    "https://api.example.com/oauth/x/callback",
                    binding);

    @BeforeEach
    void setUp() {
        when(repository.findFirstByOrderByCreatedAtAsc())
                .thenReturn(Optional.of(new XCredential("cid", "ENC")));
        when(client.newPkcePair())
                .thenReturn(new XOAuthClient.PkcePair("verifier123", "challenge"));
        when(client.buildAuthorizationUrl(
                        anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("https://x.com/i/oauth2/authorize");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    /**
     * @return [plaintext state payload, binding cookie value]
     */
    private String[] mint(String tenant) {
        TenantContext.set(TenantContext.of(tenant, "admin-" + tenant, Set.of()));
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        when(encryption.encrypt(payload.capture())).thenReturn("OPAQUE");
        XOAuthService.AuthorizeRedirect r = service.authorizeRedirect();
        TenantContext.clear();
        return new String[] {payload.getValue(), r.bindingCookie().getValue()};
    }

    @Test
    void validBoundStateIsAcceptedOnce_andReplayIsRejected() {
        String[] minted = mint("tenant_a");
        when(encryption.decrypt("s")).thenReturn(minted[0]);

        XOAuthService.DecodedState decoded = service.decodeState("s");
        service.verifyBinding(decoded, minted[1]);
        assertThat(decoded.tenantSchema()).isEqualTo("tenant_a");
        assertThat(decoded.codeVerifier()).isEqualTo("verifier123");

        assertThatThrownBy(() -> service.verifyBinding(service.decodeState("s"), minted[1]))
                .isInstanceOf(XOAuthStateException.class);
    }

    @Test
    void stateCompletedInABrowserHoldingADifferentBindingCookieIsRejected() {
        String[] attacker = mint("tenant_attacker");
        String[] victim = mint("tenant_victim");
        when(encryption.decrypt("s")).thenReturn(attacker[0]);

        assertThatThrownBy(() -> service.verifyBinding(service.decodeState("s"), victim[1]))
                .isInstanceOf(XOAuthStateException.class);
    }

    @Test
    void preFixThreeFieldStateIsRefused() {
        when(encryption.decrypt("legacy"))
                .thenReturn("tenant_a|verifier|" + Instant.now().getEpochSecond());

        assertThatThrownBy(() -> service.decodeState("legacy"))
                .isInstanceOf(XOAuthStateException.class);
    }

    @Test
    void nonceBoundToTenantACannotCompleteIntoTenantB() {
        String[] minted = mint("tenant_a");
        String nonce = minted[0].split("\\|")[2];
        when(encryption.decrypt("swapped"))
                .thenReturn("tenant_b|verifier123|" + nonce + "|" + Instant.now().getEpochSecond());

        assertThatThrownBy(() -> service.verifyBinding(service.decodeState("swapped"), minted[1]))
                .isInstanceOf(XOAuthStateException.class);
    }

    @Test
    void missingCookieIsRejectedWhenBrowserBindingIsRequired() {
        XOAuthService strict =
                new XOAuthService(
                        repository,
                        client,
                        mock(XApiClient.class),
                        encryption,
                        "https://api.example.com/oauth/x/callback",
                        new OAuthStateBinding(true, Clock.systemUTC()));
        TenantContext.set(TenantContext.of("tenant_a", "admin", Set.of()));
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        when(encryption.encrypt(payload.capture())).thenReturn("OPAQUE");
        strict.authorizeRedirect();
        TenantContext.clear();
        when(encryption.decrypt("s")).thenReturn(payload.getValue());

        assertThatThrownBy(() -> strict.verifyBinding(strict.decodeState("s"), null))
                .isInstanceOf(XOAuthStateException.class);
    }
}
