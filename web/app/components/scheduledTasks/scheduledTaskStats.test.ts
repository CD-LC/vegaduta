import { describe, expect, it } from "vitest";
import type { ScheduledTask, ScheduledTaskRun } from "../../lib/scheduledTasks";
import { runSample, taskStatusCounts, upcomingWithin } from "./scheduledTaskStats";

function task(over: Partial<ScheduledTask>): ScheduledTask {
  return {
    id: "t1",
    name: "Digest",
    timezone: "UTC",
    enabled: true,
    consecutiveFailures: 0,
    upcomingRuns: [],
    runCount: 0,
    ...over,
  } as ScheduledTask;
}

function run(over: Partial<ScheduledTaskRun>): ScheduledTaskRun {
  return {
    id: "r1",
    taskId: "t1",
    trigger: "SCHEDULE",
    status: "SUCCEEDED",
    attempt: 1,
    startedAt: "2026-10-05T08:00:00Z",
    costUsd: null,
    ...over,
  } as ScheduledTaskRun;
}

const NOW = Date.parse("2026-10-05T00:00:00Z");

describe("taskStatusCounts", () => {
  it("is all zero for no tasks", () => {
    expect(taskStatusCounts([])).toEqual({ total: 0, active: 0, failing: 0, paused: 0 });
    expect(taskStatusCounts(null)).toEqual({ total: 0, active: 0, failing: 0, paused: 0 });
  });

  it("splits into groups that do not overlap and add up to the total", () => {
    const counts = taskStatusCounts([
      task({ id: "a" }),
      task({ id: "b", consecutiveFailures: 2 }),
      task({ id: "c", enabled: false }),
      // Auto-paused after a streak: it is paused, not failing - it is no longer running at all.
      task({ id: "d", enabled: false, consecutiveFailures: 3 }),
    ]);
    expect(counts).toEqual({ total: 4, active: 1, failing: 1, paused: 2 });
    expect(counts.active + counts.failing + counts.paused).toBe(counts.total);
  });
});

describe("upcomingWithin", () => {
  it("keeps only runs inside the window, soonest first, and never a paused task's", () => {
    const window = upcomingWithin(
      [
        task({ id: "a", name: "B task", upcomingRuns: ["2026-10-05T09:00:00Z", "2026-10-06T09:00:00Z", "2026-10-07T09:00:00Z"] }),
        task({ id: "b", name: "A task", timezone: "Asia/Kolkata", upcomingRuns: ["2026-10-05T03:00:00Z"] }),
        task({ id: "c", enabled: false, upcomingRuns: ["2026-10-05T01:00:00Z"] }),
      ],
      NOW
    );
    expect(window.runs).toEqual([
      { taskId: "b", taskName: "A task", at: "2026-10-05T03:00:00Z", timezone: "Asia/Kolkata" },
      { taskId: "a", taskName: "B task", at: "2026-10-05T09:00:00Z", timezone: "UTC" },
    ]);
    expect(window.mayHaveMore).toEqual([]);
  });

  it("flags a task whose three known runs all fall inside the window: it may have more", () => {
    const window = upcomingWithin(
      [task({ id: "often", upcomingRuns: ["2026-10-05T00:15:00Z", "2026-10-05T00:30:00Z", "2026-10-05T00:45:00Z"] })],
      NOW
    );
    expect(window.runs).toHaveLength(3);
    expect(window.mayHaveMore).toEqual(["often"]);
  });

  it("ignores past, unreadable and missing entries", () => {
    const window = upcomingWithin(
      [
        task({ id: "a", upcomingRuns: ["2026-10-04T23:00:00Z", "not a date"] }),
        task({ id: "b", upcomingRuns: undefined as unknown as string[] }),
      ],
      NOW
    );
    expect(window).toEqual({ runs: [], mayHaveMore: [] });
  });
});

describe("runSample", () => {
  it("has no cost, not a zero cost, when no run reports one", () => {
    expect(runSample([])).toEqual({ count: 0, succeeded: 0, failed: 0, other: 0, costUsd: null, costedRuns: 0 });
    expect(runSample([run({}), run({ id: "r2", status: "FAILED" })]).costUsd).toBeNull();
  });

  it("counts outcomes and sums only the reported costs", () => {
    const sample = runSample([
      run({ id: "1", costUsd: 0.25 }),
      run({ id: "2", status: "FAILED", costUsd: 0.5 }),
      run({ id: "3", status: "SKIPPED" }),
      run({ id: "4", status: "ENQUEUED", costUsd: Number.NaN }),
    ]);
    expect(sample).toEqual({ count: 4, succeeded: 1, failed: 1, other: 2, costUsd: 0.75, costedRuns: 2 });
  });
});
