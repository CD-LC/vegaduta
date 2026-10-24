package com.agenticai.core.guardrails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ApprovalPolicyService;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.approval.ToolCallOrigin;
import java.util.Set;
import org.mockito.ArgumentCaptor;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * What a parked call tells the model, what happens to the calls that follow it in the same turn,
 * and what happens when the approval request cannot be recorded. Real {@link GuardedToolCallback};
 * only the collaborators it calls out to (policy, gate service, the raw tool) are doubles.
 */
class GuardedToolCallbackParkingTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final String SESSION = "sess-1";
    private static final String SCOPED_MEMORY_ID = "c1:abc123";

    private ApprovalPolicyService policy;
    private HumanApprovalGateService gate;
    private GuardrailService guardrails;
    private TurnApprovalState state;
    private AtomicInteger turn;

    @BeforeEach
    void setUp() {
        policy = mock(ApprovalPolicyService.class);
        when(policy.requiresApproval("mcp_tool.send_invoice")).thenReturn(true);
        gate = mock(HumanApprovalGateService.class);
        guardrails = mock(GuardrailService.class);
        when(guardrails.sanitizeToolOutput(anyString(), anyString(), any()))
            .thenAnswer(i -> i.getArgument(1));
        state = new TurnApprovalState(SCOPED_MEMORY_ID);
        turn = new AtomicInteger();
    }

    private static ToolCallback tool(String name, String output) {
        ToolCallback cb = mock(ToolCallback.class);
        ToolDefinition def = mock(ToolDefinition.class);
        when(def.name()).thenReturn(name);
        when(cb.getToolDefinition()).thenReturn(def);
        when(cb.call(anyString())).thenReturn(output);
        return cb;
    }

    private GuardedToolCallback guard(ToolCallback cb) {
        return new GuardedToolCallback(cb, turn, 10, guardrails, policy, gate, AGENT_ID, SESSION)
            .withTurnApproval(state);
    }

    private ApprovalRequest pending() {
        ApprovalRequest request = mock(ApprovalRequest.class);
        when(request.getId()).thenReturn(UUID.randomUUID());
        return request;
    }

    private void recordingReturns(ApprovalRequest... requests) {
        var stub = when(gate.recordToolCallForApproval(
            anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), any()));
        stub.thenReturn(requests[0], java.util.Arrays.copyOfRange(requests, 1, requests.length));
    }

    @Test
    void theParkMessageSaysResumeAutomaticallyAndDoesNotTellTheModelToAbandonTheTask() {
        recordingReturns(pending());

        String result = guard(tool("send_invoice", "ran")).call("{\"to\":\"a\"}");

        assertTrue(result.contains("send_invoice"), result);
        assertTrue(result.contains("NOT executed"), result);
        assertTrue(result.toLowerCase().contains("resumed automatically"), result);
        assertFalse(result.contains("STOP NOW"), result);
        assertFalse(result.toLowerCase().contains("do not continue the task"), result);
    }

    @Test
    void theParkMessageIsConfigurable() {
        recordingReturns(pending());
        ApprovalParkProperties texts = new ApprovalParkProperties();
        texts.setAwaitingMessage("custom park text for {tool}");

        String result = guard(tool("send_invoice", "ran")).withApprovalParkTexts(texts).call("{}");

        assertEquals("custom park text for send_invoice", result);
    }

    @Test
    void theTurnIsReportedParkedOnlyAfterTheRequestIsRecorded() {
        recordingReturns(pending());
        assertFalse(state.isParked());

        guard(tool("send_invoice", "ran")).call("{}");

        assertTrue(state.isParked());
        assertEquals("send_invoice", state.parkedTool());
    }

    @Test
    void aFailedRecordingGivesTheModelAnErrorResultAndDoesNotParkTheTurn() {
        when(gate.recordToolCallForApproval(
            anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), any()))
            .thenThrow(new IllegalStateException("db down"));
        ToolCallback invoice = tool("send_invoice", "ran");
        ToolCallback other = tool("lookup", "found");

        String result = guard(invoice).call("{}");

        assertTrue(result.contains("send_invoice"), result);
        assertTrue(result.contains("NOT executed"), result);
        assertTrue(result.toLowerCase().contains("could not be recorded"), result);
        assertFalse(state.isParked(), "nothing is waiting for approval, so the turn must not be parked");
        verify(invoice, never()).call(anyString());
        // The turn is not stuck: a later tool in the same turn still runs.
        assertEquals("found", guard(other).call("{}"));
    }

    @Test
    void aSiblingCallAfterThePark_isRecordedAsItsOwnPendingApprovalAndNotExecuted() {
        ApprovalRequest first = pending();
        ApprovalRequest second = pending();
        recordingReturns(first, second);
        ToolCallback sibling = tool("lookup", "found");

        guard(tool("send_invoice", "ran")).call("{\"to\":\"a\"}");
        String result = guard(sibling).call("{\"q\":\"x\"}");

        verify(sibling, never()).call(anyString());
        verify(gate).recordToolCallForApproval(
            eq("mcp_tool.lookup"), eq("lookup"), anyString(), eq("{\"q\":\"x\"}"),
            anyString(), eq(AGENT_ID), eq(SESSION), any(), eq(SCOPED_MEMORY_ID));
        assertTrue(result.contains("lookup"), result);
        assertTrue(result.contains(second.getId().toString()), "names the queued request: " + result);
        assertTrue(result.contains("send_invoice"), "names the call it is waiting behind: " + result);
        assertFalse(result.contains("STOP NOW"), result);
    }

    @Test
    void theSameSiblingCallIsQueuedOnlyOnce() {
        recordingReturns(pending(), pending(), pending());
        guard(tool("send_invoice", "ran")).call("{}");
        ToolCallback sibling = tool("lookup", "found");

        String firstTry = guard(sibling).call("{\"q\":\"x\"}");
        String secondTry = guard(sibling).call("{\"q\":\"x\"}");

        verify(gate, times(2)).recordToolCallForApproval(
            anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), any());
        assertEquals(firstTry, secondTry);
    }

    @Test
    void repeatingTheParkedCallIsNotRecordedAgain() {
        ApprovalRequest first = pending();
        recordingReturns(first, pending());
        ToolCallback invoice = tool("send_invoice", "ran");

        guard(invoice).call("{\"to\":\"a\"}");
        String repeat = guard(invoice).call("{\"to\":\"a\"}");

        verify(gate, times(1)).recordToolCallForApproval(
            anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), any());
        assertTrue(repeat.contains(first.getId().toString()), "points at the request already waiting: " + repeat);
        verify(invoice, never()).call(anyString());
    }

    @Test
    void queuedSiblingsAreCappedSoARunawayModelCannotFloodTheApprovalQueue() {
        recordingReturns(pending());
        ApprovalParkProperties texts = new ApprovalParkProperties();
        texts.setMaxQueuedCalls(2);
        guard(tool("send_invoice", "ran")).withApprovalParkTexts(texts).call("{}");
        ToolCallback sibling = tool("lookup", "found");

        String last = null;
        for (int i = 0; i < 5; i++) {
            last = guard(sibling).withApprovalParkTexts(texts).call("{\"q\":" + i + "}");
        }

        // 1 parked call + 2 queued siblings; the other three were refused without a record.
        verify(gate, times(3)).recordToolCallForApproval(
            anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), any());
        assertTrue(last.contains("NOT executed"), last);
        assertTrue(last.toLowerCase().contains("not queued"), last);
    }

    @Test
    void aTurnWithoutAnExplicitStateStillTracksParkingPerStepCounter() {
        recordingReturns(pending());
        GuardedToolCallback plain =
            new GuardedToolCallback(tool("send_invoice", "ran"), turn, 10, guardrails, policy, gate, AGENT_ID, SESSION);

        plain.call("{}");

        assertTrue(plain.turnApprovalState().isParked());
        assertTrue(new GuardedToolCallback(
            tool("lookup", "found"), turn, 10, guardrails, policy, gate, AGENT_ID, SESSION)
            .turnApprovalState().isParked(), "tools of the same turn share the state through the step counter");
        assertFalse(new GuardedToolCallback(
            tool("lookup", "found"), new AtomicInteger(), 10, guardrails, policy, gate, AGENT_ID, SESSION)
            .turnApprovalState().isParked());
    }

    @Test
    void theFallbackRegistryDoesNotKeepFinishedTurnsAlive() throws Exception {
        recordingReturns(pending());
        AtomicInteger finished = new AtomicInteger();
        new GuardedToolCallback(tool("send_invoice", "ran"), finished, 10, guardrails, policy, gate, AGENT_ID, SESSION)
            .call("{}");
        assertTrue(GuardedToolCallback.trackedTurnCount() >= 1);
        java.lang.ref.WeakReference<AtomicInteger> ref = new java.lang.ref.WeakReference<>(finished);
        finished = null;

        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (ref.get() != null && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(20);
        }

        assertEquals(null, ref.get(), "the turn's step counter must be collectable once the turn is over");
        assertEquals(0, GuardedToolCallback.trackedTurnCount());
    }

    // ------------------------------------------------- the goal rides in the envelope (continuation)

    private void originRecordingReturns(ApprovalRequest request) {
        when(gate.recordToolCallWithOrigin(
            anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), any()))
            .thenReturn(request);
    }

    @Test
    void aTurnThatKnowsItsGoalRecordsItWithWhoAskedSoTheTaskCanBeContinued() {
        UUID requested = UUID.randomUUID();
        state = new TurnApprovalState(SCOPED_MEMORY_ID, "Send invoice INV-7731.", requested, "user-1", Set.of());
        originRecordingReturns(pending());

        String result = guard(tool("send_invoice", "ran")).call("{\"to\":\"a\"}");

        ArgumentCaptor<ToolCallOrigin> origin = ArgumentCaptor.forClass(ToolCallOrigin.class);
        verify(gate).recordToolCallWithOrigin(
            eq("mcp_tool.send_invoice"), eq("send_invoice"), anyString(), eq("{\"to\":\"a\"}"),
            anyString(), eq(AGENT_ID), eq(SESSION), any(), origin.capture());
        assertEquals(SCOPED_MEMORY_ID, origin.getValue().memorySessionId());
        assertEquals("Send invoice INV-7731.", origin.getValue().goal());
        assertEquals(requested, origin.getValue().requestedAgentId());
        assertEquals("user-1", origin.getValue().subjectKey());
        verify(gate, never()).recordToolCallForApproval(
            anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), any(String.class));
        assertTrue(state.isParked());
        assertTrue(result.contains("send_invoice"), result);
    }

    @Test
    void aLongGoalIsCutAtTheConfiguredLimitWithAMarker() {
        state = new TurnApprovalState(SCOPED_MEMORY_ID, "g".repeat(50), UUID.randomUUID(), "user-1", Set.of());
        originRecordingReturns(pending());
        ApprovalParkProperties texts = new ApprovalParkProperties();
        texts.setMaxGoalChars(10);

        guard(tool("send_invoice", "ran")).withApprovalParkTexts(texts).call("{}");

        ArgumentCaptor<ToolCallOrigin> origin = ArgumentCaptor.forClass(ToolCallOrigin.class);
        verify(gate).recordToolCallWithOrigin(
            anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), origin.capture());
        assertEquals("g".repeat(10) + " [...message shortened]", origin.getValue().goal());
    }

    @Test
    void aZeroGoalLimitRecordsNoGoalSoTheRequestIsReplayableButNotContinuable() {
        state = new TurnApprovalState(SCOPED_MEMORY_ID, "Send invoice.", UUID.randomUUID(), "user-1", Set.of());
        recordingReturns(pending());
        ApprovalParkProperties texts = new ApprovalParkProperties();
        texts.setMaxGoalChars(0);

        guard(tool("send_invoice", "ran")).withApprovalParkTexts(texts).call("{}");

        verify(gate, never()).recordToolCallWithOrigin(
            anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), any());
        verify(gate).recordToolCallForApproval(
            anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(),
            eq(SCOPED_MEMORY_ID));
    }

    @Test
    void aQueuedSiblingCallCarriesTheSameGoal() {
        state = new TurnApprovalState(SCOPED_MEMORY_ID, "Send invoice.", UUID.randomUUID(), "user-1", Set.of());
        originRecordingReturns(pending());

        guard(tool("send_invoice", "ran")).call("{}");
        guard(tool("lookup", "found")).call("{\"q\":\"x\"}");

        ArgumentCaptor<ToolCallOrigin> origin = ArgumentCaptor.forClass(ToolCallOrigin.class);
        verify(gate, times(2)).recordToolCallWithOrigin(
            anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any(), origin.capture());
        assertEquals("Send invoice.", origin.getAllValues().get(1).goal());
    }
}
