package com.agenticai.core.chat;

import com.agenticai.core.prompts.PromptResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolExecutionResult;

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

    /**
     * The first-stop result: every queued call answered with the budget-exhausted body and the loop
     * CONTINUES ({@code returnDirect(false)}), so the model sees the limit and writes the closing
     * reply itself.
     */
    static ToolExecutionResult soft(
            PromptResolver prompts,
            ObjectMapper objectMapper,
            String budgetExhaustedMessage,
            Prompt prompt,
            boolean tokensExceeded,
            int roundsUsed,
            List<Message> history,
            List<AssistantMessage.ToolCall> calls) {
        String softStop =
                tokensExceeded
                        ? tokens(prompts, objectMapper, prompt)
                        : rounds(prompts, objectMapper, budgetExhaustedMessage, prompt, roundsUsed);
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(calls.size());
        for (AssistantMessage.ToolCall call : calls) {
            responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), softStop));
        }
        history.add(ToolResponseMessage.builder().responses(responses).build());
        return ToolExecutionResult.builder().conversationHistory(history).returnDirect(false).build();
    }

    /**
     * The second-stop result: the model kept calling tools after the soft-stop notice, so the loop
     * ends ({@code returnDirect}) with the fixed hard-stop reply as the closing message - on the
     * first response, because {@code buildGenerations} turns each response into one Generation and
     * {@code ChatClient#content()} reads only the first.
     */
    static ToolExecutionResult hard(
            PromptResolver prompts,
            String hardStopReply,
            Prompt prompt,
            List<Message> history,
            List<AssistantMessage.ToolCall> calls) {
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(calls.size());
        for (int i = 0; i < calls.size(); i++) {
            responses.add(
                    new ToolResponseMessage.ToolResponse(
                            calls.get(i).id(),
                            calls.get(i).name(),
                            i == 0
                                    ? ChatPrompts.textWithConfiguredAlias(
                                            prompts,
                                            ChatPrompts.HARD_STOP,
                                            ToolLoopRunHooks.promptRun(prompt),
                                            hardStopReply)
                                    : ""));
        }
        history.add(ToolResponseMessage.builder().responses(responses).build());
        return ToolExecutionResult.builder().conversationHistory(history).returnDirect(true).build();
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
