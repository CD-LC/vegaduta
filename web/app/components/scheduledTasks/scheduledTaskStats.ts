// Pure figures for the Scheduled tasks summary and the per-task run strip. No React, no fetch, no clock of its own
// (the caller passes "now"), so every rule is unit-tested (scheduledTaskStats.test.ts).
//
// Honesty rules these helpers exist to keep:
//   - GET /api/scheduled-tasks is not paginated (ScheduledTaskService.list), so task counts are exact.
//   - TaskResponse.upcomingRuns carries only the next 3 runs of an ENABLED task (UPCOMING_SHOWN), so a task that
//     fires more often than that can have more runs inside the window than the server told us about. Those tasks
//     are reported in `mayHaveMore` so the page can say so instead of presenting the list as complete.
//   - GET /{id}/runs returns the newest N runs (the client asks for 20): every figure derived from it is a figure
//     about THOSE runs, never a lifetime one. The lifetime count is ScheduledTask.runCount.
import type { ScheduledTask, ScheduledTaskRun } from "../../lib/scheduledTasks";

/** How many upcoming runs the server sends per task (ScheduledTaskService.UPCOMING_SHOWN). */
export const UPCOMING_PER_TASK = 3;

export interface TaskStatusCounts {
  total: number;
  /** Enabled and not on a failure streak. */
  active: number;
  /** Enabled, but the latest scheduled run(s) failed (consecutiveFailures > 0). */
  failing: number;
  /** Not enabled: paused by hand, auto-paused, finished or expired. */
  paused: number;
}

/** Splits the tasks into three groups that do not overlap, so they add up to `total`. */
export function taskStatusCounts(tasks: readonly ScheduledTask[] | null | undefined): TaskStatusCounts {
  const counts: TaskStatusCounts = { total: 0, active: 0, failing: 0, paused: 0 };
  for (const task of tasks ?? []) {
    counts.total += 1;
    if (!task.enabled) counts.paused += 1;
    else if ((task.consecutiveFailures ?? 0) > 0) counts.failing += 1;
    else counts.active += 1;
  }
  return counts;
}

export type TaskFilter = "all" | "active" | "failing" | "paused";

/** True when the task belongs to `filter` - the same three disjoint groups taskStatusCounts uses. */
export function taskInGroup(task: ScheduledTask, filter: TaskFilter): boolean {
  if (filter === "all") return true;
  if (!task.enabled) return filter === "paused";
  return (task.consecutiveFailures ?? 0) > 0 ? filter === "failing" : filter === "active";
}

/** Status filter plus a case-insensitive match on name, prompt and agent name. */
export function filterTasks(
  tasks: readonly ScheduledTask[],
  filter: TaskFilter,
  query: string,
  agentNames: ReadonlyMap<string, string> = new Map()
): ScheduledTask[] {
  const q = query.trim().toLowerCase();
  return tasks.filter((task) => {
    if (!taskInGroup(task, filter)) return false;
    if (!q) return true;
    return [task.name, task.prompt, agentNames.get(task.agentId) ?? ""].some((v) => (v || "").toLowerCase().includes(q));
  });
}

export interface UpcomingRun {
  taskId: string;
  taskName: string;
  /** ISO instant, as the server sent it. */
  at: string;
  /** The task's own IANA zone: the page shows the time in it, as everywhere else on /scheduled. */
  timezone: string;
}

export interface UpcomingWindow {
  /** Soonest first. */
  runs: UpcomingRun[];
  /** Ids of tasks whose every server-sent upcoming run is inside the window: they may run again within it. */
  mayHaveMore: string[];
}

/** The runs the server listed that start within `hours` of `nowMs`. Paused tasks never appear. */
export function upcomingWithin(tasks: readonly ScheduledTask[] | null | undefined, nowMs: number, hours = 24): UpcomingWindow {
  const end = nowMs + hours * 3_600_000;
  const runs: Array<UpcomingRun & { ms: number }> = [];
  const mayHaveMore: string[] = [];
  for (const task of tasks ?? []) {
    if (!task.enabled) continue;
    const listed = Array.isArray(task.upcomingRuns) ? task.upcomingRuns : [];
    let inside = 0;
    for (const at of listed) {
      const ms = new Date(at).getTime();
      if (Number.isNaN(ms) || ms < nowMs || ms > end) continue;
      inside += 1;
      runs.push({ taskId: task.id, taskName: task.name, at, timezone: task.timezone, ms });
    }
    if (listed.length >= UPCOMING_PER_TASK && inside === listed.length) mayHaveMore.push(task.id);
  }
  runs.sort((a, b) => a.ms - b.ms || a.taskName.localeCompare(b.taskName));
  return { runs: runs.map(({ ms: _ms, ...run }) => run), mayHaveMore };
}

export interface RunSample {
  /** How many runs the endpoint returned. */
  count: number;
  succeeded: number;
  failed: number;
  /** Everything else: skipped, cancelled, still running. */
  other: number;
  /** Sum of the costs that were reported, or null when no run in the sample carries one. */
  costUsd: number | null;
  /** How many runs in the sample carry a cost. */
  costedRuns: number;
}

/** Figures about the fetched runs only (a sample: the newest N). */
export function runSample(runs: readonly ScheduledTaskRun[] | null | undefined): RunSample {
  const sample: RunSample = { count: 0, succeeded: 0, failed: 0, other: 0, costUsd: null, costedRuns: 0 };
  let cost = 0;
  for (const run of runs ?? []) {
    sample.count += 1;
    if (run.status === "SUCCEEDED") sample.succeeded += 1;
    else if (run.status === "FAILED") sample.failed += 1;
    else sample.other += 1;
    if (typeof run.costUsd === "number" && Number.isFinite(run.costUsd)) {
      cost += run.costUsd;
      sample.costedRuns += 1;
    }
  }
  if (sample.costedRuns > 0) sample.costUsd = cost;
  return sample;
}
