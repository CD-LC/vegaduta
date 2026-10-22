package com.agenticai.core.sarvam.agenttools;

import com.agenticai.core.sarvam.SarvamLanguageIdClient;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

/**
 * Exposes {@link SarvamLanguageIdClient} as a native Spring AI tool - see {@link
 * SarvamTransliterateToolCallback}'s javadoc for why this is a thin native wrapper rather than a
 * pass-through to Sarvam's official MCP server.
 */
public class SarvamIdentifyLanguageToolCallback implements ToolCallback {

    private static final Logger log =
            LoggerFactory.getLogger(SarvamIdentifyLanguageToolCallback.class);
    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private final SarvamLanguageIdClient client;

    public SarvamIdentifyLanguageToolCallback(SarvamLanguageIdClient client) {
        this.client = client;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("sarvam_identify_language")
                .description(
                        "Detects the language AND script of a piece of text, e.g. returns language_code 'hi-IN' and "
                                + "script_code 'Deva' for Devanagari Hindi. Use this when you need to know what language a user "
                                + "wrote in before deciding whether to translate, transliterate, or reply in kind.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "input": {"type": "string", "description": "The text whose language/script should be identified"}
                    },
                    "required": ["input"]
                }
                """)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);
            String input = asString(args.get("input"));
            if (input == null || input.isBlank()) {
                return "{\"error\": \"input is required\"}";
            }
            SarvamLanguageIdClient.LanguageIdResult result = client.identify(input);
            return MAPPER.writeValueAsString(
                    Map.of(
                            "languageCode",
                                    result.languageCode() == null ? "" : result.languageCode(),
                            "scriptCode", result.scriptCode() == null ? "" : result.scriptCode()));
        } catch (Exception e) {
            log.warn("sarvam_identify_language tool call failed", e);
            return errorJson("Sarvam language identification call failed: " + e.getMessage());
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
