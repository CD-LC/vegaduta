package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The Wait node inside {@link WorkflowExecutionEngine}'s traversal: it parks the run durably (PAUSED + a wait row)
 * at the node AFTER the wait, never executes the nodes behind it, and on a deployment where the feature is off it
 * fails the run with a clear reason instead of silently skipping the wait.
 */
class WorkflowExecutionEngineWaitTest {

    private WorkflowRunRepository runRepository;
    private WorkflowNodeExecutor nodeExecutor;
    private WorkflowWaitRepository waitRepository;
    private WorkflowExecutionEngine engine;
    private final List<String> executed = new java.util.ArrayList<>();

    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        runRepository = mock(WorkflowRunRepository.class);
        nodeExecutor = mock(WorkflowNodeExecutor.class);
        waitRepository = mock(WorkflowWaitRepository.class);
        engine = new WorkflowExecutionEngine(
            runRepository, mock(OrchestratorService.class), nodeExecutor,
            mock(WorkflowEventPublisher.class), mapper(), mock(PlatformIssueService.class),
            mock(ObjectProvider.class), mock(ObjectProvider.class),
            mock(WorkflowRunCancellationService.class), mock(WorkflowRunPauseService.class),
            null, null,
            new com.agenticai.core.security.concurrent.ContextPropagatingExecutor("test-branch-", 16));
        when(nodeExecutor.executeNode(anyString(), anyString(), anyString(), any(WorkflowNodeDto.class),
            any(), any(), any(), any())).thenAnswer(invocation -> {
                WorkflowNodeDto node = invocation.getArgument(3);
                executed.add(node.id());
                return WorkflowNodeExecutor.NodeExecutionResult.ok("out-" + node.id(), "ran " + node.id(), null);
            });
        // The engine resolves {{ }} in the wait node's config through the executor; identity is enough here.
        when(nodeExecutor.resolveConfig(anyString(), anyString(), anyString(), any(WorkflowNodeDto.class), any(), any()))
            .thenAnswer(invocation -> invocation.getArgument(3));
    }

    private void enableWait() {
        engine.setWaitService(new WorkflowWaitService(
            waitRepository, new WorkflowWaitResumeTokens("secret", "https://api.example.com"), true, 7));
    }

    private static WorkflowNodeDto node(String id, String type, Map<String, Object> config) {
        return new WorkflowNodeDto(id, type, Map.of(), Map.of("config", config, "label", id));
    }

    private static Map<String, Object> cfg(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static WorkflowGraphDto triggerWaitAgent(Map<String, Object> waitConfig) {
        return new WorkflowGraphDto(
            List.of(node("t", "trigger", Map.of()), node("w", "wait", waitConfig), node("a", "agent", Map.of())),
            List.of(new WorkflowEdgeDto("e1", "t", "w", null, null), new WorkflowEdgeDto("e2", "w", "a", null, null)));
    }

    private static WorkflowDefinition definitionWithId() throws ReflectiveOperationException {
        WorkflowDefinition definition = new WorkflowDefinition(
            "Test Workflow", "desc", "{\"nodes\":[],\"edges\":[]}", WorkflowStatus.ACTIVE);
        java.lang.reflect.Field idField = WorkflowDefinition.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(definition, UUID.randomUUID());
        return definition;
    }

    @Test
    void aDurationWaitParksTheRunAtTheNextNodeWithTheWaitInputAndARow() throws Exception {
        enableWait();
        Instant before = Instant.now();

        WorkflowRun run = engine.execute(definitionWithId(), triggerWaitAgent(cfg("mode", "duration", "duration", "2h")),
            null, "system:test");

        assertEquals(WorkflowRunStatus.PAUSED, run.getStatus());
        assertEquals("a", run.getPausedNodeId(), "the run resumes at the node AFTER the wait");
        assertEquals("out-t", run.getPausedNodeInput(), "the wait passes its input through");
        assertNull(run.getCompletedAt(), "a parked run has not finished");
        assertEquals(List.of("t"), executed, "nothing behind the wait runs");
        assertTrue(run.getTraceJson().contains("AWAITING_WAIT"), run.getTraceJson());

        ArgumentCaptor<WorkflowWait> saved = ArgumentCaptor.forClass(WorkflowWait.class);
        verify(waitRepository).save(saved.capture());
        WorkflowWait row = saved.getValue();
        assertEquals(run.getId(), row.getRunId());
        assertEquals("w", row.getNodeId());
        assertEquals("a", row.getResumeNodeId());
        assertEquals("DURATION", row.getMode());
        assertEquals(WorkflowWait.Status.WAITING, row.getStatus());
        Instant earliest = before.plus(Duration.ofHours(2));
        assertTrue(!row.getWakeAt().isBefore(earliest) && row.getWakeAt().isBefore(earliest.plusSeconds(60)),
            "wake time is now + 2h, got " + row.getWakeAt());
    }

    @Test
    void aWebhookWaitParksWithItsTimeoutAsTheDeadline() throws Exception {
        enableWait();

        WorkflowRun run = engine.execute(definitionWithId(),
            triggerWaitAgent(cfg("mode", "webhook", "timeout", "1", "timeoutUnit", "hours", "onTimeout", "continue")),
            null, "system:test");

        assertEquals(WorkflowRunStatus.PAUSED, run.getStatus());
        ArgumentCaptor<WorkflowWait> saved = ArgumentCaptor.forClass(WorkflowWait.class);
        verify(waitRepository).save(saved.capture());
        assertEquals("WEBHOOK", saved.getValue().getMode());
        assertEquals(false, saved.getValue().isFailOnTimeout());
    }

    @Test
    void aWaitOnADeploymentWithTheFeatureOffFailsTheRunClearlyInsteadOfSkipping() throws Exception {
        // no setWaitService: exactly what a hand-built / flag-off engine looks like
        WorkflowRun run = engine.execute(definitionWithId(), triggerWaitAgent(cfg("duration", "2h")), null, "system:test");

        assertEquals(WorkflowRunStatus.FAILED, run.getStatus());
        assertTrue(run.getErrorMessage().contains("app.workflow.wait-node.enabled"), run.getErrorMessage());
        assertEquals(List.of("t"), executed, "the wait must not be skipped over");
        verify(waitRepository, never()).save(any());
    }

    @Test
    void aWaitBeyondTheCapFailsTheRunWithTheReason() throws Exception {
        enableWait();

        WorkflowRun run = engine.execute(definitionWithId(), triggerWaitAgent(cfg("duration", "30d")), null, "system:test");

        assertEquals(WorkflowRunStatus.FAILED, run.getStatus());
        assertTrue(run.getErrorMessage().contains("exceeds the maximum"), run.getErrorMessage());
        verify(waitRepository, never()).save(any());
    }

    @Test
    void anUntilInThePastContinuesImmediately() throws Exception {
        enableWait();

        WorkflowRun run = engine.execute(definitionWithId(),
            triggerWaitAgent(cfg("mode", "until", "until", "2020-01-01T00:00:00Z")), null, "system:test");

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        assertEquals(List.of("t", "a"), executed);
        verify(waitRepository, never()).save(any());
    }

    @Test
    void aWaitWithNothingAfterItEndsTheRunWithoutParking() throws Exception {
        enableWait();
        WorkflowGraphDto graph = new WorkflowGraphDto(
            List.of(node("t", "trigger", Map.of()), node("w", "wait", cfg("duration", "2h"))),
            List.of(new WorkflowEdgeDto("e1", "t", "w", null, null)));

        WorkflowRun run = engine.execute(definitionWithId(), graph, null, "system:test");

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        assertTrue(run.getTraceJson().contains("Nothing follows this Wait node"), run.getTraceJson());
        verify(waitRepository, never()).save(any());
    }

    @Test
    void aDryRunSkipsTheWait() throws Exception {
        enableWait();

        // (definition, graph, startNodeId, input, requestedBy, clientRunId, attachments, mode)
        WorkflowRun run = engine.execute(definitionWithId(), triggerWaitAgent(cfg("duration", "2h")), null, null,
            "system:test", null, List.of(), RunMode.DRAFT_DRY_RUN);

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        assertEquals(List.of("t", "a"), executed);
        assertTrue(run.getTraceJson().contains("DRY RUN - "), run.getTraceJson());
        verify(waitRepository, never()).save(any());
        assertNotNull(run.getTraceJson());
    }
}
