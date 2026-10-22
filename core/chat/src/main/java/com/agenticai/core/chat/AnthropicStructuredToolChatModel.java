package com.agenticai.core.chat;

import com.agenticai.core.chat.structured.StructuredOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;

/**
 * Anthropic native structured output by forced tool use. Only built (by {@link
 * LlmModelSupport#applyAnthropicStructuredSchema}) when {@code
 * app.llm.structured-output.anthropic-tool-forcing.enabled} is on, the caller asked for a schema
 * ({@link com.agenticai.core.chat.structured.StructuredSchemaSpec}) and the schema is representable
 * as a tool input schema.
 *
 * <p>{@link #call} rewrites each blocking request through {@link
 * StructuredOutput#withAnthropicForcedTool} (the schema becomes a pseudo tool's {@code
 * input_schema}, forced with {@code tool_choice}) and turns the {@code tool_use} input back into
 * the reply text ({@link StructuredOutput#unwrapForcedTool}), so callers keep parsing a JSON reply
 * exactly as before. Every case that cannot be forced - extended thinking on, the request already
 * carries tools - passes through byte-for-byte, and a model that answers in text instead of calling
 * the tool yields that text unchanged: both are the prompt-and-validate path callers already
 * implement.
 *
 * <p>Streaming is passed through untouched: structured utility calls are blocking.
 */
final class AnthropicStructuredToolChatModel implements ChatModel {

    private static final Logger log =
            LoggerFactory.getLogger(AnthropicStructuredToolChatModel.class);

    private final ChatModel delegate;
    private final String toolName;
    private final JsonNode inputSchema;

    AnthropicStructuredToolChatModel(ChatModel delegate, String toolName, JsonNode inputSchema) {
        this.delegate = delegate;
        this.toolName = toolName;
        this.inputSchema = inputSchema;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        if (prompt == null) {
            return delegate.call(prompt);
        }
        ChatOptions options = prompt.getOptions();
        ChatOptions forced =
                StructuredOutput.withAnthropicForcedTool(options, toolName, inputSchema);
        if (forced == options) {
            log.debug(
                    "Anthropic forced-tool structured output skipped for this request "
                            + "(thinking on / tools present / unsupported model); using prompt-and-validate");
            return delegate.call(prompt);
        }
        ChatResponse response = delegate.call(new Prompt(prompt.getInstructions(), forced));
        return StructuredOutput.unwrapForcedTool(response, toolName);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(prompt);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }
}
