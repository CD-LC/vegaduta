package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.guardrails.ToolOutputProperties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Built-in tool results are capped before reaching the model at the shared {@code app.tools.output.*}
 * limit (it used to be a separate 16 KB constant, below the 20,000-character guard cap); MCP results are
 * not capped again by this wrapper.
 */
class LoggingToolCallbackCapTest {

    private static LoggingToolCallback wrap(String output, UUID mcpGrantId) {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name("some_tool")
                                .description("d")
                                .inputSchema("{}")
                                .build());
        when(delegate.call(anyString())).thenReturn(output);
        @SuppressWarnings("unchecked")
        ObjectProvider<PlatformIssueService> issues = mock(ObjectProvider.class);
        return new LoggingToolCallback(
                delegate,
                mock(ToolCallLogRepository.class),
                UUID.randomUUID(),
                "session",
                issues,
                mock(ToolCircuitBreakerService.class),
                mcpGrantId);
    }

    private static final int CAP = new ToolOutputProperties().getMaxChars();

    @Test
    void builtInResultIsCappedAtTheSharedLimitWithAMarkerStatingTheOriginalLength() {
        String big = "x".repeat(CAP + 25);

        String out = wrap(big, null).call("{}");

        assertEquals(new ToolOutputProperties().limit("some_tool", big), out);
        assertTrue(out.length() <= CAP);
        assertTrue(out.contains("of " + (CAP + 25) + " characters"), out.substring(out.length() - 300));
    }

    @Test
    void theOperatorsLimitAppliesIncludingAPerToolEntry() {
        ToolOutputProperties limits = new ToolOutputProperties();
        limits.getPerTool().put("some_tool", 2_000);

        String out = wrap("y".repeat(10_000), null).withOutputLimits(limits).call("{}");

        assertTrue(out.length() <= 2_000, "got " + out.length());
    }

    @Test
    void aResultWithinTheLimitIsReturnedAsIs() {
        assertEquals("small", wrap("small", null).call("{}"));
    }

    @Test
    void mcpResultIsNotCappedAgainByTheLoggingWrapper() {
        String big = "x".repeat(CAP + 25);

        String out = wrap(big, UUID.randomUUID()).call("{}");

        assertEquals(big, out);
    }
}
