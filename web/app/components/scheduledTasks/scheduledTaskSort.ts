// Pure column sorting for the scheduled-tasks table. The server list is not paged (ScheduledTaskService.list), so
// sorting the whole list before the client pager slices it is exact.
import type { ScheduledTask } from "../../lib/scheduledTasks";

export type TaskSortKey = "created" | "name" | "nextRun" | "lastRun" | "runCount";
export type SortDir = "asc" | "desc";
export interface TaskSort {
  key: TaskSortKey;
  dir: SortDir;
}

/** What the list showed before columns were sortable: newest created first. */
export const DEFAULT_TASK_SORT: TaskSort = { key: "created", dir: "desc" };

const time = (iso: string | null | undefined): number | null => {
  if (!iso) return null;
  const ms = Date.parse(iso);
  return Number.isNaN(ms) ? null : ms;
};

function value(task: ScheduledTask, key: TaskSortKey): string | number | null {
  switch (key) {
    case "name":
      return (task.name || "").toLowerCase();
    case "nextRun":
      // A paused task has no upcoming run, whatever stale nextRunAt it still carries.
      return task.enabled ? time(task.nextRunAt) : null;
    case "lastRun":
      return time(task.lastRunAt);
    case "runCount":
      return task.runCount ?? 0;
    default:
      return time(task.createdAt) ?? 0;
  }
}

/** Returns a sorted copy. Rows with no value (never run, paused) always sort last, in either direction. */
export function sortTasks(tasks: readonly ScheduledTask[], sort: TaskSort): ScheduledTask[] {
  const sign = sort.dir === "asc" ? 1 : -1;
  return [...tasks].sort((a, b) => {
    const va = value(a, sort.key);
    const vb = value(b, sort.key);
    if (va === null && vb === null) return 0;
    if (va === null) return 1;
    if (vb === null) return -1;
    if (va < vb) return -sign;
    if (va > vb) return sign;
    return 0;
  });
}

/** Header click: the active column flips direction, a new column starts ascending (latest-first for dates). */
export function nextSort(current: TaskSort, key: TaskSortKey): TaskSort {
  if (current.key === key) return { key, dir: current.dir === "asc" ? "desc" : "asc" };
  return { key, dir: key === "lastRun" || key === "runCount" ? "desc" : "asc" };
}
