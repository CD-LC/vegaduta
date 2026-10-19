package com.agenticai.core.providers.modality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ADR-0011 phase 2: the TTS/STT configurations register whatever provider beans
 * exist, keyed by each provider's own id, instead of naming the vendor classes.
 * These tests pin that the lookup behaves exactly like the old named wiring for
 * the full distribution's provider set, and degrades (rather than failing
 * startup) when a vendor module is absent.
 */
class ModalityProviderConfigurationTest {

    private static TextToSpeechProvider tts(TtsModelProvider id) {
        TextToSpeechProvider p = mock(TextToSpeechProvider.class);
        when(p.getProvider()).thenReturn(id);
        when(p.isAvailable()).thenReturn(true);
        return p;
    }

    private static SpeechToTextProvider stt(VoiceModelProvider id) {
        SpeechToTextProvider p = mock(SpeechToTextProvider.class);
        when(p.getProvider()).thenReturn(id);
        when(p.isAvailable()).thenReturn(true);
        return p;
    }

    @Test
    void ttsLookupReturnsEachDiscoveredProviderUnderItsOwnId() {
        TextToSpeechProvider elevenLabs = tts(TtsModelProvider.ELEVENLABS);
        TextToSpeechProvider sarvam = tts(TtsModelProvider.SARVAM);
        TextToSpeechProviderFactory factory = new TextToSpeechProviderFactory();

        new TextToSpeechProviderConfiguration(factory, List.of(sarvam, elevenLabs));

        assertThat(factory.getProvider(TtsModelProvider.ELEVENLABS)).containsSame(elevenLabs);
        assertThat(factory.getProvider(TtsModelProvider.SARVAM)).containsSame(sarvam);
        assertThat(factory.getAvailableProviders()).containsOnlyKeys(TtsModelProvider.ELEVENLABS, TtsModelProvider.SARVAM);
    }

    @Test
    void sttLookupReturnsEachDiscoveredProviderUnderItsOwnId() {
        SpeechToTextProvider sidecar = stt(VoiceModelProvider.SIDECAR_WHISPER);
        SpeechToTextProvider sarvam = stt(VoiceModelProvider.SARVAM);
        SpeechToTextProviderFactory factory = new SpeechToTextProviderFactory();

        new SpeechToTextProviderConfiguration(factory, List.of(sidecar, sarvam));

        assertThat(factory.getProvider(VoiceModelProvider.SIDECAR_WHISPER)).containsSame(sidecar);
        assertThat(factory.getProvider(VoiceModelProvider.SARVAM)).containsSame(sarvam);
        assertThat(factory.getProvider(VoiceModelProvider.ELEVENLABS)).isEmpty();
        assertThat(factory.getAvailableProviders())
            .containsOnlyKeys(VoiceModelProvider.SIDECAR_WHISPER, VoiceModelProvider.SARVAM);
    }

    @Test
    void aDistributionWithoutAVendorModuleReportsItAsNotInstalledInsteadOfFailingStartup() {
        TextToSpeechProviderFactory factory = new TextToSpeechProviderFactory();

        new TextToSpeechProviderConfiguration(factory, List.of());

        assertThat(factory.isAvailable(TtsModelProvider.ELEVENLABS)).isFalse();
        assertThat(factory.getUnavailabilityReason(TtsModelProvider.ELEVENLABS))
            .isEqualTo("Provider 'elevenlabs' is not available on this deployment");
    }

    @Test
    void twoBeansClaimingTheSameIdFailFast() {
        TextToSpeechProviderFactory factory = new TextToSpeechProviderFactory();
        List<TextToSpeechProvider> clash = List.of(tts(TtsModelProvider.SARVAM), tts(TtsModelProvider.SARVAM));

        assertThatThrownBy(() -> new TextToSpeechProviderConfiguration(factory, clash))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SARVAM");
    }
}
