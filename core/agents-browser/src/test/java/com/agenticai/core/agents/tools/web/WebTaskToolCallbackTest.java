package com.agenticai.core.agents.tools.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins {@code web_task}'s failure contract, mirroring {@link WebSearchToolCallbackTest}: {@link
 * WebTaskToolCallback#call} must NEVER throw - a browser-provider outage returns a relayable
 * sentence, not a turn-killing exception.
 */
class WebTaskToolCallbackTest {

    private static WebTaskToolCallback withBackend(WebTaskBackend backend) {
        return new WebTaskToolCallback(() -> backend);
    }

    @Test
    void toolDefinitionExposesWebTaskNameAndSchema() {
        WebTaskToolCallback cb = withBackend(null);
        assertEquals("web_task", cb.getToolDefinition().name());
        assertTrue(cb.getToolDefinition().description().contains("browser"));
        assertTrue(cb.getToolDefinition().inputSchema().contains("\"task\""));
    }

    @Test
    void blankTaskReturnsRelayableFailure() {
        String out = assertDoesNotThrow(() -> withBackend(null).call("{\"task\":\"\"}"));
        assertTrue(out.contains("'task' argument is required"), out);
    }

    @Test
    void malformedJsonReturnsRelayableFailure() {
        String out = assertDoesNotThrow(() -> withBackend(null).call("{not json"));
        assertTrue(out.startsWith("Could not run the browser task"), out);
    }

    @Test
    void nonHttpStartUrlIsRejected() {
        String out = withBackend(null).call("{\"task\":\"x\",\"startUrl\":\"file:///etc/passwd\"}");
        assertTrue(out.contains("http"), out);
    }

    @Test
    void missingBackendReturnsRelayableFailure() {
        String out = withBackend(null).call("{\"task\":\"open example.com and read the title\"}");
        assertTrue(out.contains("no managed-browser provider is configured"), out);
    }

    @Test
    void backendExceptionReturnsRelayableFailure() {
        WebTaskBackend failing =
                req -> {
                    throw new WebTaskBackend.WebTaskException(
                            "provider returned HTTP 401: unauthorized");
                };
        String out = assertDoesNotThrow(() -> withBackend(failing).call("{\"task\":\"t\"}"));
        assertTrue(out.contains("HTTP 401"), out);
    }

    @Test
    void successfulTaskFramesResultAsUntrustedContent() {
        WebTaskBackend ok =
                req -> new WebTaskBackend.WebTaskResult("success", "downloaded 3 invoices", null);
        String out =
                withBackend(ok)
                        .call(
                                "{\"task\":\"get invoices\",\"startUrl\":\"https://portal.example.com\"}");
        assertTrue(out.contains("Browser task completed"), out);
        assertTrue(out.contains("not instructions"), out);
        assertTrue(out.contains("downloaded 3 invoices"), out);
    }

    @Test
    void taskLevelFailureIsRelayedWithStatus() {
        WebTaskBackend failed =
                req -> new WebTaskBackend.WebTaskResult("failed", "", "login rejected");
        String out = withBackend(failed).call("{\"task\":\"t\"}");
        assertTrue(out.contains("status: failed"), out);
        assertTrue(out.contains("login rejected"), out);
    }
}
