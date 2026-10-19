package com.agenticai.core.sarvam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.ProviderStatus;
import com.agenticai.core.providers.dto.ProviderResponse;
import com.agenticai.core.providers.modality.TtsModelProvider;
import com.agenticai.core.providers.modality.VoiceModelProvider;
import com.agenticai.core.sarvam.dto.SarvamCredentialResponse;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ADR-0011 phase 2: the Sarvam adapters moved from core/providers into this
 * module. Pins their provider ids (the keys stored in agent rows and used by
 * the kernel factories) and the credential-source behaviour that replaced the
 * hard-coded SARVAM branch in ProviderCredentialResolver / ProviderService.
 */
class SarvamProviderPluginsTest {

    @Test
    void modalityAdaptersKeepTheirProviderIds() {
        SarvamCredentialAvailabilityChecker checker = mock(SarvamCredentialAvailabilityChecker.class);

        assertThat(new SarvamTextToSpeechProvider(mock(SarvamTextToSpeechClient.class), checker).getProvider())
            .isEqualTo(TtsModelProvider.SARVAM);
        assertThat(new SarvamSpeechToTextProvider(mock(SarvamSpeechToTextClient.class), checker).getProvider())
            .isEqualTo(VoiceModelProvider.SARVAM);
    }

    @Test
    void credentialSourceOwnsSarvamAndDelegatesKeyResolutionUnchanged() {
        SarvamCredentialService service = mock(SarvamCredentialService.class);
        when(service.resolveActiveApiKey()).thenReturn("sarvam-plaintext-key");
        SarvamProviderCredentialSource source = new SarvamProviderCredentialSource(service);

        assertThat(source.providerName()).isEqualTo(ProviderName.SARVAM);
        assertThat(source.resolveActiveApiKey()).isEqualTo("sarvam-plaintext-key");
    }

    @Test
    void notConfiguredExceptionPropagatesUnchanged() {
        SarvamCredentialService service = mock(SarvamCredentialService.class);
        when(service.resolveActiveApiKey()).thenThrow(new SarvamNotConfiguredException());

        assertThatThrownBy(() -> new SarvamProviderCredentialSource(service).resolveActiveApiKey())
            .isInstanceOf(SarvamNotConfiguredException.class);
    }

    @Test
    void currentCredentialMapsActiveToActiveAndInactiveToInvalid() {
        SarvamCredentialService service = mock(SarvamCredentialService.class);
        SarvamProviderCredentialSource source = new SarvamProviderCredentialSource(service);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();

        when(service.current()).thenReturn(Optional.of(new SarvamCredentialResponse(id, SarvamCredentialStatus.ACTIVE, now, now)));
        assertThat(source.currentCredential())
            .contains(new ProviderResponse(id, ProviderName.SARVAM, ProviderStatus.ACTIVE, now, now, null));

        when(service.current()).thenReturn(Optional.of(new SarvamCredentialResponse(id, SarvamCredentialStatus.INACTIVE, now, now)));
        assertThat(source.currentCredential()).get().extracting(ProviderResponse::status).isEqualTo(ProviderStatus.INVALID);

        when(service.current()).thenReturn(Optional.empty());
        assertThat(source.currentCredential()).isEmpty();
    }
}
