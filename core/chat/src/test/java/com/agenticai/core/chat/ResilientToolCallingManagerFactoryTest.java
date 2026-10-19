package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
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
 * {@link ArgumentSanitizingToolCallingManagerTest} pins the wrapper's own
 * logic against a stub delegate. This pins the seam every chat-client factory
 * now shares, and does it <em>through Spring AI's real {@code
 * DefaultToolCallingManager}</em> rather than a stub - which is the part that
 * actually threw {@code IllegalStateException: No ToolCallback found for tool
 * name: ...} in production on 2026-07-29 and killed the turn mid-stream. If
 * that delegate ever stops behaving the way the wrapper assumes (or the
 * wrapper stops being applied here), these fail.
 */
class ResilientToolCallingManagerFactoryTest {

    private static final String KNOWN_TOOL = "remember_fact";

    private final ResilientToolCallingManagerFactory factory =
        new ResilientToolCallingManagerFactory(new ObjectMapper());

    @Test
    void theManagerHandedToEveryFactoryIsTheSanitizingWrapper() {
        assertInstanceOf(ArgumentSanitizingToolCallingManager.class, factory.create());
    }

    @Test
    void theWrapperIsSharedNotRebuiltPerChatCall() {
        // These factories build a fresh ChatClient on every single turn, so a
        // per-call manager would be pure garbage - and the stateless wrapper
        // gives no reason to make one. Matches what Spring AI's own chat models
        // do with their static DEFAULT_TOOL_CALLING_MANAGER.
        assertSame(factory.create(), factory.create());
    }

    @Test
    void aHallucinatedToolNameIsAnsweredInsteadOfThrowing() {
        // The exact 2026-07-29 shape: the model asks for a tool that is not in
        // the request's tool list. Through the raw DefaultToolCallingManager
        // this line throws IllegalStateException.
        ToolExecutionResult result = factory.create()
            .executeToolCalls(promptOffering(KNOWN_TOOL), responseCalling("search_web_exa"));

        String reply = onlyToolResponse(result);
        assertTrue(reply.contains("search_web_exa"), "the model should be told which name it invented: " + reply);
        assertTrue(
            reply.toLowerCase(Locale.ROOT).contains("do not call it again"),
            "the model should be told not to retry: " + reply
        );
    }

    @Test
    void aGatedPlatformToolExplainsTheMissingKeyRatherThanDenyingItExists() {
        ToolExecutionResult result = factory.create()
            .executeToolCalls(promptOffering(KNOWN_TOOL), responseCalling("generate_image"));

        String reply = onlyToolResponse(result).toLowerCase(Locale.ROOT);
        assertTrue(reply.contains("openai"), "should name the key that is missing: " + reply);
        assertFalse(reply.contains("you invented the name"), "generate_image is real, just not enabled: " + reply);
    }

    @Test
    void realToolsStillExecuteNormallyAlongsideAHallucinatedOne() {
        ToolExecutionResult result = factory.create()
            .executeToolCalls(promptOffering(KNOWN_TOOL), responseCalling(KNOWN_TOOL, "search_web_exa"));

        ToolResponseMessage responses = lastToolResponseMessage(result);
        assertEquals(2, responses.getResponses().size(), "both calls must be answered - providers require 1:1 on replay");
        assertEquals("real-result", responses.getResponses().get(0).responseData());
    }

    @Test
    void nonJsonArgumentsAreRepairedBeforeTheyGoBackToTheProvider() {
        // The 2026-07-28 half: the assistant message replayed as conversation
        // history must not carry arguments the provider will reject as its own
        // prior output.
        ToolExecutionResult result = factory.create()
            .executeToolCalls(promptOffering(KNOWN_TOOL), responseCallingWithArguments(KNOWN_TOOL, "not json at all"));

        assertEquals("{}", lastAssistantMessage(result).getToolCalls().get(0).arguments());
    }

    @Test
    void wellFormedArgumentsAreLeftExactlyAsTheModelWroteThem() {
        String arguments = "{\"fact\":\"he runs a brick business\"}";

        ToolExecutionResult result = factory.create()
            .executeToolCalls(promptOffering(KNOWN_TOOL), responseCallingWithArguments(KNOWN_TOOL, arguments));

        assertEquals(arguments, lastAssistantMessage(result).getToolCalls().get(0).arguments());
    }

    // --- helpers ----------------------------------------------------------

    private static Prompt promptOffering(String... toolNames) {
        List<ToolCallback> callbacks = List.of(toolNames).stream()
            .map(name -> (ToolCallback) new StubToolCallback(name))
            .toList();
        return new Prompt(
            List.of(new UserMessage("draw me a picture")),
            ToolCallingChatOptions.builder().toolCallbacks(callbacks).build()
        );
    }

    private static ChatResponse responseCalling(String... toolNames) {
        List<AssistantMessage.ToolCall> calls = List.of(toolNames).stream()
            .map(name -> new AssistantMessage.ToolCall("call-" + name, "function", name, "{}"))
            .toList();
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(calls).build())));
    }

    private static ChatResponse responseCallingWithArguments(String toolName, String arguments) {
        AssistantMessage message = AssistantMessage.builder()
            .content("")
            .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", toolName, arguments)))
            .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    /** The single tool result the delegate produced, as the model would see it. */
    private static String onlyToolResponse(ToolExecutionResult result) {
        ToolResponseMessage message = lastToolResponseMessage(result);
        assertEquals(1, message.getResponses().size());
        return message.getResponses().get(0).responseData();
    }

    private static ToolResponseMessage lastToolResponseMessage(ToolExecutionResult result) {
        List<Message> history = result.conversationHistory();
        Message last = history.get(history.size() - 1);
        return assertInstanceOf(ToolResponseMessage.class, last, "expected the turn to end in a tool response: " + last);
    }

    private static AssistantMessage lastAssistantMessage(ToolExecutionResult result) {
        return result.conversationHistory().stream()
            .filter(AssistantMessage.class::isInstance)
            .map(AssistantMessage.class::cast)
            .reduce((first, second) -> second)
            .orElseThrow(() -> new AssertionError("no assistant message in the replayed history"));
    }

    private record StubToolCallback(String name) implements ToolCallback {

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
            return "real-result";
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return call(toolInput);
        }
    }
}
