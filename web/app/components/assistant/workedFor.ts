import type { ActivityStep } from "../agents/AgentActivityTrace";

/**
 * The numbers behind one turn's "Working for 2 m 10 s · 6 tool calls, 1 failed" row (WorkedForBlock).
 * Pure so the heading can be unit-tested without rendering the trace.
 */
export interface WorkSummary {
  toolCalls: number;
  failed: number;
  /** `guardrail` events: a call the platform refused or a turn it stopped. */
  skipped: number;
  /** A tool_call with no closing tool_result/error yet, or no run_finished while the turn is live. */
  running: boolean;
  durationMs: number | null;
}

const CLOSING = new Set(["tool_result", "error"]);

export function summarizeWork(
  steps: readonly ActivityStep[] | null | undefined,
  startedAt: number | null | undefined,
  endedAt: number | null | undefined,
  now: number
): WorkSummary {
  const list = steps ?? [];
  const closed = new Set<number | string>();
  let toolCalls = 0;
  let failed = 0;
  let skipped = 0;
  let finishedMs: number | null = null;
  for (const step of list) {
    if (step.type === "tool_call") toolCalls++;
    else if (CLOSING.has(step.type)) {
      closed.add(step.id);
      if (step.type === "error") failed++;
    } else if (step.type === "guardrail") skipped++;
    else if (step.type === "run_finished" && typeof step.ms === "number") finishedMs = step.ms;
  }
  const openCall = list.some((s) => s.type === "tool_call" && !closed.has(s.id));
  const live = endedAt == null;
  const running = live && (openCall || startedAt != null);
  let durationMs: number | null = null;
  if (finishedMs != null && !live) durationMs = finishedMs;
  else if (startedAt != null) durationMs = Math.max(0, (live ? now : (endedAt ?? now)) - startedAt);
  return { toolCalls, failed, skipped, running, durationMs };
}

/** "4 m 12 s", "12 s", "<1 s" - the OpenClaw-style compact duration. */
export function formatDuration(ms: number | null | undefined): string {
  if (ms == null || !Number.isFinite(ms)) return "";
  const total = Math.floor(ms / 1000);
  if (total < 1) return "<1 s";
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  if (h > 0) return `${h} h ${m} m`;
  if (m > 0) return `${m} m ${s} s`;
  return `${s} s`;
}

/** The reason text of a guardrail step, or null when the server sent none. */
export function skippedReason(step: ActivityStep): string | null {
  const text = step.message || step.preview || null;
  return text && text.trim() ? text.trim() : null;
}

/** Steps worth a row of their own under the heading: everything but liveness beats, the run frame and pre-token phases. */
export function isVisibleStep(step: ActivityStep): boolean {
  return (
    step.type !== "heartbeat" && step.type !== "run_started" && step.type !== "run_finished" && step.type !== "phase"
  );
}

/**
 * What the turn is doing RIGHT NOW, for the live status line ("Searching the
 * web…", "Analyzing…") - the last visible step's activity, or null when the
 * turn just started and nothing has streamed yet. Keys are looked up under
 * the assistant namespace as `liveStatus.<key>`.
 */
export function liveStatusKey(steps: readonly ActivityStep[] | null | undefined): {
  key: string;
  tool?: string;
  thinking?: string;
} | null {
  // Pre-token `phase` events count here (not in the trace): "Checking safety…", "Gathering
  // context…", "Model is working…" is exactly what the silent minute before a local model's
  // first token should say.
  const list = (steps ?? []).filter((s) => isVisibleStep(s) || s.type === "phase");
  const last = list[list.length - 1];
  if (!last) return { key: "starting" };
  if (last.type === "phase") {
    const stage = last.stage ?? "";
    return { key: stage === "guardrails" || stage === "routing" || stage === "context" ? stage : "model" };
  }
  if (last.type === "thinking") {
    return { key: "analyzing", thinking: last.message ?? undefined };
  }
  if (last.type === "plan") return { key: "planning" };
  if (last.type === "tool_call") return { key: "tool", tool: last.tool ?? undefined };
  if (last.type === "tool_result") return { key: "afterTool" };
  if (last.type === "error") return { key: "recovering" };
  return { key: "working" };
}
