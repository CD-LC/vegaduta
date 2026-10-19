package com.agenticai.core.agents.coding;

import static com.agenticai.core.agents.coding.CodingTaskDtos.APPROVED;
import static com.agenticai.core.agents.coding.CodingTaskDtos.CANCELLED;
import static com.agenticai.core.agents.coding.CodingTaskDtos.FAILED;
import static com.agenticai.core.agents.coding.CodingTaskDtos.REJECTED;
import static com.agenticai.core.agents.coding.CodingTaskDtos.RUNNING;
import static com.agenticai.core.agents.coding.CodingTaskDtos.SOURCE_GIT;
import static com.agenticai.core.agents.coding.CodingTaskService.MAX_DIFF_CHARS;
import static com.agenticai.core.agents.coding.CodingTaskService.MSG_INTERNAL;
import static com.agenticai.core.agents.coding.CodingTaskService.MSG_TENANT_STOPPED;
import static com.agenticai.core.agents.coding.CodingTaskService.MSG_TOO_LARGE;

import com.agenticai.core.agents.coding.CodingTaskDtos.TaskDocument;
import com.agenticai.core.agents.coding.CodingTaskService.Plan;
import com.agenticai.core.sdlc.E2bNotConfiguredException;
import com.agenticai.core.sdlc.harness.CodingHarnessLoop;
import com.agenticai.core.sdlc.harness.CodingSandbox;
import com.agenticai.core.sdlc.harness.CodingSandboxUnavailableException;
import com.agenticai.core.sdlc.harness.GitWorkspace;
import com.agenticai.core.sdlc.harness.HarnessLimits;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantStatusGuard;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The worker half of {@link CodingTaskService}: runs one coding task on a {@link CodingTaskExecutor}
 * thread - sandbox, harness loop, approval gate, final status - and records every step in the
 * {@link CodingTaskStore}. Split out of the service to keep both files under the file-size cap;
 * what is enforced where is described on the service.
 */
final class CodingTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(CodingTaskService.class);

    /** Keeps an approved task's sandbox alive for the rollback window (owned by the service). */
    @FunctionalInterface
    interface SessionKeeper {
        void keep(String taskId, String tenantId, CodingSandbox sandbox, GitWorkspace workspace, String baseline);
    }

    private final CodingTaskSettings settings;
    private final CodingTaskStore store;
    private final TenantStatusGuard tenantStatusGuard;
    private final CodingTaskService.SandboxOpener sandboxes;
    private final CodingTaskService.ModelFactory models;
    private final LongSupplier clock;
    private final long pollMillis;
    private final Consumer<String> onEnded;
    private final SessionKeeper sessionKeeper;

    CodingTaskWorker(
        CodingTaskSettings settings, CodingTaskStore store, TenantStatusGuard tenantStatusGuard,
        CodingTaskService.SandboxOpener sandboxes, CodingTaskService.ModelFactory models, LongSupplier clock,
        long pollMillis, Consumer<String> onEnded, SessionKeeper sessionKeeper
    ) {
        this.settings = settings;
        this.store = store;
        this.tenantStatusGuard = tenantStatusGuard;
        this.sandboxes = sandboxes;
        this.models = models;
        this.clock = clock;
        this.pollMillis = pollMillis;
        this.onEnded = onEnded;
        this.sessionKeeper = sessionKeeper;
    }

    private void persist(CodingTaskState st) {
        store.save(st.plan.tenantId(), new TaskDocument(st.snapshot(clock.getAsLong()), st.plan.subject()));
    }

    private boolean cancelRequested(Plan plan) {
        try {
            return store.isCancelRequested(plan.tenantId(), plan.taskId());
        } catch (RuntimeException e) {
            // If the store is unreachable the approval gate cannot be answered either, so stopping is the safe reading.
            return true;
        }
    }

    void run(Plan plan, TenantContext tenant) {
        TenantContext.set(tenant);
        CodingTaskState st = new CodingTaskState(plan, sandboxes.runnerName());
        CodingSandbox sandbox = null;
        boolean keepSandbox = false;
        try {
            if (cancelRequested(plan)) {
                end(st, CANCELLED, "CANCELLED", "Cancelled before it started.");
                return;
            }
            if (!tenantStatusGuard.isUsable(plan.tenantId())) {
                end(st, FAILED, "ERROR", MSG_TENANT_STOPPED);
                return;
            }
            st.budget = new CodingTaskBudget(plan.budget(), clock);
            st.status = RUNNING;
            st.phase = "SEEDING";
            st.event(clock.getAsLong(), "start", "Opening an isolated " + sandboxes.runnerName() + " sandbox.");
            persist(st);

            int ttl = plan.budget().maxWallSeconds() + settings.approvalTimeoutSeconds()
                + settings.rollbackWindowSeconds() + 120;
            sandbox = sandboxes.open(ttl);
            st.event(clock.getAsLong(), "sandbox", "Sandbox ready (" + sandbox.describe() + ").");
            if (SOURCE_GIT.equals(plan.sourceKind())) {
                sandbox.seedFromGit(plan.repoUrl());
            } else {
                sandbox.seedFromArchive(plan.zip());
            }
            sandbox.prepareRepository();
            st.event(clock.getAsLong(), "seed", "Repository loaded.");
            st.phase = "EDITING";
            persist(st);

            GitWorkspace workspace = new GitWorkspace(sandbox.internal());
            CodingModel model = models.create(plan.agentId(), plan.subject(), plan.taskId());
            LlmCodingFixer fixer = new LlmCodingFixer(model, st.budget, sandbox.internal(), plan.instruction(), plan.testCommand());
            AtomicReference<String> baseline = new AtomicReference<>();
            LlmCodingReviewer reviewer = new LlmCodingReviewer(model, st.budget, plan.instruction(),
                () -> baseline.get() == null ? null : workspace.diffSince(baseline.get()));
            StoreApprovalGate gate = new StoreApprovalGate(plan, st, workspace, baseline, store, settings, clock, pollMillis,
                this::persist, () -> cancelRequested(plan));
            HarnessLimits limits = new HarnessLimits(
                plan.budget().maxFixIterations(), settings.testTimeoutSeconds(), settings.maxOutputChars());

            CodingHarnessLoop loop = new CodingHarnessLoop(workspace, sandbox.restricted(), limits, st.budget, gate)
                .withCancellation(() -> cancelRequested(plan))
                .withListener((type, detail) -> onProgress(st, baseline, type, detail));

            CodingHarnessLoop.Result result = loop.run(plan.testCommand(), fixer, fixer, reviewer);
            keepSandbox = finish(st, result, gate, sandbox, workspace);
        } catch (CodingSandboxUnavailableException | E2bNotConfiguredException e) {
            endQuietly(st, FAILED, "ERROR", sentence(e.getMessage(), "Coding tasks are not set up for this workspace."));
        } catch (RuntimeException e) {
            log.error("Coding task {} stopped on an unexpected failure", plan.taskId(), e);
            endQuietly(st, FAILED, "ERROR", MSG_INTERNAL);
        } finally {
            if (!keepSandbox && sandbox != null) {
                sandbox.close();
            }
            releaseLockQuietly(plan.tenantId(), plan.subject(), plan.taskId());
            onEnded.accept(plan.taskId());
            TenantContext.clear();
        }
    }

    private void onProgress(CodingTaskState st, AtomicReference<String> baseline, String type, String detail) {
        st.applyProgress(clock.getAsLong(), baseline, type, detail);
        try {
            persist(st);
        } catch (RuntimeException e) {
            log.warn("Could not persist progress of coding task {}", st.plan.taskId(), e);
        }
    }

    /** @return true when the sandbox must stay alive (an approved task inside its rollback window) */
    private boolean finish(CodingTaskState st, CodingHarnessLoop.Result result, StoreApprovalGate gate, CodingSandbox sandbox,
                           GitWorkspace workspace) {
        CodingHarnessLoop.Status s = result.status();
        boolean budgetStop = s == CodingHarnessLoop.Status.ERROR && st.budget.exhausted();
        String outcome = budgetStop ? "BUDGET_EXCEEDED" : s.name();
        if (s != CodingHarnessLoop.Status.PASSED) {
            // Everything but an approval rolled the workspace back and drops the diff: the view must not offer it.
            st.diffAvailable = false;
            st.diffHash = null;
            st.diffFiles = 0;
        }
        switch (s) {
            case PASSED -> {
                st.status = APPROVED;
                st.outcome = outcome;
                st.phase = APPROVED;
                st.message = "Approved. Download the patch; you can roll back for "
                    + (settings.rollbackWindowSeconds() / 60) + " more minutes.";
                st.event(clock.getAsLong(), "approved", "You approved the change.");
                boolean keep = settings.rollbackWindowSeconds() > 0 && result.baselineSha() != null;
                if (keep) {
                    // Registered BEFORE the status flips to APPROVED: rollback() requires that status, so it can
                    // never look for a session that is not there yet.
                    sessionKeeper.keep(st.plan.taskId(), st.plan.tenantId(), sandbox, workspace, result.baselineSha());
                }
                persist(st);
                return keep;
            }
            case DENIED_BY_USER -> {
                if (gate.tooLarge()) {
                    endQuietly(st, FAILED, "ERROR", MSG_TOO_LARGE.formatted(MAX_DIFF_CHARS));
                } else if (gate.timedOut()) {
                    endQuietly(st, FAILED, "APPROVAL_TIMEOUT",
                        "Nobody approved the change in time, so it was discarded.");
                } else {
                    endQuietly(st, REJECTED, outcome, "You rejected the change. The workspace was rolled back.");
                }
            }
            case CANCELLED -> endQuietly(st, CANCELLED, outcome, "Cancelled. The workspace was rolled back.");
            case TESTS_STILL_FAILING -> endQuietly(st, FAILED, outcome,
                "The tests still fail after " + result.fixIterations() + " fix attempt(s). Nothing was changed.");
            case REJECTED_BY_REVIEW -> endQuietly(st, FAILED, outcome, "The automated review rejected the change"
                + (st.review == null ? "." : ": " + st.review.notes()));
            case BUDGET_EXCEEDED -> endQuietly(st, FAILED, outcome, "Stopped: " + st.budget.stopMessage() + ".");
            case ERROR -> endQuietly(st, FAILED, outcome, budgetStop ? "Stopped: " + st.budget.stopMessage() + "."
                : "The task failed: " + lastError(result.log()));
            default -> endQuietly(st, FAILED, outcome, MSG_INTERNAL);
        }
        try {
            store.deleteDiff(st.plan.tenantId(), st.plan.taskId());
        } catch (RuntimeException e) {
            log.debug("Could not delete the diff of coding task {}", st.plan.taskId(), e);
        }
        return false;
    }

    private void end(CodingTaskState st, String status, String outcome, String message) {
        st.status = status;
        st.outcome = outcome;
        st.phase = status;
        st.message = message;
        st.event(clock.getAsLong(), status.toLowerCase(java.util.Locale.ROOT), message);
        persist(st);
    }

    private void endQuietly(CodingTaskState st, String status, String outcome, String message) {
        try {
            end(st, status, outcome, message);
        } catch (RuntimeException e) {
            log.warn("Could not record the end of coding task {} ({})", st.plan.taskId(), status, e);
        }
    }

    private static String lastError(List<String> log) {
        for (int i = log.size() - 1; i >= 0; i--) {
            String line = log.get(i);
            if (line.startsWith("error: ")) {
                return sentence(line.substring(7), "an internal error occurred");
            }
        }
        return "an internal error occurred";
    }

    private static String sentence(String message, String fallback) {
        if (message == null || message.isBlank()) {
            return fallback;
        }
        String m = message.strip();
        return m.length() <= 300 ? m : m.substring(0, 300) + "...";
    }

    void releaseLockQuietly(String tenantId, String subject, String taskId) {
        try {
            store.releaseLock(tenantId, subject, taskId);
        } catch (RuntimeException e) {
            log.warn("Could not release the coding-task lock for {} - it expires on its own", taskId, e);
        }
    }
}
