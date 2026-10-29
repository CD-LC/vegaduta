package com.agenticai.core.agents.turn.approval;

import com.agenticai.core.agents.turn.ApprovalContinuationProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.turn.ApprovalContinuation;
import com.agenticai.core.agents.turn.ApprovalContinuation.Outcome;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

class ApprovalContinuationFactoryTest {

    private static final String TENANT = "tenant-a";
    private static final String SUBJECT = "user-1";
    private static final String SESSION = "sess-1";
    private static final UUID REQUESTED_AGENT = UUID.randomUUID();
    private static final UUID ROUTED_AGENT = UUID.randomUUID();
    private static final String GOAL = "Send invoice INV-7731.";
    private static final String SCOPED =
            SessionMemoryService.scopedSessionId(TENANT, REQUESTED_AGENT, SUBJECT, SESSION);

    private final HumanApprovalGateService approvals = mock(HumanApprovalGateService.class);
    private final ToolCallLogService toolLog = mock(ToolCallLogService.class);
    private final SessionMemoryService memory = mock(SessionMemoryService.class);
    private final ApprovalContinuationProperties properties = new ApprovalContinuationProperties();
    private final ApprovalContinuationFactory factory =
            new ApprovalContinuationFactory(
                    new ObjectMapper(), approvals, provider(toolLog), provider(memory), properties);

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    private static ApprovalRequest request(String payload) {
        ApprovalRequest r = new ApprovalRequest("mcp_tool.send_invoice", "Tool call", payload, "agent:" + ROUTED_AGENT + ":session:" + SESSION);
        ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
        return r;
    }

    private static String envelope(String goal, String memory, String subjectKey) {
        return "{\"replay\":{\"version\":1,\"toolName\":\"send_invoice\",\"agentId\":\""
                + ROUTED_AGENT
                + "\",\"sessionId\":\""
                + SESSION
                + "\""
                + (memory == null ? "" : ",\"memorySessionId\":\"" + memory + "\"")
                + (goal == null ? "" : ",\"goal\":\"" + goal + "\"")
                + ",\"requestedAgentId\":\""
                + REQUESTED_AGENT
                + "\""
                + (subjectKey == null ? "" : ",\"subjectKey\":\"" + subjectKey + "\"")
                + ",\"tenantId\":\""
                + TENANT
                + "\",\"subject\":\""
                + SUBJECT
                + "\"},\"arguments\":{\"to\":\"x\"}}";
    }

    @Test
    void anEnvelopeWithTheGoalAndIdentityBecomesAContinuation() {
        ApprovalRequest request = request(envelope(GOAL, SCOPED, SUBJECT));

        Optional<ApprovalContinuation> built = factory.build(request, Outcome.COMPLETED, "sent");

        assertThat(built).isPresent();
        ApprovalContinuation c = built.get();
        assertThat(c.requestId()).isEqualTo(request.getId());
        assertThat(c.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(c.toolName()).isEqualTo("send_invoice");
        assertThat(c.argumentsJson()).isEqualTo("{\"to\":\"x\"}");
        assertThat(c.result()).isEqualTo("sent");
        assertThat(c.goal()).isEqualTo(GOAL);
        assertThat(c.requestedAgentId()).isEqualTo(REQUESTED_AGENT);
        assertThat(c.sessionId()).isEqualTo(SESSION);
        assertThat(c.subjectKey()).isEqualTo(SUBJECT);
        assertThat(c.tenantId()).isEqualTo(TENANT);
        assertThat(c.subject()).isEqualTo(SUBJECT);
        assertThat(c.memorySessionId()).isEqualTo(SCOPED);
        assertThat(c.stickyTools()).containsExactly("send_invoice");
    }

    @Test
    void anActionAToolGatedItselfOnHasItsOwnFieldsAsTheArguments() {
        // A code-workspace run keeps repoId/command/... at the top level next to the envelope (no "arguments" key).
        String selfGated = envelope(GOAL, SCOPED, SUBJECT)
                .replace(",\"arguments\":{\"to\":\"x\"}}", ",\"repoId\":\"billing\",\"command\":\"mvn -q test\"}")
                .replace("send_invoice", "run_in_code_workspace");

        ApprovalContinuation c = factory.build(request(selfGated), Outcome.COMPLETED, "exit 0").orElseThrow();

        assertThat(c.toolName()).isEqualTo("run_in_code_workspace");
        assertThat(c.argumentsJson()).isEqualTo("{\"repoId\":\"billing\",\"command\":\"mvn -q test\"}");
        assertThat(c.argumentsJson()).doesNotContain("replay").doesNotContain(TENANT);
    }

    @Test
    void anEnvelopeRecordedWithoutAGoalIsNotContinued() {
        assertThat(factory.build(request(envelope(null, SCOPED, SUBJECT)), Outcome.COMPLETED, "x")).isEmpty();
    }

    @Test
    void anEnvelopeWithoutTheMemorySubjectFallsBackToTheTenantSubject() {
        // The envelope's scoped memory id still verifies against the identity, so the missing
        // subject key falls back to the recorded subject rather than dropping the continuation.
        ApprovalContinuation c =
                factory.build(request(envelope(GOAL, SCOPED, null)), Outcome.COMPLETED, "x").orElseThrow();

        assertThat(c.subjectKey()).isEqualTo(SUBJECT);
        assertThat(c.memorySessionId()).isEqualTo(SCOPED);
    }

    @Test
    void aRawArgumentsPayloadAndGarbageAreNotContinued() {
        assertThat(factory.build(request("{\"to\":\"x\"}"), Outcome.COMPLETED, "x")).isEmpty();
        assertThat(factory.build(request("not json"), Outcome.COMPLETED, "x")).isEmpty();
        assertThat(factory.build(request(null), Outcome.COMPLETED, "x")).isEmpty();
    }

    @Test
    void aRecordedHistoryIdThatTheIdentityDoesNotDeriveIsRefused() {
        // Another conversation's history id under this request's tenant, agent and subject.
        String foreign = SessionMemoryService.scopedSessionId("tenant-b", REQUESTED_AGENT, "someone", "their-session");

        assertThat(factory.build(request(envelope(GOAL, foreign, SUBJECT)), Outcome.COMPLETED, "x")).isEmpty();
    }

    @Test
    void toolsTheConversationAlreadyUsedAreKeptMostRecentFirst() {
        ToolCallLog older = mock(ToolCallLog.class);
        when(older.getToolName()).thenReturn("lookup_customer");
        ToolCallLog newer = mock(ToolCallLog.class);
        when(newer.getToolName()).thenReturn("search_orders");
        when(toolLog.findSince(eq(ROUTED_AGENT), eq(SESSION), any(Instant.class))).thenReturn(List.of(older, newer));

        ApprovalContinuation c =
                factory.build(request(envelope(GOAL, SCOPED, SUBJECT)), Outcome.COMPLETED, "x").orElseThrow();

        assertThat(c.stickyTools()).containsExactly("send_invoice", "search_orders", "lookup_customer");
    }

    @Test
    void theNumberOfStickyToolsIsBounded() {
        properties.setMaxStickyTools(2);
        ToolCallLog a = mock(ToolCallLog.class);
        when(a.getToolName()).thenReturn("a");
        ToolCallLog b = mock(ToolCallLog.class);
        when(b.getToolName()).thenReturn("b");
        when(toolLog.findSince(eq(ROUTED_AGENT), eq(SESSION), any(Instant.class))).thenReturn(List.of(a, b));

        ApprovalContinuation c =
                factory.build(request(envelope(GOAL, SCOPED, SUBJECT)), Outcome.COMPLETED, "x").orElseThrow();

        assertThat(c.stickyTools()).containsExactly("send_invoice", "b");
    }

    @Test
    void aBrokenToolLogStillYieldsAContinuationWithTheApprovedTool() {
        when(toolLog.findSince(any(), any(), any())).thenThrow(new IllegalStateException("db down"));

        ApprovalContinuation c =
                factory.build(request(envelope(GOAL, SCOPED, SUBJECT)), Outcome.COMPLETED, "x").orElseThrow();

        assertThat(c.stickyTools()).containsExactly("send_invoice");
    }

    private static String scopeOnlyEnvelope() {
        // What a caller with no turn origin records: the call's identity + tenant context, no goal,
        // no subjectKey, no requestedAgentId, no memorySessionId.
        return "{\"replay\":{\"version\":1,\"toolName\":\"send_invoice\",\"agentId\":\""
                + ROUTED_AGENT
                + "\",\"sessionId\":\""
                + SESSION
                + "\",\"tenantId\":\""
                + TENANT
                + "\",\"subject\":\""
                + SUBJECT
                + "\"},\"arguments\":{\"to\":\"x\"}}";
    }

    @Test
    void aScopeOnlyEnvelopeContinuesTheConversationItsFieldsDerive() {
        String derived =
                SessionMemoryService.scopedSessionId(TENANT, ROUTED_AGENT, SUBJECT, SESSION);
        when(memory.getHistory(derived))
                .thenReturn(
                        List.of(
                                ChatTurn.user("Send the invoice."),
                                ChatTurn.assistant("On it.")));

        Optional<ApprovalContinuation> built =
                factory.build(request(scopeOnlyEnvelope()), Outcome.COMPLETED, "sent");

        assertThat(built).isPresent();
        ApprovalContinuation c = built.get();
        // agent + subject key fall back to the routed agent / tenant subject; the goal is the
        // conversation's last user turn.
        assertThat(c.memorySessionId()).isEqualTo(derived);
        assertThat(c.subjectKey()).isEqualTo(SUBJECT);
        assertThat(c.requestedAgentId()).isEqualTo(ROUTED_AGENT);
        assertThat(c.goal()).isEqualTo("Send the invoice.");
    }

    @Test
    void aScopeOnlyEnvelopeForASessionWithNoHistoryDoesNotContinue() {
        String derived =
                SessionMemoryService.scopedSessionId(TENANT, ROUTED_AGENT, SUBJECT, SESSION);
        when(memory.getHistory(derived)).thenReturn(List.of());

        assertThat(factory.build(request(scopeOnlyEnvelope()), Outcome.COMPLETED, "sent"))
                .isEmpty();
    }

    @Test
    void aGoalOnAPartialEnvelopeStillRequiresTheDerivedSessionToExist() {
        // The envelope records the goal but not the memory session id; a wrong derivation must not
        // start a turn under a conversation that does not exist.
        String partial = envelope(GOAL, null, SUBJECT);
        String derived =
                SessionMemoryService.scopedSessionId(TENANT, REQUESTED_AGENT, SUBJECT, SESSION);
        when(memory.getHistory(derived)).thenReturn(List.of());

        assertThat(factory.build(request(partial), Outcome.COMPLETED, "sent")).isEmpty();
    }

    @Test
    void otherPendingActionsOfTheSameConversationAreDescribedButNotTheDecidedOne() {
        ApprovalRequest decided = request(envelope(GOAL, SCOPED, SUBJECT));
        ApprovalRequest other = request(envelope(GOAL, SCOPED, SUBJECT).replace("send_invoice", "send_receipt"));
        when(approvals.findPendingRequestedBy("agent:" + ROUTED_AGENT + ":session:" + SESSION))
                .thenReturn(List.of(decided, other));

        ApprovalContinuation c = factory.build(decided, Outcome.COMPLETED, "x").orElseThrow();

        assertThat(c.otherPending()).hasSize(1);
        assertThat(c.otherPending().get(0)).startsWith("send_receipt {\"to\":\"x\"}").contains(String.valueOf(other.getId()));
    }
}
