import { runRequestLifecycle, type RequestLifecycleController } from "../requestLifecycle";
import { FRAMEWORK_MESSAGE_FAILURE_CLASSES, apiFetch, friendlyStatusFallback, type AgentStepEvent } from "./core";
import { pumpChatSse, type ChatSseHandlers } from "./chatSse";
import { knownGuardError } from "../spendCap";
import {
  RESUME_MAX_ATTEMPTS,
  clearPendingTurn,
  createEventTracker,
  decideResume,
  resumeStreamPath,
  shouldProbeOnVisible,
  turnReplyPath,
  turnSnapshotPath,
  type EventTracker,
  type PendingTurn,
  type TurnEnd,
} from "../resumableStream";

// The resume half of streamChat (api/chat.ts): when a chat stream breaks and the
// server told us the turn id (core: ResumableChatStreamService), reconnect to
// GET /api/chat/turns/{turnId}/stream?after=<last id> with exponential backoff
// and carry on rendering - never showing a token twice (events are de-duplicated
// by SSE id, see resumableStream.ts), and telling the host UI it is
// "reconnecting" so it can say so quietly. The decisions are pure and live in
// resumableStream.ts (unit-tested); this file is the network + timer wiring.

export interface ReconnectingState {
  /** 1-based resume attempt currently being waited for / made. */
  attempt: number;
  max: number;
  /** Stop waiting and stop the turn (the user pressed Stop during the backoff). */
  cancel: () => void;
}

/** The extra, all-optional members streamChat's `lifecycle` argument accepts for resumable streams. */
export interface ChatResumeLifecycle {
  signal?: AbortSignal;
  onController?: (controller: RequestLifecycleController<string | null>) => void;
  /** Called with a state while reconnecting and with null once streaming again or finished. */
  onReconnecting?: (state: ReconnectingState | null) => void;
  /**
   * Called after a RESUMED turn completed, when the server's own copy of the reply differs from what this client
   * assembled: the host replaces the assistant text with it.
   */
  onReconcile?: (text: string) => void;
  /** Overrides for tests and unusual hosts. */
  resume?: { maxAttempts?: number; baseDelayMs?: number; maxDelayMs?: number };
}

/** Everything one streamChat call (or one restored page-load turn) tracks across attempts. */
export class ChatStreamState {
  turnId: string | null = null;
  turnEnd: TurnEnd | null = null;
  sessionId: string | null = null;
  readonly tracker: EventTracker = createEventTracker();
  /** Text delivered to the host so far - what a reconcile compares against the server's copy. */
  text = "";
  lastActivityAt = Date.now();
  inFlight = false;
  resumedOnce = false;
  /** We cancelled the current attempt ourselves (tab came back to life): not a user Stop. */
  selfAborted = false;
  /** The user pressed Stop while we were waiting to reconnect. */
  stoppedInBackoff = false;
  currentController: RequestLifecycleController<string | null> | null = null;
  wake: (() => void) | null = null;
}

export function buildPumpHandlers(
  state: ChatStreamState,
  onChunk: (chunk: string) => void,
  steps?: { onEvent: (event: AgentStepEvent) => void }
): ChatSseHandlers {
  return {
    onChunk: (chunk) => {
      state.lastActivityAt = Date.now();
      state.text += chunk;
      onChunk(chunk);
    },
    steps: steps
      ? {
          onEvent: (event) => {
            state.lastActivityAt = Date.now();
            steps.onEvent(event);
          },
        }
      : undefined,
    tracker: state.tracker,
    onTurnEnd: (end) => {
      state.turnEnd = end;
    },
  };
}

/** A stream that closed without its closing frame: the connection dropped, the reply is not finished. */
export function incompleteStreamError(): Error {
  const failure: any = new Error("The stream was interrupted before the reply finished");
  failure.incompleteStream = true;
  return failure;
}

/** Fire-and-forget server-side Stop for a detached turn (a client disconnect no longer stops generation). */
export function requestServerCancel(agentId: any, sessionId: string | null): void {
  if (!sessionId) return;
  void apiFetch(`/api/agents/${agentId}/chat/sessions/${encodeURIComponent(sessionId)}/cancel`, {
    method: "POST",
  }).catch((): void => undefined);
}

/**
 * When a backgrounded tab becomes visible again and its stream has been silent
 * for a while, cancel the (probably dead) attempt ourselves so the recovery loop
 * resumes immediately instead of waiting out the idle timer. Returns a disposer.
 */
export function watchVisibility(state: ChatStreamState): () => void {
  if (typeof document === "undefined") return (): void => undefined;
  const onVisibilityChange = () => {
    if (
      shouldProbeOnVisible({
        visibilityState: document.visibilityState,
        inFlight: state.inFlight,
        turnId: state.turnId,
        lastActivityAt: state.lastActivityAt,
        now: Date.now(),
      })
    ) {
      state.selfAborted = true;
      state.currentController?.cancel("Reconnecting");
    }
  };
  document.addEventListener("visibilitychange", onVisibilityChange);
  return () => document.removeEventListener("visibilitychange", onVisibilityChange);
}

function waitBackoff(ms: number, signal: AbortSignal | undefined, state: ChatStreamState): Promise<void> {
  return new Promise<void>((resolve) => {
    if (ms <= 0 || state.stoppedInBackoff || signal?.aborted) {
      resolve();
      return;
    }
    const handle: { timer?: ReturnType<typeof setTimeout> } = {};
    const finish = () => {
      if (handle.timer !== undefined) clearTimeout(handle.timer);
      signal?.removeEventListener("abort", finish);
      state.wake = null;
      resolve();
    };
    handle.timer = setTimeout(finish, ms);
    signal?.addEventListener("abort", finish, { once: true });
    state.wake = finish;
  });
}

function cancelledError(): Error {
  const failure: any = new Error("Cancelled by caller");
  failure.failureClass = "aborted";
  return failure;
}

// One resume GET under its own request lifecycle (own controller, so the host's Stop button and status line keep
// working). Retry and the circuit breaker are off: the loop around it IS the retry policy, and a breaker that
// opened on the failures that made us resume would refuse the very request that recovers them.
function runResumeAttempt(
  state: ChatStreamState,
  handlers: ChatSseHandlers,
  lifecycle?: ChatResumeLifecycle
): Promise<string | null> {
  let operationError: any = null;
  const controller = runRequestLifecycle<string | null>(
    async (ctx) => {
      try {
        state.turnEnd = null;
        const res = await apiFetch(resumeStreamPath(state.turnId as string, state.tracker.lastId), {
          signal: ctx.signal,
          headers: { Accept: "text/event-stream, application/json" },
        });
        ctx.markConnected();
        if (!res.ok) {
          let body: any = {};
          try {
            body = await res.json();
          } catch {
            // ignore - the status says enough
          }
          const guard = knownGuardError(res.status, body);
          if (guard) throw guard;
          const failure: any = new Error(body.error || body.message || friendlyStatusFallback(res.status));
          failure.status = res.status;
          throw failure;
        }
        // Connected and replaying: the host can drop its "reconnecting" line and show the normal status again.
        lifecycle?.onReconnecting?.(null);
        state.inFlight = true;
        await pumpChatSse(res, ctx, handlers);
        if (!state.turnEnd) throw incompleteStreamError();
        return state.sessionId;
      } catch (err) {
        operationError = err;
        throw err;
      } finally {
        state.inFlight = false;
      }
    },
    {
      target: "chat-stream:resume",
      surface: "chat-stream",
      signal: lifecycle?.signal,
      // The server replays instantly, so a long connect wait is a real problem; the stream itself can legitimately
      // idle through a long tool call (the server sends a keepalive comment every 15s) and a turn may outlive the
      // original request's 5-minute ceiling.
      timeoutPolicy: { connectTimeoutMs: 30_000, streamIdleTimeoutMs: 60_000, absoluteTimeoutMs: 900_000 },
      retryPolicy: false,
      circuitBreaker: false,
    }
  );
  state.currentController = controller;
  lifecycle?.onController?.(controller);
  return controller.done.then((result) => {
    if (result.ok) return result.value;
    const failureClass = result.snapshot.failureClass;
    const failure: any =
      operationError && failureClass && !FRAMEWORK_MESSAGE_FAILURE_CLASSES.has(failureClass)
        ? operationError
        : new Error(result.snapshot.error || "The reply could not be resumed.");
    failure.failureClass = failureClass;
    throw failure;
  });
}

// After a resumed turn completed, compare what this client assembled with the server's copy of the reply
// (GET /api/chat/turns/{turnId}) and let the host replace its text if they differ. Silent on any failure: this is
// a safety net, never a reason to fail a turn that otherwise completed.
async function reconcileWithServer(state: ChatStreamState, lifecycle?: ChatResumeLifecycle): Promise<void> {
  if (!lifecycle?.onReconcile || !state.turnId) return;
  try {
    const res = await apiFetch(turnSnapshotPath(state.turnId), { headers: { Accept: "application/json" } });
    if (!res.ok) return;
    const snapshot: any = await res.json();
    if (
      snapshot &&
      typeof snapshot.text === "string" &&
      snapshot.status &&
      snapshot.status !== "running" &&
      snapshot.text !== state.text
    ) {
      lifecycle.onReconcile(snapshot.text);
    }
  } catch {
    // ignore - see above
  }
}

// The resume buffer is gone (404: expired / evicted / another replica) or no longer holds the whole reply (410: the
// bounded buffer dropped events this client needs). The turn has normally finished by then and core persisted the
// assistant message, so ask for that and hand it to the host. True when the host now has the full reply; false when it
// could not be had (the caller then reports the original failure).
async function reconcileFromPersistedReply(state: ChatStreamState, lifecycle?: ChatResumeLifecycle): Promise<boolean> {
  if (!lifecycle?.onReconcile || !state.turnId) return false;
  try {
    const res = await apiFetch(turnReplyPath(state.turnId), { headers: { Accept: "application/json" } });
    if (!res.ok) return false;
    const reply: any = await res.json();
    if (!reply || typeof reply.text !== "string" || reply.status !== "complete" || !reply.text) return false;
    if (reply.text !== state.text) lifecycle.onReconcile(reply.text);
    return true;
  } catch {
    return false;
  }
}

export interface RecoverArgs {
  agentId: any;
  state: ChatStreamState;
  handlers: ChatSseHandlers;
  lifecycle?: ChatResumeLifecycle;
  /** What the stream that just broke threw (re-thrown verbatim when there is nothing to resume). */
  firstError: any;
  firstFailureClass: string | null;
  /** Page-load restore: the first attempt is not a retry of anything, so do not wait before it. */
  skipFirstDelay?: boolean;
}

/**
 * The recovery loop. Resolves with the session id once the turn's closing frame arrives; rejects with the
 * ORIGINAL error whenever resuming is not the right answer (see decideResume), so a stream that was never
 * resumable fails exactly as it always did.
 */
export async function recoverChatStream(args: RecoverArgs): Promise<string | null> {
  const { agentId, state, handlers, lifecycle } = args;
  const maxAttempts = lifecycle?.resume?.maxAttempts ?? RESUME_MAX_ATTEMPTS;
  let err = args.firstError;
  let failureClass: string | null = args.firstFailureClass ?? null;
  let attempt = 0;
  for (;;) {
    const wasSelfAborted = state.selfAborted;
    state.selfAborted = false;
    const externalAborted = Boolean(lifecycle?.signal?.aborted);
    const userStopped = state.stoppedInBackoff || (failureClass === "aborted" && !wasSelfAborted && !externalAborted);
    const decision = decideResume({
      turnId: state.turnId,
      turnEnded: state.turnEnd !== null,
      attempt,
      maxAttempts,
      failureClass,
      httpStatus: typeof err?.status === "number" ? err.status : undefined,
      markerError: err?.streamMarker === true,
      userStopped,
      externalAborted,
      baseDelayMs: lifecycle?.resume?.baseDelayMs,
      maxDelayMs: lifecycle?.resume?.maxDelayMs,
    });
    if (!decision.resume) {
      lifecycle?.onReconnecting?.(null);
      // The closing frame arrived before whatever broke afterwards: the turn is complete.
      if (decision.reason === "turn-ended") return state.sessionId;
      // The resume GET itself was refused because the buffer is gone or incomplete: fall back to the stored reply.
      if (decision.reason === "http-error" && (err?.status === 404 || err?.status === 410)) {
        if (await reconcileFromPersistedReply(state, lifecycle)) return state.sessionId;
      }
      // A detached turn does not stop when the client goes away - Stop has to reach the server explicitly.
      if (decision.reason === "user-stopped" && state.turnId) requestServerCancel(agentId, state.sessionId);
      throw err;
    }
    attempt += 1;
    lifecycle?.onReconnecting?.({
      attempt,
      max: maxAttempts,
      cancel: () => {
        state.stoppedInBackoff = true;
        state.wake?.();
        state.currentController?.cancel("Cancelled by user");
      },
    });
    await waitBackoff(args.skipFirstDelay && attempt === 1 ? 0 : decision.delayMs, lifecycle?.signal, state);
    if (state.stoppedInBackoff) {
      lifecycle?.onReconnecting?.(null);
      requestServerCancel(agentId, state.sessionId);
      throw cancelledError();
    }
    if (lifecycle?.signal?.aborted) {
      lifecycle?.onReconnecting?.(null);
      throw err;
    }
    try {
      state.resumedOnce = true;
      const sessionId = await runResumeAttempt(state, handlers, lifecycle);
      lifecycle?.onReconnecting?.(null);
      await reconcileWithServer(state, lifecycle);
      return sessionId;
    } catch (resumeError: any) {
      err = resumeError;
      failureClass = resumeError?.failureClass ?? null;
    }
  }
}

/**
 * Page-load restore: a turn this tab left in flight (see resumableStream.ts's pending-turn storage) is replayed
 * from its first event - the page is fresh, so there is nothing to de-duplicate against - and followed live to its
 * end. Rejects with an error carrying `status` 404/410 when the server no longer has it; the host then says the
 * reply could not be recovered.
 */
export function resumeChatTurn(
  agentId: any,
  pending: PendingTurn,
  onChunk: (chunk: string) => void,
  steps?: { onEvent: (event: AgentStepEvent) => void },
  lifecycle?: ChatResumeLifecycle
): Promise<string | null> {
  const state = new ChatStreamState();
  state.turnId = pending.turnId;
  state.sessionId = pending.sessionId;
  const stopWatching = watchVisibility(state);
  return recoverChatStream({
    agentId,
    state,
    handlers: buildPumpHandlers(state, onChunk, steps),
    lifecycle,
    firstError: new Error("The reply could not be restored."),
    firstFailureClass: "stream-error",
    skipFirstDelay: true,
  }).finally(() => {
    stopWatching();
    // Kept when the page itself cancelled us (navigation away again): the turn is still worth restoring later.
    if (!lifecycle?.signal?.aborted) clearPendingTurn(String(agentId));
    lifecycle?.onReconnecting?.(null);
  });
}
