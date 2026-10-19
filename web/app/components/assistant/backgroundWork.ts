import type { AssistantTurn } from "./assistantTypes";

// Background tasks the Assistant handed over ("Keep going in the background"). The jobs live on the
// server; what this file keeps in the browser is only which thread each one was started from and what
// the person asked, so the hand-off card comes back when that thread is opened again and a finished job
// can say where it belongs. A convenience, never the source of truth: the Work rail reads the jobs from
// the server.

export interface BackgroundTask {
  jobId: string;
  agentId: string;
  /** The request as handed over. */
  title: string;
  /** Thread (session id, or "job:<id>" for a thread that began as a hand-off) it was started from. */
  threadId: string;
  startedAt: number;
}

const KEY = "vd.assistant.background";
const MAX_TASKS = 100;

/** A thread whose first message went straight to the background has no session yet: it is named by its job. */
export const JOB_THREAD_PREFIX = "job:";
export const jobThreadId = (jobId: string) => `${JOB_THREAD_PREFIX}${jobId}`;
export const isJobThread = (id: string | null | undefined) => !!id && id.startsWith(JOB_THREAD_PREFIX);

function read(scope: string): BackgroundTask[] {
  try {
    const raw = window.localStorage.getItem(`${KEY}.${scope}`);
    const rows: unknown = raw ? JSON.parse(raw) : [];
    return Array.isArray(rows)
      ? rows.filter((r): r is BackgroundTask => !!r && typeof r.jobId === "string" && typeof r.threadId === "string")
      : [];
  } catch {
    return [];
  }
}

function write(scope: string, rows: BackgroundTask[]) {
  try {
    window.localStorage.setItem(`${KEY}.${scope}`, JSON.stringify(rows.slice(0, MAX_TASKS)));
  } catch {
    /* storage blocked: the cards just do not come back after a reload */
  }
}

export function recordBackgroundTask(scope: string, task: BackgroundTask) {
  write(scope, [task, ...read(scope).filter((t) => t.jobId !== task.jobId)]);
}

export function backgroundTasks(scope: string): BackgroundTask[] {
  return read(scope);
}

export function taskForJob(scope: string, jobId: string): BackgroundTask | null {
  return read(scope).find((t) => t.jobId === jobId) ?? null;
}

/** Forget the tasks of a thread that was removed. */
export function forgetThreadTasks(scope: string, threadId: string) {
  write(scope, read(scope).filter((t) => t.threadId !== threadId));
}

/** The transcript entries that stand for a thread's background tasks (what openThread adds back). */
export function handoffTurns(tasks: readonly BackgroundTask[]): AssistantTurn[] {
  return tasks
    .slice()
    .sort((a, b) => a.startedAt - b.startedAt)
    .map((task) => ({
      id: `bg-${task.jobId}`,
      role: "assistant" as const,
      content: "",
      at: task.startedAt,
      handoff: { kind: "job" as const, id: task.jobId, title: task.title, href: `/jobs/${task.agentId}/${task.jobId}` },
    }));
}

/** The thread restored from a hand-off alone: the request, then its card. */
export function jobThreadTurns(task: BackgroundTask): AssistantTurn[] {
  return [{ id: `bgu-${task.jobId}`, role: "user", content: task.title, at: task.startedAt }, ...handoffTurns([task])];
}

export type JobPhase = "queued" | "running" | "waiting" | "done" | "failed" | "stopped";

/** The person-facing phase of a job's server status. */
export function jobPhase(status: string): JobPhase {
  switch (status) {
    case "QUEUED":
      return "queued";
    case "RUNNING":
      return "running";
    case "AWAITING_APPROVAL":
      return "waiting";
    case "SUCCEEDED":
    case "COMPLETED":
      return "done";
    case "CANCELLED":
    case "CANCELED":
      return "stopped";
    default:
      return "failed";
  }
}

export const isLivePhase = (p: JobPhase) => p === "queued" || p === "running" || p === "waiting";
