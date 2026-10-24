package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/**
 * 2026-08-13 live incident: this is now the class that converts a failed tool call into a safe,
 * non-throwing result - see its own javadoc for why that catch had to move here (outermost, past
 * {@code LoggingToolCallback}'s catch-log-rethrow) instead of living inside {@code
 * SanitizedMcpToolCallback} where an earlier fix first put it. {@link SanitizedMcpToolCallbackTest}
 * covers the sibling regression test (a delegate exception must propagate OUT of that class); this
 * file covers what happens to it once it gets here.
 */
class TenantContextPropagatingToolCallbackTest {

    private static ToolCallback fakeDelegate() {
        return mock(ToolCallback.class);
    }

    @Test
    void aSuccessfulPlainTextResultIsWrappedAsAJsonObject() {
        // Spring AI's Gemini integration (GoogleGenAiChatModel#parseJsonToMap) throws on any
        // tool result that isn't a JSON object - plain prose is the norm for real tool output,
        // not the exception.
        ToolCallback delegate = fakeDelegate();
        when(delegate.call("{}")).thenReturn("here are your invoices");
        TenantContextPropagatingToolCallback wrapped =
                new TenantContextPropagatingToolCallback(delegate, null);

        assertEquals("{\"result\":\"here are your invoices\"}", wrapped.call("{}"));
    }

    @Test
    void aResultThatIsAlreadyAJsonObjectPassesThroughUnchanged() {
        ToolCallback delegate = fakeDelegate();
        when(delegate.call("{}")).thenReturn("{\"key\":\"SCRUM-123\"}");
        TenantContextPropagatingToolCallback wrapped =
                new TenantContextPropagatingToolCallback(delegate, null);

        assertEquals("{\"key\":\"SCRUM-123\"}", wrapped.call("{}"));
    }

    @Test
    void aThrownExceptionFromTheDelegateBecomesASafeNonThrowingResult() {
        // The actual regression test: a failed MCP tool call (already logged/circuit-broken by
        // LoggingToolCallback, which sits INSIDE this class - see class javadoc) must not
        // propagate any further, or it kills the whole chat turn at the HTTP level.
        ToolCallback delegate = fakeDelegate();
        when(delegate.call("{\"project\":\"SCRUM\"}"))
                .thenThrow(
                        new RuntimeException(
                                "Error calling tool: [TextContent[text=Connection not found: SCRUM]]"));
        TenantContextPropagatingToolCallback wrapped =
                new TenantContextPropagatingToolCallback(delegate, null);

        String result = wrapped.call("{\"project\":\"SCRUM\"}");

        assertTrue(
                result.contains("Connection not found: SCRUM"),
                "expected the real failure reason, got: " + result);
        assertTrue(
                result.startsWith("{\"result\":"),
                "expected a JSON-wrapped object, got: " + result);
    }

    /**
     * The tool-step ceiling is the one deliberate hard stop in the guard chain; the exception
     * processor, the translating wrapper and the turn's error handling all list it as "propagate".
     * This catch used to turn it into an ordinary "this tool call failed" result too, which let the
     * model carry on calling tools past the ceiling it had just hit.
     */
    @Test
    void aToolStepLimitStopIsNotTurnedIntoAToolResult() {
        ToolCallback delegate = fakeDelegate();
        com.agenticai.core.guardrails.ToolStepLimitExceededException stop =
                new com.agenticai.core.guardrails.ToolStepLimitExceededException(
                        java.util.UUID.randomUUID(), "sess-1", 15);
        when(delegate.call("{}")).thenThrow(stop);
        TenantContextPropagatingToolCallback wrapped =
                new TenantContextPropagatingToolCallback(delegate, null);

        org.junit.jupiter.api.Assertions.assertSame(
                stop,
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.agenticai.core.guardrails.ToolStepLimitExceededException.class,
                        () -> wrapped.call("{}")));
    }

    @Test
    void aThrownExceptionWithNoMessageStillProducesAUsableResult() {
        ToolCallback delegate = fakeDelegate();
        when(delegate.call("{}")).thenThrow(new NullPointerException());
        TenantContextPropagatingToolCallback wrapped =
                new TenantContextPropagatingToolCallback(delegate, null);

        String result = wrapped.call("{}");

        assertTrue(
                result.contains("NullPointerException"),
                "expected a fallback identifying the failure, got: " + result);
    }

    @Test
    void tenantContextIsStillRestoredAfterTheDelegateThrows() {
        // The pre-existing context-capture/restore contract (see class javadoc) must survive
        // this fix unchanged - a failure inside safeCall's try must not skip the finally that
        // restores whatever TenantContext was on this thread before the call.
        ToolCallback delegate = fakeDelegate();
        when(delegate.call("{}")).thenThrow(new RuntimeException("boom"));
        TenantContext captured = TenantContext.of("caller-tenant", "admin-sub", Set.of());
        TenantContextPropagatingToolCallback wrapped =
                new TenantContextPropagatingToolCallback(delegate, captured);

        TenantContext.clear();
        try {
            wrapped.call("{}");
            assertEquals(
                    java.util.Optional.empty(),
                    TenantContext.current(),
                    "context must be cleared again, not left set to `captured`");
        } finally {
            TenantContext.clear();
        }
    }
}
