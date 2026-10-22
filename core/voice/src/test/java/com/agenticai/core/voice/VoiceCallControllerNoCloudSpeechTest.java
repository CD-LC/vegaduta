package com.agenticai.core.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.agenticai.core.knowledge.SidecarClient;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * The voice turn on an install without cloud speech (edition feature {@code CLOUD_SPEECH} off, the
 * personal default): Spring injects an EMPTY {@code List<CallSpeechSynthesizer>} and no {@code
 * CallSpeechTranscriber}, because neither the Sarvam nor the ElevenLabs adapter is a bean. The turn
 * must still answer, on the sidecar's Whisper, as text only.
 */
@DisplayName("Voice Call Controller - no cloud speech vendors registered")
class VoiceCallControllerNoCloudSpeechTest extends VoiceCallControllerTestBase {

    private VoiceCallController newLeanController(Optional<SidecarClient> sidecar) {
        return new VoiceCallController(
                agentService,
                agentChatService,
                tokenService,
                sidecar,
                List.of(),
                Optional.empty(),
                roomRegistry,
                providerFactory);
    }

    @Test
    @DisplayName("Whisper transcribes and the reply comes back as text with no audio chunks")
    void turnAnswersFromTheSidecarAsTextOnly() throws Exception {
        controller = newLeanController(Optional.of(sidecarClient));
        when(sidecarClient.transcribeAudioLive("audio.wav", testAudioBytes))
                .thenReturn("hello from whisper");
        MockMultipartFile audioFile =
                new MockMultipartFile("audio", "audio.wav", "audio/wav", testAudioBytes);

        TurnResult result = runTurn(audioFile, null);

        assertEquals("hello from whisper", result.transcript());
        assertEquals("sidecar-whisper", result.transcriptMethod());
        assertEquals("Test reply", result.replyText());
        assertTrue(
                result.audioChunks().stream()
                        .allMatch(
                                c ->
                                        (c.audioBase64() == null || c.audioBase64().isEmpty())
                                                && "text-only-fallback".equals(c.method())),
                "no TTS vendor is registered, so no chunk carries audio");
        assertNotNull(result.done());
    }

    @Test
    @DisplayName("With no sidecar either, the turn is refused with the not-configured message")
    void turnWithoutAnySpeechToTextIsTheNamedRefusal() {
        controller = newLeanController(Optional.empty());
        MockMultipartFile audioFile =
                new MockMultipartFile("audio", "audio.wav", "audio/wav", testAudioBytes);

        VoiceCallController.VoiceTurnAudioEmptyException ex =
                assertThrows(
                        VoiceCallController.VoiceTurnAudioEmptyException.class,
                        () -> controller.turn(testAgentId, audioFile, null, null));

        assertTrue(ex.getMessage().contains("No speech-to-text provider is configured"));
    }
}
