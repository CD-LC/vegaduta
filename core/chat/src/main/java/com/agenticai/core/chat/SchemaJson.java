package com.agenticai.core.chat;

import com.google.genai.types.Schema;
import java.util.Map;
import org.springframework.ai.util.JacksonUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one place that owns the JSON mapper Spring AI's {@code GoogleGenAiChatModel} itself uses to
 * turn tool schemas into Gemini request schemas (ADR-0012 P1b, P3), so {@link
 * GeminiSchemaSelfHealer} validates against the very same configuration. Spring AI 1.x exposed it
 * as {@code ModelOptionsUtils.OBJECT_MAPPER} (Jackson 2); Spring AI 2 removed that and the model
 * keeps a private Jackson 3 mapper: {@code JacksonUtils.getDefaultJsonMapper()} rebuilt with a
 * mix-in that makes {@code Schema} deserialise through its builder. This reproduces exactly that
 * construction (verified against spring-ai-google-genai 2.0.1's {@code GoogleGenAiChatModel}), so a
 * schema that would crash the real request-building call fails here too.
 */
final class SchemaJson {

    private static final JsonMapper MAPPER =
            JacksonUtils.getDefaultJsonMapper()
                    .rebuild()
                    .addMixIn(Schema.class, SchemaMixin.class)
                    .build();

    private SchemaJson() {}

    /** Serialises a map the way Spring AI's Gemini model does. */
    static String write(Map<String, Object> map) throws JacksonException {
        return MAPPER.writeValueAsString(map);
    }

    /**
     * Deserialises with Spring AI's own Gemini mapper; throws exactly what its {@code readValue}
     * throws.
     */
    static <T> T read(String json, Class<T> type) throws JacksonException {
        return MAPPER.readValue(json, type);
    }

    /**
     * Same mix-in Spring AI's Gemini model registers: Gemini's {@code Schema} is built through its
     * builder.
     */
    @JsonDeserialize(builder = Schema.Builder.class)
    private abstract static class SchemaMixin {}
}
