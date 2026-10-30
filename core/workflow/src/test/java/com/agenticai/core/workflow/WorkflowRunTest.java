package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link WorkflowRun}: the entity's lifecycle transitions and envelope handling. */
class WorkflowRunTest {

    private static WorkflowRun run() {
        return new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), "Flow", "user@x");
    }

    @Test
    void aNewRunStartsRunningWithItsIdentity() {
        UUID id = UUID.randomUUID();
        UUID wf = UUID.randomUUID();
        WorkflowRun r = new WorkflowRun(id, wf, "Flow", "alice");
        assertEquals(id, r.getId());
        assertEquals(wf, r.getWorkflowId());
        assertEquals("Flow", r.getWorkflowName());
        assertEquals("alice", r.getRequestedBy());
        assertEquals(WorkflowRunStatus.RUNNING, r.getStatus());
        assertNotNull(r.getStartedAt());
        assertNull(r.getCompletedAt());
        assertNull(r.getTraceJson());
        assertNull(r.getErrorMessage());
        assertEquals(RunMode.PRODUCTION, r.getRunMode());
    }

    @Test
    void updateTraceTouchesOnlyTheTrace() {
        WorkflowRun r = run();
        r.updateTrace("[{}]");
        assertEquals("[{}]", r.getTraceJson());
        assertEquals(WorkflowRunStatus.RUNNING, r.getStatus());
        assertNull(r.getCompletedAt());
    }

    @Test
    void completeStampsTheTerminalStateAndOutputs() {
        WorkflowRun r = run();
        r.complete(WorkflowRunStatus.COMPLETED, "[]", null, "out.txt", "data");
        assertEquals(WorkflowRunStatus.COMPLETED, r.getStatus());
        assertEquals("[]", r.getTraceJson());
        assertNull(r.getErrorMessage());
        assertEquals("out.txt", r.getOutputFileName());
        assertEquals("data", r.getOutputData());
        assertNotNull(r.getCompletedAt());

        WorkflowRun failed = run();
        failed.complete(WorkflowRunStatus.FAILED, "[]", "boom");
        assertEquals("boom", failed.getErrorMessage());
        assertNull(failed.getOutputFileName());
    }

    @Test
    void pauseParksWithoutCompletingAndResumeClearsTheEnvelope() {
        WorkflowRun r = run();
        r.pause("[{}]", "n7", "input-json");
        assertEquals(WorkflowRunStatus.PAUSED, r.getStatus());
        assertEquals("n7", r.getPausedNodeId());
        assertEquals("input-json", r.getPausedNodeInput());
        assertNull(r.getCompletedAt(), "a paused run has not finished");

        r.clearPauseEnvelope();
        assertNull(r.getPausedNodeId());
        assertNull(r.getPausedNodeInput());

        r.pause("[{}]", "n8", "in");
        r.resume();
        assertEquals(WorkflowRunStatus.RUNNING, r.getStatus());
        assertNull(r.getPausedNodeId());
        assertNull(r.getPausedNodeInput());
    }

    @Test
    void commentIsStrippedAndBlankBecomesNull() {
        WorkflowRun r = run();
        assertNull(r.getComment());
        r.setComment("  hello ops  ");
        assertEquals("hello ops", r.getComment());
        r.setComment("   ");
        assertNull(r.getComment());
        r.setComment(null);
        assertNull(r.getComment());
    }

    @Test
    void runModeDefaultsToProductionAndNullResetsToIt() {
        WorkflowRun r = run();
        r.setRunMode(RunMode.DRAFT);
        assertEquals(RunMode.DRAFT, r.getRunMode());
        r.setRunMode(null);
        assertEquals(RunMode.PRODUCTION, r.getRunMode());
    }

    @Test
    void workflowVersionIsStampedOnceAtRunStart() {
        WorkflowRun r = run();
        assertNull(r.getWorkflowVersion());
        r.setWorkflowVersion(4);
        assertEquals(4, r.getWorkflowVersion());
    }
}
