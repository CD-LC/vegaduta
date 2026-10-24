package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.turn.ChatParityProperties.FastBufferTrigger;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;

/**
 * Whether a FAST turn is buffered instead of streamed: the one place the decision is made, from {@code
 * app.chat.parity.fast-buffering-triggers}. Streaming and buffering are the two ways a reply reaches the
 * person; a reply that must be checked whole (a fabricated generation link, an output guardrail) cannot
 * also be streamed, so these are the turns that give streaming up.
 */
final class FastBuffering {

    private FastBuffering() {}

    /**
     * @param anyMcpToolsOffered the turn offers a tool from an MCP server
     * @param hasOutputGuardrails the agent has content guardrails on the output path; only consulted (it may
     *     cost a plan lookup) when that trigger is set
     */
    static boolean applies(
            ChatParityProperties parity,
            List<ToolCallback> tools,
            boolean anyMcpToolsOffered,
            java.util.function.BooleanSupplier hasOutputGuardrails) {
        return (parity.buffers(FastBufferTrigger.GENERATION_TOOLS) && ChatPromptDirectives.offersGenerationTool(tools))
                || (parity.buffers(FastBufferTrigger.MCP_TOOLS) && anyMcpToolsOffered)
                || (parity.buffers(FastBufferTrigger.OUTPUT_GUARDRAILS) && hasOutputGuardrails.getAsBoolean());
    }

    /** {@code check}, asked at most once: both questions above need the answer and it may cost a lookup. */
    static java.util.function.BooleanSupplier once(java.util.function.BooleanSupplier check) {
        java.util.concurrent.atomic.AtomicReference<Boolean> answer = new java.util.concurrent.atomic.AtomicReference<>();
        return () -> {
            Boolean known = answer.get();
            if (known == null) {
                known = check.getAsBoolean();
                answer.set(known);
            }
            return known;
        };
    }

    /** True when the reply is held specifically so the output guardrails can withhold it. */
    static boolean holdsForOutputCheck(
            ChatParityProperties parity, java.util.function.BooleanSupplier hasOutputGuardrails) {
        return parity.buffers(FastBufferTrigger.OUTPUT_GUARDRAILS) && hasOutputGuardrails.getAsBoolean();
    }
}
