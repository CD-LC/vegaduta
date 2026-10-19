// Pure helpers behind resumable chat streams (core: ResumableChatStreamService,
// app.chat.resumable-streams.*, default OFF). No React, no fetch - everything
// here is a decision or a piece of bookkeeping so it can be unit-tested without
// a network (see resumableStream.test.ts). The wiring lives in api/chat.ts
// (streamChat + resumeChatTurn) and components/ChatPanel.tsx.
//
// What the server contract is, in one paragraph: a request that sends
// `X-Resumable-Stream: 1` gets its generation detached from the HTTP
// connection, every SSE event carries a monotonic `id:`, the turn id comes back
// in `X-Turn-Id`, and the last frame is `event: turn-end`. If the connection
// drops, `GET /api/chat/turns/{turnId}/stream?after=<lastId>` replays what was
// missed and follows the turn live. 404 = expired / not yours / feature off,
// 410 = the bounded buffer already dropped events this client needs.

/** Request header that opts a turn in. Without it the server behaves exactly as before. */
import { isViewAsActive } from "./viewAs";

export const RESUMABLE_OPT_IN_HEADER = "X-Resumable-Stream";
/** Response header carrying the turn id (exposed to browsers by core's CORS config). */
export const TURN_ID_HEADER = "X-Turn-Id";
/** Name of the closing SSE frame. */
export const TURN_END_EVENT = "turn-end";

/** How many times one turn is resumed before the failure is surfaced. */
export const RESUME_MAX_ATTEMPTS = 6;
export const RESUME_BASE_DELAY_MS = 500;
export const RESUME_MAX_DELAY_MS = 10_000;
/** A pending-turn record older than this is ignored: core keeps a finished turn for 10 minutes by default. */
export const PENDING_TURN_MAX_AGE_MS = 10 * 60 * 1000;
/** A stream that has been silent this long when the tab becomes visible again is probed by resuming. */
export const STALE_STREAM_MS = 20_000;

// ---------------------------------------------------------------------------
// Backoff
// ---------------------------------------------------------------------------

export interface BackoffOptions {
  baseMs?: number;
  maxMs?: number;
  /** Fraction of the delay randomised either way (0 = none). Default 0.2. */
  jitter?: number;
  /** Injected so tests are deterministic. */
  random?: () => number;
}

/**
 * Delay before resume attempt `attempt` (1-based): base * 2^(attempt-1), capped at
 * maxMs, with +/- `jitter` randomisation so a fleet of tabs that lost the same
 * network does not reconnect in lockstep. Never negative, never above maxMs.
 */
export function backoffDelayMs(attempt: number, options: BackoffOptions = {}): number {
  const baseMs = options.baseMs ?? RESUME_BASE_DELAY_MS;
  const maxMs = options.maxMs ?? RESUME_MAX_DELAY_MS;
  const jitter = options.jitter ?? 0.2;
  const random = options.random ?? Math.random;
  const exponent = Math.max(0, Math.floor(attempt) - 1);
  const raw = Math.min(maxMs, baseMs * Math.pow(2, exponent));
  const spread = 1 - jitter + random() * 2 * jitter;
  return Math.max(0, Math.min(maxMs, Math.round(raw * spread)));
}

// ---------------------------------------------------------------------------
// De-duplication by event id
// ---------------------------------------------------------------------------

export interface EventTracker {
  /** Highest event id delivered so far (0 before any). This is the `after` cursor of a resume. */
  readonly lastId: number;
  /**
   * True when the event should be delivered. An event whose id is not greater
   * than the last delivered one is a replay of something already rendered and
   * is dropped; an event with NO id (a server that is not resumable-aware, i.e.
   * every flag-off stream) is always delivered and never tracked.
   */
  accept(rawId: string | null | undefined): boolean;
}

export function createEventTracker(startAfter = 0): EventTracker {
  let last = startAfter;
  return {
    get lastId() {
      return last;
    },
    accept(rawId) {
      if (rawId === null || rawId === undefined || rawId === "") return true;
      const id = Number(rawId);
      if (!Number.isFinite(id)) return true;
      if (id <= last) return false;
      last = id;
      return true;
    },
  };
}

// ---------------------------------------------------------------------------
// turn-end frame
// ---------------------------------------------------------------------------

export interface TurnEnd {
  /** complete | error | detach-timeout (open: core may add more). */
  status: string;
  lastEventId: number | null;
}

/** Parses the data of the closing frame; null when it is not the JSON core writes. */
export function parseTurnEnd(payload: string): TurnEnd | null {
  try {
    const parsed = JSON.parse(payload);
    if (!parsed || typeof parsed.status !== "string") return null;
    return {
      status: parsed.status,
      lastEventId: typeof parsed.lastEventId === "number" ? parsed.lastEventId : null,
    };
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------
// Resume decision
// ---------------------------------------------------------------------------

export type ResumeReason =
  | "resume"
  | "no-turn"
  | "turn-ended"
  | "user-stopped"
  | "external-abort"
  | "server-error"
  | "http-error"
  | "circuit-open"
  | "exhausted";

export interface ResumeContext {
  /** From X-Turn-Id; null when the server did not detach this turn (flag off, older core). */
  turnId: string | null;
  /** The closing frame was seen: the turn is over, there is nothing to resume. */
  turnEnded: boolean;
  /** Resume attempts already made for this turn. */
  attempt: number;
  maxAttempts?: number;
  /** requestLifecycle failure class of the attempt that just failed ("stream-idle-timeout", "aborted", ...). */
  failureClass: string | null;
  /** HTTP status of the failure, if it was an HTTP error (a failed resume GET). */
  httpStatus?: number;
  /** The error was the server's in-band STREAM_ERROR_MARKER: a real failure, not a dropped connection. */
  markerError?: boolean;
  /** The user pressed Stop (a cancel nobody else caused). */
  userStopped?: boolean;
  /** The caller's own AbortSignal fired (component unmounted, operation cancelled by a page). */
  externalAborted?: boolean;
  baseDelayMs?: number;
  maxDelayMs?: number;
  random?: () => number;
}

export interface ResumeDecision {
  resume: boolean;
  reason: ResumeReason;
  /** Wait this long before the attempt; 0 when not resuming. */
  delayMs: number;
}

// Transient statuses worth another try on the RESUME request itself.
const RETRYABLE_HTTP = new Set([408, 425, 429, 500, 502, 503, 504]);

/**
 * Should the failure of a chat stream be answered by resuming it, and after how
 * long? Everything that means "the connection broke" resumes; everything that
 * means "somebody decided this is over" does not:
 *   - no turn id: the server never detached this turn, there is nothing to resume;
 *   - the turn already ended, the user pressed Stop, or the page's own signal fired;
 *   - the server reported a failure in-band (resuming would replay the same failure);
 *   - the resume request got a definitive HTTP answer (404 gone/expired/not yours,
 *     410 dropped, 401/403) - only 408/425/429/5xx are transient;
 *   - the circuit breaker is open; or the attempts are used up.
 */
export function decideResume(ctx: ResumeContext): ResumeDecision {
  const no = (reason: ResumeReason): ResumeDecision => ({ resume: false, reason, delayMs: 0 });
  const maxAttempts = ctx.maxAttempts ?? RESUME_MAX_ATTEMPTS;
  if (!ctx.turnId) return no("no-turn");
  if (ctx.turnEnded) return no("turn-ended");
  if (ctx.userStopped) return no("user-stopped");
  if (ctx.externalAborted) return no("external-abort");
  if (ctx.markerError) return no("server-error");
  if (ctx.failureClass === "circuit-open") return no("circuit-open");
  if (typeof ctx.httpStatus === "number" && ctx.httpStatus >= 400 && !RETRYABLE_HTTP.has(ctx.httpStatus)) {
    return no("http-error");
  }
  if (ctx.attempt >= maxAttempts) return no("exhausted");
  return {
    resume: true,
    reason: "resume",
    delayMs: backoffDelayMs(ctx.attempt + 1, {
      baseMs: ctx.baseDelayMs,
      maxMs: ctx.maxDelayMs,
      random: ctx.random,
    }),
  };
}

// ---------------------------------------------------------------------------
// Visibility
// ---------------------------------------------------------------------------

/**
 * When a backgrounded tab becomes visible again its fetch is often dead but not
 * yet reported (mobile browsers freeze the socket and the page's timers; the
 * reader just never resolves). If a turn is in flight and nothing has arrived
 * for `staleAfterMs`, resume proactively instead of waiting out the idle timer.
 */
export function shouldProbeOnVisible(input: {
  visibilityState: string;
  inFlight: boolean;
  turnId: string | null;
  lastActivityAt: number;
  now: number;
  staleAfterMs?: number;
}): boolean {
  if (input.visibilityState !== "visible") return false;
  if (!input.inFlight || !input.turnId) return false;
  return input.now - input.lastActivityAt >= (input.staleAfterMs ?? STALE_STREAM_MS);
}

// ---------------------------------------------------------------------------
// In-flight turn memory (sessionStorage, tab-scoped, every access guarded)
// ---------------------------------------------------------------------------

export interface PendingTurn {
  turnId: string;
  agentId: string;
  sessionId: string | null;
  /** What the user sent, so a reloaded page can show the exchange it is restoring. */
  userMessage: string;
  startedAt: number;
}

const PENDING_KEY_PREFIX = "vegaduta.chat.pendingTurn.v1:";
const MAX_STORED_MESSAGE_CHARS = 8000;

export function pendingTurnKey(agentId: string): string {
  return PENDING_KEY_PREFIX + agentId;
}

// sessionStorage can be absent (SSR), blocked (private window, site data off) or
// throw on access - the feature then simply degrades to "no page-load resume".
function sessionStore(): Storage | null {
  try {
    return typeof window !== "undefined" && window.sessionStorage ? window.sessionStorage : null;
  } catch {
    return null;
  }
}

export function savePendingTurn(turn: PendingTurn, storage: Storage | null = sessionStore()): void {
  // View-as (read-only impersonation): never keep the viewed user's turn in this tab.
  if (!storage || isViewAsActive()) return;
  try {
    const record: PendingTurn = {
      ...turn,
      userMessage: String(turn.userMessage ?? "").slice(0, MAX_STORED_MESSAGE_CHARS),
    };
    storage.setItem(pendingTurnKey(turn.agentId), JSON.stringify(record));
  } catch {
    // storage full / blocked - not worth failing a chat turn over
  }
}

export function clearPendingTurn(agentId: string, storage: Storage | null = sessionStore()): void {
  if (!storage) return;
  try {
    storage.removeItem(pendingTurnKey(agentId));
  } catch {
    // ignore
  }
}

/**
 * The turn this tab left in flight for `agentId`, or null: nothing stored, not
 * valid JSON, wrong shape, or older than `maxAgeMs` (a stale record is removed -
 * the server buffer it points at has expired anyway).
 */
export function loadPendingTurn(
  agentId: string,
  now: number = Date.now(),
  storage: Storage | null = sessionStore(),
  maxAgeMs: number = PENDING_TURN_MAX_AGE_MS
): PendingTurn | null {
  if (!storage || isViewAsActive()) return null;
  try {
    const raw = storage.getItem(pendingTurnKey(agentId));
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (
      !parsed ||
      typeof parsed.turnId !== "string" ||
      !parsed.turnId ||
      parsed.agentId !== agentId ||
      typeof parsed.startedAt !== "number"
    ) {
      storage.removeItem(pendingTurnKey(agentId));
      return null;
    }
    if (now - parsed.startedAt > maxAgeMs) {
      storage.removeItem(pendingTurnKey(agentId));
      return null;
    }
    return {
      turnId: parsed.turnId,
      agentId,
      sessionId: typeof parsed.sessionId === "string" ? parsed.sessionId : null,
      userMessage: typeof parsed.userMessage === "string" ? parsed.userMessage : "",
      startedAt: parsed.startedAt,
    };
  } catch {
    return null;
  }
}

/** URL of the resume endpoint (the API base is added by apiFetch). */
export function resumeStreamPath(turnId: string, afterId: number): string {
  return `/api/chat/turns/${encodeURIComponent(turnId)}/stream?after=${Math.max(0, Math.floor(afterId) || 0)}`;
}

/** URL of the reconciliation snapshot. */
export function turnSnapshotPath(turnId: string): string {
  return `/api/chat/turns/${encodeURIComponent(turnId)}`;
}

/**
 * URL of the persisted-reply lookup: the assistant message core stored for the turn's session. The fallback when the
 * resume buffer is gone (404) or no longer holds the whole reply (410).
 */
export function turnReplyPath(turnId: string): string {
  return `/api/chat/turns/${encodeURIComponent(turnId)}/reply`;
}
