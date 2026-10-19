package com.agenticai.core.elevenlabs.agenttools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.elevenlabs.ElevenLabsApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Mirrors {@code GenerateImageToolCallbackTest}: the tool result is always JSON a model can relay,
 * never a raw upstream error.
 */
class GenerateMusicToolCallbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MusicGenerationService service = mock(MusicGenerationService.class);
    private final GenerateMusicToolCallback callback = new GenerateMusicToolCallback(service);

    @Test
    void toolIsNamedGenerateMusicAndRequiresOnlyAPrompt() throws Exception {
        assertEquals("generate_music", callback.getToolDefinition().name());
        JsonNode schema = MAPPER.readTree(callback.getToolDefinition().inputSchema());
        assertEquals(1, schema.path("required").size());
        assertEquals("prompt", schema.path("required").get(0).asText());
    }

    @Test
    void successReturnsTheStoredTrackUrlModelAndDuration() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.generate(eq("lofi beat for a bakery"), eq(12), eq(true)))
                .thenReturn(new MusicGenerationService.GeneratedTrack(id, "music_v2_5", 12, true));

        JsonNode result =
                MAPPER.readTree(
                        callback.call(
                                "{\"prompt\":\"  lofi beat for a bakery \",\"durationSeconds\":12,\"instrumental\":true}"));

        assertEquals("success", result.path("status").asText());
        assertEquals("/api/musicgen/tracks/" + id, result.path("audioUrl").asText());
        assertEquals("music_v2_5", result.path("model").asText());
        assertEquals(12, result.path("durationSeconds").asInt());
    }

    @Test
    void durationAndInstrumentalAreOptional() throws Exception {
        when(service.generate(any(), any(), anyBoolean()))
                .thenReturn(
                        new MusicGenerationService.GeneratedTrack(
                                UUID.randomUUID(), "music_v1", 20, false));

        callback.call("{\"prompt\":\"upbeat jingle\"}");

        verify(service).generate("upbeat jingle", null, false);
    }

    @Test
    void blankPromptNeverReachesTheService() throws Exception {
        assertEquals("prompt is required", errorOf(callback.call("{\"prompt\":\"   \"}")));
        verifyNoInteractions(service);
    }

    @Test
    void quotaMessageIsPassedThroughForTheModelToRelay() throws Exception {
        when(service.generate(any(), any(), anyBoolean()))
                .thenThrow(
                        new MusicGenerationQuotaExceededException(
                                "You've reached today's music generation limit (2)."));

        String error = errorOf(callback.call("{\"prompt\":\"jazz\"}"));

        assertTrue(error.startsWith("You've reached today's music generation limit (2)."));
        assertTrue(error.contains("do not retry"));
    }

    @Test
    void missingKeyNamesTheNextActionInsteadOfAnErrorCode() throws Exception {
        when(service.generate(any(), any(), anyBoolean()))
                .thenThrow(new MusicGenNotConfiguredException("no key"));

        String error = errorOf(callback.call("{\"prompt\":\"jazz\"}")).toLowerCase(Locale.ROOT);

        assertTrue(error.contains("elevenlabs"));
        assertTrue(error.contains("providers page"));
    }

    @Test
    void rawUpstreamErrorNeverLeaksIntoTheToolResult() throws Exception {
        when(service.generate(any(), any(), anyBoolean()))
                .thenThrow(
                        new ElevenLabsApiException(
                                401,
                                "ElevenLabs music call failed: HTTP 401: {\"detail\":\"invalid_api_key sk_live_abc\"}"));

        String error = errorOf(callback.call("{\"prompt\":\"jazz\"}"));

        assertFalse(error.contains("401"));
        assertFalse(error.contains("sk_live_abc"));
        assertTrue(error.contains("could not be completed"));
    }

    private static String errorOf(String json) throws Exception {
        return MAPPER.readTree(json).path("error").asText();
    }
}
