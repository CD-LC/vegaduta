import type { StudioEvent } from "./studioClient";

// Run state for the studio workflow runner (WorkflowCanvas): the per-step and
// per-run shapes, and the reducer that folds one streamed run event into them.
// Mutates in place, exactly as the runner always has; the caller re-renders.

export type StepState = {
  status: "idle" | "running" | "ok" | "skipped" | "stopped" | "rejected" | "error" | "waiting";
  summary?: string;
  output?: string;
  ms?: number;
  events: StudioEvent[];
};

export type RunState = {
  runId: string | null;
  running: boolean;
  steps: Record<string, StepState>;
  approval: { stepId: string; message: string; preview: string } | null;
  status: string | null;
  output: string;
  ms: number | null;
  error: string | null;
};

export const EMPTY_RUN: RunState = {
  runId: null,
  running: false,
  steps: {},
  approval: null,
  status: null,
  output: "",
  ms: null,
  error: null,
};

/** Fold one streamed event into the run. `onBudget` relays the fair-use counters. */
export function applyRunEvent(
  state: RunState,
  event: StudioEvent,
  onBudget: (used: number, limit: number) => void
): void {
  const stepId = "stepId" in event ? (event.stepId as string | undefined) : undefined;
  const step = stepId ? state.steps[stepId] : undefined;
  switch (event.type) {
    case "run_started":
      state.runId = event.runId ?? null;
      break;
    case "budget":
      onBudget(event.used, event.limit);
      break;
    case "step_started":
      if (step) step.status = "running";
      break;
    case "step_output":
      if (step) step.output = event.text;
      break;
    case "step_finished":
      if (step) {
        step.ms = event.ms;
        step.summary = event.summary;
        step.status =
          event.status === "ok"
            ? "ok"
            : event.status === "rejected"
              ? "rejected"
              : event.status === "budget" || event.status === "timeout"
                ? "stopped"
                : event.status === "skipped"
                  ? "skipped"
                  : "error";
      }
      break;
    case "step_skipped":
      if (step) {
        step.status = "skipped";
        step.summary = "Skipped - the condition wasn't met";
      }
      break;
    case "condition":
      if (step) step.summary = event.matched ? `Found "${event.needle}"` : `"${event.needle}" not found`;
      break;
    case "approval_required":
      state.approval = { stepId: event.stepId, message: event.message, preview: event.preview };
      if (step) step.status = "waiting";
      break;
    case "approval":
      state.approval = null;
      break;
    case "run_finished":
      if ("status" in event) {
        state.status = event.status ?? null;
        state.output = event.output ?? "";
        state.ms = event.ms;
        state.running = false;
        if (event.messagesUsed != null && event.messageLimit != null) onBudget(event.messagesUsed, event.messageLimit);
      } else if (step) {
        step.events = [...step.events, event];
      }
      break;
    case "error":
      if (step && (event.id != null || step.status === "running")) {
        step.events = [...step.events, event];
      } else {
        state.error = event.message;
      }
      break;
    case "heartbeat":
      break;
    default:
      if (step) step.events = [...step.events, event];
  }
}
