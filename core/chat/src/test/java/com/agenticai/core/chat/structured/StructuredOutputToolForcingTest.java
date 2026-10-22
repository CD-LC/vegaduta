package com.agenticai.core.chat.structured;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.chat.CallerRunsToolLoop;
import com.anthropic.models.messages.ToolChoice;
import com.anthropic.models.messages.ToolChoiceTool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.function.FunctionToolCallback;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Anthropic forced-tool structured output and Ollama {@code format}: option shaping, schema gating,
 * unwrapping.
 */
class StructuredOutputToolForcingTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();
    private static final String SCHEMA =
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\","
                + "\"properties\":{\"verdict\":{\"type\":\"string\"}},\"required\":[\"verdict\"]}";

    private static AnthropicChatOptions anthropic(String model) {
        return AnthropicChatOptions.builder().model(model).maxTokens(512).build();
    }

    private static JsonNode schema() {
        return StructuredOutput.anthropicToolInputSchema(SCHEMA);
    }

    // ------------------------------------------------------------ model gate

    @Test
    void modelAllowlistCoversClaude3AndLaterFamiliesOnly() {
        assertThat(StructuredOutput.supportsAnthropicToolForcing(anthropic("claude-sonnet-4-5")))
                .isTrue();
        assertThat(StructuredOutput.supportsAnthropicToolForcing(anthropic("claude-sonnet-5")))
                .isTrue();
        assertThat(
                        StructuredOutput.supportsAnthropicToolForcing(
                                anthropic("claude-opus-4-1-20250805")))
                .isTrue();
        assertThat(StructuredOutput.supportsAnthropicToolForcing(anthropic("claude-haiku-4-5")))
                .isTrue();
        assertThat(
                        StructuredOutput.supportsAnthropicToolForcing(
                                anthropic("claude-3-5-sonnet-20241022")))
                .isTrue();
        assertThat(StructuredOutput.supportsAnthropicToolForcing(anthropic("CLAUDE-SONNET-4")))
                .isTrue();
        assertThat(StructuredOutput.supportsAnthropicToolForcing(anthropic("claude-2.1")))
                .isFalse();
        assertThat(StructuredOutput.supportsAnthropicToolForcing(anthropic("claude-instant-1.2")))
                .isFalse();
        assertThat(StructuredOutput.supportsAnthropicToolForcing(anthropic("gpt-4o"))).isFalse();
        assertThat(StructuredOutput.supportsAnthropicToolForcing(null)).isFalse();
        // Spring AI 2 fills in a default Claude model when none is set, so unset options qualify too.
        assertThat(
                        StructuredOutput.supportsAnthropicToolForcing(
                                AnthropicChatOptions.builder().build()))
                .isTrue();
        assertThat(
                        StructuredOutput.supportsAnthropicToolForcing(
                                OpenAiChatOptions.builder().model("claude-sonnet-4-5").build()))
                .isFalse();
    }

    // ------------------------------------------------------ schema representability

    @Test
    void objectRootSchemaIsRepresentableAndDollarSchemaDropped() {
        JsonNode out = schema();
        assertThat(out).isNotNull();
        assertThat(out.has("$schema")).isFalse();
        assertThat(out.get("type").asString()).isEqualTo("object");
        assertThat(out.get("properties").has("verdict")).isTrue();
    }

    @Test
    void notRepresentableSchemasReturnNull() {
        assertThat(StructuredOutput.anthropicToolInputSchema(null)).isNull();
        assertThat(StructuredOutput.anthropicToolInputSchema("  ")).isNull();
        assertThat(StructuredOutput.anthropicToolInputSchema("not json")).isNull();
        assertThat(StructuredOutput.anthropicToolInputSchema("[1,2]")).isNull();
        // array root
        assertThat(
                        StructuredOutput.anthropicToolInputSchema(
                                "{\"type\":\"array\",\"items\":{\"type\":\"string\"}}"))
                .isNull();
        // no type
        assertThat(
                        StructuredOutput.anthropicToolInputSchema(
                                "{\"properties\":{\"a\":{\"type\":\"string\"}}}"))
                .isNull();
        // union types
        assertThat(StructuredOutput.anthropicToolInputSchema("{\"type\":[\"object\",\"null\"]}"))
                .isNull();
        // top-level combinators
        assertThat(
                        StructuredOutput.anthropicToolInputSchema(
                                "{\"type\":\"object\",\"anyOf\":[{\"required\":[\"a\"]},{\"required\":[\"b\"]}]}"))
                .isNull();
        assertThat(StructuredOutput.anthropicToolInputSchema("{\"type\":\"object\",\"oneOf\":[]}"))
                .isNull();
        assertThat(StructuredOutput.anthropicToolInputSchema("{\"type\":\"object\",\"allOf\":[]}"))
                .isNull();
        assertThat(StructuredOutput.anthropicToolInputSchema("{\"type\":\"object\",\"enum\":[{}]}"))
                .isNull();
        // non-local $ref
        assertThat(
                        StructuredOutput.anthropicToolInputSchema(
                                "{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"https://example.com/x.json\"}}}"))
                .isNull();
        // oversized
        String big =
                "{\"type\":\"object\",\"description\":\""
                        + "x".repeat(StructuredOutput.MAX_TOOL_INPUT_SCHEMA_CHARS)
                        + "\"}";
        assertThat(StructuredOutput.anthropicToolInputSchema(big)).isNull();
    }

    @Test
    void localRefsAreRepresentable() {
        String s =
                "{\"type\":\"object\",\"properties\":{\"a\":{\"$ref\":\"#/$defs/A\"}},"
                        + "\"$defs\":{\"A\":{\"type\":\"string\"}}}";
        assertThat(StructuredOutput.anthropicToolInputSchema(s)).isNotNull();
    }

    @Test
    void toolNameIsSanitizedToAnthropicsCharset() {
        assertThat(StructuredOutput.anthropicToolName("my verdict!")).isEqualTo("my_verdict_");
        assertThat(StructuredOutput.anthropicToolName(null)).isEqualTo("structured_output");
        assertThat(StructuredOutput.anthropicToolName("x".repeat(100))).hasSize(64);
    }

    // ------------------------------------------------------------ options shaping

    @Test
    void forcedToolIsAppliedToACopyAndNeverMutatesTheInput() {
        AnthropicChatOptions original = anthropic("claude-sonnet-4-5");
        AnthropicChatOptions out =
                StructuredOutput.withAnthropicForcedTool(original, "verdict", schema());

        assertThat(out).isNotSameAs(original);
        assertThat(original.getToolChoice()).isNull();
        assertThat(original.getToolCallbacks()).isNullOrEmpty();

        assertThat(out.getToolChoice().isTool()).isTrue();
        ToolChoiceTool choice = out.getToolChoice().asTool();
        assertThat(choice.name()).isEqualTo("verdict");
        assertThat(choice.disableParallelToolUse()).contains(true);
        assertThat(CallerRunsToolLoop.isSet(out)).isTrue();
        assertThat(CallerRunsToolLoop.isSet(original)).isFalse();
        assertThat(out.getThinking()).isNull();
        assertThat(out.getToolCallbacks()).hasSize(1);
        var def = out.getToolCallbacks().get(0).getToolDefinition();
        assertThat(def.name()).isEqualTo("verdict");
        assertThat(def.inputSchema()).contains("\"verdict\"").doesNotContain("$schema");
        // model and token settings survive the copy
        assertThat(out.getModel()).isEqualTo("claude-sonnet-4-5");
        assertThat(out.getMaxTokens()).isEqualTo(512);
    }

    @Test
    void thinkingEnabledFallsBackToTheUnchangedOptionsInstance() {
        AnthropicChatOptions thinking =
                AnthropicChatOptions.builder()
                        .model("claude-sonnet-4-5")
                        .maxTokens(4096)
                        .thinkingEnabled(2048)
                        .build();
        assertThat(StructuredOutput.withAnthropicForcedTool(thinking, "verdict", schema()))
                .isSameAs(thinking);
    }

    @Test
    void thinkingDisabledDoesNotBlockForcing() {
        AnthropicChatOptions off =
                AnthropicChatOptions.builder()
                        .model("claude-sonnet-4-5")
                        .maxTokens(512)
                        .thinkingDisabled()
                        .build();
        AnthropicChatOptions out =
                StructuredOutput.withAnthropicForcedTool(off, "verdict", schema());
        assertThat(out).isNotSameAs(off);
        assertThat(out.getToolChoice()).isNotNull();
        // The SDK keeps an explicit "disabled" thinking parameter instead of dropping it.
        assertThat(out.getThinking().isDisabled()).isTrue();
    }

    @Test
    void requestAlreadyCarryingToolsOrToolChoiceIsLeftAlone() {
        AnthropicChatOptions withTool =
                AnthropicChatOptions.builder()
                        .model("claude-sonnet-4-5")
                        .maxTokens(512)
                        .toolCallbacks(
                                List.of(
                                        FunctionToolCallback.<Map<String, Object>, String>builder(
                                                        "real", in -> "ok")
                                                .description("d")
                                                .inputType(Map.class)
                                                .build()))
                        .build();
        assertThat(StructuredOutput.withAnthropicForcedTool(withTool, "verdict", schema()))
                .isSameAs(withTool);

        AnthropicChatOptions withChoice =
                anthropic("claude-sonnet-4-5")
                        .mutate()
                        .toolChoice(
                                ToolChoice.ofTool(ToolChoiceTool.builder().name("other").build()))
                        .build();
        assertThat(StructuredOutput.withAnthropicForcedTool(withChoice, "verdict", schema()))
                .isSameAs(withChoice);
    }

    @Test
    void unsupportedModelOrMissingSchemaOrNameIsANoOp() {
        AnthropicChatOptions old = anthropic("claude-2.1");
        assertThat(StructuredOutput.withAnthropicForcedTool(old, "verdict", schema()))
                .isSameAs(old);
        AnthropicChatOptions ok = anthropic("claude-sonnet-4-5");
        assertThat(StructuredOutput.withAnthropicForcedTool(ok, "verdict", null)).isSameAs(ok);
        assertThat(StructuredOutput.withAnthropicForcedTool(ok, " ", schema())).isSameAs(ok);
        assertThat(StructuredOutput.withAnthropicForcedTool(ok, null, schema())).isSameAs(ok);
        OpenAiChatOptions openAi = OpenAiChatOptions.builder().model("gpt-4o").build();
        assertThat(StructuredOutput.withAnthropicForcedTool(openAi, "verdict", schema()))
                .isSameAs(openAi);
        assertThat(
                        StructuredOutput.<AnthropicChatOptions>withAnthropicForcedTool(
                                null, "verdict", schema()))
                .isNull();
    }

    // --------------------------------------------------------------- unwrapping

    private static Generation toolCall(String name, String args) {
        return new Generation(
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                                List.of(
                                        new AssistantMessage.ToolCall(
                                                "toolu_1", "function", name, args)))
                        .build(),
                ChatGenerationMetadata.builder().finishReason("tool_use").build());
    }

    private static Generation text(String t) {
        return new Generation(
                AssistantMessage.builder().content(t).build(),
                ChatGenerationMetadata.builder().finishReason("end_turn").build());
    }

    @Test
    void toolInputBecomesTheReplyTextAndPreambleIsDropped() throws Exception {
        ChatResponse raw =
                new ChatResponse(
                        List.of(
                                text("Sure, here you go"),
                                toolCall("verdict", "{\"verdict\": \"ok\"}")));
        ChatResponse out = StructuredOutput.unwrapForcedTool(raw, "verdict");

        assertThat(out.getResults()).hasSize(1);
        String reply = out.getResult().getOutput().getText();
        assertThat(MAPPER.readTree(reply).get("verdict").asString()).isEqualTo("ok");
        assertThat(out.getResult().getOutput().hasToolCalls()).isFalse();
        assertThat(out.getResult().getMetadata().getFinishReason()).isEqualTo("end_turn");
    }

    @Test
    void modelThatAnswersInTextReturnsTheResponseUntouchedForPromptAndValidate() {
        ChatResponse raw = new ChatResponse(List.of(text("{\"verdict\":\"ok\"}")));
        assertThat(StructuredOutput.unwrapForcedTool(raw, "verdict")).isSameAs(raw);
    }

    @Test
    void aCallToADifferentToolIsNotOurs() {
        ChatResponse raw = new ChatResponse(List.of(toolCall("something_else", "{}")));
        assertThat(StructuredOutput.unwrapForcedTool(raw, "verdict")).isSameAs(raw);
    }

    @Test
    void toolCallWithUnusableInputYieldsEmptyTextSoCallerParsePathHandlesIt() {
        for (String bad : new String[] {null, "", "not json", "[1]", "\"str\""}) {
            ChatResponse out =
                    StructuredOutput.unwrapForcedTool(
                            new ChatResponse(List.of(toolCall("verdict", bad))), "verdict");
            assertThat(out.getResult().getOutput().getText()).as("input=%s", bad).isEmpty();
            assertThat(out.getResult().getOutput().hasToolCalls()).isFalse();
        }
    }

    @Test
    void unwrapIsNullSafe() {
        assertThat(StructuredOutput.unwrapForcedTool(null, "verdict")).isNull();
        ChatResponse raw = new ChatResponse(List.of(text("x")));
        assertThat(StructuredOutput.unwrapForcedTool(raw, null)).isSameAs(raw);
    }

    // ------------------------------------------------------------- Ollama format

    @Test
    void ollamaFormatSetsNonStrictJsonSchemaOnACopy() {
        OpenAiChatOptions original = OpenAiChatOptions.builder().model("llama3.1").build();
        OpenAiChatOptions out = StructuredOutput.withOllamaFormat(original, "my verdict", SCHEMA);
        assertThat(out).isNotSameAs(original);
        assertThat(original.getResponseFormat()).isNull();
        assertThat(out.getResponseFormat().getType()).isEqualTo(ResponseFormat.Type.JSON_SCHEMA);
        // Spring AI 2 carries the schema as a JSON string; the SDK model supplies the schema name.
        assertThat(out.getResponseFormat().getJsonSchema()).contains("\"verdict\"");
        assertThat(out.getResponseFormat().getStrict()).isFalse();
        assertThat(out.getModel()).isEqualTo("llama3.1");
    }

    @Test
    void ollamaFormatLeavesUnusableCasesUnchanged() {
        OpenAiChatOptions plain = OpenAiChatOptions.builder().model("llama3.1").build();
        assertThat(StructuredOutput.withOllamaFormat(plain, "x", null)).isSameAs(plain);
        assertThat(StructuredOutput.withOllamaFormat(plain, "x", "not json")).isSameAs(plain);
        assertThat(StructuredOutput.withOllamaFormat(plain, "x", "[1]")).isSameAs(plain);

        OpenAiChatOptions already =
                OpenAiChatOptions.builder()
                        .model("llama3.1")
                        .responseFormat(
                                ResponseFormat.builder()
                                        .type(ResponseFormat.Type.JSON_OBJECT)
                                        .build())
                        .build();
        assertThat(StructuredOutput.withOllamaFormat(already, "x", SCHEMA)).isSameAs(already);

        AnthropicChatOptions notOpenAi = anthropic("claude-sonnet-4-5");
        assertThat(StructuredOutput.withOllamaFormat(notOpenAi, "x", SCHEMA)).isSameAs(notOpenAi);
        assertThat(StructuredOutput.<OpenAiChatOptions>withOllamaFormat(null, "x", SCHEMA))
                .isNull();
    }
}
