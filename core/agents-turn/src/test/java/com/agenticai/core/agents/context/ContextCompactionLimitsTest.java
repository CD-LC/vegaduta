package com.agenticai.core.agents.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.context.ContextCompactionEngine.Params;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.tools.context.RestoreToolResultToolCallback;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * The token estimates, the context-window name rules and the size of a restored tool result are {@link
 * ContextCompactionProperties} ({@code app.context.compaction.*}); the defaults are the old constants.
 */
class ContextCompactionLimitsTest {

    @Test
    void theDefaultsAreTheOldConstants() {
        ContextCompactionProperties p = new ContextCompactionProperties();
        assertEquals(4, p.getMessageOverheadTokens());
        assertEquals(1000, p.getMediaTokens());
        assertEquals(6, p.getResponseOverheadTokens());
        assertEquals(40, p.getSummaryFrameTokens());
        assertEquals(48, p.getPlaceholderTokens());
        assertEquals(16 * 1024, p.getRestoreResultMaxChars());
        assertEquals(0.6, ContextCompactionProperties.DEFAULT_CLEAR_AT);
        assertEquals(0.85, ContextCompactionProperties.DEFAULT_COMPACT_AT);
        assertEquals(3, ContextCompactionProperties.DEFAULT_KEEP_RECENT_TOOL_RESULTS);
        assertEquals(800, ContextCompactionProperties.DEFAULT_SUMMARY_TOKENS);
        assertEquals(128_000, ContextCompactionProperties.DEFAULT_WINDOW_TOKENS);
    }

    @Test
    void theMessageOverheadIsConfigurable() {
        Message message = new UserMessage("hello there");
        int base = (int) SessionMemoryService.estimateTokens("hello there");
        assertEquals(base + 4, ContextCompactionEngine.messageTokens(message));

        ContextCompactionProperties p = new ContextCompactionProperties();
        p.setMessageOverheadTokens(20);
        assertEquals(base + 20, ContextCompactionEngine.messageTokens(message, p));
    }

    @Test
    void theToolCallOverheadIsConfigurable() {
        Message message = AssistantMessage.builder()
                .content("ok")
                .toolCalls(List.of(new AssistantMessage.ToolCall("1", "function", "search", "{}")))
                .build();
        ContextCompactionProperties p = new ContextCompactionProperties();
        int byDefault = ContextCompactionEngine.messageTokens(message, p);
        p.setResponseOverheadTokens(26);

        assertEquals(byDefault + 20, ContextCompactionEngine.messageTokens(message, p));
    }

    @Test
    void theToolResponseOverheadIsConfigurable() {
        Message message = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("1", "search", "result")))
                .build();
        ContextCompactionProperties p = new ContextCompactionProperties();
        int byDefault = ContextCompactionEngine.messageTokens(message, p);
        p.setResponseOverheadTokens(16);

        assertEquals(byDefault + 10, ContextCompactionEngine.messageTokens(message, p));
    }

    @Test
    void theEstimatesCarriedByTheParamsAreWhatTheEngineCounts() {
        List<Message> messages = List.of(new UserMessage("hello there"));
        ContextCompactionProperties p = new ContextCompactionProperties();
        p.setMessageOverheadTokens(100);
        Params params = new Params(10_000, 0.6, 0.85, 3, 6, 800, false, p);

        ContextCompactionEngine.Result result = new ContextCompactionEngine().apply(messages, 0, params, null);

        assertEquals((int) SessionMemoryService.estimateTokens("hello there") + 100, result.tokensBefore());
    }

    @Test
    void theSevenArgumentParamsKeepTheShippedEstimates() {
        Params params = new Params(10_000, 0.6, 0.85, 3, 6, 800, false);
        List<Message> messages = List.of(new UserMessage("hello there"));

        assertEquals(
                (int) SessionMemoryService.estimateTokens("hello there") + 4,
                new ContextCompactionEngine().apply(messages, 0, params, null).tokensBefore());
    }

    @Test
    void theWindowRulesAreConfigurable() {
        ContextCompactionProperties p = new ContextCompactionProperties();
        assertEquals(200_000, ModelContextWindows.heuristic("claude-sonnet-x", 128_000, p.getWindowRules()));
        assertEquals(200_000, ModelContextWindows.heuristic("models/gemini-2.5-pro", 128_000, p.getWindowRules()));
        assertEquals(128_000, ModelContextWindows.heuristic("openai/gpt-5-mini", 64_000, p.getWindowRules()));
        assertEquals(128_000, ModelContextWindows.heuristic("o3-mini", 64_000, p.getWindowRules()));
        assertEquals(64_000, ModelContextWindows.heuristic("some-local-model", 64_000, p.getWindowRules()));
        assertEquals(64_000, ModelContextWindows.heuristic("a-mo3-x", 64_000, p.getWindowRules()), "o3 only as a prefix");

        ContextCompactionProperties.WindowRule rule = new ContextCompactionProperties.WindowRule();
        rule.setContains(List.of("claude"));
        rule.setTokens(1_000_000);
        p.setWindowRules(List.of(rule));
        assertEquals(1_000_000, ModelContextWindows.heuristic("claude-sonnet-x", 128_000, p.getWindowRules()));
        assertEquals(64_000, ModelContextWindows.heuristic("gemini-2.5-pro", 64_000, p.getWindowRules()));
    }

    @Test
    void aCatalogWindowStillWinsOverTheRules() {
        ContextCompactionProperties p = new ContextCompactionProperties();
        assertEquals(42_000, ModelContextWindows.resolve(42_000, "claude-x", 128_000, p.getWindowRules()));
        assertEquals(200_000, ModelContextWindows.resolve(null, "claude-x", 128_000, p.getWindowRules()));
    }

    @Test
    void aRestoredToolResultIsCutAtTheConfiguredSize() {
        UUID agent = UUID.randomUUID();
        ToolCallLogService logs = mock(ToolCallLogService.class);
        Instant now = Instant.now();
        ToolCallLog logged = new ToolCallLog(agent, "s", "search", "{}", "z".repeat(1000), null, now, now);
        when(logs.findRecentByAgentIdAndSessionIdAndToolName(any(), any(), any())).thenReturn(List.of(logged));
        RestoreToolResultToolCallback tool = new RestoreToolResultToolCallback(logs, agent, "s", 100);

        String restored = tool.call("{\"stepId\":\"" + ToolResultRestoreIds.stepId("search", "{}") + "\"}");

        assertTrue(restored.contains("[truncated 900 chars]"), restored);
    }
}
