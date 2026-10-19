package com.agenticai.core.agents.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.context.ContextCompactionEngine.Params;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.chat.ToolLoopHistoryTransform;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * One turn's compaction run: model-aware windows, step events, the summariser latch, attaching to a
 * client.
 */
class CompactionRunTest {

    private static final Params PARAMS = new Params(10_000, 0.6, 0.85, 2, 2, 100, true);

    private static AssistantMessage call(String id) {
        return AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", "search", "{}")))
                .build();
    }

    private static ToolResponseMessage result(String id, int chars) {
        return ToolResponseMessage.builder()
                .responses(
                        List.of(
                                new ToolResponseMessage.ToolResponse(
                                        id, "search", "x".repeat(chars))))
                .build();
    }

    private static List<Message> toolRun(int rounds, int chars) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("sys"));
        messages.add(new UserMessage("go"));
        for (int i = 1; i <= rounds; i++) {
            messages.add(call("c" + i));
            messages.add(result("c" + i, chars));
        }
        return messages;
    }

    private static List<Message> longChat(int exchanges) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("memory block"));
        for (int i = 1; i <= exchanges; i++) {
            messages.add(new UserMessage("u" + i + " " + "q".repeat(3_000)));
            messages.add(new AssistantMessage("a" + i + " " + "r".repeat(3_000)));
        }
        return messages;
    }

    private static CompactionRun run(
            Params params,
            String model,
            Optional<String> summary,
            AtomicInteger calls,
            int maxSummaries,
            AgentStepEventEmitter events) {
        return new CompactionRun(
                new ContextCompactionEngine(),
                params,
                model,
                128_000,
                0,
                (existing, turns) -> {
                    calls.incrementAndGet();
                    return summary;
                },
                events,
                maxSummaries);
    }

    @Test
    void theToolLoopTransformClearsOldResultsOnceTheWindowFillsUp() {
        CompactionRun run = run(PARAMS, "m", Optional.empty(), new AtomicInteger(), 3, null);
        List<Message> history = toolRun(5, 8_000);

        List<Message> out = run.transform(history, "m");

        assertNotSame(history, out);
        ToolResponseMessage first = (ToolResponseMessage) out.get(3);
        assertTrue(
                first.getResponses().get(0).responseData().startsWith("{\"context_cleared\":true"));
    }

    @Test
    void aSmallPromptComesBackAsTheSameList() {
        CompactionRun run = run(PARAMS, "m", Optional.empty(), new AtomicInteger(), 3, null);
        List<Message> history = toolRun(1, 100);

        assertSame(history, run.transform(history, "m"));
    }

    @Test
    void aModelSwitchMidRunIsSizedByItsOwnWindowNotTheFirstModels() {
        Params huge = new Params(1_000_000, 0.6, 0.85, 2, 2, 100, true);
        CompactionRun run =
                run(huge, "first-model", Optional.empty(), new AtomicInteger(), 3, null);
        List<Message> history = toolRun(10, 32_000); // ~80_000 tokens

        assertSame(history, run.transform(history, "first-model"), "600_000 is nowhere near");
        assertNotSame(
                history,
                run.transform(history, "gpt-4o"),
                "a 128_000 floor puts the line at 76_800");
    }

    @Test
    void aContextCompactedStepEventCarriesTheCounts() {
        AgentStepEventEmitter events = AgentStepEventEmitter.active();
        CompactionRun run = run(PARAMS, "m", Optional.empty(), new AtomicInteger(), 3, events);

        run.transform(toolRun(5, 8_000), "m");
        events.complete();
        List<String> emitted = events.asFlux().collectList().block();

        assertEquals(1, emitted.size());
        String event = emitted.get(0);
        assertTrue(event.startsWith(AgentStepEventEmitter.STEP_EVENT_MARKER));
        assertTrue(event.contains("\"type\":\"context_compacted\""), event);
        assertTrue(event.contains("\"cleared\":3"), event);
        assertTrue(event.contains("\"compacted\":0"), event);
        assertTrue(event.contains("\"tokensBefore\":"), event);
        assertTrue(event.contains("\"tokensAfter\":"), event);
    }

    @Test
    void noEventIsEmittedWhenNothingChanged() {
        AgentStepEventEmitter events = AgentStepEventEmitter.active();
        CompactionRun run = run(PARAMS, "m", Optional.empty(), new AtomicInteger(), 3, events);

        run.transform(toolRun(1, 100), "m");
        events.complete();

        assertEquals(0, events.asFlux().collectList().block().size());
    }

    @Test
    void aSummariserFailureEndsFurtherAttemptsInThisRun() {
        AtomicInteger calls = new AtomicInteger();
        CompactionRun run = run(PARAMS, "m", Optional.empty(), calls, 3, null);
        List<Message> history = longChat(8);

        assertSame(history, run.transform(history, "m"));
        assertSame(history, run.transform(history, "m"));
        assertSame(history, run.transform(history, "m"));

        assertEquals(1, calls.get(), "one timeout must not become a timeout per tool round");
    }

    @Test
    void aRunSummarisesAtMostMaxSummariesTimes() {
        AtomicInteger calls = new AtomicInteger();
        CompactionRun run = run(PARAMS, "m", Optional.of("summary"), calls, 1, null);
        List<Message> first = longChat(8);

        assertNotSame(first, run.transform(first, "m"));
        List<Message> second = longChat(8);
        assertSame(second, run.transform(second, "m"));

        assertEquals(1, calls.get());
    }

    @Test
    void withoutASummariserClearingStillWorksAndCompactionIsSkipped() {
        CompactionRun run =
                new CompactionRun(
                        new ContextCompactionEngine(), PARAMS, "m", 128_000, 0, null, null, 3);
        List<Message> chat = longChat(8);
        List<Message> tools = toolRun(5, 8_000);

        assertSame(chat, run.transform(chat, "m"));
        assertNotSame(tools, run.transform(tools, "m"));
    }

    @Test
    void prepareMessagesCountsTheSystemPromptAndTheUserMessageThatWillBeAddedAroundTheHistory() {
        AtomicInteger calls = new AtomicInteger();
        CompactionRun run = run(PARAMS, "m", Optional.of("summary"), calls, 3, null);
        List<Message> chat = longChat(4); // ~6_100 tokens: under the line on its own

        assertSame(chat, run.prepareMessages(chat, "short prompt", "hi"));
        List<Message> out =
                run.prepareMessages(
                        chat, "p".repeat(16_000), "hi"); // +4_000 tokens of system prompt

        assertNotSame(chat, out);
        assertEquals(1, calls.get());
    }

    @Test
    void toolSchemaOverheadCountsTowardsTheWindow() {
        ToolCallback big = tool("big", "d".repeat(8_000), "{\"type\":\"object\"}");
        int overhead = CompactionRun.toolSchemaTokens(List.of(big));
        assertTrue(overhead >= 2_000, "was " + overhead);
        CompactionRun run =
                new CompactionRun(
                        new ContextCompactionEngine(),
                        PARAMS,
                        "m",
                        128_000,
                        overhead,
                        null,
                        null,
                        3);
        List<Message> history = toolRun(3, 6_000); // ~4_600 tokens + ~2_000 of schemas

        List<Message> out = run.transform(history, "m");

        assertNotSame(history, out);
    }

    @Test
    void attachPutsTheRunIntoTheClientsToolContext() {
        ChatClient client = mock(ChatClient.class);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient rebuilt = mock(ChatClient.class);
        when(client.mutate()).thenReturn(builder);
        when(builder.defaultToolContext(any())).thenReturn(builder);
        when(builder.build()).thenReturn(rebuilt);
        CompactionRun run = run(PARAMS, "m", Optional.empty(), new AtomicInteger(), 3, null);

        ChatClient attached = run.attach(client);

        assertSame(rebuilt, attached);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> context = ArgumentCaptor.forClass(Map.class);
        verify(builder).defaultToolContext(context.capture());
        assertSame(run, context.getValue().get(ToolLoopHistoryTransform.TOOL_CONTEXT_KEY));
    }

    @Test
    void attachFallsBackToTheOriginalClientWhenItCannotBeRebuilt() {
        ChatClient client = mock(ChatClient.class);
        when(client.mutate()).thenThrow(new IllegalStateException("no"));
        CompactionRun run = run(PARAMS, "m", Optional.empty(), new AtomicInteger(), 3, null);

        assertSame(client, run.attach(client));
    }

    private static ToolCallback tool(String name, String description, String schema) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description(description)
                        .inputSchema(schema)
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return "{}";
            }
        };
    }
}
