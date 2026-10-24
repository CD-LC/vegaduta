package com.agenticai.core.chat;

import java.util.List;
import java.util.Map;

/**
 * Wire types for {@code POST /api/completions/agent-turn}: one model turn of a
 * client-side tool-calling agent (the IDE plugins' coding agent), in the same
 * shape that agent already speaks ({@code clients/shared/src/agent/types.ts}).
 *
 * <p>The server NEVER executes the tools. It forwards the tool definitions to the
 * model and returns whatever tool calls the model asked for; the client runs them
 * on the user's machine (that is the entire point - the files and the shell are
 * there) and sends the results back as {@code tool} messages on the next turn.
 */
public final class AgentTurn {

    private AgentTurn() {
    }

    /**
     * One conversation message. {@code role} is system | user | assistant | tool.
     * Assistant messages may carry {@code toolCalls}; tool messages carry the
     * {@code toolCallId} and {@code name} of the call they answer.
     */
    public record Message(String role, String content, List<ToolCall> toolCalls, String toolCallId, String name) {
    }

    /** A model-requested tool call. {@code args} is the parsed argument object. */
    public record ToolCall(String id, String name, Map<String, Object> args) {
    }

    /** A tool the model may call. {@code parameters} is a JSON Schema object. */
    public record Tool(String name, String description, Map<String, Object> parameters) {
    }

    /**
     * @param model optional preference: honoured only when it names an ENABLED pool row,
     *     otherwise ignored - a client can steer between pool models but never name an
     *     arbitrary one.
     */
    public record Request(List<Message> messages, List<Tool> tools, Integer maxTokens, Double temperature, String model) {
    }

    public record Usage(Long promptTokens, Long completionTokens) {
    }

    /**
     * {@code finishReason}, one vocabulary whatever the provider: {@code tool_calls} (the reply
     * carries calls to run), {@code stop} (finished), {@code length} (cut off at the output limit -
     * also when it was a tool call that was cut off and could not be used), {@code content_filter}
     * (stopped by the provider's safety system), {@code malformed_tool_call} (the model's tool call
     * could not be used). A provider reason this service does not recognise is passed through
     * unchanged. Tool-call ids are always present and non-blank: one is generated for providers
     * (Gemini) that send none.
     */
    public record Result(String content, List<ToolCall> toolCalls, String model, String finishReason, Usage usage) {
    }
}
