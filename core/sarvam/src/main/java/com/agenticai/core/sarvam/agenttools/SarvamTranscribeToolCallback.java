package com.agenticai.core.sarvam.agenttools;

import com.agenticai.core.sarvam.SarvamSpeechToTextClient;
import java.util.Base64;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

/**
 * Exposes {@link SarvamSpeechToTextClient} as a native Spring AI tool - Sarvam Integration Build
 * Plan, Stage 4. Takes the audio as a base64 string tool argument rather than a file reference,
 * since a plain-text {@code ToolCallback} argument is the only channel an LLM tool call actually
 * has to hand this callback bytes - there is no shared "already-uploaded file" registry a tool
 * argument could point at instead. This is deliberately a general-purpose, short-clip tool (a
 * base64 string in a JSON tool-call argument has practical size limits well below a real voice
 * message), not the real inbound-voice-note path: Stage 5's planned omnichannel work reads
 * WhatsApp/Telegram audio attachments directly off the channel webhook and calls {@link
 * SarvamSpeechToTextClient} from there, never through this tool.
 *
 * <p>{@code mode=translate} (only honored by {@code saaras:v3}, this client's default) is worth
 * calling out in the tool description since it turns "transcribe this audio" into "transcribe AND
 * translate to English" in one call.
 */
public class SarvamTranscribeToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(SarvamTranscribeToolCallback.class);
    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private final SarvamSpeechToTextClient client;

    public SarvamTranscribeToolCallback(SarvamSpeechToTextClient client) {
        this.client = client;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("sarvam_transcribe")
                .description(
                        "Transcribes a short audio clip (base64-encoded WAV/MP3/etc., not a URL) to text using Sarvam AI, "
                                + "with support for Indian languages plus English. mode='translate' transcribes AND translates "
                                + "the result to English in one call (e.g. a Hindi voice clip becomes English text directly); "
                                + "mode='transcribe' (default) keeps the original spoken language. language_code is optional - "
                                + "omit it to auto-detect the spoken language.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "audio_base64": {"type": "string", "description": "Base64-encoded audio file bytes"},
                        "filename": {"type": "string", "description": "Optional original filename, e.g. voice-note.wav"},
                        "mode": {"type": "string", "enum": ["transcribe", "translate", "verbatim", "translit", "codemix"], "description": "Optional, defaults to transcribe"},
                        "language_code": {"type": "string", "description": "Optional BCP-47 code of the spoken language - omit to auto-detect"}
                    },
                    "required": ["audio_base64"]
                }
                """)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
            String audioBase64 = asString(args.get("audio_base64"));
            if (audioBase64 == null || audioBase64.isBlank()) {
                return "{\"error\": \"audio_base64 is required\"}";
            }
            byte[] audioBytes;
            try {
                audioBytes = Base64.getDecoder().decode(audioBase64);
            } catch (IllegalArgumentException decodeError) {
                return errorJson("audio_base64 is not valid base64: " + decodeError.getMessage());
            }
            String filename = asString(args.get("filename"));
            String mode = asString(args.get("mode"));
            String languageCode = asString(args.get("language_code"));
            SarvamSpeechToTextClient.TranscriptionResult result =
                    client.transcribe(audioBytes, filename, null, mode, languageCode);
            return MAPPER.writeValueAsString(
                    Map.of(
                            "transcript", result.transcript() == null ? "" : result.transcript(),
                            "languageCode",
                                    result.languageCode() == null ? "" : result.languageCode()));
        } catch (Exception e) {
            log.warn("sarvam_transcribe tool call failed", e);
            return errorJson("Sarvam speech-to-text call failed: " + e.getMessage());
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
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
}
