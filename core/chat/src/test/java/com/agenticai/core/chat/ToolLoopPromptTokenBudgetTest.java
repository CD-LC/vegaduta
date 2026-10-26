package com.agenticai.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * {@code chat.toolLoop.maxPromptTokensPerTurn}: the run's cumulative prompt-token budget travels on
 * its {@link ToolLoopTranscript}; once a round pushes the total over the cap the loop answers the
 * model's calls with the soft-stop result instead of executing them (and hard-stops a model that
 * keeps calling). Runs without the key, or a 0 cap, are unaffected.
 */
class ToolLoopPromptTokenBudgetTest {

    private static final List<Message> BASE = List.of(new UserMessage("research it"));

    private static ChatResponse calling(String tool, int promptTokens) {
        Generation generation =
                new Generation(
                        AssistantMessage.builder()
                                .content("")
                                .toolCalls(
                                        List.of(
                                                new AssistantMessage.ToolCall(
                                                        "c1", "function", tool, "{}")))
                                .build());
        return new ChatResponse(
                List.of(generation),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(promptTokens, 0, promptTokens))
                        .build());
    }

    private static ArgumentSanitizingToolCallingManager manager() {
        ToolCallingManager delegate =
                new ToolCallingManager() {
                    @Override
                    public List<ToolDefinition> resolveToolDefinitions(
                            ToolCallingChatOptions chatOptions) {
                        return List.of();
                    }

                    @Override
                    public ToolExecutionResult executeToolCalls(
                            Prompt prompt, ChatResponse chatResponse) {
                        List<Message> history = new ArrayList<>(prompt.getInstructions());
                        return ToolExecutionResult.builder()
                                .conversationHistory(history)
                                .returnDirect(false)
                                .build();
                    }
                };
        return new ArgumentSanitizingToolCallingManager(delegate, new ObjectMapper());
    }

    private static ToolExecutionResult run(
            ArgumentSanitizingToolCallingManager manager,
            ToolLoopTranscript transcript,
            int promptTokens) {
        ToolCallingChatOptions.Builder options = ToolCallingChatOptions.builder();
        if (transcript != null) {
            options.toolContext(Map.of(ToolLoopTranscript.TOOL_CONTEXT_KEY, transcript));
        }
        Prompt prompt = new Prompt(new ArrayList<>(BASE), options.build());
        return manager.executeToolCalls(prompt, calling("lookup", promptTokens));
    }

    private static boolean budgetStopped(ToolExecutionResult result) {
        return result.conversationHistory().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .flatMap(m -> ((ToolResponseMessage) m).getResponses().stream())
                .anyMatch(
                        r ->
                                r.responseData() != null
                                        && r.responseData()
                                                .contains(
                                                        ArgumentSanitizingToolCallingManager
                                                                .TOOL_BUDGET_EXHAUSTED));
    }

    @Test
    void aRoundOverTheCapIsAnsweredWithTheTokenWordedSoftStop() {
        ToolLoopTranscript transcript = new ToolLoopTranscript().promptTokenBudget(10_000L);

        ToolExecutionResult result = run(manager(), transcript, 20_000);

        assertThat(budgetStopped(result)).isTrue();
        assertThat(result.returnDirect()).isFalse();
        String resultText =
                result.conversationHistory().stream()
                        .filter(ToolResponseMessage.class::isInstance)
                        .flatMap(m -> ((ToolResponseMessage) m).getResponses().stream())
                        .map(ToolResponseMessage.ToolResponse::responseData)
                        .findFirst()
                        .orElse("");
        assertThat(resultText).contains("Token budget");
        assertThat(resultText).doesNotContain("tool round(s)");
    }

    @Test
    void cumulativeSpendAcrossRoundsIsWhatIsCappedNotEachRound() {
        ToolLoopTranscript transcript = new ToolLoopTranscript().promptTokenBudget(15_000L);
        ArgumentSanitizingToolCallingManager manager = manager();

        assertThat(budgetStopped(run(manager, transcript, 9_000))).isFalse();
        assertThat(budgetStopped(run(manager, transcript, 9_000))).isTrue();
    }

    @Test
    void noTranscriptOrNoCapMeansNoBudget() {
        assertThat(budgetStopped(run(manager(), null, Integer.MAX_VALUE))).isFalse();
        assertThat(
                        budgetStopped(
                                run(
                                        manager(),
                                        new ToolLoopTranscript().promptTokenBudget(0L),
                                        Integer.MAX_VALUE)))
                .isFalse();
        assertThat(
                        budgetStopped(
                                run(
                                        manager(),
                                        new ToolLoopTranscript().promptTokenBudget(null),
                                        Integer.MAX_VALUE)))
                .isFalse();
    }

    @Test
    void aResponseReportingNoUsageCountsNothing() {
        Generation generation =
                new Generation(
                        AssistantMessage.builder()
                                .content("")
                                .toolCalls(
                                        List.of(
                                                new AssistantMessage.ToolCall(
                                                        "c1", "function", "lookup", "{}")))
                                .build());
        ChatResponse response = new ChatResponse(List.of(generation));
        ToolCallingChatOptions options =
                ToolCallingChatOptions.builder()
                        .toolContext(
                                Map.of(
                                        ToolLoopTranscript.TOOL_CONTEXT_KEY,
                                        new ToolLoopTranscript().promptTokenBudget(1L)))
                        .build();

        ToolExecutionResult result =
                manager().executeToolCalls(new Prompt(new ArrayList<>(BASE), options), response);

        assertThat(budgetStopped(result)).isFalse();
    }
}
