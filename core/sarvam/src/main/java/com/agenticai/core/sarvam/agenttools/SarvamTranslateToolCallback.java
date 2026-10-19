package com.agenticai.core.sarvam.agenttools;

import com.agenticai.core.sarvam.SarvamTranslateClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Exposes {@link SarvamTranslateClient} as a native Spring AI tool - Sarvam Integration Build Plan,
 * Stage 4. Same "plain in-process ToolCallback, not an MCP server" choice every other native tool
 * in this package makes (see {@code GoogleDriveToolCallback}'s javadoc for the shared reasoning).
 *
 * <p>{@code source_language_code} is optional; omitting it (or passing {@code "auto"}) auto-detects
 * the source language, which only {@code mayura:v1} supports - so this callback switches to that
 * model itself when detection is requested, and otherwise lets {@link SarvamTranslateClient}'s own
 * default ({@code sarvam-translate:v1}, all 22 scheduled languages) apply. Callers that already
 * know the source language get full 22-language coverage; callers that don't get {@code
 * mayura:v1}'s 12-language auto- detect instead - an honest trade-off exposed once, here, rather
 * than silently picking one model and hiding the limitation from the tool schema.
 */
public class SarvamTranslateToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(SarvamTranslateToolCallback.class);
    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();
    private static final String AUTO_DETECT_MODEL = "mayura:v1";

    private final SarvamTranslateClient client;

    public SarvamTranslateToolCallback(SarvamTranslateClient client) {
        this.client = client;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("sarvam_translate")
                .description(
                        "Translates text into an Indian language (or English) using Sarvam AI. Preserves meaning, not just "
                                + "script - use this instead of translating yourself when the tenant has Sarvam configured, since "
                                + "it's a dedicated translation model. target_language_code is a BCP-47 code, e.g. 'hi-IN' Hindi, "
                                + "'ta-IN' Tamil, 'bn-IN' Bengali, 'mr-IN' Marathi, 'te-IN' Telugu, 'kn-IN' Kannada, 'gu-IN' "
                                + "Gujarati, 'ml-IN' Malayalam, 'pa-IN' Punjabi, 'od-IN' Odia, 'en-IN' English (plus several more "
                                + "scheduled Indian languages). source_language_code is optional - omit it to auto-detect.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "text": {"type": "string", "description": "The text to translate"},
                        "target_language_code": {"type": "string", "description": "BCP-47 target language code, e.g. hi-IN"},
                        "source_language_code": {"type": "string", "description": "Optional BCP-47 source language code - omit to auto-detect"}
                    },
                    "required": ["text", "target_language_code"]
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
            String target = asString(args.get("target_language_code"));
            if (text == null || target == null) {
                return "{\"error\": \"text and target_language_code are required\"}";
            }
            String source = asString(args.get("source_language_code"));
            boolean autoDetect =
                    source == null || source.isBlank() || "auto".equalsIgnoreCase(source);
            String model = autoDetect ? AUTO_DETECT_MODEL : null;
            String resolvedSource = autoDetect ? "auto" : source;
            SarvamTranslateClient.TranslationResult result =
                    client.translate(text, resolvedSource, target, model, null);
            return MAPPER.writeValueAsString(
                    Map.of(
                            "translatedText",
                                    result.translatedText() == null ? "" : result.translatedText(),
                            "detectedSourceLanguage",
                                    result.sourceLanguageCode() == null
                                            ? ""
                                            : result.sourceLanguageCode()));
        } catch (Exception e) {
            log.warn("sarvam_translate tool call failed", e);
            return errorJson("Sarvam translate call failed: " + e.getMessage());
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
