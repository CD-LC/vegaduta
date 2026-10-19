package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

/**
 * Defensive MCP hardening (2026-08-05) - argument-value validation added to the existing {@link
 * SanitizedMcpToolCallback} (result sanitization already covered by {@link
 * McpToolOutputSanitizerTest}). Confirms a dangerous argument (path traversal / SSRF-shaped URL,
 * see {@link McpToolArgumentGuardTest}) is blocked BEFORE the delegate is ever invoked, and that an
 * ordinary argument reaches the delegate and still gets its result sanitized exactly as before.
 *
 * <p>2026-08-13 correction: a delegate exception is deliberately left to PROPAGATE out of this
 * class (see this class's own javadoc) - {@link TenantContextPropagatingToolCallbackTest} covers
 * the actual catch-and-wrap behavior, which now lives one layer further out, past {@code
 * LoggingToolCallback}. Only {@link #blockedResult} (never a delegate failure - the call never
 * happens) goes through {@link McpToolOutputSanitizer#ensureJsonObject} at this layer.
 */
class SanitizedMcpToolCallbackTest {

    private static ToolCallback fakeDelegate(String name) {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition()).thenReturn(new DefaultToolDefinition(name, "d", "{}"));
        return delegate;
    }

    @Test
    void benignArgumentsReachTheDelegateAndResultIsStillSanitized() {
        ToolCallback delegate = fakeDelegate("search");
        when(delegate.call("{\"q\":\"invoices\"}")).thenReturn("here are your invoices");
        SanitizedMcpToolCallback wrapped = new SanitizedMcpToolCallback(delegate);

        String result = wrapped.call("{\"q\":\"invoices\"}");

        assertEquals("here are your invoices", result);
        verify(delegate).call("{\"q\":\"invoices\"}");
    }

    @Test
    void benignArgumentsReachTheDelegateOnTheToolContextOverloadToo() {
        ToolCallback delegate = fakeDelegate("search");
        ToolContext context = new ToolContext(java.util.Map.of());
        when(delegate.call("{\"q\":\"invoices\"}", context)).thenReturn("ok");
        SanitizedMcpToolCallback wrapped = new SanitizedMcpToolCallback(delegate);

        assertEquals("ok", wrapped.call("{\"q\":\"invoices\"}", context));
        verify(delegate).call("{\"q\":\"invoices\"}", context);
    }

    @Test
    void pathTraversalArgumentIsBlockedBeforeReachingTheDelegate() {
        ToolCallback delegate = fakeDelegate("read_file");
        SanitizedMcpToolCallback wrapped = new SanitizedMcpToolCallback(delegate);

        String result = wrapped.call("{\"path\": \"../../../etc/passwd\"}");

        assertTrue(result.contains("blocked"), "expected a block explanation, got: " + result);
        assertTrue(
                result.contains("read_file"),
                "expected the tool name to be echoed, got: " + result);
        verify(delegate, never()).call(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void ssrfShapedUrlArgumentIsBlockedBeforeReachingTheDelegate() {
        ToolCallback delegate = fakeDelegate("fetch_url");
        SanitizedMcpToolCallback wrapped = new SanitizedMcpToolCallback(delegate);

        String result = wrapped.call("{\"url\": \"http://169.254.169.254/latest/meta-data/\"}");

        assertTrue(result.contains("blocked"), "expected a block explanation, got: " + result);
        verify(delegate, never()).call(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void dangerousArgumentIsBlockedOnTheToolContextOverloadToo() {
        ToolCallback delegate = fakeDelegate("fetch_url");
        ToolContext context = new ToolContext(java.util.Map.of());
        SanitizedMcpToolCallback wrapped = new SanitizedMcpToolCallback(delegate);

        String result = wrapped.call("{\"url\": \"http://127.0.0.1/admin\"}", context);

        assertTrue(result.contains("blocked"));
        verify(delegate, never())
                .call(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void blockedResultIsWrappedAsARealJsonObject() {
        // blockedResult never throws (the delegate is never called), so wrapping it here is
        // unconditionally safe - unlike a delegate failure, which must propagate (see class
        // javadoc's 2026-08-13 note) so LoggingToolCallback can log/circuit-break it first.
        ToolCallback delegate = fakeDelegate("read_file");
        SanitizedMcpToolCallback wrapped = new SanitizedMcpToolCallback(delegate);

        String result = wrapped.call("{\"path\": \"../../../etc/passwd\"}");

        assertTrue(
                result.startsWith("{\"result\":"),
                "expected a JSON-wrapped object, got: " + result);
    }

    @Test
    void aThrownExceptionFromTheDelegatePropagatesRatherThanBeingSwallowed() {
        // 2026-08-13 correction (see class javadoc): catching here used to hide every MCP
        // failure from LoggingToolCallback (which wraps THIS class), silently breaking
        // AgentChatService#firstToolFailureSince's auto-replan, the circuit breaker's failure
        // streak, and the trace UI's error indicator - all three depend on the exception
        // actually reaching LoggingToolCallback's catch-log-rethrow. Verifying propagation
        // here, not swallowing, is the regression test for that incident.
        ToolCallback delegate = fakeDelegate("jira_create_issue");
        RuntimeException failure =
                new RuntimeException(
                        "Error calling tool: [TextContent[text=Connection not found: SCRUM]]");
        when(delegate.call("{\"project\":\"SCRUM\"}")).thenThrow(failure);
        SanitizedMcpToolCallback wrapped = new SanitizedMcpToolCallback(delegate);

        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> wrapped.call("{\"project\":\"SCRUM\"}"));
        assertEquals(failure, thrown);
    }

    @Test
    void aThrownExceptionFromTheDelegatePropagatesOnTheToolContextOverloadToo() {
        ToolCallback delegate = fakeDelegate("jira_create_issue");
        ToolContext context = new ToolContext(java.util.Map.of());
        RuntimeException failure = new RuntimeException("Connection not found: SCRUM");
        when(delegate.call("{\"project\":\"SCRUM\"}", context)).thenThrow(failure);
        SanitizedMcpToolCallback wrapped = new SanitizedMcpToolCallback(delegate);

        RuntimeException thrown =
                assertThrows(
                        RuntimeException.class,
                        () -> wrapped.call("{\"project\":\"SCRUM\"}", context));
        assertEquals(failure, thrown);
    }

    @Test
    void malformedJsonArgumentsAreNotBlockedByThisGuard() {
        // ArgumentSanitizingToolCallingManager already owns "repair malformed
        // JSON before replay" - this guard fails open on unparsable input
        // rather than duplicating that concern (see McpToolArgumentGuard's
        // javadoc), so a non-JSON argument still reaches the delegate.
        ToolCallback delegate = fakeDelegate("search");
        when(delegate.call("not-json")).thenReturn("whatever");
        SanitizedMcpToolCallback wrapped = new SanitizedMcpToolCallback(delegate);

        assertEquals("whatever", wrapped.call("not-json"));
        verify(delegate).call("not-json");
    }

    @Test
    void anOutputOnlyWrapperLetsAFirstPartyToolsDangerousLookingArgumentsThrough() {
        // http_request bodies legitimately carry URLs and paths (a webhook callback_url); the
        // executor owns SSRF.
        ToolCallback delegate = fakeDelegate("http_request_write");
        String input = "{\"path\": \"../../../etc/passwd\"}";
        when(delegate.call(input)).thenReturn("sent");
        SanitizedMcpToolCallback wrapped = new SanitizedMcpToolCallback(delegate, false);

        assertEquals("sent", wrapped.call(input));
        verify(delegate).call(input);
    }
}
