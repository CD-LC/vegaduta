package com.agenticai.core.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.Map;
import org.springframework.ai.model.ModelOptionsUtils;

/**
 * The one place that touches Spring AI's shared {@code ModelOptionsUtils.OBJECT_MAPPER} (ADR-0012
 * P1b). That mapper is what {@code GoogleGenAiChatModel} itself uses to build Gemini request
 * schemas, so the self-healer must validate against the very same configuration (see {@link
 * GeminiSchemaSelfHealer}). Spring AI 2 moves it to Jackson 3, so P3 changes this class only.
 */
final class SchemaJson {

    private SchemaJson() {}

    /** Serialises a map the way Spring AI's own model classes do. */
    static String write(Map<String, Object> map) throws JsonProcessingException {
        return ModelOptionsUtils.OBJECT_MAPPER.writeValueAsString(map);
    }

    /**
     * Deserialises with Spring AI's own mapper; throws exactly what {@code OBJECT_MAPPER.readValue}
     * throws.
     */
    static <T> T read(String json, Class<T> type) throws JsonProcessingException {
        return ModelOptionsUtils.OBJECT_MAPPER.readValue(json, type);
    }
}
