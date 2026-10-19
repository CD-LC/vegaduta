package com.agenticai.core.sdlc.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Bounded test-fix loop with checkpoint/rollback, a review step and a human gate.
 *
 * <p>Flow: baseline checkpoint, run tests; while they fail and iterations and
 * budget remain, ask the {@link Fixer} for a change and checkpoint it. Once tests
 * pass, a {@link Reviewer} inspects the diff against the baseline, then the
 * {@link HarnessApprovalGate} is asked. Any non-success outcome rolls the
 * workspace back to the baseline, so a stopped run never leaves a half-edited tree.
 *
 * <p>Pure orchestration: no Spring, no process spawning. It is only reachable via
 * code that checks {@code app.coding-harness.enabled} (default false).
 *
 * <p>Stage 1 additions (all optional, default to the stage 0 behaviour): a progress
 * listener, a cooperative cancel check, and an initial edit step before the first
 * test run (see {@link #run(String, Fixer, Fixer, Reviewer)}).
 */
public class CodingHarnessLoop {

    public enum Status { PASSED, TESTS_STILL_FAILING, BUDGET_EXCEEDED, REJECTED_BY_REVIEW, DENIED_BY_USER, ERROR, CANCELLED }

    /** The agent step: edit the workspace in response to a failing test run. */
    @FunctionalInterface
    public interface Fixer {
        String attemptFix(int iteration, String failureOutput);
    }

    @FunctionalInterface
    public interface Reviewer {
        Verdict review(String diff);
    }

    /**
     * Optional progress hook. Events are {@code baseline}, {@code edit}, {@code tests}, {@code review},
     * {@code approval} and {@code rollback}; {@code detail} is short human-readable text. A throwing
     * listener is ignored: progress reporting must never break a run.
     */
    @FunctionalInterface
    public interface ProgressListener {
        void onEvent(String type, String detail);
    }

    public record Verdict(boolean approved, String notes) { }

    public record Result(Status status, int fixIterations, String baselineSha, String finalSha, List<String> log) { }

    private final GitWorkspace workspace;
    private final ShellRunner shell;
    private final HarnessLimits limits;
    private final HarnessBudget budget;
    private final HarnessApprovalGate gate;
    private ProgressListener listener = (type, detail) -> { };
    private BooleanSupplier cancelled = () -> false;

    public CodingHarnessLoop(GitWorkspace workspace, ShellRunner shell, HarnessLimits limits,
                             HarnessBudget budget, HarnessApprovalGate gate) {
        this.workspace = workspace;
        this.shell = shell;
        this.limits = limits == null ? HarnessLimits.defaults() : limits;
        this.budget = budget == null ? HarnessBudget.UNLIMITED : budget;
        this.gate = gate;
    }

    /** Attaches a progress listener (default: none). Returns {@code this}. */
    public CodingHarnessLoop withListener(ProgressListener progress) {
        this.listener = progress == null ? (type, detail) -> { } : progress;
        return this;
    }

    /** Attaches a cooperative cancel check, consulted before every step (default: never). Returns {@code this}. */
    public CodingHarnessLoop withCancellation(BooleanSupplier cancel) {
        this.cancelled = cancel == null ? () -> false : cancel;
        return this;
    }

    public Result run(String testCommand, Fixer fixer, Reviewer reviewer) {
        return run(testCommand, null, fixer, reviewer);
    }

    /**
     * As {@link #run(String, Fixer, Reviewer)}, plus an optional {@code initialEdit} applied (and
     * checkpointed) once after the baseline and before the first test run. An instruction-driven task
     * ("add feature X") needs it: without it a green baseline would finish with no change at all.
     * {@code null} gives exactly the original behaviour.
     */
    public Result run(String testCommand, Fixer initialEdit, Fixer fixer, Reviewer reviewer) {
        List<String> log = new ArrayList<>();
        if (testCommand == null || testCommand.isBlank() || fixer == null || reviewer == null || gate == null) {
            return new Result(Status.ERROR, 0, null, null,
                List.of("testCommand, fixer, reviewer and gate are required"));
        }
        String baseline = null;
        int iteration = 0;
        try {
            workspace.ensureRepository();
            baseline = workspace.checkpoint("harness baseline");
            emit("baseline", baseline);
            if (initialEdit != null) {
                if (cancelled.getAsBoolean()) {
                    log.add("cancelled");
                    return rolledBack(Status.CANCELLED, baseline, iteration, log);
                }
                String note = initialEdit.attemptFix(0, "");
                budget.record("", note);
                String sha = workspace.checkpoint("harness initial edit");
                emit("edit", "initial edit " + sha + noteSuffix(note));
            }
            while (true) {
                if (cancelled.getAsBoolean()) {
                    log.add("cancelled");
                    return rolledBack(Status.CANCELLED, baseline, iteration, log);
                }
                ShellRunner.ShellResult tests = shell.run(testCommand, limits.testTimeoutSeconds());
                log.add("tests run " + iteration + ": exit " + tests.exitCode());
                emit("tests", "run " + iteration + " exit " + tests.exitCode() + "\n" + truncate(tests.output()));
                if (tests.ok()) {
                    return finish(baseline, iteration, reviewer, log);
                }
                if (budget.exhausted()) {
                    log.add(budget.stopMessage());
                    return rolledBack(Status.BUDGET_EXCEEDED, baseline, iteration, log);
                }
                if (iteration >= limits.maxFixIterations()) {
                    log.add("fix iteration limit reached (" + limits.maxFixIterations() + ")");
                    return rolledBack(Status.TESTS_STILL_FAILING, baseline, iteration, log);
                }
                iteration++;
                String failure = truncate(tests.output());
                String note = fixer.attemptFix(iteration, failure);
                budget.record(failure, note);
                String fixSha = workspace.checkpoint("harness fix " + iteration);
                emit("edit", "fix " + iteration + " " + fixSha + noteSuffix(note));
            }
        } catch (RuntimeException e) {
            log.add("error: " + e.getMessage());
            if (baseline != null) {
                try {
                    workspace.rollbackTo(baseline);
                } catch (RuntimeException rollbackFailure) {
                    log.add("rollback failed: " + rollbackFailure.getMessage());
                }
            }
            return new Result(Status.ERROR, iteration, baseline, baseline, log);
        }
    }

    private Result finish(String baseline, int iteration, Reviewer reviewer, List<String> log) {
        String finalSha = workspace.checkpoint("harness final");
        String diff = truncate(workspace.diffSince(baseline));
        if (cancelled.getAsBoolean()) {
            log.add("cancelled before review");
            return rolledBack(Status.CANCELLED, baseline, iteration, log);
        }
        Verdict verdict = reviewer.review(diff);
        budget.record(diff, verdict == null ? "" : verdict.notes());
        emit("review", verdict == null ? "no verdict"
            : (verdict.approved() ? "approved: " : "rejected: ") + verdict.notes());
        if (verdict == null || !verdict.approved()) {
            log.add("review rejected: " + (verdict == null ? "no verdict" : verdict.notes()));
            return rolledBack(Status.REJECTED_BY_REVIEW, baseline, iteration, log);
        }
        emit("approval", "waiting for a human decision");
        boolean ok = gate.approve("apply_code_changes",
            "Tests pass after " + iteration + " fix iteration(s). Review notes: " + verdict.notes());
        if (!ok) {
            if (cancelled.getAsBoolean()) {
                log.add("cancelled while waiting for approval");
                return rolledBack(Status.CANCELLED, baseline, iteration, log);
            }
            log.add("user declined the changes");
            return rolledBack(Status.DENIED_BY_USER, baseline, iteration, log);
        }
        return new Result(Status.PASSED, iteration, baseline, finalSha, log);
    }

    private Result rolledBack(Status status, String baseline, int iteration, List<String> log) {
        workspace.rollbackTo(baseline);
        log.add("workspace rolled back to " + baseline);
        emit("rollback", "workspace rolled back to " + baseline);
        return new Result(status, iteration, baseline, baseline, log);
    }

    private void emit(String type, String detail) {
        try {
            listener.onEvent(type, detail);
        } catch (RuntimeException ignored) {
            // progress reporting must never break the run
        }
    }

    private static String noteSuffix(String note) {
        return note == null || note.isBlank() ? "" : ": " + note;
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= limits.maxOutputChars() ? s : s.substring(s.length() - limits.maxOutputChars());
    }
}
