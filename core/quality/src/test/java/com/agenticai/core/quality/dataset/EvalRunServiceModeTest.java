package com.agenticai.core.quality.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.quality.QualitySettings;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.settings.DefaultSettingsResolver;
import com.agenticai.core.settings.InMemorySettingsStore;
import com.agenticai.core.settings.SettingOverride;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingsProperties;
import com.agenticai.core.settings.SettingsRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Which mode an eval run takes, on the real trigger and replay path: the full production pipeline in the eval
 * sandbox unless something says otherwise; the quick text-only replay when a run asks for it, when its dataset pins
 * it, or when the platform or the workspace made it the default; the run records the mode it used; and what the mode
 * means for the replay (tools offered or withheld, the expected-tools assertion, the auto-approval).
 */
class EvalRunServiceModeTest extends EvalRunServiceFixture {

    private static final String TENANT = "tenant-1";

    private InMemorySettingsStore store;
    private SettingsProperties settingsProperties;
    private MockEnvironment env;

    @BeforeEach
    void wire() {
        store = new InMemorySettingsStore();
        settingsProperties = new SettingsProperties();
        settingsProperties.setCacheTtl(Duration.ZERO);
        env = new MockEnvironment();
        judgeScores(0.9);
    }

    private void unpinnedDataset() {
        pin(null);
    }

    private void pin(Boolean tools) {
        dataset = new EvalDataset(AGENT_ID, "Regression set", null, 0.7, tools, "user@example.com");
        ReflectionTestUtils.setField(dataset, "id", DATASET_ID);
        when(datasetService.requireDataset(AGENT_ID, DATASET_ID)).thenReturn(dataset);
        when(datasetRepository.findById(DATASET_ID)).thenReturn(java.util.Optional.of(dataset));
    }

    private void catalog() {
        service.settings = new DefaultSettingsResolver(
                new SettingsRegistry(List.of(new QualitySettings(env))), store, List.of(), settingsProperties, env);
    }

    private void workspaceDefault(String value) {
        store.saveCurrent(new SettingOverride(
                SettingScope.TENANT, "", QualitySettings.EVAL_DEFAULT_MODE, value, 1, "tester", Instant.now()));
    }

    private void replays() throws Exception {
        org.mockito.Mockito.doReturn(new AgentTurnReplayer.ReplayedTurn("done", List.of()))
            .when(replayer).replay(any(AgentTurnReplayer.ReplayRequest.class));
    }

    private AgentTurnReplayer.ReplayRequest replayedWith(EvalRun run) throws Exception {
        ArgumentCaptor<AgentTurnReplayer.ReplayRequest> sent =
            ArgumentCaptor.forClass(AgentTurnReplayer.ReplayRequest.class);
        verify(replayer).replay(sent.capture());
        assertTrue(sent.getValue().sessionId().startsWith("eval:" + run.getId()));
        return sent.getValue();
    }

    private EvalRun runWithMode(String mode) {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        when(runExecutor.trySubmit(captor.capture())).thenReturn(true);
        EvalRun run = service.triggerRun(AGENT_ID, DATASET_ID,
            new EvalRunService.RunOptions(null, null, 1, null, null, mode));
        captor.getValue().run();
        return runStore.get(run.getId());
    }

    // ------------------------------------------------------------------ the default is the full pipeline

    @Test
    void aRunThatNamesNoModeOnADatasetThatPinsNoneReplaysThroughTheFullPipeline() throws Exception {
        unpinnedDataset();
        replays();

        EvalRun run = runWithMode(null);

        assertEquals(EvalMode.FULL, run.getEvalMode());
        assertEquals(AgentTurnReplayer.ReplayMode.TOOLS, replayedWith(run).mode(),
            "the default replay offers the agent's tools, with no auto-approval");
        assertFalse(run.isAutoApproved());
    }

    @Test
    void theShippedPolicyDefaultIsFull() {
        assertEquals(EvalMode.FULL, service.sandboxPolicy.defaultMode());
        assertEquals(EvalMode.FULL, service.effectiveDefaultMode());
    }

    // ------------------------------------------------------------------ quick is explicit

    @Test
    void aRunCanAskForQuickAndThenOnlyTextIsReplayed() throws Exception {
        unpinnedDataset();
        replays();

        EvalRun run = runWithMode("QUICK");

        assertEquals(EvalMode.QUICK, run.getEvalMode());
        assertEquals(AgentTurnReplayer.ReplayMode.TEXT_ONLY, replayedWith(run).mode());
    }

    @Test
    void theModeIsReadWhateverItsCase() throws Exception {
        unpinnedDataset();
        replays();

        assertEquals(EvalMode.QUICK, runWithMode(" quick ").getEvalMode());
    }

    @Test
    void anUnknownModeIsA400AndNothingIsQueued() {
        unpinnedDataset();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> service.triggerRun(AGENT_ID, DATASET_ID,
                new EvalRunService.RunOptions(null, null, 1, null, null, "turbo")));

        assertTrue(refused.getMessage().contains("FULL or QUICK"), refused.getMessage());
        verify(runRepository, never()).save(any(EvalRun.class));
        verify(runExecutor, never()).trySubmit(any());
    }

    // ------------------------------------------------------------------ precedence: run, dataset pin, workspace, platform

    @Test
    void aDatasetThatPinsQuickRunsQuickUntilARunAsksForFull() throws Exception {
        pin(false);
        replays();

        EvalRun pinned = runWithMode(null);
        assertEquals(EvalMode.QUICK, pinned.getEvalMode());
        assertEquals(AgentTurnReplayer.ReplayMode.TEXT_ONLY, replayedWith(pinned).mode());
    }

    @Test
    void aRunThatAsksForFullOverridesADatasetPinnedToQuick() throws Exception {
        pin(false);
        replays();

        EvalRun run = runWithMode("FULL");

        assertEquals(EvalMode.FULL, run.getEvalMode());
        assertEquals(AgentTurnReplayer.ReplayMode.TOOLS, replayedWith(run).mode());
    }

    @Test
    void theOperatorCanMakeQuickThePlatformDefault() throws Exception {
        unpinnedDataset();
        service.sandboxPolicy = EvalSandboxPolicy.of(EvalMode.QUICK, Set.of(), "eval-auto-approve", List.of());
        replays();

        EvalRun run = runWithMode(null);

        assertEquals(EvalMode.QUICK, run.getEvalMode());
        assertEquals(AgentTurnReplayer.ReplayMode.TEXT_ONLY, replayedWith(run).mode());
    }

    @Test
    void aWorkspaceCanSwitchTheDefaultBackToQuickWithItsOwnSetting() throws Exception {
        unpinnedDataset();
        catalog();
        workspaceDefault("quick");
        replays();

        EvalRun run = runWithMode(null);

        assertEquals(EvalMode.QUICK, run.getEvalMode(), "the workspace's setting replaces the platform's full");
        assertEquals(AgentTurnReplayer.ReplayMode.TEXT_ONLY, replayedWith(run).mode());
    }

    @Test
    void aWorkspaceSettingOfFullBeatsAPlatformDefaultOfQuick() {
        unpinnedDataset();
        env.setProperty("app.quality.eval.default-mode", "quick");
        service.sandboxPolicy = EvalSandboxPolicy.of(EvalMode.QUICK, Set.of(), "eval-auto-approve", List.of());
        catalog();
        assertEquals(EvalMode.QUICK, service.effectiveDefaultMode(), "no override: the platform value");

        workspaceDefault("full");

        assertEquals(EvalMode.FULL, service.effectiveDefaultMode());
    }

    @Test
    void theDatasetsPinStillBeatsTheWorkspaceSetting() throws Exception {
        pin(true);
        catalog();
        workspaceDefault("quick");
        replays();

        EvalRun run = runWithMode(null);

        assertEquals(EvalMode.FULL, run.getEvalMode());
        assertEquals(AgentTurnReplayer.ReplayMode.TOOLS, replayedWith(run).mode());
    }

    @Test
    void aSettingsFailureFallsBackToThePlatformValueInsteadOfFailingTheTrigger() {
        unpinnedDataset();
        service.settings = new DefaultSettingsResolver(
            new SettingsRegistry(List.of()), store, List.of(), settingsProperties, env); // the key is not registered

        assertEquals(EvalMode.FULL, service.effectiveDefaultMode());
    }

    // ------------------------------------------------------------------ what the mode means for the replay

    @Test
    void theExpectedToolsAssertionIsEvaluatedInFullAndLeftOutInQuick() throws Exception {
        unpinnedDataset();
        EvalDatasetCase asserting = new EvalDatasetCase(DATASET_ID, "hi", "hello", "[\"lookup\"]", 0);
        when(caseRepository.findByDatasetIdOrderByPositionAscCreatedAtAsc(DATASET_ID)).thenReturn(List.of(asserting));
        org.mockito.Mockito.doReturn(new AgentTurnReplayer.ReplayedTurn("done", List.of()))
            .when(replayer).replay(any(AgentTurnReplayer.ReplayRequest.class));

        EvalRun full = runWithMode("FULL");
        EvalRunCaseResult fullResult = resultStore.values().iterator().next();
        assertEquals(Boolean.FALSE, fullResult.getExpectedToolsSatisfied(),
            "a full run that never called the expected tool has failed the assertion");
        assertEquals(1, full.getFailedAssertionCount());

        resultStore.clear();
        org.mockito.Mockito.clearInvocations(replayer);
        // The first run's worker cleared the thread's tenant context when it finished.
        TenantContext.set(TenantContext.of(TENANT, "tester", Set.of()));
        // The live-run guard is a database constraint; the in-memory store has none, so a second run is fine here.
        EvalRun quick = runWithMode("QUICK");
        EvalRunCaseResult quickResult = resultStore.values().iterator().next();
        assertNull(quickResult.getExpectedToolsSatisfied(), "a quick run made no tool calls: nothing to assert");
        assertEquals(0, quick.getFailedAssertionCount());
    }

    @Test
    void aCallTheSandboxSimulatedStillCountsAsTheAgentHavingMadeIt() throws Exception {
        unpinnedDataset();
        EvalDatasetCase asserting = new EvalDatasetCase(DATASET_ID, "send it", "sent", "[\"send_invoice\"]", 0);
        when(caseRepository.findByDatasetIdOrderByPositionAscCreatedAtAsc(DATASET_ID)).thenReturn(List.of(asserting));
        org.mockito.Mockito.doReturn(new AgentTurnReplayer.ReplayedTurn("sent", List.of(
            new AgentTurnReplayer.ReplayedToolCall(
                "send_invoice", "{}", "simulated", AgentTurnReplayer.ToolStatus.SIMULATED))))
            .when(replayer).replay(any(AgentTurnReplayer.ReplayRequest.class));

        EvalRun run = runWithMode(null);

        EvalRunCaseResult result = resultStore.values().iterator().next();
        assertEquals(Boolean.TRUE, result.getExpectedToolsSatisfied());
        assertTrue(result.getToolCallsJson().contains("\"status\":\"SIMULATED\""), result.getToolCallsJson());
        assertEquals(0, run.getFailedAssertionCount());
    }

    @Test
    void aQuickRunNeverUsesTheAutoApprovalAndIsNotCheckedAgainstTheTenantList() throws Exception {
        dataset = new EvalDataset(AGENT_ID, "Regression set", null, 0.7, (Boolean) null, true, "user@example.com");
        ReflectionTestUtils.setField(dataset, "id", DATASET_ID);
        when(datasetService.requireDataset(AGENT_ID, DATASET_ID)).thenReturn(dataset);
        when(datasetRepository.findById(DATASET_ID)).thenReturn(java.util.Optional.of(dataset));
        service.sandboxPolicy = EvalSandboxPolicy.of(EvalMode.FULL, Set.of(), "eval-auto-approve", List.of());
        replays();

        // In a workspace that is not declared non-production a FULL run of this dataset is refused ...
        assertThrows(IllegalArgumentException.class, () -> runWithMode("FULL"));
        org.mockito.Mockito.clearInvocations(replayer);

        // ... and a QUICK run of it has no tool calls to approve, so it runs and is not flagged.
        TenantContext.set(TenantContext.of(TENANT, "tester", Set.of()));
        EvalRun quick = runWithMode("QUICK");
        assertFalse(quick.isAutoApproved());
        assertEquals(AgentTurnReplayer.ReplayMode.TEXT_ONLY, replayedWith(quick).mode());
    }

    @Test
    void theRunKeepsTheModeItWasTriggeredWithWhateverTheDatasetBecomes() {
        unpinnedDataset();
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        when(runExecutor.trySubmit(captor.capture())).thenReturn(true);

        EvalRun run = service.triggerRun(AGENT_ID, DATASET_ID,
            new EvalRunService.RunOptions(null, null, 1, null, null, "QUICK"));
        dataset.updateDefinition("Regression set", null, 0.7, true); // pinned to FULL afterwards

        assertEquals(EvalMode.QUICK, runStore.get(run.getId()).getEvalMode());
    }
}
