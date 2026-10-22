package com.agenticai.core.workflow.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.workflow.OrchestratorService;
import com.agenticai.core.workflow.RunMode;
import com.agenticai.core.workflow.WorkflowDefinition;
import com.agenticai.core.workflow.WorkflowRun;
import com.agenticai.core.workflow.WorkflowRunRepository;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.version.WorkflowVersion;
import com.agenticai.core.workflow.version.WorkflowVersionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@code TemporalWorkflowExecutionEngine.execute(..., RunMode)} against Temporal's in-memory test
 * environment. The engine is the only place that decides which graph a Temporal run executes (live
 * for DRAFT, the promoted snapshot for PRODUCTION), stamps the mode on the run row, and carries it
 * into the workflow input; mutating any of the three used to leave every test green because the
 * workflow tests build their inputs by hand.
 */
class TemporalWorkflowExecutionEngineTest {

    private static final UUID WF = UUID.randomUUID();

    private TestWorkflowEnvironment testEnv;
    private RecordingActivities activities;
    private WorkflowRunRepository runRepository;
    private WorkflowVersionService versions;
    private TemporalWorkflowExecutionEngine engine;
    private WorkflowDefinition definition;

    private final WorkflowGraphDto live = graphWith("live-trigger");

    private static WorkflowGraphDto graphWith(String triggerId) {
        return new WorkflowGraphDto(
                List.of(
                        new WorkflowNodeDto(
                                triggerId, "trigger", Map.of(), Map.of("label", "Start"))),
                List.of());
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        testEnv = TestWorkflowEnvironment.newInstance();
        activities = new RecordingActivities();
        Worker worker = testEnv.newWorker(TemporalConfig.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(WorkflowRunWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        testEnv.start();

        runRepository = mock(WorkflowRunRepository.class);
        when(runRepository.findById(any())).thenReturn(Optional.empty());

        versions = mock(WorkflowVersionService.class);
        WorkflowVersion snapshot = mock(WorkflowVersion.class);
        when(snapshot.getVersionNumber()).thenReturn(3);
        when(snapshot.getGraphJson())
                .thenReturn(new ObjectMapper().writeValueAsString(graphWith("snapshot-trigger")));
        when(versions.resolveRunVersion(WF)).thenReturn(Optional.of(snapshot));
        when(versions.currentVersionNumber(WF)).thenReturn(7);
        ObjectProvider<WorkflowVersionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(versions);

        engine =
                new TemporalWorkflowExecutionEngine(
                        runRepository,
                        mock(OrchestratorService.class),
                        mock(WorkflowEventPublisher.class),
                        testEnv.getWorkflowClient(),
                        provider);
        // @Value is not applied outside Spring; without a wait the engine would return before the
        // run finishes.
        java.lang.reflect.Field timeout =
                TemporalWorkflowExecutionEngine.class.getDeclaredField("runTimeoutSeconds");
        timeout.setAccessible(true);
        timeout.setLong(engine, 30L);

        definition = mock(WorkflowDefinition.class);
        when(definition.getId()).thenReturn(WF);
        when(definition.getName()).thenReturn("wf");
        when(definition.getMaxStepsPerRun()).thenReturn(50);
        when(definition.getStepTimeoutSeconds()).thenReturn(30);
        when(definition.getMaxRetryAttempts()).thenReturn(0);
    }

    @AfterEach
    void tearDown() {
        testEnv.close();
    }

    private WorkflowRun savedRun() {
        ArgumentCaptor<WorkflowRun> saved = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(runRepository).save(saved.capture());
        return saved.getValue();
    }

    @Test
    void aDraftRunExecutesTheCallersLiveGraphAndStampsDraftOnTheRowAndEveryActivity() {
        engine.execute(definition, live, null, "hello", "ann", UUID.randomUUID(), RunMode.DRAFT);

        assertThat(activities.calls).hasSize(1);
        assertThat(activities.calls.get(0).node().id())
                .as("a draft run must not be swapped for the promoted snapshot")
                .isEqualTo("live-trigger");
        assertThat(activities.calls.get(0).expressionScope()).isNotNull();
        assertThat(activities.calls.get(0).expressionScope().mode()).isEqualTo("draft");
        WorkflowRun run = savedRun();
        assertThat(run.getRunMode()).isEqualTo(RunMode.DRAFT);
        verify(versions, never()).resolveRunVersion(any());
    }

    @Test
    void aProductionRunStillExecutesThePromotedSnapshotAndBindsNoScope() {
        engine.execute(
                definition, live, null, "hello", "ann", UUID.randomUUID(), RunMode.PRODUCTION);

        assertThat(activities.calls).hasSize(1);
        assertThat(activities.calls.get(0).node().id()).isEqualTo("snapshot-trigger");
        assertThat(activities.calls.get(0).expressionScope()).isNull();
        WorkflowRun run = savedRun();
        assertThat(run.getRunMode()).isEqualTo(RunMode.PRODUCTION);
        assertThat(run.getWorkflowVersion()).isEqualTo(3);
    }

    @Test
    void theOverloadWithoutAModeIsProduction() {
        engine.execute(definition, live, null, "hello", "ann", UUID.randomUUID());

        assertThat(activities.calls.get(0).node().id()).isEqualTo("snapshot-trigger");
        assertThat(savedRun().getRunMode()).isEqualTo(RunMode.PRODUCTION);
    }

    private static class RecordingActivities implements WorkflowNodeActivities {
        final List<NodeExecutionActivityInput> calls =
                java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public NodeExecutionActivityResult executeNode(NodeExecutionActivityInput input) {
            calls.add(input);
            return new NodeExecutionActivityResult(
                    "out", "OK", "ok", null, null, List.of(), null, null, 1000L, 2000L);
        }

        @Override
        public void recordProgress(RunProgressInput input) {
            // the row is not under test here
        }

        @Override
        public void recordContainerOutput(ContainerOutputInput input) {
            // not under test
        }
    }
}
