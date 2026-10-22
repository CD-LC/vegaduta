package com.agenticai.core.chat.structured;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;

class StructuredOutputSchemaTest {

    private static final String STRICT_SCHEMA =
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\","
                    + "\"properties\":{\"a\":{\"type\":\"string\"}},\"required\":[\"a\"],\"additionalProperties\":false}";
    private static final String LOOSE_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"},\"b\":{\"type\":\"integer\"}},\"required\":[\"a\"]}";

    @Test
    void openAiStrictSchemaOnSupportedModel() {
        OpenAiChatOptions original = OpenAiChatOptions.builder().model("gpt-4o-mini").build();
        OpenAiChatOptions out =
                StructuredOutput.withJsonSchema(original, "my verdict!", STRICT_SCHEMA, true);
        assertThat(out).isNotSameAs(original);
        assertThat(original.getResponseFormat()).isNull();
        assertThat(out.getResponseFormat().getType()).isEqualTo(ResponseFormat.Type.JSON_SCHEMA);
        assertThat(out.getResponseFormat().getJsonSchema().getName()).isEqualTo("my_verdict_");
        assertThat(out.getResponseFormat().getJsonSchema().getStrict()).isTrue();
    }

    @Test
    void looseSchemaIsSentNonStrict() {
        OpenAiChatOptions out =
                StructuredOutput.withJsonSchema(
                        OpenAiChatOptions.builder().model("gpt-5").build(),
                        "x",
                        LOOSE_SCHEMA,
                        true);
        assertThat(out.getResponseFormat().getType()).isEqualTo(ResponseFormat.Type.JSON_SCHEMA);
        assertThat(out.getResponseFormat().getJsonSchema().getStrict()).isFalse();
    }

    @Test
    void unsupportedOpenAiCompatModelFallsBackToJsonObject() {
        OpenAiChatOptions out =
                StructuredOutput.withJsonSchema(
                        OpenAiChatOptions.builder().model("llama-3.3-70b").build(),
                        "x",
                        STRICT_SCHEMA,
                        true);
        assertThat(out.getResponseFormat().getType()).isEqualTo(ResponseFormat.Type.JSON_OBJECT);
    }

    @Test
    void killSwitchFallsBackToJsonObject() {
        OpenAiChatOptions out =
                StructuredOutput.withJsonSchema(
                        OpenAiChatOptions.builder().model("gpt-4o").build(),
                        "x",
                        STRICT_SCHEMA,
                        false);
        assertThat(out.getResponseFormat().getType()).isEqualTo(ResponseFormat.Type.JSON_OBJECT);
    }

    @Test
    void invalidSchemaFallsBackToJsonObject() {
        OpenAiChatOptions out =
                StructuredOutput.withJsonSchema(
                        OpenAiChatOptions.builder().model("gpt-4o").build(), "x", "not json", true);
        assertThat(out.getResponseFormat().getType()).isEqualTo(ResponseFormat.Type.JSON_OBJECT);
    }

    @Test
    void geminiGetsCleanedResponseSchema() {
        GoogleGenAiChatOptions original =
                GoogleGenAiChatOptions.builder().model("gemini-2.5-flash").build();
        GoogleGenAiChatOptions out =
                StructuredOutput.withJsonSchema(original, "x", STRICT_SCHEMA, true);
        assertThat(out.getResponseMimeType()).isEqualTo("application/json");
        assertThat(out.getResponseSchema())
                .contains("\"properties\"")
                .doesNotContain("$schema")
                .doesNotContain("additionalProperties");
        assertThat(original.getResponseSchema()).isNull();
    }

    @Test
    void geminiSchemaWithRefsFallsBackToJsonMode() {
        GoogleGenAiChatOptions out =
                StructuredOutput.withJsonSchema(
                        GoogleGenAiChatOptions.builder().model("gemini-2.5-pro").build(),
                        "x",
                        "{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"#/$defs/A\"}},\"$defs\":{\"A\":{\"type\":\"string\"}}}",
                        true);
        assertThat(out.getResponseMimeType()).isEqualTo("application/json");
        assertThat(out.getResponseSchema()).isNull();
    }

    @Test
    void unsupportedOpenAiVariantsKeepJsonObject() {
        for (String model :
                new String[] {
                    "o1-mini",
                    "o1-preview",
                    "gpt-4o-2024-05-13",
                    "gpt-4o-audio-preview",
                    "gpt-4-turbo",
                    "gemini-2.5-flash"
                }) {
            assertThat(
                            StructuredOutput.supportsJsonSchema(
                                    OpenAiChatOptions.builder().model(model).build()))
                    .as(model)
                    .isFalse();
        }
        for (String model :
                new String[] {
                    "gpt-4o",
                    "gpt-4o-2024-08-06",
                    "gpt-4o-mini",
                    "gpt-4.1-mini",
                    "gpt-5-mini",
                    "o3-mini",
                    "o4-mini",
                    "o1"
                }) {
            assertThat(
                            StructuredOutput.supportsJsonSchema(
                                    OpenAiChatOptions.builder().model(model).build()))
                    .as(model)
                    .isTrue();
        }
    }

    @Test
    void geminiNonTextAndOldModelsUnsupported() {
        assertThat(
                        StructuredOutput.supportsJsonSchema(
                                GoogleGenAiChatOptions.builder().model("gemini-1.0-pro").build()))
                .isFalse();
        assertThat(
                        StructuredOutput.supportsJsonSchema(
                                GoogleGenAiChatOptions.builder()
                                        .model("gemini-2.5-flash-image")
                                        .build()))
                .isFalse();
        assertThat(
                        StructuredOutput.supportsJsonSchema(
                                GoogleGenAiChatOptions.builder().model("gemini-2.5-flash").build()))
                .isTrue();
    }

    @Test
    void propertyNamedPropertiesIsNotAKeyword() {
        String schema =
                "{\"type\":\"object\",\"properties\":{\"properties\":{\"type\":\"string\"},"
                        + "\"additionalProperties\":{\"type\":\"string\"}},"
                        + "\"required\":[\"properties\",\"additionalProperties\"],\"additionalProperties\":false}";
        OpenAiChatOptions out =
                StructuredOutput.withJsonSchema(
                        OpenAiChatOptions.builder().model("gpt-4o").build(), "x", schema, true);
        assertThat(out.getResponseFormat().getJsonSchema().getStrict()).isTrue();
        GoogleGenAiChatOptions g =
                StructuredOutput.withJsonSchema(
                        GoogleGenAiChatOptions.builder().model("gemini-2.5-flash").build(),
                        "x",
                        schema,
                        true);
        // the property NAMED additionalProperties survives; the keyword does not
        assertThat(g.getResponseSchema())
                .contains("\"additionalProperties\":{\"type\":\"string\"}");
        assertThat(g.getResponseSchema()).doesNotContain("\"additionalProperties\":false");
    }

    @Test
    void ollamaAndBedrockStyleOptionsUntouched() {
        org.springframework.ai.chat.prompt.ChatOptions plain =
                org.springframework.ai.chat.prompt.ChatOptions.builder().model("llama3").build();
        assertThat(StructuredOutput.withJsonSchema(plain, "x", STRICT_SCHEMA, true))
                .isSameAs(plain);
    }

    @Test
    void anthropicIsUntouched() {
        AnthropicChatOptions anthropic =
                AnthropicChatOptions.builder().model("claude-sonnet-4-5").build();
        assertThat(StructuredOutput.supportsJsonSchema(anthropic)).isFalse();
        assertThat(StructuredOutput.withJsonSchema(anthropic, "x", STRICT_SCHEMA, true))
                .isSameAs(anthropic);
    }
}
