package com.agenticai.core.quality.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.DatasetResponse;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.RunDetailResponse;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.RunResponse;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.TriggerRunRequest;
import com.agenticai.core.quality.dataset.dto.EvalDatasetDtos.UpsertDatasetRequest;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The eval API's mode surface: a run is triggered with a {@code mode} (FULL or QUICK) or none, a dataset is created
 * and edited with a {@code mode} (FULL, QUICK, or DEFAULT to drop the pin) or the older boolean, and the responses say
 * which mode a dataset pins, which one a run of it takes right now, which one a run used, and which tools the sandbox
 * simulated in it.
 */
class EvalDatasetControllerModeTest {

    private static final String TENANT_ID = "tenant-1";
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID DATASET_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();

    private EvalDatasetService datasetService;
    private EvalRunService runService;
    private EvalDatasetController controller;

    @BeforeEach
    void setUp() {
        datasetService = mock(EvalDatasetService.class);
        runService = mock(EvalRunService.class);
        TenantLimits tenantLimits = mock(TenantLimits.class);
        when(tenantLimits.plan(TENANT_ID)).thenReturn(TenantPlan.PREMIUM);
        TenantContext.set(TenantContext.of(TENANT_ID, "user@example.com", Set.of()));
        controller = new EvalDatasetController(datasetService, runService, tenantLimits, false);
        when(runService.effectiveDefaultMode()).thenReturn(EvalMode.FULL);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private EvalDataset dataset(Boolean pin) {
        EvalDataset dataset = new EvalDataset(AGENT_ID, "Regression set", null, 0.7, pin, "user@example.com");
        org.springframework.test.util.ReflectionTestUtils.setField(dataset, "id", DATASET_ID);
        return dataset;
    }

    private EvalRun run(EvalMode mode) {
        EvalRun run = mock(EvalRun.class);
        when(run.getEvalMode()).thenReturn(mode);
        when(run.getId()).thenReturn(RUN_ID);
        when(run.getDatasetId()).thenReturn(DATASET_ID);
        when(run.getAgentId()).thenReturn(AGENT_ID);
        when(run.getStatus()).thenReturn(EvalRunStatus.QUEUED);
        when(run.getOverallScore()).thenReturn(null);
        when(run.getCreatedAt()).thenReturn(Instant.now());
        return run;
    }

    // ------------------------------------------------------------------ trigger

    @Test
    void aRunIsTriggeredWithTheModeTheBodyNames() {
        EvalRun queued = run(EvalMode.QUICK);
        when(runService.triggerRun(eq(AGENT_ID), eq(DATASET_ID), any())).thenReturn(queued);

        RunResponse response = controller.triggerRun(
            AGENT_ID, DATASET_ID, new TriggerRunRequest(null, null, null, null, "QUICK"), null).getBody();

        ArgumentCaptor<EvalRunService.RunOptions> options = ArgumentCaptor.forClass(EvalRunService.RunOptions.class);
        verify(runService).triggerRun(eq(AGENT_ID), eq(DATASET_ID), options.capture());
        assertEquals("QUICK", options.getValue().mode());
        assertEquals("QUICK", response.mode(), "the response says which mode the run was given");
    }

    @Test
    void aRunTriggeredWithNoBodyNamesNoModeSoTheDefaultApplies() {
        EvalRun queued = run(EvalMode.FULL);
        when(runService.triggerRun(eq(AGENT_ID), eq(DATASET_ID), any())).thenReturn(queued);

        RunResponse response = controller.triggerRun(AGENT_ID, DATASET_ID, null, null).getBody();

        ArgumentCaptor<EvalRunService.RunOptions> options = ArgumentCaptor.forClass(EvalRunService.RunOptions.class);
        verify(runService).triggerRun(eq(AGENT_ID), eq(DATASET_ID), options.capture());
        assertNull(options.getValue().mode());
        assertEquals("FULL", response.mode());
    }

    @Test
    void theRunDetailListsTheToolsTheSandboxSimulatedOnceEach() {
        EvalRun finished = run(EvalMode.FULL);
        when(datasetService.requireDataset(AGENT_ID, DATASET_ID)).thenReturn(dataset(null));
        when(runService.requireRun(DATASET_ID, RUN_ID)).thenReturn(finished);
        EvalDatasetCase first = new EvalDatasetCase(DATASET_ID, "u1", null, null, 0);
        EvalDatasetCase second = new EvalDatasetCase(DATASET_ID, "u2", null, null, 1);
        String calls1 = "[{\"toolName\":\"send_invoice\",\"input\":\"{}\",\"output\":\"x\",\"status\":\"SIMULATED\"},"
            + "{\"toolName\":\"lookup\",\"input\":\"{}\",\"output\":\"y\",\"status\":\"OK\"}]";
        String calls2 = "[{\"toolName\":\"send_invoice\",\"input\":\"{}\",\"output\":\"x\",\"status\":\"SIMULATED\"},"
            + "{\"toolName\":\"remember_fact\",\"input\":\"{}\",\"output\":\"z\",\"status\":\"SIMULATED\"}]";
        when(runService.listCaseResults(RUN_ID)).thenReturn(List.of(
            EvalRunCaseResult.scored(RUN_ID, first, "a1", calls1, null, 0.9, null),
            EvalRunCaseResult.scored(RUN_ID, second, "a2", calls2, null, 0.9, null)));

        RunDetailResponse detail = controller.getRun(AGENT_ID, DATASET_ID, RUN_ID).getBody();

        assertEquals(List.of("send_invoice", "remember_fact"), detail.simulatedTools());
        assertEquals("SIMULATED", detail.caseResults().get(0).toolCalls().get(0).status());
    }

    @Test
    void aQuickRunReportsNoSimulatedTools() {
        EvalRun finished = run(EvalMode.QUICK);
        when(datasetService.requireDataset(AGENT_ID, DATASET_ID)).thenReturn(dataset(false));
        when(runService.requireRun(DATASET_ID, RUN_ID)).thenReturn(finished);
        when(runService.listCaseResults(RUN_ID)).thenReturn(List.of());

        assertTrue(controller.getRun(AGENT_ID, DATASET_ID, RUN_ID).getBody().simulatedTools().isEmpty());
    }

    // ------------------------------------------------------------------ dataset create / update / read

    private void createAnswers(Boolean pin) {
        when(datasetService.createDataset(eq(AGENT_ID), any(), any(), any(), any(), any())).thenReturn(dataset(pin));
        when(datasetService.countCases(DATASET_ID)).thenReturn(0L);
    }

    private Boolean createdWith(UpsertDatasetRequest request) {
        ArgumentCaptor<Boolean> pin = ArgumentCaptor.forClass(Boolean.class);
        verify(datasetService).createDataset(eq(AGENT_ID), any(), any(), any(), pin.capture(), any());
        return pin.getValue();
    }

    @Test
    void aDatasetCreatedWithModeFullOrQuickPinsIt() {
        createAnswers(true);
        controller.createDataset(AGENT_ID, new UpsertDatasetRequest("d", null, null, null, null, "full"));
        assertEquals(Boolean.TRUE, createdWith(null));
    }

    @Test
    void aDatasetCreatedWithModeQuickPinsQuick() {
        createAnswers(false);
        controller.createDataset(AGENT_ID, new UpsertDatasetRequest("d", null, null, null, null, "QUICK"));
        assertEquals(Boolean.FALSE, createdWith(null));
    }

    @Test
    void theOlderBooleanStillWorksWhenNoModeIsNamed() {
        createAnswers(false);
        controller.createDataset(AGENT_ID, new UpsertDatasetRequest("d", null, null, false, null));
        assertEquals(Boolean.FALSE, createdWith(null));
    }

    @Test
    void aDatasetCreatedWithNeitherPinsNothing() {
        createAnswers(null);
        controller.createDataset(AGENT_ID, new UpsertDatasetRequest("d", null, null, null, null));
        assertNull(createdWith(null));
    }

    @Test
    void anUnknownDatasetModeIsRefusedBeforeTheServiceIsCalled() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> controller.createDataset(AGENT_ID, new UpsertDatasetRequest("d", null, null, null, null, "turbo")));

        assertTrue(refused.getMessage().contains("FULL or QUICK"), refused.getMessage());
        org.mockito.Mockito.verifyNoInteractions(datasetService);
    }

    @Test
    void modeDefaultOnAnUpdateDropsThePinAndNullLeavesItAlone() {
        when(datasetService.updateDataset(eq(AGENT_ID), eq(DATASET_ID), any(), any(), any(), any(), any(),
            org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(dataset(null));
        when(datasetService.countCases(DATASET_ID)).thenReturn(0L);

        controller.updateDataset(AGENT_ID, DATASET_ID, new UpsertDatasetRequest("d", null, null, null, null, "default"));
        verify(datasetService).updateDataset(AGENT_ID, DATASET_ID, "d", null, null, null, null, true);

        controller.updateDataset(AGENT_ID, DATASET_ID, new UpsertDatasetRequest("d2", null, null, null, null, null));
        verify(datasetService).updateDataset(AGENT_ID, DATASET_ID, "d2", null, null, null, null, false);
    }

    @Test
    void theDatasetResponseSaysWhatItPinsAndWhatARunOfItTakesRightNow() {
        when(datasetService.countCases(DATASET_ID)).thenReturn(2L);
        createAnswers(null);
        DatasetResponse unpinned = controller.createDataset(
            AGENT_ID, new UpsertDatasetRequest("d", null, null, null, null)).getBody();
        assertNull(unpinned.mode());
        assertNull(unpinned.toolExecutionEnabled());
        assertEquals("FULL", unpinned.effectiveMode(), "it follows the workspace default, the full pipeline");

        when(runService.effectiveDefaultMode()).thenReturn(EvalMode.QUICK);
        createAnswers(null);
        assertEquals("QUICK", controller.createDataset(
            AGENT_ID, new UpsertDatasetRequest("d", null, null, null, null)).getBody().effectiveMode(),
            "a workspace that switched its default back sees quick");

        createAnswers(true);
        DatasetResponse pinned = controller.createDataset(
            AGENT_ID, new UpsertDatasetRequest("d", null, null, null, null, "FULL")).getBody();
        assertEquals("FULL", pinned.mode());
        assertEquals("FULL", pinned.effectiveMode(), "a pin beats the default");
        assertEquals(Boolean.TRUE, pinned.toolExecutionEnabled());
    }
}
