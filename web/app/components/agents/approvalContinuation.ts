import { failedDisplayText, isFailedMessage } from "../../lib/failedTurn";

/**
 * After a person decides a parked tool call, the PLATFORM carries the task on by itself: core starts a turn for
 * the same conversation (AgentToolCallApprovalExecutor -> ApprovalContinuationLauncher). The browser sends
 * nothing. That turn runs detached from the approving request, so it is not streamed to anybody; what the
 * browser can see is the conversation it stores:
 *
 *   1. a user message that opens with `<approval_result request="<id>" ...>` (written by the platform, never
 *      by the person - core's ApprovalContinuation.MESSAGE_MARKER), added when the turn starts, and
 *   2. the agent's reply after it, added when the turn ends (or a failed-reply entry if it broke).
 *
 * This module is the pure half of watching for those two entries; useApprovalContinuation is the polling half.
 */

/** Opens every continuation message core stores in a conversation (core: ApprovalContinuation.MESSAGE_MARKER). */
export const APPROVAL_CONTINUATION_MARKER = "<approval_result";

/** How often the stored conversation is read while a continuation is expected or running. */
export const CONTINUATION_POLL_MS = 2_000;

/**
 * How long to wait for the continuation to START (its marker message to appear). Core starts it inside the
 * approve / reject call itself, so a marker that is not there this long after the decision is not coming: the
 * task was not restarted (a background job, continuation switched off, a retryable failure, a decision made by
 * someone who cannot read this conversation).
 */
export const CONTINUATION_START_WAIT_MS = 20_000;

/**
 * How long a started continuation is followed before the watcher gives up. Matches core's default
 * app.approvals.continuation.turn-timeout (PT10M), the longest a continuation turn is allowed to run.
 */
export const CONTINUATION_MAX_WAIT_MS = 10 * 60_000;

/** The shape of one stored message, as GET .../sessions/{id}/transcript returns it. */
export interface StoredMessage {
  role?: string | null;
  content?: string | null;
  status?: string | null;
  error?: boolean;
}

/** True when `content` is a continuation message the platform wrote (not something the person typed). */
export function isApprovalContinuationMessage(content: string | null | undefined): boolean {
  return typeof content === "string" && content.startsWith(APPROVAL_CONTINUATION_MARKER);
}

/** The approval request id a continuation message belongs to, or null when it is not one. */
export function continuationRequestId(content: string | null | undefined): string | null {
  if (!isApprovalContinuationMessage(content)) return null;
  const match = /^<approval_result\s+request="([^"]*)"/.exec(content as string);
  return match ? match[1] : null;
}

export type ContinuationProgress =
  /** No continuation message stored for this decision (yet). */
  | { phase: "waiting" }
  /** The continuation turn started; its reply is not stored yet. */
  | { phase: "running" }
  /** The agent's reply to the continuation is stored. `failed`: the turn broke and `content` is the reason. */
  | { phase: "replied"; content: string; failed: boolean };

/** Where the continuation of approval request `requestId` stands in a stored conversation. */
export function locateContinuation(
  messages: ReadonlyArray<StoredMessage> | null | undefined,
  requestId: string
): ContinuationProgress {
  if (!Array.isArray(messages)) return { phase: "waiting" };
  const start = messages.findIndex((m) => m?.role === "user" && continuationRequestId(m.content) === requestId);
  if (start < 0) return { phase: "waiting" };
  const reply = messages.slice(start + 1).find((m) => m?.role === "assistant");
  if (!reply) return { phase: "running" };
  const failed = isFailedMessage(reply);
  return { phase: "replied", content: failed ? failedDisplayText(reply) : (reply.content ?? ""), failed };
}
