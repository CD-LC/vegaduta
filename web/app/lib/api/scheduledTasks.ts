import { apiFetch, toJson, toJsonQuietOn, jsonBody } from "./core";
import type {
  ScheduledTask,
  ScheduledTaskPayload,
  ScheduledTaskRun,
  ScheduledTasksConfig,
  SchedulePreview,
} from "../scheduledTasks";

// Scheduled tasks (core com.agenticai.core.agents.scheduledtask, /api/scheduled-tasks/**): prompts an agent runs by
// itself on a schedule. Off by default on the server (app.scheduled-tasks.enabled): while off every route except
// /capabilities answers 404, so the page reads getScheduledTasksConfig() first and never calls the rest.

const enc = encodeURIComponent;

export const scheduledTasksApi = {
  // 404 = a core that predates the feature: an expected state, not a failure worth a toast.
  getScheduledTasksConfig: (): Promise<ScheduledTasksConfig> =>
    apiFetch(`/api/scheduled-tasks/capabilities`).then(toJsonQuietOn([404])),
  // skipGetCache: a task's last status, run count and next run change on the server's clock, not on a write from
  // this tab. Through the shared GET cache a reload inside the stale window replayed the copy taken while a run
  // was still in flight, so the badge read "Running" beside a run row that read "Succeeded" (prod pass bug 14).
  listScheduledTasks: (): Promise<ScheduledTask[]> =>
    apiFetch(`/api/scheduled-tasks`, { skipGetCache: true }).then(toJson<ScheduledTask[]>),
  // 403 = the workspace plan limit (free cap, Premium-only channel, shared demo): an expected state the form shows
  // inline with an upgrade link, not a global error toast.
  createScheduledTask: (payload: ScheduledTaskPayload): Promise<ScheduledTask> =>
    apiFetch(`/api/scheduled-tasks`, { method: "POST", ...jsonBody(payload) }).then(toJsonQuietOn([403])),
  updateScheduledTask: (id: string, payload: ScheduledTaskPayload): Promise<ScheduledTask> =>
    apiFetch(`/api/scheduled-tasks/${enc(id)}`, { method: "PUT", ...jsonBody(payload) }).then(toJsonQuietOn([403])),
  deleteScheduledTask: (id: string) =>
    apiFetch(`/api/scheduled-tasks/${enc(id)}`, { method: "DELETE" }).then((res) => (res.ok ? null : toJson(res))),
  pauseScheduledTask: (id: string): Promise<ScheduledTask> =>
    apiFetch(`/api/scheduled-tasks/${enc(id)}/pause`, { method: "POST" }).then(toJson<ScheduledTask>),
  resumeScheduledTask: (id: string): Promise<ScheduledTask> =>
    apiFetch(`/api/scheduled-tasks/${enc(id)}/resume`, { method: "POST" }).then(toJson<ScheduledTask>),
  runScheduledTaskNow: (id: string): Promise<ScheduledTaskRun> =>
    apiFetch(`/api/scheduled-tasks/${enc(id)}/run-now`, { method: "POST" }).then(toJson<ScheduledTaskRun>),
  // skipGetCache, like the task list above: the panel re-reads the runs when the task badge flips to a final status,
  // and through the shared GET cache that read was served the copy taken while the run was still "running" (fresh
  // for 15 s, then stale-while-revalidate for 5 min, refreshed only for the NEXT read). The server settles the run
  // row and the task badge in the same sweep, so the stale row was a client cache artefact (prod re-verification R5).
  listScheduledTaskRuns: (id: string, limit = 20): Promise<ScheduledTaskRun[]> =>
    apiFetch(`/api/scheduled-tasks/${enc(id)}/runs?limit=${limit}`, { skipGetCache: true }).then(toJson<ScheduledTaskRun[]>),
  /** Validates a schedule and returns the server's own next three runs (used for a custom cron). */
  previewScheduledTaskSchedule: (payload: {
    frequency: string;
    time?: string;
    day?: string;
    cron?: string;
    timezone: string;
  }): Promise<SchedulePreview> =>
    apiFetch(`/api/scheduled-tasks/preview`, { method: "POST", ...jsonBody(payload) }).then(toJsonQuietOn([400])),
};
