package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * {@link ParallelBranchRunner} joins branches in completion order, so a failure surfaces as soon as
 * the failing branch finishes - not after every earlier-indexed branch has. On a slow first branch
 * and a fast failing second branch, the old index-order join waited for the slow one; this pins the
 * fail-fast behavior with a real thread pool.
 */
class ParallelBranchRunnerTest {

    private static WorkflowNodeDto node(String id) {
        return new WorkflowNodeDto(id, "agent", Map.of(), Map.of());
    }

    @Test
    void aFailureInALaterBranchSurfacesWithoutWaitingForEarlierSlowBranches() throws Exception {
        var executor = Executors.newFixedThreadPool(3);
        try {
            ParallelBranchRunner runner = new ParallelBranchRunner(executor);
            WorkflowNodeDto slow = node("slow");
            WorkflowNodeDto failing = node("failing");

            long startedAt = System.nanoTime();
            ParallelBranchRunner.Joined joined =
                    runner.run(
                            List.of(slow, failing),
                            branch -> {
                                if (branch.id().equals("failing")) {
                                    throw new IllegalStateException("boom");
                                }
                                try {
                                    Thread.sleep(5_000);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                return WorkflowNodeExecutor.NodeExecutionResult.ok(
                                        "x", "done", null);
                            });
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertNotNull(joined.failure());
            assertEquals("failing", joined.failedBranch().id());
            assertTrue(
                    elapsedMillis < 2_000,
                    "joined after "
                            + elapsedMillis
                            + "ms - a completion-order join must not wait"
                            + " for the 5s slow branch");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void everySucceedingBranchIsJoined() {
        var executor = Executors.newFixedThreadPool(3);
        try {
            ParallelBranchRunner runner = new ParallelBranchRunner(executor);
            List<WorkflowNodeDto> branches = List.of(node("a"), node("b"), node("c"));
            ParallelBranchRunner.Joined joined =
                    runner.run(
                            branches,
                            branch ->
                                    WorkflowNodeExecutor.NodeExecutionResult.ok(
                                            branch.id(), "done", null));

            assertNull(joined.failure());
            assertEquals(3, joined.outcomes().size());
            assertEquals(
                    List.of("a", "b", "c"),
                    joined.outcomes().stream().map(o -> o.node().id()).sorted().toList());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void outcomesAreJoinedEvenWhenAnEarlierIndexedBranchFinishesLast() {
        var executor = Executors.newFixedThreadPool(3);
        try {
            ParallelBranchRunner runner = new ParallelBranchRunner(executor);
            WorkflowNodeDto slow = node("slow");
            WorkflowNodeDto fast = node("fast");

            ParallelBranchRunner.Joined joined =
                    runner.run(
                            List.of(slow, fast),
                            branch -> {
                                if (branch.id().equals("slow")) {
                                    try {
                                        Thread.sleep(500);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                }
                                return WorkflowNodeExecutor.NodeExecutionResult.ok(
                                        branch.id(), "done", null);
                            });

            assertNull(joined.failure());
            assertEquals(2, joined.outcomes().size());
        } finally {
            executor.shutdownNow();
        }
    }
}
