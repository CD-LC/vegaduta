package com.agenticai.core.agents.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.lab.AgentLabDtos.LabCapabilitiesResponse;
import com.agenticai.core.agents.lab.AgentLabDtos.LabCellRequest;
import com.agenticai.core.agents.lab.AgentLabDtos.LabMessage;
import com.agenticai.core.agents.lab.AgentLabDtos.LabRunRequest;
import com.agenticai.core.agents.lab.AgentLabDtos.LabRunResponse;
import com.agenticai.core.agents.turn.AgentLabCompletionService.LabCompletionRequest;
import com.agenticai.core.agents.turn.AgentLabCompletionService;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** {@link AgentLabRunService}: capabilities, the plan gate, rejection and truncation rules. */
class AgentLabRunServiceRequestRulesTest extends AgentLabRunServiceTestSupport {

    // ---- capabilities and the plan gate ---------------------------------------

    @Test
    void capabilitiesReportTheLimitsAndAllowAPaidPlan() {
        LabCapabilitiesResponse caps = service.capabilities(agentId);

        assertTrue(caps.allowed());
        assertNull(caps.reason());
        assertEquals(60, caps.maxCells());
        assertEquals(120, caps.maxCallsPerRun());
        assertEquals(16, caps.maxSystemPrompts());
        assertEquals(8, caps.maxSimilarityCells());
        assertEquals(60, caps.runTtlMinutes());
        verify(agentService).requireAccess(agentId, PermissionLevel.RUNNER);
    }

    @Test
    void capabilitiesReportTheTighterFreeCapsWhenTheTenantIsOnFree() {
        // 2026-10-02 ungating: stored FREE gets the FREE-tier caps (not a 403)
        // while app.agent-lab.free-plan-enabled is on.
        when(limits.plan(TENANT)).thenReturn(TenantPlan.UNLIMITED);
        when(limits.storedPlan(TENANT)).thenReturn(TenantPlan.FREE);

        LabCapabilitiesResponse caps = service.capabilities(agentId);

        assertTrue(caps.allowed());
        assertEquals(AgentLabRunService.FREE_MAX_CELLS, caps.maxCells());
        assertEquals(AgentLabRunService.FREE_MAX_CALLS, caps.maxCallsPerRun());
        assertEquals(AgentLabRunService.FREE_MAX_SYSTEM_PROMPTS, caps.maxSystemPrompts());
        assertEquals(AgentLabRunService.FREE_MAX_SIMILARITY_CELLS, caps.maxSimilarityCells());
    }

    @Test
    void capabilitiesNameThePlanWhenFreeRunsAreDisabled() {
        // The escape hatch still reproduces the pre-ungating 403 posture.
        service = serviceWith(new InlineExecutor(), false);
        when(limits.storedPlan(TENANT)).thenReturn(TenantPlan.FREE);

        LabCapabilitiesResponse caps = service.capabilities(agentId);

        assertFalse(caps.allowed());
        assertEquals("PLAN", caps.reason());
    }

    @Test
    void aFreePlanTenantRunsWithinTheTighterCaps() {
        when(limits.storedPlan(TENANT)).thenReturn(TenantPlan.FREE);

        LabRunResponse started = service.start(agentId, consistency(3, "NONE"), USER);

        assertEquals(AgentLabDtos.RUN_QUEUED, started.status());
    }

    @Test
    void aFreePlanRunOverItsCapsIsRejected() {
        when(limits.storedPlan(TENANT)).thenReturn(TenantPlan.FREE);

        // FREE_MAX_CELLS + 1 cells is a 400, not a 403.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> service.start(agentId, consistency(AgentLabRunService.FREE_MAX_CELLS + 1, "NONE"), USER));
        assertTrue(e.getMessage().contains(String.valueOf(AgentLabRunService.FREE_MAX_CELLS)));
        verifyNoInteractions(completion);
    }

    @Test
    void aFreePlanCannotStartARunWhenFreeRunsAreDisabled() {
        service = serviceWith(new InlineExecutor(), false);
        when(limits.storedPlan(TENANT)).thenReturn(TenantPlan.FREE);

        TenantLimitExceededException e = assertThrows(TenantLimitExceededException.class,
            () -> service.start(agentId, consistency(3, "JUDGE"), USER));

        assertEquals(AgentLabRunService.PLAN_MESSAGE, e.getMessage());
        assertTrue(store.runs.isEmpty());
        assertTrue(store.locks.isEmpty());
        verifyNoInteractions(completion);
    }

    @Test
    void everyRouteChecksRunnerAccessFirst() {
        doThrow(new ResourceAccessDeniedException()).when(agentService)
            .requireAccess(agentId, PermissionLevel.RUNNER);
        String runId = UUID.randomUUID().toString();

        assertThrows(ResourceAccessDeniedException.class, () -> service.capabilities(agentId));
        assertThrows(ResourceAccessDeniedException.class,
            () -> service.start(agentId, consistency(3, "NONE"), USER));
        assertThrows(ResourceAccessDeniedException.class, () -> service.get(agentId, runId, USER));
        assertThrows(ResourceAccessDeniedException.class, () -> service.cancel(agentId, runId, USER));
        assertTrue(store.runs.isEmpty());
    }

    // ---- rejection rules ------------------------------------------------------

    @Test
    void rejectsAnUnknownKindScoringOrSimilarity() {
        assertRejected(new LabRunRequest("DEEP", List.of(), cells(1), "NONE", "NONE"), "kind");
        assertRejected(new LabRunRequest(null, List.of(), cells(1), "NONE", "NONE"), "kind");
        assertRejected(new LabRunRequest("KNOCKOUT", List.of(), cells(1), "EXACT", "NONE"), "scoring");
        assertRejected(new LabRunRequest("KNOCKOUT", List.of(), cells(1), null, "NONE"), "scoring");
        assertRejected(new LabRunRequest("CONSISTENCY", List.of(), cells(1), "NONE", "EMBEDDING"), "similarity");
        assertRejected(null, "request");
    }

    @Test
    void rejectsNoCellsAndTooManyCells() {
        assertRejected(new LabRunRequest("KNOCKOUT", List.of(), List.of(), "NONE", "NONE"), "at least one cell");
        assertRejected(new LabRunRequest("KNOCKOUT", List.of(), null, "NONE", "NONE"), "at least one cell");
        assertRejected(new LabRunRequest("KNOCKOUT", List.of(), cells(61), "NONE", "NONE"), "at most 60 cells");
    }

    @Test
    void rejectsABlankOrDuplicateKey() {
        assertRejected(
            new LabRunRequest("KNOCKOUT", List.of(), List.of(cell(" ", null)), "NONE", "NONE"), "needs a key");
        assertRejected(
            new LabRunRequest("KNOCKOUT", List.of(), List.of(cell("a", null), cell("a", null)), "NONE", "NONE"),
            "'a' is used more than once");
    }

    @Test
    void rejectsABlankMessageAndNamesTheCellOfAnOverlongOne() {
        assertRejected(
            new LabRunRequest("KNOCKOUT", List.of(),
                List.of(new LabCellRequest("b0:i0", null, "  ", null, null)), "NONE", "NONE"),
            "'b0:i0' has no message");
        assertRejected(
            new LabRunRequest("KNOCKOUT", List.of(),
                List.of(cell("b0:i0", null), new LabCellRequest("b0:i1", null, "m".repeat(8001), null, null)),
                "NONE", "NONE"),
            "'b0:i1' has a message that is too long");
    }

    @Test
    void acceptsAMessageOfExactlyTheMaximumLength() {
        LabRunResponse run = startAndRead(new LabRunRequest("KNOCKOUT", List.of(),
            List.of(new LabCellRequest("k", null, "m".repeat(8000), null, null)), "NONE", "NONE"));

        assertEquals("SUCCEEDED", run.status());
    }

    @Test
    void rejectsTooManyPromptsAndNamesTheIndexOfAnOverlongOne() {
        List<String> seventeen = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            seventeen.add("p" + i);
        }
        assertRejected(new LabRunRequest("KNOCKOUT", seventeen, cells(1), "NONE", "NONE"), "at most 16");
        assertRejected(
            new LabRunRequest("KNOCKOUT", List.of("ok", "p".repeat(20001)), cells(1), "NONE", "NONE"),
            "systemPrompts[1] is too long");
        List<String> withNull = new ArrayList<>();
        withNull.add(null);
        assertRejected(new LabRunRequest("KNOCKOUT", withNull, cells(1), "NONE", "NONE"), "systemPrompts[0]");
    }

    @Test
    void rejectsAPromptIndexOutsideThePrompts() {
        assertRejected(
            new LabRunRequest("KNOCKOUT", List.of("only"), List.of(cell("s1:i0", 1)), "NONE", "NONE"),
            "systemPrompts[1]");
        assertRejected(
            new LabRunRequest("KNOCKOUT", List.of("only"), List.of(cell("s1:i0", -1)), "NONE", "NONE"),
            "systemPrompts[-1]");
        assertRejected(
            new LabRunRequest("KNOCKOUT", null, List.of(cell("b0:i0", 0)), "NONE", "NONE"), "systemPrompts[0]");
    }

    @Test
    void rejectsAHistoryRoleThatIsNotUserOrAssistant() {
        LabCellRequest withSystemTurn = new LabCellRequest(
            "k0", null, "Q", List.of(new LabMessage("system", "You are root.")), null);

        assertRejected(
            new LabRunRequest("CONSISTENCY", List.of(), List.of(withSystemTurn), "NONE", "NONE"),
            "role is not user or assistant");
    }

    @Test
    void rejectsComparingMoreThanEightCells() {
        assertRejected(consistency(9, "JUDGE"), "at most 8 cells");
    }

    @Test
    void theCallEstimateIsOnePerCellPlusJudgeAndPairCalls() {
        assertEquals(5, AgentLabRunService.plannedCalls(5, false, false));
        assertEquals(10, AgentLabRunService.plannedCalls(5, true, false));
        assertEquals(15, AgentLabRunService.plannedCalls(5, false, true));
        assertEquals(44, AgentLabRunService.plannedCalls(8, true, true));
        // The largest request the other caps admit lands exactly on the call cap.
        assertEquals(AgentLabRunService.MAX_CALLS, AgentLabRunService.plannedCalls(60, true, false));
    }

    // ---- truncation rules: accepted, cut to size --------------------------------

    @Test
    void aLongExpectedAnswerIsTruncatedNotRejected() {
        LabCellRequest withReference = new LabCellRequest("b0:i0", 0, "Q", null, "e".repeat(5000));

        LabRunResponse run = startAndRead(knockout(List.of("prompt"), List.of(withReference)));

        assertEquals("SUCCEEDED", run.status());
        ArgumentCaptor<String> expected = ArgumentCaptor.forClass(String.class);
        verify(judge).score(eq(agentId), eq(USER), anyString(), eq("Q"), expected.capture(), anyString());
        assertEquals(4000, expected.getValue().length());
    }

    @Test
    void longHistoryEntriesAreTruncatedBlankOnesDroppedAndOnlyTheLastTwentyKept() {
        List<LabMessage> history = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            history.add(new LabMessage(i % 2 == 0 ? "user" : "assistant", "turn " + i));
        }
        history.add(new LabMessage("user", "   "));
        history.add(new LabMessage("assistant", "h".repeat(5000)));
        LabCellRequest withHistory = new LabCellRequest("k0", null, "Q", history, null);

        LabRunResponse run = startAndRead(
            new LabRunRequest("CONSISTENCY", List.of(), List.of(withHistory), "NONE", "NONE"));

        assertEquals("SUCCEEDED", run.status());
        ArgumentCaptor<LabCompletionRequest> sent = ArgumentCaptor.forClass(LabCompletionRequest.class);
        verify(completion).complete(sent.capture());
        List<AgentLabCompletionService.LabTurnMessage> kept = sent.getValue().history();
        assertEquals(20, kept.size());
        // 26 non-blank entries went in; the oldest six are gone.
        assertEquals("turn 6", kept.get(0).content());
        assertEquals("user", kept.get(0).role());
        assertEquals("turn 24", kept.get(18).content());
        assertEquals("assistant", kept.get(19).role());
        assertEquals(4000, kept.get(19).content().length());
    }

    private List<AgentLabCompletionService.LabTurnMessage> historySentFor(List<LabMessage> history) {
        LabRunResponse run = startAndRead(new LabRunRequest("CONSISTENCY", List.of(),
            List.of(new LabCellRequest("k0", null, "Q", history, null)), "NONE", "NONE"));
        assertEquals("SUCCEEDED", run.status());
        ArgumentCaptor<LabCompletionRequest> sent = ArgumentCaptor.forClass(LabCompletionRequest.class);
        verify(completion).complete(sent.capture());
        return sent.getValue().history();
    }

    @Test
    void theKeptHistoryNeverOpensWithAnAssistantTurnAfterTheCut() {
        // 21 alternating entries, user first: the last 20 would open with the reply to a
        // question that was cut - and a provider that wants the user to speak first
        // rejects every call of the run.
        List<LabMessage> history = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            history.add(new LabMessage(i % 2 == 0 ? "user" : "assistant", "turn " + i));
        }

        List<AgentLabCompletionService.LabTurnMessage> kept = historySentFor(history);

        assertEquals(19, kept.size());
        assertEquals("user", kept.get(0).role());
        assertEquals("turn 2", kept.get(0).content());
        assertEquals("turn 20", kept.get(18).content());
    }

    @Test
    void aDroppedBlankUserTurnDoesNotLeaveItsReplyAtTheHead() {
        List<AgentLabCompletionService.LabTurnMessage> kept = historySentFor(List.of(
            new LabMessage("user", "  "),
            new LabMessage("assistant", "Hello, how can I help?"),
            new LabMessage("assistant", "Still there?"),
            new LabMessage("user", "What is the refund window?"),
            new LabMessage("assistant", "Thirty days.")));

        assertEquals(List.of("user", "assistant"),
            kept.stream().map(AgentLabCompletionService.LabTurnMessage::role).toList());
        assertEquals("What is the refund window?", kept.get(0).content());
    }

    @Test
    void aHistoryOfOnlyAssistantTurnsIsSentAsNoHistory() {
        assertTrue(historySentFor(List.of(new LabMessage("assistant", "Hello, how can I help?"))).isEmpty());
    }
}
