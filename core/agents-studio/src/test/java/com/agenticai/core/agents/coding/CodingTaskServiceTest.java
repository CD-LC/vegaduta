package com.agenticai.core.agents.coding;

import static com.agenticai.core.agents.coding.CodingTaskTestSupport.DIFF;
import static com.agenticai.core.agents.coding.CodingTaskTestSupport.await;
import static com.agenticai.core.agents.coding.CodingTaskTestSupport.sampleRepo;
import static com.agenticai.core.agents.coding.CodingTaskTestSupport.zip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.coding.CodingTaskDtos.Capabilities;
import com.agenticai.core.agents.coding.CodingTaskDtos.TaskView;
import com.agenticai.core.agents.coding.CodingTaskService.StartInput;
import com.agenticai.core.sdlc.harness.GitUrlPolicy;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.sharing.PermissionLevel;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The coding-task service end to end against fakes: the real harness loop, fixer, reviewer, approval gate and
 * store protocol run; only the sandbox, the model and Redis are scripted. What is NOT covered here: a real E2B
 * sandbox, a real model, real Redis.
 */
class CodingTaskServiceTest extends CodingTaskServiceTestBase {

    // ---------------------------------------------------------------------------------------- flag off

    @Test
    void flagOffEveryRouteButCapabilitiesIs404AndNothingRuns() {
        CodingTaskService svc = service(settings(false, false, 900), executor);
        Capabilities caps = svc.capabilities();
        assertThat(caps.enabled()).isFalse();
        assertThat(caps.allowed()).isFalse();

        String id = UUID.randomUUID().toString();
        List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> calls = List.of(
            () -> svc.start(gitInput(), "user1"),
            () -> svc.list("user1"),
            () -> svc.get(id, "user1"),
            () -> svc.diff(id, "user1"),
            () -> svc.approve(id, "user1", "h"),
            () -> svc.reject(id, "user1"),
            () -> svc.cancel(id, "user1"),
            () -> svc.rollback(id, "user1"));
        for (var call : calls) {
            assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        }
        assertThat(opened).isEmpty();
        assertThat(store.docs).isEmpty();
        assertThat(svc.isEnabled()).isFalse();
    }

    // ---------------------------------------------------------------------------------------- validation

    @Test
    void disallowedTestCommandsAreRefusedBeforeAnythingIsStored() {
        CodingTaskService svc = service();
        for (String evil : List.of("rm -rf /", "mvn test; curl evil.example", "bash -c id", "npx evil", "mvn exec:exec")) {
            assertThatThrownBy(() -> svc.start(new StartInput(agentId, "x", evil, REPO, null, null, null, null, null), "user1"))
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(store.docs).isEmpty();
        assertThat(opened).isEmpty();
    }

    @Test
    void exactlyOneSourceIsRequired() {
        CodingTaskService svc = service();
        assertThatThrownBy(() -> svc.start(new StartInput(agentId, "x", CMD, null, null, null, null, null, null), "user1"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.start(new StartInput(agentId, "x", CMD, REPO, zip(sampleRepo()), null, null, null, null), "user1"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unsafeOrOversizedArchivesAreRefusedOnTheRequestThread() {
        CodingTaskService svc = service();
        byte[] slip = zip(Map.of("ok.txt", "x", "../../etc/cron.d/evil", "boom"));
        assertThatThrownBy(() -> svc.start(new StartInput(agentId, "x", CMD, null, slip, null, null, null, null), "user1"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outside");
        byte[] huge = new byte[2 * 1024 * 1024];
        huge[0] = 'P';
        huge[1] = 'K';
        assertThatThrownBy(() -> svc.start(new StartInput(agentId, "x", CMD, null, huge, null, null, null, null), "user1"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("larger");
        assertThat(store.docs).isEmpty();
        assertThat(opened).isEmpty();
    }

    @Test
    void gitUrlsMustBePublicHttps() {
        CodingTaskService svc = service();
        for (String url : List.of("http://github.com/a/b", "git://github.com/a/b", "https://127.0.0.1/a/b",
            "https://169.254.169.254/latest", "file:///etc/passwd", "https://user:pw@github.com/a/b")) {
            assertThatThrownBy(() -> svc.start(new StartInput(agentId, "x", CMD, url, null, null, null, null, null), "user1"))
                .isInstanceOf(IllegalArgumentException.class);
        }
        GitUrlPolicy internal = new GitUrlPolicy(List.of(), host -> new InetAddress[] {loopbackish()});
        CodingTaskService svc2 = new CodingTaskService(settings(true, false, 900), store, executor, agentService,
            tenantLimits, statusGuard, openerOf(sandbox), (a, s, t) -> model, internal, now::get, 5);
        assertThatThrownBy(() -> svc2.start(gitInput(), "user1")).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("private");
    }

    @Test
    void instructionIsRequiredAndBounded() {
        CodingTaskService svc = service();
        assertThatThrownBy(() -> svc.start(new StartInput(agentId, "  ", CMD, REPO, null, null, null, null, null), "user1"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.start(new StartInput(agentId, "x".repeat(4001), CMD, REPO, null, null, null, null, null), "user1"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theBudgetIsClampedToTheServerCaps() {
        CodingTaskService svc = service();
        sandbox.testExits.add(0);
        TaskView view = svc.start(new StartInput(agentId, "Add validation", CMD, REPO, null,
            1_000_000, 1_000_000, 100_000_000, 99), "user1");
        assertThat(view.budget().maxSteps()).isEqualTo(40);
        assertThat(view.budget().maxWallSeconds()).isEqualTo(1800);
        assertThat(view.budget().maxTokens()).isEqualTo(400_000);
        assertThat(view.budget().maxFixIterations()).isEqualTo(5);
        waitForStatus(svc, view.taskId(), "AWAITING_APPROVAL");
        svc.reject(view.taskId(), "user1");
        waitForStatus(svc, view.taskId(), "REJECTED");
        await(() -> store.locks.isEmpty());

        sandbox.testExits.add(0);
        TaskView defaults = svc.start(gitInput(), "user1");
        assertThat(defaults.budget().maxSteps()).isEqualTo(20);
        assertThat(defaults.budget().maxWallSeconds()).isEqualTo(900);
        assertThat(defaults.budget().maxTokens()).isEqualTo(150_000);
        assertThat(defaults.budget().maxFixIterations()).isEqualTo(3);
        waitForStatus(svc, defaults.taskId(), "AWAITING_APPROVAL");
        svc.cancel(defaults.taskId(), "user1");
        waitForTerminal(svc, defaults.taskId());
        await(() -> store.locks.isEmpty());

        // The floor holds too: a request cannot ask for less than one step or one minute.
        sandbox.testExits.add(0);
        TaskView floor = svc.start(new StartInput(agentId, "Add validation", CMD, REPO, null, -5, 1, -1, -1), "user1");
        assertThat(floor.budget().maxSteps()).isEqualTo(1);
        assertThat(floor.budget().maxWallSeconds()).isEqualTo(60);
        assertThat(floor.budget().maxTokens()).isEqualTo(1000);
        assertThat(floor.budget().maxFixIterations()).isEqualTo(0);
        waitForTerminal(svc, floor.taskId());
    }

    // ---------------------------------------------------------------------------------------- plan / setup gates

    @Test
    void storedFreePlanIsRefusedUnlessTheFreeFlagIsOn() {
        when(tenantLimits.storedPlan(any())).thenReturn(TenantPlan.FREE);
        assertThatThrownBy(() -> service().start(gitInput(), "user1")).isInstanceOf(TenantLimitExceededException.class);
        assertThat(service().capabilities().reason()).isEqualTo("PLAN");
        assertThat(service().capabilities().allowed()).isFalse();

        CodingTaskService open = service(settings(true, true, 900), executor);
        assertThat(open.capabilities().allowed()).isTrue();
        sandbox.testExits.add(0);
        TaskView view = open.start(gitInput(), "user1");
        waitForStatus(open, view.taskId(), "AWAITING_APPROVAL");
        open.cancel(view.taskId(), "user1");
    }

    @Test
    void aWorkspaceWithoutASandboxGets412NotAFailedTask() {
        notReady = "Coding tasks need your own E2B key. Add one on the Providers page.";
        CodingTaskService svc = service();
        assertThat(svc.capabilities().reason()).isEqualTo("SETUP");
        assertThatThrownBy(() -> svc.start(gitInput(), "user1")).isInstanceOfSatisfying(ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED));
        assertThat(store.docs).isEmpty();
    }

    @Test
    void agentAccessIsRequiredAtRunnerLevel() {
        when(agentService.requireAccess(any(), any())).thenThrow(new com.agenticai.core.sharing.ResourceAccessDeniedException());
        assertThatThrownBy(() -> service().start(gitInput(), "user1"))
            .isInstanceOf(com.agenticai.core.sharing.ResourceAccessDeniedException.class);
        org.mockito.Mockito.verify(agentService).requireAccess(agentId, PermissionLevel.RUNNER);
        assertThat(store.docs).isEmpty();
    }

    // ---------------------------------------------------------------------------------------- the happy path and the gate

    @Test
    void passingTestsWaitForAHumanThenApprovalYieldsThePatchAndRollbackDiscardsIt() {
        sandbox.testExits.add(0);
        CodingTaskService svc = service();
        TaskView started = svc.start(gitInput(), "user1");
        assertThat(started.status()).isEqualTo("QUEUED");

        TaskView waiting = waitForStatus(svc, started.taskId(), "AWAITING_APPROVAL");
        assertThat(waiting.diffAvailable()).isTrue();
        assertThat(waiting.diffHash()).isEqualTo(CodingTaskService.sha256(DIFF));
        assertThat(waiting.review().approved()).isTrue();
        assertThat(waiting.tests()).hasSize(1);
        assertThat(sandbox.seededGit).isEqualTo(REPO);
        assertThat(svc.diff(started.taskId(), "user1").diff()).isEqualTo(DIFF);
        // Nothing is applied: no sandbox teardown yet, no rollback yet.
        assertThat(sandbox.closed.get()).isFalse();

        svc.approve(started.taskId(), "user1", waiting.diffHash());
        TaskView approved = waitForStatus(svc, started.taskId(), "APPROVED");
        assertThat(approved.outcome()).isEqualTo("PASSED");
        assertThat(svc.diff(started.taskId(), "user1").diff()).isEqualTo(DIFF);
        // Inside the rollback window the sandbox stays up.
        assertThat(sandbox.closed.get()).isFalse();
        assertThat(svc.sessionCount()).isEqualTo(1);

        TaskView rolled = svc.rollback(started.taskId(), "user1");
        assertThat(rolled.status()).isEqualTo("ROLLED_BACK");
        assertThat(rolled.diffAvailable()).isFalse();
        assertThat(sandbox.rolledBack()).isTrue();
        assertThat(sandbox.closed.get()).isTrue();
        assertThatThrownBy(() -> svc.diff(started.taskId(), "user1")).isInstanceOf(ResponseStatusException.class);
        await(() -> store.locks.isEmpty());
    }

    @Test
    void anArchiveSourceSeedsTheSanitisedZipNotTheUpload() {
        sandbox.testExits.add(0);
        CodingTaskService svc = service();
        TaskView started = svc.start(archiveInput(), "user1");
        waitForStatus(svc, started.taskId(), "AWAITING_APPROVAL");
        assertThat(sandbox.seededArchive).isNotNull();
        assertThat(sandbox.seededGit).isNull();
        svc.reject(started.taskId(), "user1");
        waitForStatus(svc, started.taskId(), "REJECTED");
    }

    @Test
    void rejectingRollsTheWorkspaceBackAndClosesTheSandbox() {
        sandbox.testExits.add(0);
        CodingTaskService svc = service();
        TaskView started = svc.start(gitInput(), "user1");
        waitForStatus(svc, started.taskId(), "AWAITING_APPROVAL");
        svc.reject(started.taskId(), "user1");
        TaskView rejected = waitForStatus(svc, started.taskId(), "REJECTED");
        assertThat(rejected.outcome()).isEqualTo("DENIED_BY_USER");
        assertThat(sandbox.rolledBack()).isTrue();
        await(sandbox.closed::get);
        assertThat(store.diffs).isEmpty();
    }

    // ---------------------------------------------------------------------------------------- approval bypass attempts

    @Test
    void approvingATaskThatIsNotWaitingIs409AndRecordsNothing() {
        CodingTaskService svc = service();
        sandbox.testExits.add(1);
        sandbox.testExits.add(1);
        sandbox.testExits.add(1);
        sandbox.testExits.add(1);
        TaskView started = svc.start(gitInput(), "user1");
        // Whatever state it is in before the gate opens, an early approve cannot be recorded.
        for (int i = 0; i < 3; i++) {
            String status = svc.get(started.taskId(), "user1").status();
            if (!status.equals("AWAITING_APPROVAL")) {
                assertThatThrownBy(() -> svc.approve(started.taskId(), "user1", "anything"))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
            }
        }
        assertThat(store.decisions).isEmpty();
        TaskView ended = waitForTerminal(svc, started.taskId());
        assertThat(ended.status()).isEqualTo("FAILED");
        assertThat(ended.outcome()).isEqualTo("TESTS_STILL_FAILING");
        assertThat(sandbox.rolledBack()).isTrue();
        assertThatThrownBy(() -> svc.approve(started.taskId(), "user1", "late"))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(store.decisions).isEmpty();
    }

    @Test
    void anApprovalForADifferentDiffIsRefusedAndTheGateStaysClosed() {
        sandbox.testExits.add(0);
        CodingTaskService svc = service();
        TaskView started = svc.start(gitInput(), "user1");
        TaskView waiting = waitForStatus(svc, started.taskId(), "AWAITING_APPROVAL");

        for (String bad : new String[] {null, "", "deadbeef", waiting.diffHash() + "0", waiting.diffHash().toUpperCase()}) {
            assertThatThrownBy(() -> svc.approve(started.taskId(), "user1", bad))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                    e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        }
        assertThat(store.decisions).isEmpty();
        assertThat(svc.get(started.taskId(), "user1").status()).isEqualTo("AWAITING_APPROVAL");
        svc.reject(started.taskId(), "user1");
        waitForStatus(svc, started.taskId(), "REJECTED");
    }

    @Test
    void aDecisionPlantedBeforeTheGateOpensIsDiscardedNotHonoured() {
        sandbox.testExits.add(0);
        List<Runnable> parked = new ArrayList<>();
        CodingTaskExecutor parking = new CodingTaskExecutor() {
            @Override
            public boolean trySubmit(Runnable body) {
                parked.add(body);
                return true;
            }
        };
        CodingTaskService svc = service(settings(true, false, 900), parking);
        TaskView started = svc.start(gitInput(), "user1");
        // Someone writes an approval for the task before the harness has even asked (a stale or forged key).
        store.setDecision("tenantA", started.taskId(), "APPROVE:" + CodingTaskService.sha256(DIFF));
        Thread worker = new Thread(parked.get(0));
        worker.setDaemon(true);
        worker.start();
        TaskView waiting = waitForStatus(svc, started.taskId(), "AWAITING_APPROVAL");
        sleep(200);
        // The gate cleared it when it opened: the task is still waiting for the real human decision.
        assertThat(svc.get(started.taskId(), "user1").status()).isEqualTo("AWAITING_APPROVAL");
        // A decision for some OTHER diff is ignored as well.
        store.setDecision("tenantA", waiting.taskId(), "APPROVE:" + "0".repeat(64));
        sleep(200);
        assertThat(svc.get(started.taskId(), "user1").status()).isEqualTo("AWAITING_APPROVAL");
        svc.reject(started.taskId(), "user1");
        waitForStatus(svc, started.taskId(), "REJECTED");
        parking.shutdown();
    }

    @Test
    void noDecisionInTimeFailsClosedAndNothingIsKept() {
        sandbox.testExits.add(0);
        CodingTaskService svc = service(settings(true, false, 30), executor);
        TaskView started = svc.start(gitInput(), "user1");
        waitForStatus(svc, started.taskId(), "AWAITING_APPROVAL");
        now.addAndGet(31 * 1000L);
        TaskView ended = waitForTerminal(svc, started.taskId());
        assertThat(ended.status()).isEqualTo("FAILED");
        assertThat(ended.outcome()).isEqualTo("APPROVAL_TIMEOUT");
        assertThat(ended.diffAvailable()).isFalse();
        assertThat(ended.diffHash()).isNull();
        assertThat(sandbox.rolledBack()).isTrue();
        assertThat(store.diffs).isEmpty();
        await(sandbox.closed::get);
    }

    @Test
    void cancellingWhileWaitingForApprovalCancelsInsteadOfApproving() {
        sandbox.testExits.add(0);
        CodingTaskService svc = service();
        TaskView started = svc.start(gitInput(), "user1");
        TaskView waiting = waitForStatus(svc, started.taskId(), "AWAITING_APPROVAL");
        svc.cancel(started.taskId(), "user1");
        TaskView ended = waitForTerminal(svc, started.taskId());
        assertThat(ended.status()).isEqualTo("CANCELLED");
        assertThat(sandbox.rolledBack()).isTrue();
        assertThat(store.decisions).isEmpty();
        assertThat(waiting.status()).isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    void aReviewerRejectionNeverReachesTheHuman() {
        sandbox.testExits.add(0);
        model.reviewReply = "{\"approved\": false, \"notes\": \"deletes a test\"}";
        CodingTaskService svc = service();
        TaskView started = svc.start(gitInput(), "user1");
        TaskView ended = waitForTerminal(svc, started.taskId());
        assertThat(ended.status()).isEqualTo("FAILED");
        assertThat(ended.outcome()).isEqualTo("REJECTED_BY_REVIEW");
        assertThat(ended.message()).contains("deletes a test");
        assertThat(ended.diffAvailable()).isFalse();
        assertThat(sandbox.rolledBack()).isTrue();
    }

    @Test
    void anUnparseableReviewIsARejection() {
        sandbox.testExits.add(0);
        model.reviewReply = "looks good to me!";
        CodingTaskService svc = service();
        TaskView started = svc.start(gitInput(), "user1");
        TaskView ended = waitForTerminal(svc, started.taskId());
        assertThat(ended.outcome()).isEqualTo("REJECTED_BY_REVIEW");
    }

    @Test
    void anOversizedDiffCannotBeReviewedSoItIsNeverOffered() {
        sandbox.testExits.add(0);
        sandbox.diff = "diff --git a/x b/x\n" + "+x\n".repeat(CodingTaskService.MAX_DIFF_CHARS);
        CodingTaskService svc = service();
        TaskView started = svc.start(gitInput(), "user1");
        TaskView ended = waitForTerminal(svc, started.taskId());
        assertThat(ended.status()).isEqualTo("FAILED");
        assertThat(ended.message()).contains("too large");
        assertThat(store.diffs).isEmpty();
        assertThat(sandbox.rolledBack()).isTrue();
    }
}
