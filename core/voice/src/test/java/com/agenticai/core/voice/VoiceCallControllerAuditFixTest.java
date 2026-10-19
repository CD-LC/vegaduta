package com.agenticai.core.voice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.elevenlabs.VoiceQuotaExceededException;
import com.agenticai.core.sarvam.SarvamNotConfiguredException;
import com.agenticai.core.sarvam.SarvamSpeechToTextClient;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.voice.dto.SpeakResponse;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import reactor.core.publisher.Flux;

/**
 * {@link VoiceCallController} regressions from the 2026-08-08 voice TTS/STT audit: quota and
 * tenant.
 */
@DisplayName("Voice Call Controller - quota and tenant regressions")
class VoiceCallControllerAuditFixTest extends VoiceCallControllerTestBase {

    // ====== 2026-08-08 voice TTS/STT audit fix regression tests ======

    @Test
    @DisplayName(
            "turn(): ElevenLabs quota-exceeded surfaces as a distinct error event, not a silent"
                    + " Sarvam/text-only fallback")
    void turnShouldSurfaceElevenLabsQuotaExceededAsDistinctErrorEvent() throws Exception {
        controller = newController(Optional.empty(), ttsClient);

        when(sttClient.transcribe(testAudioBytes, "audio.wav"))
                .thenReturn(new SarvamSpeechToTextClient.TranscriptionResult("test", null, null));
        when(ttsClient.textToSpeech(anyString(), anyString(), isNull()))
                .thenThrow(
                        new VoiceQuotaExceededException(
                                "You've reached today's voice generation limit (5). Try again"
                                        + " tomorrow, or upgrade for a higher limit."));

        MockMultipartFile audioFile =
                new MockMultipartFile("audio", "audio.wav", "audio/wav", testAudioBytes);

        TurnResult result = runTurn(audioFile, null);

        assertNotNull(
                result.errorMessage(),
                "expected a distinct `error` SSE event for the quota rejection");
        assertTrue(
                result.errorMessage().contains("upgrade for a higher limit"),
                "unexpected message: " + result.errorMessage());
        assertTrue(
                result.audioChunks().isEmpty(),
                "a quota rejection must not silently downgrade to a text-only-fallback audio-chunk"
                        + " event");
        // Falling through to Sarvam after a quota rejection would be
        // pointless (same shared ledger) and would defeat the whole point
        // of surfacing the rejection distinctly.
        verify(sarvamTtsClient, never()).synthesize(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("turn(): Sarvam TTS fallback is quota-gated the same as ElevenLabs, not unlimited")
    void turnShouldQuotaGateSarvamTtsFallback() throws Exception {
        controller = newController(Optional.empty(), null);

        when(sttClient.transcribe(testAudioBytes, "audio.wav"))
                .thenReturn(new SarvamSpeechToTextClient.TranscriptionResult("test", null, null));
        doThrow(
                        new VoiceQuotaExceededException(
                                "Your plan supports up to 300 characters per voice generation."
                                        + " Upgrade for longer clips."))
                .when(voiceQuotaService)
                .checkAllowed(any(), any(), any());

        MockMultipartFile audioFile =
                new MockMultipartFile("audio", "audio.wav", "audio/wav", testAudioBytes);

        TurnResult result = runTurn(audioFile, null);

        assertNotNull(
                result.errorMessage(),
                "expected a distinct `error` SSE event for the quota rejection");
        assertTrue(
                result.errorMessage().contains("Upgrade"),
                "unexpected message: " + result.errorMessage());
        verify(sarvamTtsClient, never()).synthesize(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("speak(): quota-exceeded surfaces as a distinct method, not text-only-fallback")
    void speakShouldSurfaceQuotaExceededDistinctly() {
        controller = newController(Optional.empty(), ttsClient);

        when(ttsClient.textToSpeech(anyString(), anyString(), isNull()))
                .thenThrow(
                        new VoiceQuotaExceededException(
                                "You've reached today's voice generation limit (5). Try again"
                                        + " tomorrow, or upgrade for a higher limit."));

        SpeakResponse response =
                controller.speak(
                        testAgentId, new com.agenticai.core.voice.dto.SpeakRequest("hello world"));

        assertEquals("quota-exceeded", response.method());
        assertEquals("", response.audioBase64());
        assertTrue(
                response.speechText().contains("upgrade for a higher limit"),
                "unexpected speechText: " + response.speechText());
        verify(sarvamTtsClient, never()).synthesize(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName(
            "turn(): a missing Sarvam credential (last-resort STT fallback) surfaces a distinct"
                    + " configuration message, not the generic audio-quality one")
    void turnShouldSurfaceDistinctMessageWhenNoSttProviderConfigured() throws Exception {
        controller = newController(Optional.empty(), ttsClient);

        when(sttClient.transcribe(any(), anyString()))
                .thenThrow(new SarvamNotConfiguredException());

        MockMultipartFile audioFile =
                new MockMultipartFile("audio", "audio.wav", "audio/wav", testAudioBytes);

        VoiceCallController.VoiceTurnAudioEmptyException ex =
                assertThrows(
                        VoiceCallController.VoiceTurnAudioEmptyException.class,
                        () -> controller.turn(testAgentId, audioFile, null, null));

        assertTrue(
                ex.getMessage().contains("No speech-to-text provider is configured"),
                "unexpected message: " + ex.getMessage());
    }

    /**
     * Each sentence's TTS call runs on Reactor's bounded-elastic pool and the turn's audio is
     * persisted from the stream's completion thread; neither has the request's TenantContext. The
     * voice lookup, the credential/quota reads and the audio ledger write are all tenant-schema
     * work, so they must run under the tenant the turn was started with (a context-less thread
     * fails them, and the caller silently gets text-only replies).
     */
    @Test
    @DisplayName("turn(): synthesis and the audio ledger write run under the caller's tenant")
    void turnShouldRunSynthesisAndPersistenceUnderTheCallersTenant() throws Exception {
        controller = newController(Optional.empty(), ttsClient);
        List<String> tenantSeen = new CopyOnWriteArrayList<>();
        java.util.function.Supplier<String> current =
                () -> TenantContext.current().map(TenantContext::getTenantId).orElse("NONE");
        when(elevenLabsVoiceService.hasAnyVoice())
                .thenAnswer(
                        inv -> {
                            tenantSeen.add("voice:" + current.get());
                            return false;
                        });
        when(ttsClient.textToSpeech(anyString(), anyString(), isNull()))
                .thenAnswer(
                        inv -> {
                            tenantSeen.add("tts:" + current.get());
                            return new byte[] {1, 2, 3};
                        });
        doAnswer(
                        inv -> {
                            tenantSeen.add("store:" + current.get());
                            return null;
                        })
                .when(generatedAudioService)
                .store(any(), any());
        when(agentChatService.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null)))
                .thenReturn(Flux.just("Hello there. ", "How are you?"));
        when(sttClient.transcribe(testAudioBytes, "audio.wav"))
                .thenReturn(new SarvamSpeechToTextClient.TranscriptionResult("test", null, null));
        MockMultipartFile audioFile =
                new MockMultipartFile("audio", "audio.wav", "audio/wav", testAudioBytes);
        TenantContext.set(TenantContext.of("tenant-a", "alice", Set.of()));

        TurnResult result = runTurn(audioFile, null);

        assertEquals(2, result.audioChunks().size());
        assertTrue(tenantSeen.contains("tts:tenant-a"), "seen: " + tenantSeen);
        assertTrue(tenantSeen.contains("store:tenant-a"), "seen: " + tenantSeen);
        assertTrue(
                tenantSeen.stream().allMatch(seen -> seen.endsWith(":tenant-a")),
                "every tenant-schema step must see tenant-a, seen: " + tenantSeen);
        assertEquals("tenant-a", TenantContext.current().orElseThrow().getTenantId());
    }
}
