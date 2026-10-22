package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
 * A model that asks for the same idempotent lookup twice in one reply gets told to use the result
 * it already has (qwen3.5:4b re-issued a byte-identical web_search before answering).
 */
class RepeatToolCallGuardTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private ToolCallingManager manager(String guardedTools) {
        return new ArgumentSanitizingToolCallingManager(
                        ToolCallingManager.builder().build(), objectMapper, 25, 8, true, meters)
                .withRepeatGuard(guardedTools);
    }

    @Test
    void anIdenticalRepeatIsAnsweredNotRun() {
        Counting search = new Counting("web_search");
        ToolExecutionResult result =
                manager(RepeatToolCallGuard.DEFAULT_TOOLS)
                        .executeToolCalls(
                                promptAfter(search, "web_search", "{\"query\":\"node lts\"}"),
                                responseCalling("web_search", "{\"query\":\"node lts\"}"));

        assertEquals(0, search.calls.get(), "a repeat must not run again");
        assertFalse(result.returnDirect(), "the first repeat lets the model answer normally");
        String reply = lastResponses(result).get(0).responseData();
        assertTrue(reply.contains(RepeatToolCallGuard.DUPLICATE_MARKER), reply);
        assertEquals(1.0, meters.counter(RepeatToolCallGuard.METRIC, "tool", "web_search").count());
    }

    @Test
    void keyOrderAndWhitespaceDoNotMakeACallNew() {
        Counting search = new Counting("web_search");
        ToolExecutionResult result =
                manager(RepeatToolCallGuard.DEFAULT_TOOLS)
                        .executeToolCalls(
                                promptAfter(search, "web_search", "{\"query\":\"x\",\"limit\":3}"),
                                responseCalling(
                                        "web_search", "{ \"limit\": 3,  \"query\": \"x\" }"));

        assertEquals(0, search.calls.get());
        assertTrue(
                lastResponses(result)
                        .get(0)
                        .responseData()
                        .contains(RepeatToolCallGuard.DUPLICATE_MARKER));
    }

    @Test
    void aDifferentQueryRunsNormally() {
        Counting search = new Counting("web_search");
        manager(RepeatToolCallGuard.DEFAULT_TOOLS)
                .executeToolCalls(
                        promptAfter(search, "web_search", "{\"query\":\"node lts\"}"),
                        responseCalling("web_search", "{\"query\":\"node 24 release date\"}"));

        assertEquals(1, search.calls.get());
    }

    @Test
    void aToolThatIsNotListedMayBeCalledTwice() {
        Counting shell = new Counting("local_shell_read");
        manager(RepeatToolCallGuard.DEFAULT_TOOLS)
                .executeToolCalls(
                        promptAfter(shell, "local_shell_read", "{\"command\":\"date\"}"),
                        responseCalling("local_shell_read", "{\"command\":\"date\"}"));

        assertEquals(1, shell.calls.get(), "state-reading tools legitimately repeat");
    }

    @Test
    void aRoundMixingNewAndRepeatedCallsRunsNormally() {
        Counting search = new Counting("web_search");
        manager(RepeatToolCallGuard.DEFAULT_TOOLS)
                .executeToolCalls(
                        promptAfter(search, "web_search", "{\"query\":\"a\"}"),
                        responseCallingTwo(
                                "web_search",
                                "{\"query\":\"a\"}",
                                "web_search",
                                "{\"query\":\"b\"}"));

        assertEquals(2, search.calls.get());
    }

    @Test
    void repeatingAgainAfterTheNoticeEndsTheLoop() {
        Counting search = new Counting("web_search");
        ToolCallingManager manager = manager(RepeatToolCallGuard.DEFAULT_TOOLS);
        ToolExecutionResult first =
                manager.executeToolCalls(
                        promptAfter(search, "web_search", "{\"query\":\"q\"}"),
                        responseCalling("web_search", "{\"query\":\"q\"}"));

        List<Message> continued = new ArrayList<>(first.conversationHistory());
        ToolExecutionResult second =
                manager.executeToolCalls(
                        new Prompt(continued, options(search)),
                        responseCalling("web_search", "{\"query\":\"q\"}"));

        assertTrue(second.returnDirect(), "a model that ignores the notice is ended, not looped");
        assertEquals(RepeatToolCallGuard.STOP_REPLY, lastResponses(second).get(0).responseData());
        assertEquals(0, search.calls.get());
    }

    @Test
    void anEarlierTurnsCallsDoNotCount() {
        Counting search = new Counting("web_search");
        List<Message> history = new ArrayList<>(priorRound("web_search", "{\"query\":\"q\"}"));
        history.add(new UserMessage("a new question"));
        manager(RepeatToolCallGuard.DEFAULT_TOOLS)
                .executeToolCalls(
                        new Prompt(history, options(search)),
                        responseCalling("web_search", "{\"query\":\"q\"}"));

        assertEquals(1, search.calls.get(), "the guard is per reply, not per conversation");
    }

    @Test
    void blankTurnsTheGuardOffAndStarGuardsEveryTool() {
        Counting search = new Counting("web_search");
        manager("")
                .executeToolCalls(
                        promptAfter(search, "web_search", "{}"),
                        responseCalling("web_search", "{}"));
        assertEquals(1, search.calls.get());

        Counting shell = new Counting("local_shell_read");
        manager("*")
                .executeToolCalls(
                        promptAfter(shell, "local_shell_read", "{}"),
                        responseCalling("local_shell_read", "{}"));
        assertEquals(0, shell.calls.get());
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static ToolCallingChatOptions options(ToolCallback... tools) {
        return ToolCallingChatOptions.builder().toolCallbacks(List.of(tools)).build();
    }

    private static List<Message> priorRound(String tool, String arguments) {
        return List.of(
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                                List.of(
                                        new AssistantMessage.ToolCall(
                                                "prior", "function", tool, arguments)))
                        .build(),
                ToolResponseMessage.builder()
                        .responses(
                                List.of(
                                        new ToolResponseMessage.ToolResponse(
                                                "prior", tool, "result")))
                        .build());
    }

    /** A user message, then one completed round that called {@code tool}. */
    private static Prompt promptAfter(ToolCallback callback, String tool, String arguments) {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("search for it"));
        messages.addAll(priorRound(tool, arguments));
        return new Prompt(messages, options(callback));
    }

    private static ChatResponse responseCalling(String tool, String arguments) {
        return new ChatResponse(
                List.of(
                        new Generation(
                                AssistantMessage.builder()
                                        .content("")
                                        .toolCalls(
                                                List.of(
                                                        new AssistantMessage.ToolCall(
                                                                "now",
                                                                "function",
                                                                tool,
                                                                arguments)))
                                        .build())));
    }

    private static ChatResponse responseCallingTwo(
            String tool1, String args1, String tool2, String args2) {
        return new ChatResponse(
                List.of(
                        new Generation(
                                AssistantMessage.builder()
                                        .content("")
                                        .toolCalls(
                                                List.of(
                                                        new AssistantMessage.ToolCall(
                                                                "c1", "function", tool1, args1),
                                                        new AssistantMessage.ToolCall(
                                                                "c2", "function", tool2, args2)))
                                        .build())));
    }

    private static List<ToolResponseMessage.ToolResponse> lastResponses(
            ToolExecutionResult result) {
        List<Message> history = result.conversationHistory();
        return assertInstanceOf(ToolResponseMessage.class, history.get(history.size() - 1))
                .getResponses();
    }

    private static final class Counting implements ToolCallback {
        final AtomicInteger calls = new AtomicInteger();
        private final String name;

        Counting(String name) {
            this.name = name;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(name)
                    .description("stub")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            calls.incrementAndGet();
            return "result";
        }
    }
}
