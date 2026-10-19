package com.agenticai.core.agents.tools.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RequestHumanToolCallback}. Manual {@code mock(X.class)} + constructor
 * injection, same convention {@code AddToKnowledgeBaseToolCallbackTest} already uses for a
 * single-service-backed tool callback in this codebase (no
 * {@code @ExtendWith(MockitoExtension.class)}, no Spring context).
 */
class RequestHumanToolCallbackTest {

    private HumanHandoffPort handoffPort;
    private RequestHumanToolCallback callback;

    @BeforeEach
    void setUp() {
        handoffPort = mock(HumanHandoffPort.class);
        callback = new RequestHumanToolCallback(handoffPort, "sess-1");
    }

    @Test
    void toolDefinitionHasExpectedNameAndRequiredArgs() {
        assertEquals("request_human", callback.getToolDefinition().name());
        assertTrue(callback.getToolDefinition().inputSchema().contains("\"reason\""));
    }

    @Test
    void delegatesToThePortWithTheConstructorCapturedSessionIdNotAModelSuppliedOne() {
        when(handoffPort.requestHuman(eq("sess-1"), eq("Customer wants a refund")))
                .thenReturn(
                        new HumanHandoffPort.HandoffOutcome(
                                true,
                                "A team member has been notified and will join this conversation shortly."));

        // Even if the model tried to smuggle a sessionId into the input, the tool only ever reads
        // "reason" from toolInput - the session identity is fixed at construction time.
        String out =
                callback.call(
                        "{\"reason\":\"Customer wants a refund\",\"sessionId\":\"attacker-controlled\"}");

        verify(handoffPort).requestHuman("sess-1", "Customer wants a refund");
        assertTrue(
                out.contains("\"escalated\":true"),
                "expected escalated:true in output, got: " + out);
        assertTrue(
                out.contains("notified"),
                "expected the port's detail message to be surfaced, got: " + out);
    }

    @Test
    void surfacesADeclinedEscalationAsEscalatedFalseRatherThanAnError() {
        when(handoffPort.requestHuman(eq("sess-1"), eq("just curious")))
                .thenReturn(
                        new HumanHandoffPort.HandoffOutcome(
                                false,
                                "This conversation has not been approved by an administrator yet."));

        String out = callback.call("{\"reason\":\"just curious\"}");

        assertTrue(
                out.contains("\"escalated\":false"),
                "expected escalated:false in output, got: " + out);
        assertTrue(
                out.contains("not been approved"),
                "expected the port's decline detail, got: " + out);
    }

    @Test
    void rejectsMissingReason() {
        String out = callback.call("{}");
        assertTrue(
                out.contains("reason is required"), "expected a required-arg error, got: " + out);
        verify(handoffPort, never())
                .requestHuman(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void rejectsBlankReason() {
        String out = callback.call("{\"reason\":\"   \"}");
        assertTrue(
                out.contains("reason is required"), "expected a required-arg error, got: " + out);
        verify(handoffPort, never())
                .requestHuman(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void surfacesAnUnexpectedPortFailureAsCleanJsonRatherThanThrowing() {
        when(handoffPort.requestHuman(eq("sess-1"), eq("hello")))
                .thenThrow(new RuntimeException("unexpected failure with \"quotes\" in it"));

        String out = callback.call("{\"reason\":\"hello\"}");

        assertTrue(
                out.contains("Could not request a human"), "expected a clean error, got: " + out);
        // Must still be valid JSON even though the underlying exception message contains raw
        // quotes - same "route error text through Jackson, never string-concat it" convention
        // ForwardAttachmentToolCallback/AddToKnowledgeBaseToolCallback both follow.
        assertTrue(
                out.startsWith("{") && out.endsWith("}"),
                "expected valid-looking JSON, got: " + out);
    }
}
