package com.agenticai.core.chat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Exactly what one model call was sent, as seen by {@link LlmMetricsAdvisor} -
 * the last point before the provider adapter, so it is the final prompt after
 * every directive, memory block, skill block and governance rewrite. Powers the
 * "What was sent" inspector in the playground and the workflow run viewer.
 *
 * <p>Held in memory only ({@link LlmRequestSnapshotStore}), tenant-scoped, text
 * redacted for secret shapes and size-capped. {@code totalChars} is the size of the
 * (capped) text shown; {@code fullTotalChars} is the real size sent, including tool-call and
 * tool-result payloads. Never persisted and never logged:
 * {@code spring.ai.chat.observations.log-prompt} stays false on purpose.
 */
public record LlmRequestSnapshot(
    String id,
    Instant at,
    String agentId,
    String sessionId,
    String provider,
    String model,
    boolean streaming,
    Map<String, Object> options,
    List<Message> messages,
    List<Tool> tools,
    Map<String, String> context,
    int totalChars,
    boolean truncated,
    int toolSchemaChars,
    int prefixChars,
    int prefixTokensEstimate,
    Long providerPromptTokens,
    int fullTotalChars
) {

    /** Backward-compatible constructor (pre prompt-budget fields). */
    public LlmRequestSnapshot(
        String id, Instant at, String agentId, String sessionId, String provider, String model, boolean streaming,
        Map<String, Object> options, List<Message> messages, List<Tool> tools, Map<String, String> context,
        int totalChars, boolean truncated
    ) {
        this(id, at, agentId, sessionId, provider, model, streaming, options, messages, tools, context,
            totalChars, truncated, 0, 0, 0, null, totalChars);
    }

    /** Pre fullTotalChars constructor (adds the prompt-budget fields). */
    public LlmRequestSnapshot(
        String id, Instant at, String agentId, String sessionId, String provider, String model, boolean streaming,
        Map<String, Object> options, List<Message> messages, List<Tool> tools, Map<String, String> context,
        int totalChars, boolean truncated, int toolSchemaChars, int prefixChars, int prefixTokensEstimate,
        Long providerPromptTokens
    ) {
        this(id, at, agentId, sessionId, provider, model, streaming, options, messages, tools, context,
            totalChars, truncated, toolSchemaChars, prefixChars, prefixTokensEstimate, providerPromptTokens, totalChars);
    }

    /** Copy with the provider-reported prompt token count attached once the response usage is known. */
    public LlmRequestSnapshot withProviderPromptTokens(Long tokens) {
        return new LlmRequestSnapshot(id, at, agentId, sessionId, provider, model, streaming, options, messages, tools,
            context, totalChars, truncated, toolSchemaChars, prefixChars, prefixTokensEstimate, tokens, fullTotalChars);
    }

    /** One prompt message. {@code role} is system | user | assistant | tool. */
    public record Message(String role, String text, int chars, List<String> toolCalls, int mediaCount, int payloadChars) {
        public Message(String role, String text, int chars, List<String> toolCalls, int mediaCount) {
            this(role, text, chars, toolCalls, mediaCount, chars);
        }
    }

    /** {@code inputSchema} is truncated for display; {@code schemaChars} is the real size sent to the model. */
    public record Tool(String name, String description, String inputSchema, int schemaChars) {
        public Tool(String name, String description, String inputSchema) {
            this(name, description, inputSchema, inputSchema == null ? 0 : inputSchema.length());
        }
    }
}
