package com.agenticai.core.sarvam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.ProviderStatus;
import com.agenticai.core.providers.dto.ProviderResponse;
import com.agenticai.core.sarvam.dto.SarvamCredentialResponse;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link SarvamProviderCredentialSource}'s kernel-facing adapter: the provider identity, the
 * straight-through key resolution and the credential-status merge (ACTIVE stays ACTIVE; the
 * two-state Sarvam table maps INACTIVE to {@link ProviderStatus#INVALID} so the provider dropdown's
 * status check behaves the same as every other provider).
 */
class SarvamProviderCredentialSourceTest {

    private final SarvamCredentialService credentialService = mock(SarvamCredentialService.class);
    private final SarvamProviderCredentialSource source =
            new SarvamProviderCredentialSource(credentialService);

    @Test
    void reportsTheSarvamProviderIdentity() {
        assertThat(source.providerName()).isEqualTo(ProviderName.SARVAM);
    }

    @Test
    void resolveActiveApiKeyDelegatesVerbatimIncludingTheException() {
        when(credentialService.resolveActiveApiKey()).thenReturn("plain-key");
        assertThat(source.resolveActiveApiKey()).isEqualTo("plain-key");

        when(credentialService.resolveActiveApiKey()).thenThrow(new SarvamNotConfiguredException());
        assertThatThrownBy(source::resolveActiveApiKey)
                .isInstanceOf(SarvamNotConfiguredException.class);
    }

    @Test
    void emptyCredentialMapsToEmpty() {
        when(credentialService.current()).thenReturn(Optional.empty());

        assertThat(source.currentCredential()).isEmpty();
    }

    @Test
    void activeCredentialsMapToProviderActive() {
        UUID id = UUID.randomUUID();
        Instant created = Instant.parse("2026-08-01T00:00:00Z");
        Instant updated = Instant.parse("2026-09-01T00:00:00Z");
        when(credentialService.current())
                .thenReturn(
                        Optional.of(
                                new SarvamCredentialResponse(
                                        id, SarvamCredentialStatus.ACTIVE, created, updated)));

        Optional<ProviderResponse> response = source.currentCredential();

        assertThat(response).isPresent();
        assertThat(response.get().id()).isEqualTo(id);
        assertThat(response.get().providerName()).isEqualTo(ProviderName.SARVAM);
        assertThat(response.get().status()).isEqualTo(ProviderStatus.ACTIVE);
        assertThat(response.get().createdAt()).isEqualTo(created);
        assertThat(response.get().updatedAt()).isEqualTo(updated);
    }

    @Test
    void inactiveCredentialsMapToInvalidSoTheDropdownHidesThem() {
        SarvamCredentialResponse inactive =
                new SarvamCredentialResponse(
                        UUID.randomUUID(),
                        SarvamCredentialStatus.INACTIVE,
                        Instant.now(),
                        Instant.now());

        ProviderResponse mapped = SarvamProviderCredentialSource.toProviderResponse(inactive);

        assertThat(mapped.status()).isEqualTo(ProviderStatus.INVALID);
        assertThat(mapped.providerName()).isEqualTo(ProviderName.SARVAM);
    }
}
