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
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * {@code chat.toolLoop.resultClearing.enabled} / {@code .thresholdChars}: the tool loop is a process-wide singleton, so
 * a run's own switch and threshold travel with its {@link ToolLoopTranscript} and are applied instead of the manager's.
 * Run through the real manager and the real clearing; only Spring AI's tool execution is stubbed.
 */
class ToolLoopClearingSettingsTest {

    private static final int RESULT_CHARS = 1_000;
    private static final int RESULTS = 6;

    private static List<Message> history() {
        List<Message> history = new ArrayList<>();
        history.add(new UserMessage("research it"));
        for (int i = 1; i <= RESULTS; i++) {
            String id = "c" + i;
            history.add(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", "lookup", "{\"n\":" + i + "}")))
                .build());
            history.add(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, "lookup", "x".repeat(RESULT_CHARS))))
                .build());
        }
        return history;
    }

    private static ArgumentSanitizingToolCallingManager manager(ToolResultClearing platform) {
        ToolCallingManager delegate = new ToolCallingManager() {
            @Override
            public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
                return List.of();
            }

            @Override
            public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
                return ToolExecutionResult.builder().conversationHistory(history()).returnDirect(false).build();
            }
        };
        return new ArgumentSanitizingToolCallingManager(delegate, new ObjectMapper()).withResultClearing(platform);
    }

    private static List<Message> run(ArgumentSanitizingToolCallingManager manager, ToolLoopTranscript transcript) {
        ToolCallingChatOptions.Builder options = ToolCallingChatOptions.builder();
        if (transcript != null) {
            options.toolContext(Map.of(ToolLoopTranscript.TOOL_CONTEXT_KEY, transcript));
        }
        Prompt prompt = new Prompt(List.of(new UserMessage("research it")), options.build());
        ChatResponse response = new ChatResponse(List.of(new Generation(AssistantMessage.builder()
            .content("")
            .toolCalls(List.of(new AssistantMessage.ToolCall("c7", "function", "lookup", "{\"n\":7}")))
            .build())));
        return manager.executeToolCalls(prompt, response).conversationHistory();
    }

    private static long clearedResults(List<Message> history) {
        return history.stream()
            .filter(ToolResponseMessage.class::isInstance)
            .flatMap(m -> ((ToolResponseMessage) m).getResponses().stream())
            .filter(r -> r.responseData().startsWith(ToolResultClearing.CONTEXT_CLEARED_JSON_PREFIX))
            .count();
    }

    private static final ToolResultClearing PLATFORM_ON = new ToolResultClearing(true, 2_000, 3);
    private static final ToolResultClearing PLATFORM_OFF = new ToolResultClearing(false, 2_000, 3);

    @Test
    void aRunWithNoOverrideBehavesExactlyAsTheManagersOwnClearing() {
        assertThat(clearedResults(run(manager(PLATFORM_ON), new ToolLoopTranscript())))
            .as("6 results of 1000 chars, threshold 2000, keep the last 3: the older 3 are cleared")
            .isEqualTo(3);
        assertThat(clearedResults(run(manager(PLATFORM_OFF), new ToolLoopTranscript()))).isZero();
        assertThat(clearedResults(run(manager(PLATFORM_ON), null))).isEqualTo(3);
    }

    @Test
    void aRunCanSwitchClearingOnWhereThePlatformHasItOff() {
        ToolLoopTranscript transcript = new ToolLoopTranscript().resultClearing(true, null);

        assertThat(clearedResults(run(manager(PLATFORM_OFF), transcript)))
            .as("the platform threshold (2000) still applies, only the switch changed")
            .isEqualTo(3);
    }

    @Test
    void aRunCanSwitchClearingOffWhereThePlatformHasItOn() {
        ToolLoopTranscript transcript = new ToolLoopTranscript().resultClearing(false, null);

        assertThat(clearedResults(run(manager(PLATFORM_ON), transcript))).isZero();
    }

    @Test
    void aRunsOwnThresholdDecidesWhenResultsAreCleared() {
        long total = (long) RESULT_CHARS * RESULTS;

        assertThat(clearedResults(run(manager(PLATFORM_ON), new ToolLoopTranscript().resultClearing(null, (int) total + 1))))
            .as("a threshold above the accumulated results clears nothing")
            .isZero();
        assertThat(clearedResults(run(manager(PLATFORM_ON), new ToolLoopTranscript().resultClearing(null, 500))))
            .isEqualTo(3);
    }

    @Test
    void anOverrideChangesOnlyThatRunNotTheManagerOrTheNextRun() {
        ArgumentSanitizingToolCallingManager shared = manager(PLATFORM_ON);

        assertThat(clearedResults(run(shared, new ToolLoopTranscript().resultClearing(false, null)))).isZero();
        assertThat(clearedResults(run(shared, new ToolLoopTranscript()))).isEqualTo(3);
    }

    @Test
    void aThresholdThatIsNotPositiveIsIgnored() {
        assertThat(clearedResults(run(manager(PLATFORM_ON), new ToolLoopTranscript().resultClearing(null, 0)))).isEqualTo(3);
        assertThat(new ToolLoopTranscript().resultClearing(null, -5).clearingThresholdChars()).isNull();
    }
}
