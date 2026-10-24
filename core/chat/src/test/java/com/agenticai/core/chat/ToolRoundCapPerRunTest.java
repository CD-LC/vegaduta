package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The tool loop's round cap is a process-wide number (derived from the platform's highest call ceiling), but the
 * ceiling now differs per agent. A run that carries its own cap in its transcript (the turn sets it from the
 * agent's settings) is held to that one; a run without it keeps the manager's.
 */
class ToolRoundCapPerRunTest {

    private static final String TOOL = "read_file";

    private ToolCallingManager manager() {
        return new ResilientToolCallingManagerFactory(
            new ObjectMapper(), null, null, new ToolLoopProperties(), 15, 2, 10, true, "").create();
    }

    @Test
    void aRunWithItsOwnHigherCapIsNotStoppedAtThePlatformsRound42() {
        CountingTool tool = new CountingTool();
        ToolLoopTranscript transcript = new ToolLoopTranscript().limitRounds(120);

        ToolExecutionResult result =
            manager().executeToolCalls(promptAfterRounds(tool, 60, transcript), responseCalling());

        assertEquals(1, tool.calls.get(), "round 61 runs under the run's own cap of 120");
        assertFalse(lastResponse(result).contains(ArgumentSanitizingToolCallingManager.TOOL_BUDGET_EXHAUSTED));
    }

    @Test
    void theSameRunWithoutAnOwnCapIsStoppedByTheManagersBackstop() {
        CountingTool tool = new CountingTool();

        ToolExecutionResult result =
            manager().executeToolCalls(promptAfterRounds(tool, 60, new ToolLoopTranscript()), responseCalling());

        assertEquals(0, tool.calls.get());
        assertTrue(lastResponse(result).contains(ArgumentSanitizingToolCallingManager.TOOL_BUDGET_EXHAUSTED));
    }

    @Test
    void aRunsOwnLowerCapStopsItSooner() {
        CountingTool tool = new CountingTool();
        ToolLoopTranscript transcript = new ToolLoopTranscript().limitRounds(5);

        ToolExecutionResult result =
            manager().executeToolCalls(promptAfterRounds(tool, 5, transcript), responseCalling());

        assertEquals(0, tool.calls.get());
        assertTrue(lastResponse(result).contains("5 tool round(s)"), lastResponse(result));
    }

    private static Prompt promptAfterRounds(ToolCallback tool, int rounds, ToolLoopTranscript transcript) {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("build the project"));
        for (int i = 0; i < rounds; i++) {
            String id = "prior-" + i;
            messages.add(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", TOOL, "{}"))).build());
            messages.add(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, TOOL, "ok"))).build());
        }
        return new Prompt(messages, ToolCallingChatOptions.builder()
            .toolCallbacks(List.of(tool))
            .toolContext(Map.of(ToolLoopTranscript.TOOL_CONTEXT_KEY, transcript))
            .build());
    }

    private static ChatResponse responseCalling() {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
            .toolCalls(List.of(new AssistantMessage.ToolCall("call-now", "function", TOOL, "{}"))).build())));
    }

    private static String lastResponse(ToolExecutionResult result) {
        List<Message> history = result.conversationHistory();
        ToolResponseMessage last = assertInstanceOf(ToolResponseMessage.class, history.get(history.size() - 1));
        return last.getResponses().get(0).responseData();
    }

    private static final class CountingTool implements ToolCallback {

        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(TOOL).description("stub")
                .inputSchema("{\"type\": \"object\", \"properties\": {}}").build();
        }

        @Override
        public String call(String toolInput) {
            calls.incrementAndGet();
            return "{\"ok\":true}";
        }
    }
}
