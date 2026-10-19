package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.PlatformIssueService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

/** Built-in tool results are capped before reaching the model; MCP results are not capped again. */
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

    @Test
    void builtInResultIsCapped() {
        String big = "x".repeat(McpToolOutputSanitizer.MAX_BUILTIN_RESULT_CHARS + 25);

        String out = wrap(big, null).call("{}");

        assertEquals(McpToolOutputSanitizer.capBuiltInResult(big), out);
        assertTrue(out.length() < big.length());
        assertTrue(out.endsWith("\n...[truncated 25 chars]"));
    }

    @Test
    void mcpResultIsNotCappedAgainByTheLoggingWrapper() {
        String big = "x".repeat(McpToolOutputSanitizer.MAX_BUILTIN_RESULT_CHARS + 25);

        String out = wrap(big, UUID.randomUUID()).call("{}");

        assertEquals(big, out);
    }
}
