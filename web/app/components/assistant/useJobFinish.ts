"use client";

import { useEffect, useRef } from "react";
import type { AgentJobSummaryResponse } from "../../lib/api/types";
import { isLivePhase, jobPhase, taskForJob, type BackgroundTask } from "./backgroundWork";

/**
 * Tells the pane when a background task it started has just finished: a job seen queued, running or
 * waiting on an approval in one read and finished in the next. `onFinished` runs once per job, only for
 * jobs this browser started (the thread map in backgroundWork.ts), so a job from another device or a
 * routine never writes into a conversation by surprise.
 */
export function useJobFinish(
  jobs: readonly AgentJobSummaryResponse[],
  scope: string,
  onFinished: (job: AgentJobSummaryResponse, task: BackgroundTask) => void
) {
  const seen = useRef<Map<string, boolean>>(new Map());
  const handler = useRef(onFinished);
  handler.current = onFinished;

  useEffect(() => {
    for (const job of jobs) {
      const live = isLivePhase(jobPhase(job.status));
      const wasLive = seen.current.get(job.id);
      seen.current.set(job.id, live);
      if (!live && wasLive === true) {
        const task = taskForJob(scope, job.id);
        if (task) handler.current(job, task);
      }
    }
  }, [jobs, scope]);
}
