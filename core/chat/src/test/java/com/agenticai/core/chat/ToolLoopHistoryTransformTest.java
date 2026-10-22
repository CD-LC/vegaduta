package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
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
 * The per-run {@link ToolLoopHistoryTransform} hook of {@link
 * ArgumentSanitizingToolCallingManager}: applied after a tool round when the request's tool context
 * carries one, invisible otherwise.
 */
class ToolLoopHistoryTransformTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static List<Message> roundHistory() {
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage("system"));
        history.add(new UserMessage("go"));
        history.add(
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                                List.of(
                                        new AssistantMessage.ToolCall(
                                                "call-1", "function", "search", "{}")))
                        .build());
        history.add(
                ToolResponseMessage.builder()
                        .responses(
                                List.of(
                                        new ToolResponseMessage.ToolResponse(
                                                "call-1", "search", "{\"result\":\"big\"}")))
                        .build());
        return history;
    }

    private static Prompt promptWith(Map<String, Object> toolContext, String model) {
        ToolCallingChatOptions.Builder builder = ToolCallingChatOptions.builder();
        if (toolContext != null) {
            builder.toolContext(toolContext);
        }
        if (model != null) {
            builder.model(model);
        }
        return new Prompt(List.of(new UserMessage("go")), builder.build());
    }

    private static ChatResponse response() {
        AssistantMessage message =
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                                List.of(
                                        new AssistantMessage.ToolCall(
                                                "call-1", "function", "search", "{}")))
                        .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private ArgumentSanitizingToolCallingManager managerReturning(
            List<Message> history, boolean returnDirect) {
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
                        return ToolExecutionResult.builder()
                                .conversationHistory(history)
                                .returnDirect(returnDirect)
                                .build();
                    }
                };
        return new ArgumentSanitizingToolCallingManager(delegate, objectMapper);
    }

    @Test
    void theRunsTransformIsAppliedToTheHistoryAfterAToolRound() {
        AtomicReference<String> seenModel = new AtomicReference<>();
        List<Message> replacement = List.of(new UserMessage("replaced"));
        ToolLoopHistoryTransform transform =
                (history, modelId) -> {
                    seenModel.set(modelId);
                    assertEquals(4, history.size());
                    return replacement;
                };
        Prompt prompt =
                promptWith(
                        Map.of(ToolLoopHistoryTransform.TOOL_CONTEXT_KEY, transform), "some-model");

        ToolExecutionResult result =
                managerReturning(roundHistory(), false).executeToolCalls(prompt, response());

        assertEquals(replacement, result.conversationHistory());
        assertEquals("some-model", seenModel.get());
    }

    @Test
    void withoutTheKeyTheHistoryIsExactlyWhatTheDelegateProduced() {
        List<Message> history = roundHistory();

        ToolExecutionResult result =
                managerReturning(history, false)
                        .executeToolCalls(promptWith(null, null), response());

        assertEquals(history, result.conversationHistory());
    }

    @Test
    void aTransformThatThrowsNeverCostsTheTurn() {
        ToolLoopHistoryTransform failing =
                (history, modelId) -> {
                    throw new IllegalStateException("boom");
                };
        List<Message> history = roundHistory();
        Prompt prompt =
                promptWith(Map.of(ToolLoopHistoryTransform.TOOL_CONTEXT_KEY, failing), null);

        ToolExecutionResult result =
                managerReturning(history, false).executeToolCalls(prompt, response());

        assertEquals(history, result.conversationHistory());
    }

    @Test
    void aTransformThatReturnsNullFallsBackToTheUntouchedHistory() {
        ToolLoopHistoryTransform nothing = (history, modelId) -> null;
        List<Message> history = roundHistory();
        Prompt prompt =
                promptWith(Map.of(ToolLoopHistoryTransform.TOOL_CONTEXT_KEY, nothing), null);

        ToolExecutionResult result =
                managerReturning(history, false).executeToolCalls(prompt, response());

        assertEquals(history, result.conversationHistory());
    }

    @Test
    void aReturnDirectRoundDoesNotRunTheTransform() {
        AtomicReference<Object> called = new AtomicReference<>();
        ToolLoopHistoryTransform transform =
                (history, modelId) -> {
                    called.set("called");
                    return history;
                };
        Prompt prompt =
                promptWith(Map.of(ToolLoopHistoryTransform.TOOL_CONTEXT_KEY, transform), null);

        managerReturning(roundHistory(), true).executeToolCalls(prompt, response());

        assertNull(called.get());
    }

    @Test
    void aNonTransformValueUnderTheKeyIsIgnored() {
        List<Message> history = roundHistory();
        Prompt prompt =
                promptWith(
                        Map.of(ToolLoopHistoryTransform.TOOL_CONTEXT_KEY, "not a transform"), null);

        ToolExecutionResult result =
                managerReturning(history, false).executeToolCalls(prompt, response());

        assertEquals(history, result.conversationHistory());
        assertTrue(result.conversationHistory().get(3) instanceof ToolResponseMessage);
        assertSame(history.get(1), result.conversationHistory().get(1));
    }
}
