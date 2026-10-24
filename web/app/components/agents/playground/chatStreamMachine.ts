/**
 * The chat stream as an explicit state machine (BIG-FILES-SPLIT-PLAN, file 4).
 *
 *   idle --start--> streaming --settle--> idle
 *   streaming --fail + settle--> error | resumable
 *
 * There is deliberately no "awaiting approval" phase: a turn whose tool call the gate parked ENDS (the model is
 * told to stop), so no turn is ever mid-stream waiting on a human. What follows a decision is a separate turn that
 * core runs by itself; the playground follows it with useApprovalContinuation, not with this machine.
 *
 * `error` is the plain-language reason (shown under the transcript); `resumable`
 * marks a failure that kept partial reply text, so "continue" or "try again" is
 * meaningful. A failure that happens while a turn is still busy does not cut
 * the turn short: the phase moves to error/resumable only when the turn settles,
 * which is the behaviour the old independent `sending`/`error` booleans had.
 *
 * Pure and framework-free so the transitions are unit-testable.
 */
export type ChatStreamPhase = "idle" | "streaming" | "error" | "resumable";

export interface ChatStreamState {
  phase: ChatStreamPhase;
  error: string | null;
  resumable: boolean;
}

export type ChatStreamEvent =
  | { type: "start" }
  | { type: "settle" }
  | { type: "fail"; message: string; resumable?: boolean }
  | { type: "clear-error" };

export const initialChatStreamState: ChatStreamState = { phase: "idle", error: null, resumable: false };

/** A turn is in flight (the composer is locked, Cancel is offered). */
export function isBusyPhase(phase: ChatStreamPhase): boolean {
  return phase === "streaming";
}

function restingPhase(state: Pick<ChatStreamState, "error" | "resumable">): ChatStreamPhase {
  if (!state.error) return "idle";
  return state.resumable ? "resumable" : "error";
}

export function chatStreamReducer(state: ChatStreamState, event: ChatStreamEvent): ChatStreamState {
  switch (event.type) {
    case "start":
      return { ...state, phase: "streaming" };
    case "settle":
      return { ...state, phase: restingPhase(state) };
    case "fail": {
      const next: ChatStreamState = { ...state, error: event.message, resumable: !!event.resumable };
      return isBusyPhase(state.phase) ? next : { ...next, phase: restingPhase(next) };
    }
    case "clear-error": {
      const next: ChatStreamState = { ...state, error: null, resumable: false };
      return isBusyPhase(state.phase) ? next : { ...next, phase: "idle" };
    }
    default:
      return state;
  }
}
