package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The per-invocation tool-loop cap and the runtime-exception-to-tool-result translation, both
 * through Spring AI's real {@code DefaultToolCallingManager} so the assertions pin what a chat
 * model's internal loop would actually get.
 */
class ToolLoopLimitAndExceptionTranslationTest {

    private static final String TOOL = "search_docs";

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- loop cap --------------------------------------------------------

    @Test
    void withinTheCapToolsExecuteNormally() {
        CountingTool tool = new CountingTool(TOOL, "ok");
        ToolCallingManager manager = managerWithCaps(3, 8, new SimpleMeterRegistry());

        ToolExecutionResult result =
                manager.executeToolCalls(promptWithPriorRounds(tool, 2), responseCalling(TOOL));

        assertFalse(result.returnDirect());
        assertEquals(1, tool.calls.get());
        assertEquals("ok", lastToolResponses(result).get(0).responseData());
    }

    @Test
    void firstRoundPastTheCapIsASoftStopThatExecutesNothing() {
        CountingTool tool = new CountingTool(TOOL, "ok");
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ToolCallingManager manager = managerWithCaps(3, 8, meters);

        ToolExecutionResult result =
                manager.executeToolCalls(
                        promptWithPriorRounds(tool, 3), responseCalling(TOOL, TOOL));

        assertFalse(
                result.returnDirect(),
                "the soft stop must let the model write a normal final answer");
        assertEquals(0, tool.calls.get(), "no tool may execute once the budget is exhausted");
        List<ToolResponseMessage.ToolResponse> responses = lastToolResponses(result);
        assertEquals(
                2,
                responses.size(),
                "every call must still be answered - providers require 1:1 on replay");
        assertTrue(
                responses
                        .get(0)
                        .responseData()
                        .contains(ArgumentSanitizingToolCallingManager.TOOL_BUDGET_EXHAUSTED));
        assertEquals(
                1.0,
                meters.counter(
                                ArgumentSanitizingToolCallingManager.LOOP_LIMIT_METRIC,
                                "stage",
                                "soft",
                                "reason",
                                "rounds")
                        .count());
    }

    @Test
    void aModelThatIgnoresTheSoftStopIsEndedWithReturnDirect() {
        CountingTool tool = new CountingTool(TOOL, "ok");
        ToolCallingManager manager = managerWithCaps(3, 8, new SimpleMeterRegistry());

        // Round 4 got the soft stop; the model asks again in round 5.
        ToolExecutionResult soft =
                manager.executeToolCalls(promptWithPriorRounds(tool, 3), responseCalling(TOOL));
        List<Message> continued = new ArrayList<>(soft.conversationHistory());
        Prompt next = new Prompt(continued, optionsOffering(tool));

        ToolExecutionResult hard = manager.executeToolCalls(next, responseCalling(TOOL));

        assertTrue(hard.returnDirect(), "the loop must end rather than call the model again");
        assertEquals(0, tool.calls.get());
        assertEquals(
                ArgumentSanitizingToolCallingManager.HARD_STOP_REPLY,
                lastToolResponses(hard).get(0).responseData());
    }

    @Test
    void earlierTurnsInPersistedHistoryDoNotCountAgainstThisTurn() {
        CountingTool tool = new CountingTool(TOOL, "ok");
        ToolCallingManager manager = managerWithCaps(2, 8, new SimpleMeterRegistry());

        // Five tool rounds from a previous turn, then the current user message.
        List<Message> history = new ArrayList<>(priorRounds(5));
        history.add(new UserMessage("new question"));

        ToolExecutionResult result =
                manager.executeToolCalls(
                        new Prompt(history, optionsOffering(tool)), responseCalling(TOOL));

        assertEquals(1, tool.calls.get(), "the budget is per invocation, not per conversation");
        assertFalse(result.returnDirect());
    }

    @Test
    void hallucinatedToolCallsCountAgainstTheirOwnCap() {
        CountingTool tool = new CountingTool(TOOL, "ok");
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ToolCallingManager manager = managerWithCaps(25, 2, meters);

        ToolExecutionResult result =
                manager.executeToolCalls(
                        new Prompt(List.of(new UserMessage("hi")), optionsOffering(tool)),
                        responseCalling("made_up_a", "made_up_b", "made_up_c"));

        assertTrue(
                lastToolResponses(result)
                        .get(0)
                        .responseData()
                        .contains(ArgumentSanitizingToolCallingManager.TOOL_BUDGET_EXHAUSTED));
        assertEquals(
                3.0,
                meters.counter(ArgumentSanitizingToolCallingManager.UNKNOWN_TOOL_METRIC).count());
        assertEquals(
                1.0,
                meters.counter(
                                ArgumentSanitizingToolCallingManager.LOOP_LIMIT_METRIC,
                                "stage",
                                "soft",
                                "reason",
                                "unknown_tools")
                        .count());
    }

    // --- exception translation ------------------------------------------

    @Test
    void aPlainRuntimeExceptionComesBackAsARedactedToolResultInsteadOfKillingTheTurn() {
        ToolCallback throwing =
                new StubTool(
                        TOOL,
                        input -> {
                            throw new IllegalStateException(
                                    "upstream 500 for https://x.test/api?token=supersecretvalue123");
                        });
        ToolCallingManager manager = new ResilientToolCallingManagerFactory(objectMapper).create();

        ToolExecutionResult result =
                manager.executeToolCalls(
                        new Prompt(List.of(new UserMessage("hi")), optionsOffering(throwing)),
                        responseCalling(TOOL));

        String reply = lastToolResponses(result).get(0).responseData();
        assertTrue(reply.contains("\"error\""), reply);
        assertTrue(
                reply.contains("upstream 500"),
                "the model should still see what went wrong: " + reply);
        assertFalse(reply.contains("supersecretvalue123"), reply);
    }

    @Test
    void anErrorStillPropagates() {
        ToolCallback throwing =
                new StubTool(
                        TOOL,
                        input -> {
                            throw new StackOverflowError();
                        });
        ToolCallingManager manager = new ResilientToolCallingManagerFactory(objectMapper).create();

        assertThrows(
                StackOverflowError.class,
                () ->
                        manager.executeToolCalls(
                                new Prompt(
                                        List.of(new UserMessage("hi")), optionsOffering(throwing)),
                                responseCalling(TOOL)));
    }

    @Test
    void translatingWrapperForwardsTheToolContext() {
        AtomicReference<ToolContext> seen = new AtomicReference<>();
        ToolCallback contextAware =
                new StubTool(TOOL, input -> "ok") {
                    @Override
                    public String call(String toolInput, ToolContext toolContext) {
                        seen.set(toolContext);
                        return "ok";
                    }
                };
        ToolContext context = new ToolContext(Map.of("tenant", "t1"));

        new ToolExceptionTranslatingCallback(contextAware).call("{}", context);

        assertSame(context, seen.get());
    }

    // --- helpers ----------------------------------------------------------

    private ToolCallingManager managerWithCaps(
            int maxRounds, int maxUnknown, SimpleMeterRegistry meters) {
        return new ArgumentSanitizingToolCallingManager(
                ToolCallingManager.builder()
                        .toolExecutionExceptionProcessor(
                                new RedactingToolExecutionExceptionProcessor())
                        .build(),
                objectMapper,
                maxRounds,
                maxUnknown,
                true,
                meters);
    }

    private static ToolCallingChatOptions optionsOffering(ToolCallback... tools) {
        return ToolCallingChatOptions.builder().toolCallbacks(List.of(tools)).build();
    }

    /** A user message followed by {@code rounds} completed tool rounds of this invocation. */
    private static Prompt promptWithPriorRounds(ToolCallback tool, int rounds) {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("do the thing"));
        messages.addAll(priorRounds(rounds));
        return new Prompt(messages, optionsOffering(tool));
    }

    private static List<Message> priorRounds(int rounds) {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < rounds; i++) {
            String id = "prior-" + i;
            messages.add(
                    AssistantMessage.builder()
                            .content("")
                            .toolCalls(
                                    List.of(
                                            new AssistantMessage.ToolCall(
                                                    id, "function", TOOL, "{}")))
                            .build());
            messages.add(
                    ToolResponseMessage.builder()
                            .responses(
                                    List.of(new ToolResponseMessage.ToolResponse(id, TOOL, "ok")))
                            .build());
        }
        return messages;
    }

    private static ChatResponse responseCalling(String... toolNames) {
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        for (int i = 0; i < toolNames.length; i++) {
            calls.add(new AssistantMessage.ToolCall("call-" + i, "function", toolNames[i], "{}"));
        }
        return new ChatResponse(
                List.of(
                        new Generation(
                                AssistantMessage.builder().content("").toolCalls(calls).build())));
    }

    private static List<ToolResponseMessage.ToolResponse> lastToolResponses(
            ToolExecutionResult result) {
        List<Message> history = result.conversationHistory();
        return assertInstanceOf(ToolResponseMessage.class, history.get(history.size() - 1))
                .getResponses();
    }

    private static class StubTool implements ToolCallback {

        private final String name;
        private final java.util.function.Function<String, String> body;

        StubTool(String name, java.util.function.Function<String, String> body) {
            this.name = name;
            this.body = body;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(name)
                    .description("stub")
                    .inputSchema("{\"type\": \"object\", \"properties\": {}}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            return body.apply(toolInput);
        }
    }

    private static final class CountingTool extends StubTool {

        final AtomicInteger calls;

        CountingTool(String name, String output) {
            this(name, output, new AtomicInteger());
        }

        private CountingTool(String name, String output, AtomicInteger calls) {
            super(
                    name,
                    input -> {
                        calls.incrementAndGet();
                        return output;
                    });
            this.calls = calls;
        }
    }
}
