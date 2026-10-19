package com.agenticai.core.agents.coding;

import static com.agenticai.core.agents.coding.CodingTaskDtos.QUEUED;
import static com.agenticai.core.agents.coding.CodingTaskService.MAX_EVENTS;

import com.agenticai.core.agents.coding.CodingTaskDtos.Event;
import com.agenticai.core.agents.coding.CodingTaskDtos.Review;
import com.agenticai.core.agents.coding.CodingTaskDtos.TaskView;
import com.agenticai.core.agents.coding.CodingTaskDtos.TestRun;
import com.agenticai.core.agents.coding.CodingTaskDtos.Usage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Mutable state owned by the worker thread; every write to the store goes through a snapshot of it. */
final class CodingTaskState {
    private static final Pattern TEST_EVENT = Pattern.compile("^run (\\d+) exit (-?\\d+)\\n?", Pattern.DOTALL);


    final CodingTaskService.Plan plan;
    final String runner;
    String status = QUEUED;
    String outcome;
    String phase = QUEUED;
    String message;
    final List<Event> events = new ArrayList<>();
    final List<TestRun> tests = new ArrayList<>();
    Review review;
    boolean diffAvailable;
    String diffHash;
    int diffFiles;
    boolean testFilesTouched;
    boolean cancelRequested;
    CodingTaskBudget budget;

    CodingTaskState(CodingTaskService.Plan plan, String runner) {
        this.plan = plan;
        this.runner = runner;
    }

    void event(long atMs, String type, String text) {
        if (events.size() >= MAX_EVENTS) {
            events.remove(0);
        }
        events.add(new Event(atMs, type, CodingModelSupport.clip(text, 1500)));
    }

    void applyProgress(long nowMs, AtomicReference<String> baseline, String type, String detail) {
        switch (type) {
            case "baseline" -> {
                baseline.set(detail);
                phase = "EDITING";
                event(nowMs, "checkpoint", "Baseline checkpoint " + shortSha(detail));
        }
            case "edit" -> {
                phase = "TESTING";
                event(nowMs, "edit", detail);
        }
            case "tests" -> {
                Matcher m = TEST_EVENT.matcher(detail);
                int iteration = 0;
                int exit = 1;
                String output = detail;
                if (m.find()) {
                    iteration = Integer.parseInt(m.group(1));
                    exit = Integer.parseInt(m.group(2));
                    output = detail.substring(m.end());
                }
                if (tests.size() >= 12) {
                    tests.remove(0);
                }
                tests.add(new TestRun(iteration, exit, CodingModelSupport.clip(output, 2000)));
                event(nowMs, "tests", "Test run " + iteration + (exit == 0 ? " passed." : " failed (exit " + exit + ")."));
                phase = exit == 0 ? "REVIEWING" : "EDITING";
        }
            case "review" -> {
                boolean ok = detail.startsWith("approved");
                review = new Review(ok, detail.replaceFirst("^(approved|rejected): ?", ""));
                event(nowMs, "review", detail);
        }
            case "approval" -> event(nowMs, "approval", "Waiting for your decision on the change.");
            case "rollback" -> event(nowMs, "rollback", detail);
            default -> event(nowMs, type, detail);
    }
    }

    TaskView snapshot(long nowMs) {
        Usage usage = budget == null ? new Usage(0, 0, 0) : new Usage(budget.steps(), budget.tokens(), budget.elapsedMs());
        return new TaskView(plan.taskId(), plan.agentId().toString(), status, outcome, phase, plan.instruction(),
            plan.testCommand(), plan.sourceKind(), plan.repoUrl(), runner, plan.budget(), usage,
            List.copyOf(events), List.copyOf(tests), review, diffAvailable, diffHash, diffFiles,
            testFilesTouched, message, cancelRequested, plan.createdAtMs(), nowMs);
    }

    private static String shortSha(String sha) {
        return sha == null ? "" : sha.substring(0, Math.min(8, sha.length()));
    }
}
