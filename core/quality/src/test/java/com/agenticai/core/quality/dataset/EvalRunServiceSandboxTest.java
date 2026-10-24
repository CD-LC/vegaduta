package com.agenticai.core.quality.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * How a run chooses between a quick text-only replay, the real tool pipeline in the eval sandbox, and that pipeline
 * under the sandbox auto-approval: the run's mode and the dataset's auto-approval setting pick it, the
 * auto-approval is refused unless the operator declared the tenant non-production (at trigger AND at every replay),
 * and the run records that it ran that way. (Which mode a run takes is {@link EvalRunServiceModeTest}.)
 */
class EvalRunServiceSandboxTest extends EvalRunServiceFixture {

    private static final String TENANT = "tenant-1";

    @BeforeEach
    void toolDataset() {
        dataset = new EvalDataset(AGENT_ID, "Regression set", null, 0.7, true, true, "user@example.com");
        ReflectionTestUtils.setField(dataset, "id", DATASET_ID);
        when(datasetService.requireDataset(AGENT_ID, DATASET_ID)).thenReturn(dataset);
        when(datasetRepository.findById(DATASET_ID)).thenReturn(java.util.Optional.of(dataset));
        judgeScores(0.9);
    }

    private void allowTenant(boolean allowed) {
        service.sandboxPolicy = EvalSandboxPolicy.of(
            EvalMode.FULL, allowed ? Set.of(TENANT) : Set.of(), "eval-auto-approve", List.of("local_shell"));
    }

    private void replaysWith(AgentTurnReplayer.ReplayedTurn turn) throws Exception {
        // doReturn: the mock calls real default methods, and stubbing through when() would run this one.
        org.mockito.Mockito.doReturn(turn).when(replayer).replay(any(AgentTurnReplayer.ReplayRequest.class));
    }

    @Test
    void anAutoApprovingDatasetIsRefusedForATenantTheOperatorDidNotDeclareNonProduction() {
        allowTenant(false);

        IllegalArgumentException refused =
            assertThrows(IllegalArgumentException.class, () -> triggerAndCapture(1));

        assertTrue(refused.getMessage().contains("app.quality.eval.auto-approve.tenant-ids"), refused.getMessage());
        verify(runRepository, never()).save(any(EvalRun.class));
        verify(runExecutor, never()).trySubmit(any());
    }

    @Test
    void anAutoApprovingRunInADeclaredTenantRecordsTheFlagAndReplaysUnderTheAutoApprovalMode() throws Exception {
        allowTenant(true);
        replaysWith(new AgentTurnReplayer.ReplayedTurn("done", List.of(), 2));

        EvalRun run = runInline(1);

        assertTrue(run.isAutoApproved(), "the run must say a person decided none of its tool calls");
        ArgumentCaptor<AgentTurnReplayer.ReplayRequest> sent =
            ArgumentCaptor.forClass(AgentTurnReplayer.ReplayRequest.class);
        verify(replayer).replay(sent.capture());
        assertEquals(AgentTurnReplayer.ReplayMode.TOOLS_AUTO_APPROVE, sent.getValue().mode());
        assertEquals(AGENT_ID, sent.getValue().agentId());
        assertTrue(sent.getValue().sessionId().startsWith("eval:" + run.getId()));
    }

    @Test
    void aToolDatasetWithoutAutoApprovalRunsTheRealPipelineAndIsNotFlagged() throws Exception {
        dataset = new EvalDataset(AGENT_ID, "Regression set", null, 0.7, true, "user@example.com");
        ReflectionTestUtils.setField(dataset, "id", DATASET_ID);
        when(datasetService.requireDataset(AGENT_ID, DATASET_ID)).thenReturn(dataset);
        when(datasetRepository.findById(DATASET_ID)).thenReturn(java.util.Optional.of(dataset));
        allowTenant(false); // not declared, and it does not matter: nothing is auto-approved
        replaysWith(new AgentTurnReplayer.ReplayedTurn("done", List.of()));

        EvalRun run = runInline(1);

        assertFalse(run.isAutoApproved());
        ArgumentCaptor<AgentTurnReplayer.ReplayRequest> sent =
            ArgumentCaptor.forClass(AgentTurnReplayer.ReplayRequest.class);
        verify(replayer).replay(sent.capture());
        assertEquals(AgentTurnReplayer.ReplayMode.TOOLS, sent.getValue().mode());
    }

    @Test
    void aTextOnlyDatasetReplaysTextOnlyWhateverTheSandboxPolicySays() throws Exception {
        dataset = new EvalDataset(AGENT_ID, "Regression set", null, 0.7, false, "user@example.com");
        ReflectionTestUtils.setField(dataset, "id", DATASET_ID);
        when(datasetService.requireDataset(AGENT_ID, DATASET_ID)).thenReturn(dataset);
        when(datasetRepository.findById(DATASET_ID)).thenReturn(java.util.Optional.of(dataset));
        allowTenant(true);
        replaysWith(new AgentTurnReplayer.ReplayedTurn("done", List.of()));

        EvalRun run = runInline(1);

        assertFalse(run.isAutoApproved());
        ArgumentCaptor<AgentTurnReplayer.ReplayRequest> sent =
            ArgumentCaptor.forClass(AgentTurnReplayer.ReplayRequest.class);
        verify(replayer).replay(sent.capture());
        assertEquals(AgentTurnReplayer.ReplayMode.TEXT_ONLY, sent.getValue().mode());
    }

    @Test
    void removingTheTenantFromTheListStopsARunThatWasAlreadyQueued() throws Exception {
        allowTenant(true);
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        when(runExecutor.trySubmit(captor.capture())).thenReturn(true);
        EvalRun run = triggerAndCapture(1);
        assertTrue(run.isAutoApproved());

        allowTenant(false); // the operator changes their mind before the worker picks the run up
        captor.getValue().run();

        verify(replayer, never()).replay(any(AgentTurnReplayer.ReplayRequest.class));
        EvalRun stored = runStore.get(run.getId());
        assertEquals(1, stored.getErroredCaseCount(), "the case must end as an ERROR, not run un-approved or pass");
        EvalRunCaseResult result = resultStore.values().iterator().next();
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("no longer permitted"), result.getErrorMessage());
    }

    @Test
    void theReplayModeIsChosenByTheRunsModeAndTheAutoApprovalSnapshot() {
        assertEquals(AgentTurnReplayer.ReplayMode.TEXT_ONLY, AgentTurnReplayer.ReplayMode.of(EvalMode.QUICK, true));
        assertEquals(AgentTurnReplayer.ReplayMode.TOOLS, AgentTurnReplayer.ReplayMode.of(EvalMode.FULL, false));
        assertEquals(AgentTurnReplayer.ReplayMode.TOOLS_AUTO_APPROVE,
            AgentTurnReplayer.ReplayMode.of(EvalMode.FULL, true));
    }

    @Test
    void theBooleanFormOfTheModeStillMapsToTheSameThreeModes() {
        assertEquals(AgentTurnReplayer.ReplayMode.TEXT_ONLY, AgentTurnReplayer.ReplayMode.of(false, false));
        assertEquals(AgentTurnReplayer.ReplayMode.TEXT_ONLY, AgentTurnReplayer.ReplayMode.of(false, true));
        assertEquals(AgentTurnReplayer.ReplayMode.TOOLS, AgentTurnReplayer.ReplayMode.of(true, false));
        assertEquals(AgentTurnReplayer.ReplayMode.TOOLS_AUTO_APPROVE, AgentTurnReplayer.ReplayMode.of(true, true));
    }

    @Test
    void aReplayerThatPredatesTheAutoApprovalRefusesItInsteadOfRunningWithoutADecision() {
        AgentTurnReplayer old = (agent, session, subject, message, tools, timeout) ->
            new AgentTurnReplayer.ReplayedTurn("x", List.of());

        assertThrows(UnsupportedOperationException.class, () -> old.replay(new AgentTurnReplayer.ReplayRequest(
            AGENT_ID, "s", "u", "hi", AgentTurnReplayer.ReplayMode.TOOLS_AUTO_APPROVE, java.time.Duration.ofSeconds(1))));
    }

    @Test
    void theTenantContextOfTheRunIsWhatTheListIsCheckedAgainst() {
        TenantContext.set(TenantContext.of("someone-else", "tester", Set.of()));
        allowTenant(true); // lists "tenant-1" only

        assertThrows(IllegalArgumentException.class, () -> triggerAndCapture(1));
    }
}
