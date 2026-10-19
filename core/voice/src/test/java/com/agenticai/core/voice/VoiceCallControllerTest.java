package com.agenticai.core.voice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.sarvam.SarvamSpeechToTextClient;
import com.agenticai.core.voice.dto.VoiceTurnAudioChunkEvent;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import reactor.core.publisher.Flux;

/**
 * Tests for VoiceCallController's transcription fallback logic, error handling, and audio
 * synthesis. Ensures graceful degradation when sidecar is unavailable. Selected-provider routing is
 * in {@link VoiceCallControllerProviderTest}; the 2026-08-08 quota/tenant regressions in {@link
 * VoiceCallControllerAuditFixTest}.
 */
@DisplayName("Voice Call Controller - Transcription & Synthesis")
class VoiceCallControllerTest extends VoiceCallControllerTestBase {

    @Test
    @DisplayName("Should use sidecar Whisper when available (no selected provider)")
    void shouldUseSidecarWhenAvailable() throws Exception {
        // Setup controller with sidecar present
        controller = newController(Optional.of(sidecarClient), ttsClient);

        when(sidecarClient.transcribeAudioLive("audio.wav", testAudioBytes))
                .thenReturn("hello from sidecar");

        MockMultipartFile audioFile =
                new MockMultipartFile("audio", "audio.wav", "audio/wav", testAudioBytes);

        TurnResult result = runTurn(audioFile, null);

        assertEquals("hello from sidecar", result.transcript());
        assertEquals("Test reply", result.replyText());
        assertFalse(result.audioChunks().isEmpty());
        assertNotNull(result.done());
        verify(sidecarClient).transcribeAudioLive("audio.wav", testAudioBytes);
        verify(sttClient, never()).transcribe(any(), any());
    }

    @Test
    @DisplayName("Should fallback to Sarvam when sidecar fails (no selected provider)")
    void shouldFallbackToSarvamOnSidecarFailure() throws Exception {
        // Setup controller with sidecar present
        controller = newController(Optional.of(sidecarClient), ttsClient);

        // Sidecar fails
        when(sidecarClient.transcribeAudioLive(anyString(), any()))
                .thenThrow(new RuntimeException("Sidecar connection failed"));

        // Sarvam succeeds
        when(sttClient.transcribe(testAudioBytes, "audio.wav"))
                .thenReturn(
                        new SarvamSpeechToTextClient.TranscriptionResult(
                                "hello from sarvam", null, null));

        MockMultipartFile audioFile =
                new MockMultipartFile("audio", "audio.wav", "audio/wav", testAudioBytes);

        TurnResult result = runTurn(audioFile, null);

        assertEquals("hello from sarvam", result.transcript());
        assertEquals("Test reply", result.replyText());
        verify(sidecarClient).transcribeAudioLive("audio.wav", testAudioBytes);
        verify(sttClient).transcribe(testAudioBytes, "audio.wav");
    }

    @Test
    @DisplayName("Should use Sarvam when sidecar is not available (knowledge profile inactive)")
    void shouldUseSarvamWhenSidecarNotAvailable() throws Exception {
        // Setup controller WITHOUT sidecar (knowledge profile not active)
        controller = newController(Optional.empty(), ttsClient);

        when(sttClient.transcribe(testAudioBytes, "audio.wav"))
                .thenReturn(
                        new SarvamSpeechToTextClient.TranscriptionResult(
                                "hello from sarvam", null, null));

        MockMultipartFile audioFile =
                new MockMultipartFile("audio", "audio.wav", "audio/wav", testAudioBytes);

        TurnResult result = runTurn(audioFile, null);

        assertEquals("hello from sarvam", result.transcript());
        assertEquals("Test reply", result.replyText());
        verify(sttClient).transcribe(testAudioBytes, "audio.wav");
    }

    @Test
    @DisplayName("Should throw error when both transcription methods fail")
    void shouldThrowErrorWhenAllTranscriptionMethodsFail() throws Exception {
        controller = newController(Optional.of(sidecarClient), ttsClient);

        when(sidecarClient.transcribeAudioLive(anyString(), any()))
                .thenThrow(new RuntimeException("Sidecar down"));
        when(sttClient.transcribe(any(), anyString()))
                .thenThrow(new RuntimeException("Sarvam down"));

        MockMultipartFile audioFile =
                new MockMultipartFile("audio", "audio.wav", "audio/wav", testAudioBytes);

        // Still thrown synchronously before any streaming begins - speech-to-text
        // runs before the Flux is ever constructed, so this is unchanged by the
        // move to SSE.
        VoiceCallController.VoiceTurnAudioEmptyException ex =
                assertThrows(
                        VoiceCallController.VoiceTurnAudioEmptyException.class,
                        () -> controller.turn(testAgentId, audioFile, null, null));

        assertTrue(ex.getMessage().contains("transcribe"));
    }

    @Test
    @DisplayName("Should throw error when transcription produces empty text")
    void shouldThrowErrorOnEmptyTranscription() throws Exception {
        controller = newController(Optional.of(sidecarClient), ttsClient);

        // Sidecar succeeds but returns empty
        when(sidecarClient.transcribeAudioLive(anyString(), any())).thenReturn("");

        // Sarvam also returns empty
        when(sttClient.transcribe(any(), anyString()))
                .thenReturn(new SarvamSpeechToTextClient.TranscriptionResult("", null, null));

        MockMultipartFile audioFile =
                new MockMultipartFile("audio", "audio.wav", "audio/wav", testAudioBytes);

        VoiceCallController.VoiceTurnAudioEmptyException ex =
                assertThrows(
                        VoiceCallController.VoiceTurnAudioEmptyException.class,
                        () -> controller.turn(testAgentId, audioFile, null, null));

        assertTrue(ex.getMessage().contains("speech"));
    }

    @Test
    @DisplayName(
            "Should generate TTS audio and base64-encode it, streamed as one audio-chunk event")
    void shouldGenerateTtsAudioAndEncode() throws Exception {
        controller = newController(Optional.empty(), ttsClient);

        byte[] fakeAudio = new byte[] {0x01, 0x02, 0x03};
        when(ttsClient.textToSpeech(anyString(), anyString(), isNull())).thenReturn(fakeAudio);
        when(sttClient.transcribe(testAudioBytes, "audio.wav"))
                .thenReturn(new SarvamSpeechToTextClient.TranscriptionResult("test", null, null));

        MockMultipartFile audioFile =
                new MockMultipartFile("audio", "audio.wav", "audio/wav", testAudioBytes);

        TurnResult result = runTurn(audioFile, null);

        // "Test reply" has no sentence-boundary punctuation, so
        // SpeechStreamNormalizer releases it as exactly one piece on flush -
        // one audio-chunk event for the whole (short) reply.
        assertEquals(1, result.audioChunks().size());
        VoiceTurnAudioChunkEvent chunk = result.audioChunks().get(0);
        assertEquals("elevenlabs", chunk.method());
        byte[] decoded = java.util.Base64.getDecoder().decode(chunk.audioBase64());
        assertArrayEquals(fakeAudio, decoded);

        // Exactly one row persisted for the whole turn, not one per chunk.
        verify(generatedAudioService, times(1)).store(any(), any());
    }

    @Test
    @DisplayName(
            "Should persist exactly one audio row for the whole turn even with multiple sentence"
                    + " chunks")
    void shouldPersistExactlyOneRowRegardlessOfChunkCount() throws Exception {
        controller = newController(Optional.empty(), ttsClient);

        // Three sentences, streamed as separate tokens the way a real LLM
        // reply would arrive - SpeechStreamNormalizer should release one
        // piece per sentence, which means one synthesizeChunk() call and one
        // audio-chunk event each. The point of this test is what happens
        // AFTER that: VoiceQuotaService's ledger is row-counted (see
        // persistTurnAudio's javadoc), so N chunks must still collapse into
        // exactly one store() call, not N.
        when(agentChatService.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null)))
                .thenReturn(Flux.just("Hello there. ", "How are you? ", "Great!"));
        when(ttsClient.textToSpeech(anyString(), anyString(), isNull()))
                .thenReturn(new byte[] {1, 2, 3});
        when(sttClient.transcribe(testAudioBytes, "audio.wav"))
                .thenReturn(new SarvamSpeechToTextClient.TranscriptionResult("test", null, null));

        MockMultipartFile audioFile =
                new MockMultipartFile("audio", "audio.wav", "audio/wav", testAudioBytes);

        TurnResult result = runTurn(audioFile, null);

        assertEquals(3, result.audioChunks().size(), "expected one audio-chunk event per sentence");
        assertTrue(result.audioChunks().get(0).textFragment().contains("Hello there"));
        assertTrue(result.audioChunks().get(1).textFragment().contains("How are you"));
        assertTrue(result.audioChunks().get(2).textFragment().contains("Great"));
        for (VoiceTurnAudioChunkEvent chunk : result.audioChunks()) {
            assertEquals("elevenlabs", chunk.method());
        }
        assertNotNull(result.done());
        assertTrue(result.done().replyText().contains("Hello there"));
        assertTrue(result.done().replyText().contains("Great"));

        // The core fix under test: 3 audio chunks must still be exactly ONE
        // persisted row, not three (VoiceQuotaService counts rows).
        verify(generatedAudioService, times(1)).store(any(), any());
        verify(sarvamAudioService, never()).store(any(), any());
    }

    @Test
    @DisplayName("Should reject empty audio uploads")
    void shouldRejectEmptyAudio() throws Exception {
        controller = newController(Optional.empty(), ttsClient);

        MockMultipartFile emptyFile =
                new MockMultipartFile("audio", "audio.wav", "audio/wav", new byte[0]);

        VoiceCallController.VoiceTurnAudioEmptyException ex =
                assertThrows(
                        VoiceCallController.VoiceTurnAudioEmptyException.class,
                        () -> controller.turn(testAgentId, emptyFile, null, null));

        // Asserts the opening clause only, not the whole sentence: the rest of this
        // message is user-facing microphone-troubleshooting guidance that is expected to
        // be reworded as the diagnostics UX evolves, and pinning it verbatim here just
        // breaks the build every time someone improves the copy.
        assertTrue(
                ex.getMessage().startsWith("No audio was uploaded"),
                "unexpected message: " + ex.getMessage());
    }
}
