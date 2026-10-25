package com.agenticai.core.chat;

import com.agenticai.core.prompts.PromptResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * The soft-stop tool-result bodies the tool loop returns instead of executing past a limit
 * (rounds spent, cumulative prompt-token budget spent). Factored out of
 * {@link ArgumentSanitizingToolCallingManager} for size; the body is one ordered JSON object so
 * the model sees the same {@code tool_budget_exhausted} contract whichever limit trips first.
 */
final class ToolLoopSoftStops {

    private ToolLoopSoftStops() {}

    static String rounds(
            PromptResolver prompts,
            ObjectMapper objectMapper,
            String budgetExhaustedMessage,
            Prompt prompt,
            int roundsUsed) {
        String instruction = ChatPrompts.textWithConfiguredAlias(
            prompts, ChatPrompts.TOOL_BUDGET_ROUNDS, ToolLoopRunHooks.promptRun(prompt), budgetExhaustedMessage,
            "rounds", String.valueOf(roundsUsed));
        return body(objectMapper, instruction);
    }

    /** The run's cumulative prompt-token budget is spent: same marker JSON, a token worded instruction. */
    static String tokens(PromptResolver prompts, ObjectMapper objectMapper, Prompt prompt) {
        String instruction = ChatPrompts.text(
            prompts, ChatPrompts.TOOL_BUDGET_TOKENS, ToolLoopRunHooks.promptRun(prompt));
        return body(objectMapper, instruction);
    }

    private static String body(ObjectMapper objectMapper, String instruction) {
        try {
            // Ordered: error first, the same shape GuardedToolCallback writes for its call ceiling.
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("error", ArgumentSanitizingToolCallingManager.TOOL_BUDGET_EXHAUSTED);
            body.put("result", instruction);
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            return ToolLoopMarkers.fallbackSoftStop(instruction);
        }
    }
}
