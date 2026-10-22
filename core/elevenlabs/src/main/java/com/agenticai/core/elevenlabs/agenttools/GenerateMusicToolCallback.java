package com.agenticai.core.elevenlabs.agenttools;

import com.agenticai.core.json.JsonMappers;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code generate_music} - the music sibling of {@code GenerateImageToolCallback}, same contract:
 * quota enforced before the billable call (inside {@link MusicGenerationService}), bytes stored,
 * and a URL handed back for the model to repeat verbatim - never raw audio in a tool result, and
 * never a raw upstream error.
 */
public class GenerateMusicToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(GenerateMusicToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    /**
     * The chat renders any URL with this prefix as an inline audio player - see web's MediaCard.
     */
    public static final String TRACK_URL_PREFIX = "/api/musicgen/tracks/";

    private final MusicGenerationService musicGenerationService;

    public GenerateMusicToolCallback(MusicGenerationService musicGenerationService) {
        this.musicGenerationService = musicGenerationService;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("generate_music")
                .description(
                        "Composes an original piece of music from a text description and returns an audioUrl that plays "
                                + "INLINE in the chat. This tool cannot return audio bytes directly, so to let the user hear it you "
                                + "MUST include the returned audioUrl verbatim in your reply (the chat renders any "
                                + "/api/musicgen/tracks/... URL as an audio player). Use it when the user asks for music, a song, "
                                + "a jingle, a soundtrack, a beat, background or hold music. Do NOT use it to read text aloud - "
                                + "that is the speak tool. Describe genre, mood, tempo and instruments in the prompt; do not name "
                                + "real artists or copyrighted songs.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "prompt": {"type": "string", "description": "What the music should sound like: genre, mood, tempo, instruments, and lyrics or theme if it should be sung"},
                        "durationSeconds": {"type": "integer", "description": "Optional length in seconds. Defaults to 20; longer requests are shortened to the workspace's plan limit."},
                        "instrumental": {"type": "boolean", "description": "Optional. true for no vocals. Defaults to false."}
                    },
                    "required": ["prompt"]
                }
                """)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
            String prompt = asString(args.get("prompt"));
            if (prompt == null || prompt.isBlank()) {
                return errorJson("prompt is required");
            }
            MusicGenerationService.GeneratedTrack track =
                    musicGenerationService.generate(
                            prompt.trim(),
                            asInteger(args.get("durationSeconds")),
                            Boolean.TRUE.equals(asBoolean(args.get("instrumental"))));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("audioUrl", TRACK_URL_PREFIX + track.id());
            result.put("durationSeconds", track.durationSeconds());
            result.put("model", track.model());
            result.put(
                    "instructions",
                    "Include the audioUrl verbatim in your reply so the chat shows an audio player. "
                            + "Do not invent, shorten or rewrite the URL.");
            return MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            log.warn("generate_music tool call failed", e);
            return errorJson(consumerReason(e));
        }
    }

    /**
     * Written at the model, not the user - same posture as {@code
     * GenerateImageToolCallback#consumerReason}.
     */
    private static String consumerReason(Exception e) {
        if (e instanceof MusicGenerationQuotaExceededException quota) {
            return quota.getMessage() + " Tell the user this and do not retry the tool.";
        }
        if (e instanceof MusicGenNotConfiguredException) {
            return "Music generation is not switched on for this workspace. It needs an ElevenLabs API key added on the "
                    + "Providers page of the console. Tell the user exactly that, and do not retry the tool.";
        }
        return "Music generation could not be completed just now. Tell the user the music could not be generated and "
                + "that they can try again in a moment, perhaps with a simpler description that names no real artist or "
                + "song; answer the rest of their message normally.";
    }

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

    private static Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return (int) Math.round(Double.parseDouble(text.trim()));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Boolean asBoolean(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        return value instanceof String text ? Boolean.parseBoolean(text.trim()) : null;
    }
}
