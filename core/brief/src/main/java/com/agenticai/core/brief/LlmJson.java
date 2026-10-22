package com.agenticai.core.brief;

import com.agenticai.core.chat.structured.StructuredOutput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parses a model reply into a JSON object. A thin, package-private facade over the platform-wide
 * {@link StructuredOutput} helper (fence stripping, prose before/after the JSON, braces inside
 * string values), kept so the planner/tune call sites and their tests read the same as before.
 */
final class LlmJson {

    private LlmJson() {}

    static String stripFence(String raw) {
        return StructuredOutput.stripCodeFence(raw);
    }

    /**
     * @return the parsed object, or null when {@code raw} holds no parseable JSON object.
     */
    static JsonNode parseObject(ObjectMapper mapper, String raw) {
        return StructuredOutput.readObject(mapper, raw).orElse(null);
    }
}
