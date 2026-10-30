package com.agenticai.core.agents.tools.approvaltrace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.approval.ApprovalHistoryService;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalRequestNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApprovalTraceServiceTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final String SESSION = "sess-1";

    private final ApprovalHistoryService requests = mock(ApprovalHistoryService.class);
    private final ToolCallLogRepository calls = mock(ToolCallLogRepository.class);
    private final ApprovalTraceService service =
            new ApprovalTraceService(requests, calls, new ObjectMapper());

    private ApprovalRequest request(String payload, boolean approved) {
        ApprovalRequest r =
                new ApprovalRequest("mcp_tool.load_skill", "Load skill", payload, "agent");
        org.springframework.test.util.ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
        if (approved) {
            r.markApproved("admin-1");
        }
        when(requests.find(r.getId())).thenReturn(Optional.of(r));
        return r;
    }

    private static String envelope() {
        return "{\"replay\":{\"agentId\":\""
                + AGENT
                + "\",\"sessionId\":\""
                + SESSION
                + "\",\"goal\":\"write the report\"}}";
    }

    private static ToolCallLog call(String tool, Instant at, String output, String error) {
        return new ToolCallLog(
                AGENT, SESSION, tool, "{\"name\":\"x\"}", output, error, at, at.plusMillis(50));
    }

    @Test
    void listsTheCallsMadeAfterTheDecisionAndFlagsFailures() {
        ApprovalRequest r = request(envelope(), true);
        Instant at = r.getDecidedAt();
        when(calls.findTop200ByAgentIdAndSessionIdAndStartedAtGreaterThanEqualOrderByStartedAtAsc(
                        AGENT, SESSION, at))
                .thenReturn(
                        List.of(
                                call("load_skill", at, "# Skill", null),
                                call("generate_document", at.plusSeconds(5), null, "boom")));

        ApprovalTraceView view = service.trace(r.getId());

        assertEquals("APPROVED", view.status());
        assertEquals("admin-1", view.decidedBy());
        assertEquals(AGENT, view.conversation().agentId());
        assertEquals("write the report", view.conversation().goal());
        assertEquals(2, view.calls().size());
        assertEquals("OK", view.calls().get(0).outcome());
        assertEquals("FAILED", view.calls().get(1).outcome());
        assertTrue(view.traceNote().contains("matched by conversation and time"));
    }

    @Test
    void dropsCallsAfterTheWindow() {
        ApprovalRequest r = request(envelope(), true);
        Instant at = r.getDecidedAt();
        when(calls.findTop200ByAgentIdAndSessionIdAndStartedAtGreaterThanEqualOrderByStartedAtAsc(
                        any(), anyString(), any()))
                .thenReturn(
                        List.of(
                                call("a", at, "ok", null),
                                call(
                                        "b",
                                        at.plus(ApprovalTraceService.WINDOW)
                                                .plus(Duration.ofSeconds(1)),
                                        "late",
                                        null)));

        assertEquals(1, service.trace(r.getId()).calls().size());
    }

    @Test
    void aRequestWithoutAConversationSaysSoAndReadsNoCalls() {
        ApprovalRequest r = request("{}", true);

        ApprovalTraceView view = service.trace(r.getId());

        assertNull(view.conversation());
        assertTrue(view.calls().isEmpty());
        assertTrue(view.traceNote().contains("did not come from an agent turn"));
        verifyNoInteractions(calls);
    }

    @Test
    void anUndecidedRequestHasNoCalls() {
        ApprovalRequest r = request(envelope(), false);

        ApprovalTraceView view = service.trace(r.getId());

        assertTrue(view.calls().isEmpty());
        assertTrue(view.traceNote().startsWith("Not decided yet"));
        verifyNoInteractions(calls);
    }

    @Test
    void masksBearerTokensAndLongOpaqueValuesAndShortensText() {
        String masked =
                ApprovalTraceService.summary(
                        "Authorization: Bearer abc.def-ghi key=ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcd "
                                + "x".repeat(400));
        assertTrue(masked.contains("Bearer [redacted]"));
        assertTrue(masked.contains("[redacted]"));
        assertTrue(masked.length() <= ApprovalTraceService.SUMMARY_CHARS + 3);
        assertNull(ApprovalTraceService.summary("  "));
    }

    @Test
    void anUnknownRequestIsNotFound() {
        UUID id = UUID.randomUUID();
        when(requests.find(id)).thenReturn(Optional.empty());
        assertThrows(ApprovalRequestNotFoundException.class, () -> service.trace(id));
    }
}
