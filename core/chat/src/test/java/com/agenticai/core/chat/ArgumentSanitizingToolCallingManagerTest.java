package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
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
 * Locks in both halves of what this wrapper defends against, each traceable
 * to a live incident:
 *
 * <ul>
 *   <li>2026-07-28 - Sarvam emitting non-JSON tool-call {@code arguments},
 *   which its own API then rejects on replay, aborting the stream.</li>
 *   <li>2026-07-29 (agent MYSURU_MITRA) - Sarvam emitting tool <em>names</em>
 *   that were never offered ({@code generate_image}, and the entirely
 *   invented {@code search_web_exa}), which made Spring AI throw {@code
 *   IllegalStateException: No ToolCallback found for tool name: ...} and kill
 *   the turn mid-stream. Recorded in {@code bahuth.llm_call_log} at
 *   2026-07-29 11:11:52Z / 11:11:19Z.</li>
 * </ul>
 */
class ArgumentSanitizingToolCallingManagerTest {

    private static final String KNOWN_TOOL = "remember_fact";

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- unknown tool names (2026-07-29 incident) -------------------------

    @Test
    void hallucinatedToolNameGetsAFallbackCallbackInsteadOfFailingTheTurn() {
        CapturingToolCallingManager delegate = new CapturingToolCallingManager();
        ArgumentSanitizingToolCallingManager manager = new ArgumentSanitizingToolCallingManager(delegate, objectMapper);

        Prompt prompt = promptOfferingTools(KNOWN_TOOL);
        manager.executeToolCalls(prompt, responseCalling("search_web_exa"));

        ToolCallback fallback = callbackNamed(delegate.captured, "search_web_exa")
            .orElseThrow(() -> new AssertionError("no fallback callback was injected for the hallucinated name"));
        String reply = fallback.call("{}");
        assertTrue(reply.contains("search_web_exa"), "the reply should name the tool the model invented: " + reply);
        assertTrue(
            reply.toLowerCase(Locale.ROOT).contains("do not call it again"),
            "the reply should tell the model not to retry: " + reply
        );
        // The real tool must still be there - injecting a fallback never
        // replaces the request's own tools.
        assertTrue(callbackNamed(delegate.captured, KNOWN_TOOL).isPresent());
    }

    @Test
    void gatedPlatformToolExplainsTheConfigurationStepRatherThanClaimingItDoesNotExist() {
        CapturingToolCallingManager delegate = new CapturingToolCallingManager();
        ArgumentSanitizingToolCallingManager manager = new ArgumentSanitizingToolCallingManager(delegate, objectMapper);

        // Exactly the live failure: the tenant has no ACTIVE OpenAI credential,
        // so AgentToolProvider#resolveImageGenTools registered nothing, but the
        // model asked for generate_image anyway.
        manager.executeToolCalls(promptOfferingTools(KNOWN_TOOL), responseCalling("generate_image"));

        String reply = callbackNamed(delegate.captured, "generate_image").orElseThrow().call("{}");
        String lower = reply.toLowerCase(Locale.ROOT);
        assertTrue(lower.contains("openai"), "should name the key that is missing: " + reply);
        assertTrue(lower.contains("providers"), "should name where to add it: " + reply);
        assertFalse(lower.contains("you invented the name"), "generate_image is real, just not enabled: " + reply);
    }

    @Test
    void modelCallingAToolWhenNoneWereOfferedIsStillAnsweredNotThrown() {
        CapturingToolCallingManager delegate = new CapturingToolCallingManager();
        ArgumentSanitizingToolCallingManager manager = new ArgumentSanitizingToolCallingManager(delegate, objectMapper);

        manager.executeToolCalls(promptOfferingTools(), responseCalling("anything_at_all"));

        assertNotNull(delegate.captured);
        assertTrue(callbackNamed(delegate.captured, "anything_at_all").isPresent());
    }

    @Test
    void mixedRealAndHallucinatedCallsKeepTheRealToolUntouched() {
        CapturingToolCallingManager delegate = new CapturingToolCallingManager();
        ArgumentSanitizingToolCallingManager manager = new ArgumentSanitizingToolCallingManager(delegate, objectMapper);

        Prompt prompt = promptOfferingTools(KNOWN_TOOL);
        manager.executeToolCalls(prompt, responseCalling(KNOWN_TOOL, "search_web_exa"));

        ToolCallback real = callbackNamed(delegate.captured, KNOWN_TOOL).orElseThrow();
        assertEquals("real-result", real.call("{}"), "the genuine tool must still be the genuine callback");
        assertTrue(callbackNamed(delegate.captured, "search_web_exa").isPresent());
    }

    @Test
    void aResponseUsingOnlyOfferedToolsPassesThePromptThroughUnchanged() {
        CapturingToolCallingManager delegate = new CapturingToolCallingManager();
        ArgumentSanitizingToolCallingManager manager = new ArgumentSanitizingToolCallingManager(delegate, objectMapper);

        Prompt prompt = promptOfferingTools(KNOWN_TOOL);
        manager.executeToolCalls(prompt, responseCalling(KNOWN_TOOL));

        assertSame(prompt, delegate.captured, "no unknown name means nothing to add - don't rebuild the prompt");
    }

    // --- malformed arguments (2026-07-28 incident, pre-existing behavior) --

    @Test
    void nonJsonToolCallArgumentsAreReplacedBeforeReplay() {
        AssistantMessage withBadArguments = AssistantMessage.builder()
            .content("")
            .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", KNOWN_TOOL, "not json at all")))
            .build();
        ToolCallingManager delegate = new FixedHistoryToolCallingManager(List.of(withBadArguments));
        ArgumentSanitizingToolCallingManager manager = new ArgumentSanitizingToolCallingManager(delegate, objectMapper);

        ToolExecutionResult result = manager.executeToolCalls(promptOfferingTools(KNOWN_TOOL), responseCalling(KNOWN_TOOL));

        AssistantMessage repaired = (AssistantMessage) result.conversationHistory().get(0);
        assertEquals("{}", repaired.getToolCalls().get(0).arguments());
    }

    @Test
    void validToolCallArgumentsAreLeftExactlyAsTheModelWroteThem() {
        String arguments = "{\"fact\":\"he runs a brick business\"}";
        AssistantMessage message = AssistantMessage.builder()
            .content("")
            .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", KNOWN_TOOL, arguments)))
            .build();
        ToolCallingManager delegate = new FixedHistoryToolCallingManager(List.of(message));
        ArgumentSanitizingToolCallingManager manager = new ArgumentSanitizingToolCallingManager(delegate, objectMapper);

        ToolExecutionResult result = manager.executeToolCalls(promptOfferingTools(KNOWN_TOOL), responseCalling(KNOWN_TOOL));

        AssistantMessage passedThrough = (AssistantMessage) result.conversationHistory().get(0);
        assertEquals(arguments, passedThrough.getToolCalls().get(0).arguments());
    }

    // --- persisted-history sanitization (2026-08-09 gap) -------------------
    // executeToolCalls only ever repairs the CURRENT turn's fresh tool-
    // execution output - it never touches history loaded from persisted
    // session storage and handed to a fresh ChatClient call as pre-existing
    // messages. sanitizeHistory closes that: a caller loading history from
    // SessionMemoryService runs it through here directly, no ToolCallingManager
    // instance or live tool execution required.

    @Test
    void sanitizeHistoryRepairsAPoisonedAssistantMessageFromManyTurnsAgo() {
        AssistantMessage poisoned = AssistantMessage.builder()
            .content("")
            .toolCalls(List.of(new AssistantMessage.ToolCall("call-77", "function", KNOWN_TOOL, "not json at all")))
            .build();
        List<Message> history = List.of(new UserMessage("earlier question"), poisoned, new UserMessage("current question"));

        List<Message> sanitized = ArgumentSanitizingToolCallingManager.sanitizeHistory(history, objectMapper);

        AssistantMessage repaired = (AssistantMessage) sanitized.get(1);
        assertEquals("{}", repaired.getToolCalls().get(0).arguments());
    }

    @Test
    void sanitizeHistoryLeavesValidToolCallsAndNonAssistantMessagesUntouched() {
        String arguments = "{\"fact\":\"he runs a brick business\"}";
        AssistantMessage valid = AssistantMessage.builder()
            .content("")
            .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", KNOWN_TOOL, arguments)))
            .build();
        UserMessage userMessage = new UserMessage("hello");
        List<Message> history = List.of(userMessage, valid);

        List<Message> sanitized = ArgumentSanitizingToolCallingManager.sanitizeHistory(history, objectMapper);

        assertSame(userMessage, sanitized.get(0));
        assertEquals(arguments, ((AssistantMessage) sanitized.get(1)).getToolCalls().get(0).arguments());
    }

    // --- helpers ----------------------------------------------------------

    private static Prompt promptOfferingTools(String... toolNames) {
        List<ToolCallback> callbacks = List.of(toolNames).stream()
            .map(name -> (ToolCallback) new StubToolCallback(name))
            .toList();
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
            .toolCallbacks(callbacks)
            .build();
        return new Prompt(List.of(new UserMessage("draw me a picture")), options);
    }

    private static ChatResponse responseCalling(String... toolNames) {
        List<AssistantMessage.ToolCall> calls = List.of(toolNames).stream()
            .map(name -> new AssistantMessage.ToolCall("call-" + name, "function", name, "{}"))
            .toList();
        AssistantMessage message = AssistantMessage.builder().content("").toolCalls(calls).build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static Optional<ToolCallback> callbackNamed(Prompt prompt, String name) {
        ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
        return options.getToolCallbacks().stream()
            .filter(callback -> name.equals(callback.getToolDefinition().name()))
            .findFirst();
    }

    /** Captures the prompt it was handed, so the test can inspect what the delegate would actually resolve against. */
    private static final class CapturingToolCallingManager implements ToolCallingManager {

        private Prompt captured;

        @Override
        public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
            return List.of();
        }

        @Override
        public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
            this.captured = prompt;
            return ToolExecutionResult.builder().conversationHistory(List.of()).returnDirect(false).build();
        }
    }

    /** Returns a fixed conversation history, so the argument-sanitizing half can be asserted on its own. */
    private record FixedHistoryToolCallingManager(List<Message> history) implements ToolCallingManager {

        @Override
        public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
            return List.of();
        }

        @Override
        public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
            return ToolExecutionResult.builder().conversationHistory(history).returnDirect(false).build();
        }
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
    }
}
