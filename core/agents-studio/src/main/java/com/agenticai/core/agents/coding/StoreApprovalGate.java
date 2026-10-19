package com.agenticai.core.agents.coding;

import static com.agenticai.core.agents.coding.CodingTaskDtos.AWAITING_APPROVAL;

import com.agenticai.core.sdlc.harness.GitWorkspace;
import com.agenticai.core.sdlc.harness.HarnessApprovalGate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The harness's {@link HarnessApprovalGate}: publishes the diff, flips the task to
 * AWAITING_APPROVAL and blocks until a decision bound to that exact diff arrives. Every other
 * way out - no decision in time, a cancel, a store error, an oversized diff - returns false
 * (deny); the only {@code true} path reads the decision key that {@code CodingTaskService#approve} writes.
 */
final class StoreApprovalGate implements HarnessApprovalGate {
    private final CodingTaskService.Plan plan;
    private final CodingTaskState st;
    private final GitWorkspace workspace;
    private final AtomicReference<String> baseline;
    private final CodingTaskStore store;
    private final CodingTaskSettings settings;
    private final LongSupplier clock;
    private final long pollMillis;
    private final Consumer<CodingTaskState> persist;
    private final BooleanSupplier cancelRequested;
    private volatile boolean tooLarge;
    private volatile boolean timedOut;

    StoreApprovalGate(CodingTaskService.Plan plan, CodingTaskState st, GitWorkspace workspace, AtomicReference<String> baseline,
        CodingTaskStore store, CodingTaskSettings settings, LongSupplier clock, long pollMillis,
        Consumer<CodingTaskState> persist, BooleanSupplier cancelRequested) {
        this.store = store;
        this.settings = settings;
        this.clock = clock;
        this.pollMillis = pollMillis;
        this.persist = persist;
        this.cancelRequested = cancelRequested;
        this.plan = plan;
        this.st = st;
        this.workspace = workspace;
        this.baseline = baseline;
    }

    boolean tooLarge() {
        return tooLarge;
    }

    boolean timedOut() {
        return timedOut;
    }

    @Override
    public boolean approve(String action, String summary) {
        String base = baseline.get();
        if (base == null) {
            return false;
        }
        String diff = workspace.diffSince(base);
        if (diff.length() > CodingTaskService.MAX_DIFF_CHARS) {
            tooLarge = true;
            return false;
        }
        String hash = CodingTaskService.sha256(diff);
        List<String> testFiles = CodingModelSupport.touchedTestFiles(diff);
        store.saveDiff(plan.tenantId(), plan.taskId(), diff);
        store.clearDecision(plan.tenantId(), plan.taskId());
        st.diffAvailable = true;
        st.diffHash = hash;
        st.diffFiles = CodingModelSupport.countFiles(diff);
        st.testFilesTouched = !testFiles.isEmpty();
        st.status = AWAITING_APPROVAL;
        st.phase = AWAITING_APPROVAL;
        st.message = summary + (testFiles.isEmpty() ? ""
            : " Note: the change modifies test files (" + String.join(", ", testFiles.subList(0, Math.min(5, testFiles.size()))) + ").");
        persist.accept(st);

        long deadline = clock.getAsLong() + settings.approvalTimeoutSeconds() * 1000L;
        while (clock.getAsLong() < deadline) {
            if (cancelRequested.getAsBoolean()) {
                return false;
            }
            Optional<String> decision = store.decision(plan.tenantId(), plan.taskId());
            if (decision.isPresent()) {
                store.clearDecision(plan.tenantId(), plan.taskId());
                String d = decision.get();
                if (d.equals("APPROVE:" + hash)) {
                    return true;
                }
                if (d.equals("REJECT")) {
                    return false;
                }
                // A decision for some other diff: ignore it and keep waiting for the right one.
            }
            try {
                Thread.sleep(pollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        timedOut = true;
        return false;
    }
}
