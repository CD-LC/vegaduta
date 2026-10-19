package com.agenticai.core.sdlc.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodingHarnessLoopTest {

    private static final String TEST_CMD = "mvn -q test";

    /** Fake sandbox: scripted test exit codes, git commits answered with fake shas. */
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

    private static CodingHarnessLoop loop(FakeShell shell, int maxFix, HarnessBudget budget, boolean approve) {
        return new CodingHarnessLoop(new GitWorkspace(shell), shell,
            new HarnessLimits(maxFix, 60, 1000), budget, (action, summary) -> approve);
    }

    @Test
    void passesOnFirstRunWithoutFixing() {
        FakeShell shell = new FakeShell();
        shell.testExits.add(0);
        int[] fixes = {0};
        var r = loop(shell, 3, null, true).run(TEST_CMD, (i, f) -> {
            fixes[0]++;
            return "";
        }, d -> new CodingHarnessLoop.Verdict(true, "fine"));
        assertEquals(CodingHarnessLoop.Status.PASSED, r.status());
        assertEquals(0, fixes[0]);
        assertFalse(shell.rolledBack());
    }

    @Test
    void fixesThenPasses() {
        FakeShell shell = new FakeShell();
        shell.testExits.add(1);
        shell.testExits.add(1);
        shell.testExits.add(0);
        var r = loop(shell, 3, null, true).run(TEST_CMD, (i, f) -> "patched",
            d -> new CodingHarnessLoop.Verdict(true, "ok"));
        assertEquals(CodingHarnessLoop.Status.PASSED, r.status());
        assertEquals(2, r.fixIterations());
    }

    @Test
    void stopsAtIterationLimitAndRollsBack() {
        FakeShell shell = new FakeShell();
        int[] fixes = {0};
        var r = loop(shell, 2, null, true).run(TEST_CMD, (i, f) -> {
            fixes[0]++;
            return "";
        }, d -> new CodingHarnessLoop.Verdict(true, ""));
        assertEquals(CodingHarnessLoop.Status.TESTS_STILL_FAILING, r.status());
        assertEquals(2, fixes[0]);
        assertTrue(shell.rolledBack());
        assertEquals(r.baselineSha(), r.finalSha());
    }

    @Test
    void stopsWhenBudgetExhausted() {
        FakeShell shell = new FakeShell();
        HarnessBudget spent = new HarnessBudget() {
            @Override public boolean exhausted() { return true; }
            @Override public void record(String i, String o) { }
            @Override public String stopMessage() { return "Run budget reached"; }
        };
        int[] fixes = {0};
        var r = loop(shell, 5, spent, true).run(TEST_CMD, (i, f) -> {
            fixes[0]++;
            return "";
        }, d -> new CodingHarnessLoop.Verdict(true, ""));
        assertEquals(CodingHarnessLoop.Status.BUDGET_EXCEEDED, r.status());
        assertEquals(0, fixes[0]);
        assertTrue(shell.rolledBack());
    }

    @Test
    void reviewRejectionRollsBack() {
        FakeShell shell = new FakeShell();
        shell.testExits.add(0);
        var r = loop(shell, 3, null, true).run(TEST_CMD, (i, f) -> "",
            d -> new CodingHarnessLoop.Verdict(false, "deleted tests"));
        assertEquals(CodingHarnessLoop.Status.REJECTED_BY_REVIEW, r.status());
        assertTrue(shell.rolledBack());
    }

    @Test
    void userDenialRollsBack() {
        FakeShell shell = new FakeShell();
        shell.testExits.add(0);
        var r = loop(shell, 3, null, false).run(TEST_CMD, (i, f) -> "",
            d -> new CodingHarnessLoop.Verdict(true, "ok"));
        assertEquals(CodingHarnessLoop.Status.DENIED_BY_USER, r.status());
        assertTrue(shell.rolledBack());
    }

    @Test
    void fixerExceptionRollsBackAndReportsError() {
        FakeShell shell = new FakeShell();
        var r = loop(shell, 3, null, true).run(TEST_CMD, (i, f) -> {
            throw new IllegalStateException("model down");
        }, d -> new CodingHarnessLoop.Verdict(true, ""));
        assertEquals(CodingHarnessLoop.Status.ERROR, r.status());
        assertTrue(shell.rolledBack());
    }

    @Test
    void missingCollaboratorsIsAnErrorWithoutTouchingTheWorkspace() {
        FakeShell shell = new FakeShell();
        var r = loop(shell, 3, null, true).run(" ", (i, f) -> "", d -> new CodingHarnessLoop.Verdict(true, ""));
        assertEquals(CodingHarnessLoop.Status.ERROR, r.status());
        assertTrue(shell.commands.isEmpty());
    }

    @Test
    void limitsAreClamped() {
        HarnessLimits l = new HarnessLimits(999, 0, 1);
        assertEquals(HarnessLimits.MAX_ITERATIONS_CEILING, l.maxFixIterations());
        assertEquals(5, l.testTimeoutSeconds());
        assertEquals(500, l.maxOutputChars());
    }

    @Test
    void labelsAreSanitizedAndShasValidated() {
        assertEquals("a b", GitWorkspace.sanitizeLabel("a'; rm -rf / #b".replace("rm -rf / #", "")));
        assertEquals("checkpoint", GitWorkspace.sanitizeLabel("';$()"));
        FakeShell shell = new FakeShell();
        GitWorkspace ws = new GitWorkspace(shell);
        assertThrows(IllegalArgumentException.class, () -> ws.rollbackTo("HEAD; rm -rf /"));
        assertThrows(IllegalArgumentException.class, () -> ws.diffSince(null));
    }
}
