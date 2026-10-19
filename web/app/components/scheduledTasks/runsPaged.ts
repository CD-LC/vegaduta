import { fetchPaged, type PagedResult } from "../../lib/api/pagedFetch";
import type { ScheduledTaskRun } from "../../lib/scheduledTasks";

/**
 * One page of a task's run history: GET /api/scheduled-tasks/{id}/runs?limit&offset, newest first.
 * `total` is the X-Total-Count the server sends when it supports offset; a core that predates it ignores `offset`
 * and sends no total, in which case the caller sees `total === undefined` and only the newest page is reachable.
 */
export function listTaskRunsPage(taskId: string, limit: number, offset: number): Promise<PagedResult<ScheduledTaskRun>> {
  const qs = new URLSearchParams({ limit: String(limit) });
  if (offset > 0) qs.set("offset", String(offset));
  return fetchPaged<ScheduledTaskRun>(`/api/scheduled-tasks/${encodeURIComponent(taskId)}/runs?${qs.toString()}`, limit);
}
