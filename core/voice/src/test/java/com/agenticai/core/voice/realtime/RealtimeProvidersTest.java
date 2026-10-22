package com.agenticai.core.voice.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Frame-shape tests for the two realtime providers - the upstream contract each provider's API
 * documents. A wrong field name here means the provider silently ignores our config, so these pin
 * the wire format.
 */
class RealtimeProvidersTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    @Test
    void providerParse_acceptsBothSpellings_andRejectsGarbage() {
        assertEquals(RealtimeProvider.GEMINI_LIVE, RealtimeProvider.parse("gemini-live"));
        assertEquals(RealtimeProvider.GEMINI_LIVE, RealtimeProvider.parse("GEMINI_LIVE"));
        assertEquals(RealtimeProvider.OPENAI_REALTIME, RealtimeProvider.parse("openai-realtime"));
        assertEquals(RealtimeProvider.OPENAI_REALTIME, RealtimeProvider.parse("openai"));
        assertNull(RealtimeProvider.parse("sarvam"));
        assertNull(RealtimeProvider.parse(null));
    }

    @Test
    void geminiSetupFrame_matchesTheBidiGenerateContentShape() throws Exception {
        String frame =
                GeminiLiveRealtimeProvider.buildSetupFrame(
                        new RealtimeSessionConfig(
                                "k",
                                "gemini-2.5-flash-native-audio-preview-09-2025",
                                "You are helpful.",
                                "Aoede"));

        JsonNode setup = MAPPER.readTree(frame).path("setup");
        assertEquals(
                "models/gemini-2.5-flash-native-audio-preview-09-2025",
                setup.path("model").asString());
        assertEquals(
                "AUDIO",
                setup.path("generationConfig").path("responseModalities").get(0).asString());
        assertEquals(
                "Aoede",
                setup.path("generationConfig")
                        .path("speechConfig")
                        .path("voiceConfig")
                        .path("prebuiltVoiceConfig")
                        .path("voiceName")
                        .asString());
        assertEquals(
                "You are helpful.",
                setup.path("systemInstruction").path("parts").get(0).path("text").asString());
    }

    @Test
    void geminiSetupFrame_defaultsModelAndVoice_andPrefixesModelsNamespace() throws Exception {
        String frame =
                GeminiLiveRealtimeProvider.buildSetupFrame(
                        new RealtimeSessionConfig("k", "gemini-live-x", null, null));

        JsonNode setup = MAPPER.readTree(frame).path("setup");
        assertEquals("models/gemini-live-x", setup.path("model").asString());
        assertEquals(
                "Puck",
                setup.path("generationConfig")
                        .path("speechConfig")
                        .path("voiceConfig")
                        .path("prebuiltVoiceConfig")
                        .path("voiceName")
                        .asString());
        assertTrue(setup.path("systemInstruction").isMissingNode());
    }

    @Test
    void openAiSessionUpdate_matchesTheRealtimeApiShape() throws Exception {
        String frame =
                OpenAiRealtimeProvider.buildSessionUpdate(
                        new RealtimeSessionConfig("k", "gpt-realtime", "Speak plainly.", "verse"));

        JsonNode root = MAPPER.readTree(frame);
        assertEquals("session.update", root.path("type").asString());
        JsonNode session = root.path("session");
        assertEquals("audio", session.path("modalities").get(0).asString());
        assertEquals("Speak plainly.", session.path("instructions").asString());
        assertEquals("verse", session.path("voice").asString());
        assertEquals("pcm16", session.path("input_audio_format").asString());
        assertEquals("pcm16", session.path("output_audio_format").asString());
        assertEquals("server_vad", session.path("turn_detection").path("type").asString());
    }

    @Test
    void openAiSessionUpdate_defaultsVoiceToAlloy() throws Exception {
        JsonNode session =
                MAPPER.readTree(
                                OpenAiRealtimeProvider.buildSessionUpdate(
                                        new RealtimeSessionConfig("k", null, null, null)))
                        .path("session");
        assertEquals("alloy", session.path("voice").asString());
    }
}
