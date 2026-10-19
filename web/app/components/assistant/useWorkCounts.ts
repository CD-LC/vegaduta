"use client";

import { useEffect, useState } from "react";
import { assistantApi } from "../../lib/api/assistant";
import type { AgentJobSummaryResponse, ApprovalRequestResponse } from "../../lib/api/types";
import type { ScheduledTask } from "../../lib/scheduledTasks";

/** What the Work rail shows in Phase 1: how much is waiting on the person and how much is running. */
export interface WorkCounts {
  pendingApprovals: number;
  runningJobs: number;
  /** Jobs parked on an approval (AWAITING_APPROVAL) - counted under "needs you" as well. */
  parkedJobs: number;
  /** null until the first read; false when the last read of either list failed. */
  reachable: boolean | null;
  /** The selected agent's recent background jobs, newest first (every state). */
  jobs: AgentJobSummaryResponse[];
  /** The person's routines (scheduled tasks), the old Jobs page's second tab; empty where the feature is off. */
  routines: ScheduledTask[];
}

export const WORK_POLL_MS = 10_000;
/** While something is queued or running the list is read faster so progress and the finish show up. */
export const WORK_POLL_LIVE_MS = 4_000;

const EMPTY: WorkCounts = { pendingApprovals: 0, runningJobs: 0, parkedJobs: 0, reachable: null, jobs: [], routines: [] };

/**
 * Polls GET /api/approvals?status=PENDING and GET /api/agent-jobs?status=live every WORK_POLL_MS while the
 * tab is visible, and at once when `refreshKey` changes (a turn settled). Both reads are quiet, so a core
 * without the routes just shows the rail as unavailable instead of toasting every ten seconds.
 */
export function useWorkCounts(enabled: boolean, refreshKey: unknown, agentId: string | null = null): WorkCounts {
  const [counts, setCounts] = useState<WorkCounts>(EMPTY);

  useEffect(() => {
    if (!enabled) return undefined;
    let live = true;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let liveNow = false;

    const read = async () => {
      if (typeof document !== "undefined" && document.visibilityState === "hidden") return;
      const [approvals, jobs, recent, routines] = await Promise.all([
        assistantApi.listPendingApprovalsQuiet().then(
          (rows): ApprovalRequestResponse[] | null => (Array.isArray(rows) ? rows : null),
          (): null => null
        ),
        assistantApi.listLiveJobsQuiet().then(
          (rows): AgentJobSummaryResponse[] | null => (Array.isArray(rows) ? rows : null),
          (): null => null
        ),
        agentId
          ? assistantApi.listRecentJobsQuiet(agentId).then(
              (rows): AgentJobSummaryResponse[] | null => (Array.isArray(rows) ? rows : null),
              (): null => null
            )
          : Promise.resolve<AgentJobSummaryResponse[] | null>([]),
        assistantApi.listScheduledTasksQuiet().then(
          (rows): ScheduledTask[] | null => (Array.isArray(rows) ? rows : null),
          (): null => null
        ),
      ]);
      if (!live) return;
      liveNow = (jobs ?? []).length > 0;
      const running = (jobs ?? []).filter((j) => j.status === "RUNNING" || j.status === "QUEUED").length;
      const parked = (jobs ?? []).filter((j) => j.status === "AWAITING_APPROVAL").length;
      setCounts({
        pendingApprovals: approvals?.length ?? 0,
        runningJobs: running,
        parkedJobs: parked,
        reachable: approvals !== null && jobs !== null,
        jobs: recent ?? [],
        routines: routines ?? [],
      });
    };
    const loop = () => {
      void read().finally(() => {
        if (live) timer = setTimeout(loop, liveNow ? WORK_POLL_LIVE_MS : WORK_POLL_MS);
      });
    };
    loop();
    const onVisible = () => {
      if (document.visibilityState === "visible") void read();
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      live = false;
      if (timer) clearTimeout(timer);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [enabled, refreshKey, agentId]);

  return counts;
}
