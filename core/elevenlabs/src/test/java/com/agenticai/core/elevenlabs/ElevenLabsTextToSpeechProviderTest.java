package com.agenticai.core.elevenlabs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.modality.TextToSpeechProviderException;
import com.agenticai.core.providers.modality.TtsModelProvider;
import org.junit.jupiter.api.Test;

/**
 * ADR-0011 phase 2: the ElevenLabs TTS adapter moved from core/providers into
 * this module. Pins its provider id and availability contract.
 */
class ElevenLabsTextToSpeechProviderTest {

    private final ElevenLabsCredentialService credentials = mock(ElevenLabsCredentialService.class);
    private final ElevenLabsTextToSpeechProvider provider = new ElevenLabsTextToSpeechProvider(
        mock(ElevenLabsClient.class), credentials, mock(ElevenLabsVoiceService.class));

    @Test
    void keepsItsProviderId() {
        assertThat(provider.getProvider()).isEqualTo(TtsModelProvider.ELEVENLABS);
    }

    @Test
    void availabilityFollowsTheTenantCredential() {
        when(credentials.isActive()).thenReturn(true);
        assertThat(provider.isAvailable()).isTrue();

        when(credentials.isActive()).thenReturn(false);
        assertThat(provider.isAvailable()).isFalse();
        assertThatThrownBy(() -> provider.synthesize("hello"))
            .isInstanceOf(TextToSpeechProviderException.class);
    }
}
