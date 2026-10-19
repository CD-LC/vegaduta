package com.agenticai.core.sdlc.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** The stage 1 additions to the loop: initial edit, progress listener, cooperative cancel. */
class CodingHarnessLoopStage1Test {

    private static final String TEST_CMD = "mvn -q test";

    static final class FakeShell implements ShellRunner {
        final List<String> commands = new ArrayList<>();
        final Deque<Integer> testExits = new ArrayDeque<>();
        int commits;

        @Override
        public ShellResult run(String command, int timeoutSeconds) {
            commands.add(command);
            if (command.equals(TEST_CMD)) {
                int exit = testExits.isEmpty() ? 1 : testExits.poll();
                return new ShellResult(exit, exit == 0 ? "ok" : "FAILED test");
            }
            if (command.contains(" commit ")) {
                commits++;
            }
            if (command.equals("git rev-parse HEAD")) {
                return new ShellResult(0, String.format("%07x\n", 0xabc000 + commits));
            }
            return new ShellResult(0, command.startsWith("git diff") ? "diff --git a b" : "");
        }

        boolean rolledBack() {
            return commands.stream().anyMatch(c -> c.startsWith("git reset -q --hard "));
        }
    }

    private static CodingHarnessLoop loop(FakeShell shell, boolean approve) {
        return new CodingHarnessLoop(new GitWorkspace(shell), shell, new HarnessLimits(3, 60, 1000), null,
            (action, summary) -> approve);
    }

    @Test
    void initialEditRunsOnceBeforeTheFirstTestRunEvenWhenTheBaselineIsGreen() {
        FakeShell shell = new FakeShell();
        shell.testExits.add(0);
        List<Integer> iterations = new ArrayList<>();
        var r = loop(shell, true).run(TEST_CMD, (i, f) -> {
            iterations.add(i);
            return "added the feature";
        }, (i, f) -> "should not be needed", d -> new CodingHarnessLoop.Verdict(true, "fine"));
        assertEquals(CodingHarnessLoop.Status.PASSED, r.status());
        assertEquals(List.of(0), iterations);
        int editCommit = indexOf(shell, "harness initial edit");
        int firstTest = shell.commands.indexOf(TEST_CMD);
        assertTrue(editCommit >= 0 && editCommit < firstTest, shell.commands.toString());
    }

    @Test
    void withoutAnInitialEditBehaviourIsTheOriginalOne() {
        FakeShell shell = new FakeShell();
        shell.testExits.add(0);
        var r = loop(shell, true).run(TEST_CMD, (i, f) -> "x", d -> new CodingHarnessLoop.Verdict(true, "ok"));
        assertEquals(CodingHarnessLoop.Status.PASSED, r.status());
        assertEquals(-1, indexOf(shell, "harness initial edit"));
    }

    @Test
    void listenerSeesBaselineEditsTestsReviewApprovalInOrder() {
        FakeShell shell = new FakeShell();
        shell.testExits.add(1);
        shell.testExits.add(0);
        List<String> types = new ArrayList<>();
        var r = loop(shell, true).withListener((type, detail) -> types.add(type))
            .run(TEST_CMD, (i, f) -> "n", d -> new CodingHarnessLoop.Verdict(true, "ok"));
        assertEquals(CodingHarnessLoop.Status.PASSED, r.status());
        assertEquals(List.of("baseline", "tests", "edit", "tests", "review", "approval"), types);
    }

    @Test
    void aThrowingListenerNeverBreaksTheRun() {
        FakeShell shell = new FakeShell();
        shell.testExits.add(0);
        var r = loop(shell, true).withListener((t, d) -> {
            throw new IllegalStateException("listener bug");
        }).run(TEST_CMD, (i, f) -> "n", d -> new CodingHarnessLoop.Verdict(true, "ok"));
        assertEquals(CodingHarnessLoop.Status.PASSED, r.status());
    }

    @Test
    void cancelBeforeTheNextStepRollsBackAndReportsCancelled() {
        FakeShell shell = new FakeShell();
        shell.testExits.add(1);
        shell.testExits.add(1);
        AtomicBoolean cancel = new AtomicBoolean();
        int[] fixes = {0};
        var r = loop(shell, true).withCancellation(cancel::get).run(TEST_CMD, (i, f) -> {
            fixes[0]++;
            cancel.set(true); // the user cancels while the fix is being made
            return "n";
        }, d -> new CodingHarnessLoop.Verdict(true, "ok"));
        assertEquals(CodingHarnessLoop.Status.CANCELLED, r.status());
        assertEquals(1, fixes[0]);
        assertTrue(shell.rolledBack());
    }

    @Test
    void cancelWhileWaitingForApprovalIsCancelledNotDenied() {
        FakeShell shell = new FakeShell();
        shell.testExits.add(0);
        AtomicBoolean cancel = new AtomicBoolean();
        var loop = new CodingHarnessLoop(new GitWorkspace(shell), shell, new HarnessLimits(3, 60, 1000), null,
            (action, summary) -> {
                cancel.set(true);
                return false;
            }).withCancellation(cancel::get);
        var r = loop.run(TEST_CMD, (i, f) -> "n", d -> new CodingHarnessLoop.Verdict(true, "ok"));
        assertEquals(CodingHarnessLoop.Status.CANCELLED, r.status());
        assertTrue(shell.rolledBack());
    }

    @Test
    void aDeclinedApprovalStillRollsBack() {
        FakeShell shell = new FakeShell();
        shell.testExits.add(0);
        var r = loop(shell, false).run(TEST_CMD, (i, f) -> "n", d -> new CodingHarnessLoop.Verdict(true, "ok"));
        assertEquals(CodingHarnessLoop.Status.DENIED_BY_USER, r.status());
        assertTrue(shell.rolledBack());
    }

    @Test
    void aGateThatThrowsFailsClosedAndRollsBack() {
        FakeShell shell = new FakeShell();
        shell.testExits.add(0);
        var loop = new CodingHarnessLoop(new GitWorkspace(shell), shell, new HarnessLimits(3, 60, 1000), null,
            (action, summary) -> {
                throw new IllegalStateException("store down");
            });
        var r = loop.run(TEST_CMD, (i, f) -> "n", d -> new CodingHarnessLoop.Verdict(true, "ok"));
        assertEquals(CodingHarnessLoop.Status.ERROR, r.status());
        assertTrue(shell.rolledBack());
        assertFalse(r.log().isEmpty());
    }

    private static int indexOf(FakeShell shell, String fragment) {
        for (int i = 0; i < shell.commands.size(); i++) {
            if (shell.commands.get(i).contains(fragment)) {
                return i;
            }
        }
        return -1;
    }
}
