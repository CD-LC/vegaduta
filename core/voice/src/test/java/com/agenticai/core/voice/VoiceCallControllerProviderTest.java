package com.agenticai.core.voice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.agenticai.core.providers.modality.SpeechToTextProviderException;
import com.agenticai.core.providers.modality.SpeechToTextTranscriptionException;
import com.agenticai.core.providers.modality.VoiceModelProvider;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/** {@link VoiceCallController} with an agent-selected speech-to-text provider and its fallbacks. */
@DisplayName("Voice Call Controller - Selected STT provider")
class VoiceCallControllerProviderTest extends VoiceCallControllerTestBase {

    // ====== New tests for multi-provider integration ======

    @Test
    @DisplayName("Should use agent's selected SIDECAR_WHISPER provider")
    void shouldUseAgentSelectedSidecarProvider() throws Exception {
        controller = newController(Optional.of(sidecarClient), ttsClient);

        when(mockAgent.getVoiceModelProvider()).thenReturn(VoiceModelProvider.SIDECAR_WHISPER);
        when(mockAgent.getVoiceModel()).thenReturn("openai/whisper-1");
        when(providerFactory.getProvider(VoiceModelProvider.SIDECAR_WHISPER))
                .thenReturn(Optional.of(mockProvider));
        when(mockProvider.isAvailable()).thenReturn(true);
        when(mockProvider.transcribe(
                        eq(testAudioBytes), eq("audio.wav"), eq("openai/whisper-1"), isNull()))
                .thenReturn("hello from selected whisper");

        MockMultipartFile audioFile =
                new MockMultipartFile("audio", "audio.wav", "audio/wav", testAudioBytes);

        TurnResult result = runTurn(audioFile, null);

        assertEquals("hello from selected whisper", result.transcript());
        verify(mockProvider)
                .transcribe(eq(testAudioBytes), eq("audio.wav"), eq("openai/whisper-1"), isNull());
    }

    @Test
    @DisplayName("Should use agent's selected SARVAM provider")
    void shouldUseAgentSelectedSarvamProvider() throws Exception {
        controller = newController(Optional.empty(), ttsClient);

        when(mockAgent.getVoiceModelProvider()).thenReturn(VoiceModelProvider.SARVAM);
        when(mockAgent.getVoiceModel()).thenReturn("saaras:v3");
        when(providerFactory.getProvider(VoiceModelProvider.SARVAM))
                .thenReturn(Optional.of(mockProvider));
        when(mockProvider.isAvailable()).thenReturn(true);
        when(mockProvider.transcribe(
                        eq(testAudioBytes), eq("audio.wav"), eq("saaras:v3"), isNull()))
                .thenReturn("hello from selected sarvam");

        MockMultipartFile audioFile =
                new MockMultipartFile("audio", "audio.wav", "audio/wav", testAudioBytes);

        TurnResult result = runTurn(audioFile, null);

        assertEquals("hello from selected sarvam", result.transcript());
        verify(mockProvider)
                .transcribe(eq(testAudioBytes), eq("audio.wav"), eq("saaras:v3"), isNull());
    }

    @Test
    @DisplayName("Should fallback to sidecar when selected provider fails with provider exception")
    void shouldFallbackToSidecarOnProviderFailure() throws Exception {
        controller = newController(Optional.of(sidecarClient), ttsClient);

        when(mockAgent.getVoiceModelProvider()).thenReturn(VoiceModelProvider.SARVAM);
        when(mockAgent.getVoiceModel()).thenReturn("saaras:v3");
        when(providerFactory.getProvider(VoiceModelProvider.SARVAM))
                .thenReturn(Optional.of(mockProvider));
        when(mockProvider.isAvailable()).thenReturn(true);
        when(mockProvider.transcribe(
                        eq(testAudioBytes), eq("audio.wav"), eq("saaras:v3"), isNull()))
                .thenThrow(new SpeechToTextProviderException("API error", 503, true));

        // Fallback to sidecar
        when(sidecarClient.transcribeAudioLive("audio.wav", testAudioBytes))
                .thenReturn("fallback to sidecar");

        MockMultipartFile audioFile =
                new MockMultipartFile("audio", "audio.wav", "audio/wav", testAudioBytes);

        TurnResult result = runTurn(audioFile, null);

        assertEquals("fallback to sidecar", result.transcript());
        verify(mockProvider).transcribe(any(byte[].class), anyString(), anyString(), isNull());
        verify(sidecarClient).transcribeAudioLive("audio.wav", testAudioBytes);
    }

    @Test
    @DisplayName("Should fail immediately on transcription exception (audio quality issue)")
    void shouldFailImmediatelyOnTranscriptionException() throws Exception {
        controller = newController(Optional.of(sidecarClient), ttsClient);

        when(mockAgent.getVoiceModelProvider()).thenReturn(VoiceModelProvider.SARVAM);
        when(mockAgent.getVoiceModel()).thenReturn("saaras:v3");
        when(providerFactory.getProvider(VoiceModelProvider.SARVAM))
                .thenReturn(Optional.of(mockProvider));
        when(mockProvider.isAvailable()).thenReturn(true);
        when(mockProvider.transcribe(
                        eq(testAudioBytes), eq("audio.wav"), eq("saaras:v3"), isNull()))
                .thenThrow(new SpeechToTextTranscriptionException("No speech detected"));

        MockMultipartFile audioFile =
                new MockMultipartFile("audio", "audio.wav", "audio/wav", testAudioBytes);

        VoiceCallController.VoiceTurnAudioEmptyException ex =
                assertThrows(
                        VoiceCallController.VoiceTurnAudioEmptyException.class,
                        () -> controller.turn(testAgentId, audioFile, null, null));

        assertTrue(ex.getMessage().contains("transcribe"));
        // Should NOT fall back to sidecar since this is an audio quality issue
        verify(sidecarClient, never()).transcribeAudioLive(anyString(), any());
    }

    @Test
    @DisplayName("Should handle unavailable selected provider gracefully")
    void shouldHandleUnavailableSelectedProvider() throws Exception {
        controller = newController(Optional.of(sidecarClient), ttsClient);

        when(mockAgent.getVoiceModelProvider()).thenReturn(VoiceModelProvider.SARVAM);
        when(mockAgent.getVoiceModel()).thenReturn("saaras:v3");
        when(providerFactory.getProvider(VoiceModelProvider.SARVAM))
                .thenReturn(Optional.of(mockProvider));
        when(mockProvider.isAvailable()).thenReturn(false);

        // Should fall back to sidecar
        when(sidecarClient.transcribeAudioLive("audio.wav", testAudioBytes))
                .thenReturn("fallback to sidecar");

        MockMultipartFile audioFile =
                new MockMultipartFile("audio", "audio.wav", "audio/wav", testAudioBytes);

        TurnResult result = runTurn(audioFile, null);

        assertEquals("fallback to sidecar", result.transcript());
        verify(mockProvider, never())
                .transcribe(any(byte[].class), anyString(), anyString(), isNull());
        verify(sidecarClient).transcribeAudioLive("audio.wav", testAudioBytes);
    }

    @Test
    @DisplayName("Should handle unregistered selected provider gracefully")
    void shouldHandleUnregisteredSelectedProvider() throws Exception {
        controller = newController(Optional.of(sidecarClient), ttsClient);

        when(mockAgent.getVoiceModelProvider()).thenReturn(VoiceModelProvider.ELEVENLABS);
        when(mockAgent.getVoiceModel()).thenReturn("eleven_multilingual_v2");
        when(providerFactory.getProvider(VoiceModelProvider.ELEVENLABS))
                .thenReturn(Optional.empty()); // Not registered yet

        // Should fall back to sidecar
        when(sidecarClient.transcribeAudioLive("audio.wav", testAudioBytes))
                .thenReturn("fallback to sidecar");

        MockMultipartFile audioFile =
                new MockMultipartFile("audio", "audio.wav", "audio/wav", testAudioBytes);

        TurnResult result = runTurn(audioFile, null);

        assertEquals("fallback to sidecar", result.transcript());
        verify(sidecarClient).transcribeAudioLive("audio.wav", testAudioBytes);
    }
}
