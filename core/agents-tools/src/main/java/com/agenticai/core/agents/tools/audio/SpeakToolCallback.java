package com.agenticai.core.agents.tools.audio;

import com.agenticai.core.agentspi.SpeakBackend;
import com.agenticai.core.json.JsonMappers;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

/**
 * Unifies what used to be two separate, differently-named agent tools - {@code
 * speak_with_cloned_voice} (ElevenLabs) and {@code sarvam_speak} (Sarvam) - into one {@code speak}
 * tool (2026-08-02, audio provider gap). A tenant with both configured previously saw both tools
 * and had to guess which one to call; a tenant with only one configured was fine, but the tool name
 * itself leaked which provider was in use, the same OpenAI-only problem {@code
 * GenerateImageToolCallback} had before {@code ImageGenerationOrchestratorService}.
 *
 * <p>Unlike image generation, ElevenLabs and Sarvam are not fully interchangeable products -
 * ElevenLabs speaks in a specific cloned brand voice (language-agnostic, the voice itself decides),
 * Sarvam speaks a requested Indian language/English with a stock voice - so there is no
 * "orchestrator that tries both" the way {@code ImageGenerationOrchestratorService} does; {@code
 * AgentToolProvider#resolveAudioTools} picks exactly ONE backend per agent (ElevenLabs first, since
 * a cloned voice is a deliberate tenant opt-in; Sarvam otherwise) and this class is a thin wrapper
 * around whichever one it was constructed with. {@code language}/{@code voice} are accepted as
 * generic optional fields and each {@link SpeakBackend} implementation ignores whichever of them
 * does not apply to it, rather than the tool schema branching on provider.
 */
public class SpeakToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(SpeakToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final SpeakBackend backend;

    public SpeakToolCallback(SpeakBackend backend) {
        this.backend = backend;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("speak")
                .description(
                        "Converts text to spoken audio and returns an audioUrl that plays INLINE in the chat. This tool "
                                + "cannot return audio bytes directly, so to let the user hear it you MUST copy the exact "
                                + "audioUrl string from this tool's OWN result into your reply, verbatim, on its own line. "
                                + "Do NOT construct, paraphrase, guess, or reuse a URL from a previous turn or from this "
                                + "description - only the literal audioUrl this specific call returns will actually play; "
                                + "any other string (even one that looks plausible) renders as dead text. "
                                + "If you have NOT called this tool during your current turn, you must never write an "
                                + "audioUrl, a path under /api/.../audio/, or any text implying audio was generated ('Here is "
                                + "the audio...', 'Generated speech', etc.) - the frontend renders any such reference as a "
                                + "real audio player, and one with no matching tool call fails to load, which is worse than "
                                + "saying nothing. If a request cannot be fulfilled literally (e.g. 'sing this' - you can "
                                + "speak text aloud but not sing a melody), either call this tool to speak the words as plain "
                                + "TTS and say plainly that's what you did, or explain in plain text why you can't - never "
                                + "fabricate a result to appear compliant. "
                                + "Use it when the user asks for a voice reply, an audio "
                                + "version of some text, or wants to hear something spoken aloud. language is an optional "
                                + "BCP-47 code (e.g. 'hi-IN', 'ta-IN', 'en-IN') honored only by providers that support "
                                + "multiple languages; voice is an optional provider-specific voice/speaker id or name. Both "
                                + "are safe to omit and are ignored by a provider that doesn't use them.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "text": {"type": "string", "description": "The text to speak"},
                        "language": {"type": "string", "description": "Optional BCP-47 language code, e.g. hi-IN"},
                        "voice": {"type": "string", "description": "Optional voice/speaker id or name"}
                    },
                    "required": ["text"]
                }
                """)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
            String text = asString(args.get("text"));
            if (text == null || text.isBlank()) {
                return errorJson("text is required");
            }
            String language = asString(args.get("language"));
            String voice = asString(args.get("voice"));
            SpeakBackend.SpokenAudio result = backend.speak(text, language, voice);
            return MAPPER.writeValueAsString(
                    Map.of(
                            "status",
                            "success",
                            "audioUrl",
                            result.audioUrl(),
                            "provider",
                            backend.providerLabel(),
                            "detail",
                            result.detail() == null ? "" : result.detail(),
                            "instructions",
                            "The audio was generated successfully. To let the user hear it you MUST include this "
                                    + "exact URL on its own line in your reply: "
                                    + result.audioUrl()
                                    + " — the chat renders it inline as an audio player."));
        } catch (Exception e) {
            log.warn("speak tool call failed via {}", backend.providerLabel(), e);
            return errorJson(
                    backend.providerLabel() + " text-to-speech call failed: " + e.getMessage());
        }
    }

    // Same bug class fixed in KnowledgeSearchToolCallback - hand-built
    // "{\"error\": \"...\"}" strings break if the exception message
    // contains a raw '"'.
    private static String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\": \"error\"}";
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
