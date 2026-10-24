package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
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
 * Rounds versus calls. The personal profile promises 150 tool calls per turn ("a project build is dozens
 * of file reads"), but a hidden constant of 25 ROUNDS per model invocation ended the loop first, so the
 * documented limit was never reachable. The round cap now follows the call ceiling unless an operator
 * sets one explicitly, and says so in the log when that explicit number is the lower of the two.
 */
class ToolRoundCapFollowsCallCeilingTest {

    private static final String TOOL = "read_file";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int GRACE = 10;

    private ToolCallingManager manager(ToolLoopProperties loop, int callsPerTurn, int autonomousMultiplier) {
        return new ResilientToolCallingManagerFactory(
            objectMapper, null, null, loop, callsPerTurn, autonomousMultiplier, GRACE, true, "").create();
    }

    @Test
    void theDefaultRoundCapIsTheHighestCallCeilingNotAHiddenTwentyFive() {
        ToolLoopProperties loop = new ToolLoopProperties();

        assertEquals(0, loop.getMaxRoundsPerInvocation(), "shipped default: follow the call ceiling");
        // highest call ceiling + the calls answered with the notice past it + the one that is stopped
        assertEquals(41, loop.effectiveMaxRounds(15, 2, 10), "shared server: 15 calls, doubled for FULLY_AUTONOMOUS");
        assertEquals(311, loop.effectiveMaxRounds(150, 2, 10), "personal profile: 150 calls");
        assertEquals(51, loop.effectiveMaxRounds(40, 1, 10));
        assertEquals(3, loop.effectiveMaxRounds(1, 1, 1));
    }

    @Test
    void anExplicitRoundCapWinsEvenWhenLower() {
        ToolLoopProperties loop = new ToolLoopProperties();
        loop.setMaxRoundsPerInvocation(12);

        assertEquals(12, loop.effectiveMaxRounds(150, 2, 10));
    }

    @Test
    void aTurnAllowed150CallsIsNotStoppedAtRound26() {
        CountingTool tool = new CountingTool();
        ToolCallingManager manager = manager(new ToolLoopProperties(), 150, 2);

        ToolExecutionResult result = manager.executeToolCalls(promptAfterRounds(tool, 100), responseCalling());

        assertEquals(1, tool.calls.get(), "round 101 of a 150-call turn must still run");
        assertFalse(result.returnDirect());
        assertFalse(lastResponse(result).contains(ArgumentSanitizingToolCallingManager.TOOL_BUDGET_EXHAUSTED));
    }

    @Test
    void theDefaultSharedServerSettingsLetFullyAutonomousAgentsReachTheirThirtyCallsAndThePolicyAfterIt() {
        CountingTool tool = new CountingTool();
        ToolCallingManager manager = manager(new ToolLoopProperties(), 15, 2);

        ToolExecutionResult at41 = manager.executeToolCalls(promptAfterRounds(tool, 40), responseCalling());
        assertEquals(1, tool.calls.get(), "round 41 still runs: the call guard decides first (30 calls + 10 notices)");
        assertFalse(at41.returnDirect());

        ToolExecutionResult at42 = manager.executeToolCalls(promptAfterRounds(tool, 41), responseCalling());
        assertEquals(1, tool.calls.get(), "the backstop only fires beyond all of that");
        assertTrue(lastResponse(at42).contains(ArgumentSanitizingToolCallingManager.TOOL_BUDGET_EXHAUSTED));
    }

    @Test
    void anExplicitCapStillStopsTheLoopAtItsOwnNumberWithTheSameStructuredResult() {
        ToolLoopProperties loop = new ToolLoopProperties();
        loop.setMaxRoundsPerInvocation(10);
        CountingTool tool = new CountingTool();
        ToolCallingManager manager = manager(loop, 150, 2);

        ToolExecutionResult result = manager.executeToolCalls(promptAfterRounds(tool, 10), responseCalling());

        assertEquals(0, tool.calls.get());
        String body = lastResponse(result);
        assertTrue(body.contains("\"error\":\"tool_budget_exhausted\""), body);
        assertTrue(body.contains("10 tool round(s)"), "names the rounds used: " + body);
    }

    @Test
    void theStopWordingIsConfiguration() {
        ToolLoopProperties loop = new ToolLoopProperties();
        loop.setMaxRoundsPerInvocation(2);
        loop.setBudgetExhaustedMessage("No more tools after {rounds} rounds.");
        loop.setHardStopReply("Stopped.");
        CountingTool tool = new CountingTool();
        ToolCallingManager manager = manager(loop, 15, 2);

        ToolExecutionResult soft = manager.executeToolCalls(promptAfterRounds(tool, 2), responseCalling());
        assertTrue(lastResponse(soft).contains("No more tools after 2 rounds."), lastResponse(soft));

        List<Message> continued = new ArrayList<>(soft.conversationHistory());
        ToolExecutionResult hard = manager.executeToolCalls(
            new Prompt(continued, ToolCallingChatOptions.builder().toolCallbacks(List.of(tool)).build()),
            responseCalling());
        assertTrue(hard.returnDirect());
        assertEquals("Stopped.", lastResponse(hard));
    }

    // --- helpers ---------------------------------------------------------

    private static Prompt promptAfterRounds(ToolCallback tool, int rounds) {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("build the project"));
        for (int i = 0; i < rounds; i++) {
            String id = "prior-" + i;
            messages.add(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", TOOL, "{}"))).build());
            messages.add(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, TOOL, "ok"))).build());
        }
        return new Prompt(messages, ToolCallingChatOptions.builder().toolCallbacks(List.of(tool)).build());
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
