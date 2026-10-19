package com.agenticai.core.sarvam.agenttools;

import com.agenticai.core.sarvam.SarvamTransliterationClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Exposes {@link SarvamTransliterationClient} as a native Spring AI tool.
 *
 * <p>Added alongside {@code sarvam_identify_language} (July 2026, prompted by Sarvam's own official
 * MCP server release at mcp.sarvam.ai/docs.sarvam.ai exposing 14 tools) - both underlying clients
 * were already built in Sarvam Stage 3 but never wrapped as agent-callable tools. Rather than
 * wiring the whole third-party MCP server in (it's a local-only Python/uvx subprocess package that
 * writes audio/document output to a fixed local filesystem path, built for individual developer
 * tools like Claude Desktop/Cursor - not a multi-tenant cloud backend), this platform keeps native,
 * tenant-scoped, call-logged wrappers around the Sarvam REST APIs it already has clients for, so
 * the consumer-facing surface stays exactly what it already is: one Sarvam API key on the Providers
 * page, one per-agent attach toggle - see {@link
 * com.agenticai.core.agents.tools.AgentToolProvider#resolveSarvamTools}.
 */
public class SarvamTransliterateToolCallback implements ToolCallback {

    private static final Logger log =
            LoggerFactory.getLogger(SarvamTransliterateToolCallback.class);
    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private final SarvamTransliterationClient client;

    public SarvamTransliterateToolCallback(SarvamTransliterationClient client) {
        this.client = client;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("sarvam_transliterate")
                .description(
                        "Converts text from one script to another while PRESERVING PRONUNCIATION, e.g. 'namaste' -> 'नमस्ते' "
                                + "or 'नमस्ते' -> 'namaste'. Use this instead of sarvam_translate when the goal is script "
                                + "conversion (same words, different alphabet), not meaning conversion (different words). "
                                + "source_language_code and target_language_code are BCP-47 codes, e.g. 'en-IN', 'hi-IN', 'ta-IN'.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "input": {"type": "string", "description": "The text to transliterate"},
                        "source_language_code": {"type": "string", "description": "BCP-47 source language code, e.g. en-IN"},
                        "target_language_code": {"type": "string", "description": "BCP-47 target language code, e.g. hi-IN"}
                    },
                    "required": ["input", "source_language_code", "target_language_code"]
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
            String source = asString(args.get("source_language_code"));
            String target = asString(args.get("target_language_code"));
            if (input == null || source == null || target == null) {
                return "{\"error\": \"input, source_language_code and target_language_code are required\"}";
            }
            SarvamTransliterationClient.TransliterationResult result =
                    client.transliterate(input, source, target);
            return MAPPER.writeValueAsString(
                    Map.of(
                            "transliteratedText",
                                    result.transliteratedText() == null
                                            ? ""
                                            : result.transliteratedText(),
                            "sourceLanguageCode",
                                    result.sourceLanguageCode() == null
                                            ? ""
                                            : result.sourceLanguageCode()));
        } catch (Exception e) {
            log.warn("sarvam_transliterate tool call failed", e);
            return errorJson("Sarvam transliterate call failed: " + e.getMessage());
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
