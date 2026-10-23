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
 * <p>Held in {@link LlmRequestSnapshotStore}: in memory and, with {@code app.llm.request-snapshot-store.mode=redis},
 * in Redis for the same time-to-live, tenant-scoped, text redacted for secret shapes and size-capped.
 * {@code totalChars} is the size of the (capped) text shown; {@code fullTotalChars} is the real size sent,
 * including tool-call and tool-result payloads. Never written to a database and never logged:
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

    /**
     * One prompt message. {@code role} is system | user | assistant | tool. {@code blocks} says where the parts
     * of a system message came from (prompt-registry key, scope, version); empty for the other roles and when
     * nothing could be matched.
     */
    public record Message(
        String role, String text, int chars, List<String> toolCalls, int mediaCount, int payloadChars,
        List<PromptBlockTag> blocks
    ) {
        public Message {
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
        }

        /** Pre-provenance constructor. */
        public Message(String role, String text, int chars, List<String> toolCalls, int mediaCount, int payloadChars) {
            this(role, text, chars, toolCalls, mediaCount, payloadChars, List.of());
        }

        public Message(String role, String text, int chars, List<String> toolCalls, int mediaCount) {
            this(role, text, chars, toolCalls, mediaCount, chars, List.of());
        }
    }

    /** {@code inputSchema} is truncated for display; {@code schemaChars} is the real size sent to the model. */
    public record Tool(String name, String description, String inputSchema, int schemaChars) {
        public Tool(String name, String description, String inputSchema) {
            this(name, description, inputSchema, inputSchema == null ? 0 : inputSchema.length());
        }
    }
}
