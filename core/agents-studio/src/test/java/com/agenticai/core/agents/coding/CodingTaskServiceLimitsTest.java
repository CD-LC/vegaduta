package com.agenticai.core.agents.coding;

import static com.agenticai.core.agents.coding.CodingTaskTestSupport.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.agents.coding.CodingTaskDtos.TaskDocument;
import com.agenticai.core.agents.coding.CodingTaskDtos.TaskView;
import com.agenticai.core.agents.coding.CodingTaskService.StartInput;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The coding-task service against fakes, second half: budget stops, the confined fixer, tenancy,
 * concurrency and liveness, and the tool's wait. Fixture in {@link CodingTaskServiceTestBase}.
 */
class CodingTaskServiceLimitsTest extends CodingTaskServiceTestBase {

    // ---------------------------------------------------------------------------------------- budget

    @Test
    void anExhaustedStepBudgetStopsTheTaskAndRollsBack() {
        CodingTaskService svc = service();
        // One model call allowed: the initial edit uses it, the tests fail, the loop must stop (not call the model again).
        TaskView started = svc.start(new StartInput(agentId, "Add validation", CMD, REPO, null, 1, null, null, null), "user1");
        TaskView ended = waitForTerminal(svc, started.taskId());
        assertThat(ended.status()).isEqualTo("FAILED");
        assertThat(ended.outcome()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(ended.message()).contains("step budget");
        assertThat(model.prompts).hasSize(1);
        assertThat(sandbox.rolledBack()).isTrue();
    }

    @Test
    void theWallClockBudgetStopsTheTaskOnceItIsSpent() {
        // The model call takes "two minutes" of clock; the budget is one minute.
        modelFactory = (agent, subject, taskId) -> (system, user) -> {
            now.addAndGet(120_000L);
            return model.complete(system, user);
        };
        CodingTaskService svc = service();
        TaskView started = svc.start(new StartInput(agentId, "Add validation", CMD, REPO, null, null, 60, null, null), "user1");
        TaskView ended = waitForTerminal(svc, started.taskId());
        assertThat(ended.status()).isEqualTo("FAILED");
        assertThat(ended.outcome()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(ended.message()).contains("time budget");
        assertThat(model.prompts).hasSize(1);
        assertThat(sandbox.rolledBack()).isTrue();
    }

    // ---------------------------------------------------------------------------------------- the fixer is confined

    @Test
    void editsOutsideTheWorkspaceAreSkippedAndNeverReachTheShell() {
        sandbox.testExits.add(0);
        model.fixerReplies.add("{\"edits\":["
            + "{\"path\":\"../../etc/passwd\",\"content\":\"pwned\"},"
            + "{\"path\":\"/etc/cron.d/x\",\"content\":\"pwned\"},"
            + "{\"path\":\".git/hooks/pre-commit\",\"content\":\"#!/bin/sh\"},"
            + "{\"path\":\"a/../../b\",\"delete\":true},"
            + "{\"path\":\"src/Foo.java\",\"content\":\"class Foo { }\"}],\"note\":\"mixed\"}");
        CodingTaskService svc = service();
        TaskView started = svc.start(gitInput(), "user1");
        TaskView waiting = waitForStatus(svc, started.taskId(), "AWAITING_APPROVAL");
        assertThat(waiting.events().stream().map(e -> e.text()).filter(t -> t.contains("skipped")).findFirst()).isPresent();
        synchronized (sandbox.internalCommands) {
            assertThat(sandbox.internalCommands.stream().filter(c -> c.contains("base64 -d")).toList()).hasSize(1);
            assertThat(sandbox.internalCommands.stream().filter(c -> c.contains("passwd") || c.contains("cron.d") || c.contains("pre-commit")).toList()).isEmpty();
        }
        svc.reject(started.taskId(), "user1");
        waitForStatus(svc, started.taskId(), "REJECTED");
    }

    // ---------------------------------------------------------------------------------------- tenancy

    @Test
    void anotherUserOrAnotherTenantSeesA404ForEveryRoute() {
        sandbox.testExits.add(0);
        CodingTaskService svc = service();
        TaskView started = svc.start(gitInput(), "user1");
        TaskView waiting = waitForStatus(svc, started.taskId(), "AWAITING_APPROVAL");

        for (String[] who : new String[][] {{"tenantA", "user2"}, {"tenantB", "user1"}}) {
            as(who[0], who[1]);
            String user = who[1];
            List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> calls = List.of(
                () -> svc.get(started.taskId(), user),
                () -> svc.diff(started.taskId(), user),
                () -> svc.approve(started.taskId(), user, waiting.diffHash()),
                () -> svc.reject(started.taskId(), user),
                () -> svc.cancel(started.taskId(), user),
                () -> svc.rollback(started.taskId(), user));
            for (var call : calls) {
                assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class,
                    e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
            }
            assertThat(svc.list(user)).isEmpty();
        }
        assertThat(store.decisions).isEmpty();
        assertThat(store.cancels).isEmpty();

        as("tenantA", "user1");
        assertThat(svc.list("user1")).extracting(TaskView::taskId).containsExactly(started.taskId());
        svc.reject(started.taskId(), "user1");
        waitForStatus(svc, started.taskId(), "REJECTED");
    }

    @Test
    void aMalformedTaskIdIsA404NotAStoreProbe() {
        CodingTaskService svc = service();
        assertThatThrownBy(() -> svc.get("../../etc/passwd", "user1")).isInstanceOfSatisfying(ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> svc.get("*", "user1")).isInstanceOf(ResponseStatusException.class);
    }

    // ---------------------------------------------------------------------------------------- concurrency and liveness

    @Test
    void oneLiveTaskPerUserThenTheSlotIsFreedWhenItEnds() {
        sandbox.testExits.add(0);
        CodingTaskService svc = service();
        TaskView first = svc.start(gitInput(), "user1");
        waitForStatus(svc, first.taskId(), "AWAITING_APPROVAL");
        assertThatThrownBy(() -> svc.start(gitInput(), "user1")).isInstanceOfSatisfying(ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        // A different user in the same tenant is not blocked by it.
        as("tenantA", "user2");
        sandbox.testExits.add(0);
        TaskView other = svc.start(gitInput(), "user2");
        assertThat(other.taskId()).isNotEqualTo(first.taskId());
        svc.cancel(other.taskId(), "user2");
        as("tenantA", "user1");
        svc.reject(first.taskId(), "user1");
        waitForStatus(svc, first.taskId(), "REJECTED");
        await(() -> store.locks.isEmpty() || !store.locks.containsKey("tenantA|user1"));
    }

    @Test
    void aSaturatedPoolIsA429AndLeavesNothingBehind() {
        CodingTaskExecutor full = new CodingTaskExecutor() {
            @Override
            public boolean trySubmit(Runnable body) {
                return false;
            }
        };
        CodingTaskService svc = service(settings(true, false, 900), full);
        assertThatThrownBy(() -> svc.start(gitInput(), "user1")).isInstanceOfSatisfying(ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        assertThat(store.docs).isEmpty();
        assertThat(store.locks).isEmpty();
        assertThat(svc.liveTaskCount()).isZero();
        full.shutdown();
    }

    @Test
    void aQueuedTaskCanBeCancelledAtOnce() {
        List<Runnable> parked = new ArrayList<>();
        CodingTaskExecutor parking = new CodingTaskExecutor() {
            @Override
            public boolean trySubmit(Runnable body) {
                parked.add(body);
                return true;
            }

            @Override
            public boolean remove(Runnable body) {
                return parked.remove(body);
            }
        };
        CodingTaskService svc = service(settings(true, false, 900), parking);
        TaskView started = svc.start(gitInput(), "user1");
        assertThat(started.status()).isEqualTo("QUEUED");
        svc.cancel(started.taskId(), "user1");
        assertThat(svc.get(started.taskId(), "user1").status()).isEqualTo("CANCELLED");
        assertThat(parked).isEmpty();
        assertThat(store.locks).isEmpty();
        parking.shutdown();
    }

    @Test
    void aTaskWhoseJvmDiedIsFailedByThePollAndTheLockFreed() {
        CodingTaskService svc = service();
        String id = UUID.randomUUID().toString();
        TaskView orphan = new TaskView(id, agentId.toString(), "RUNNING", null, "TESTING", "x", CMD, "GIT", REPO, "e2b",
            new CodingTaskDtos.Budget(20, 900, 150000, 3), new CodingTaskDtos.Usage(0, 0, 0), List.of(), List.of(), null,
            false, null, 0, false, null, false, 1L, 1L);
        store.save("tenantA", new TaskDocument(orphan, "user1"));
        store.locks.put("tenantA|user1", id);
        // No liveness mark and not in this JVM: it is dead.
        TaskView polled = svc.get(id, "user1");
        assertThat(polled.status()).isEqualTo("FAILED");
        assertThat(polled.message()).contains("stopped responding");
        assertThat(store.locks).isEmpty();
    }

    // ---------------------------------------------------------------------------------------- tool support

    @Test
    void awaitForToolReturnsOnceTheTaskWaitsForApprovalAndNeverDecidesAnything() {
        sandbox.testExits.add(0);
        CodingTaskService svc = service();
        TaskView started = svc.start(gitInput(), "user1");
        TaskView seen = svc.awaitForTool(started.taskId(), "user1", 5);
        assertThat(seen.status()).isEqualTo("AWAITING_APPROVAL");
        assertThat(store.decisions).isEmpty();
        svc.reject(started.taskId(), "user1");
        waitForStatus(svc, started.taskId(), "REJECTED");
    }
}
