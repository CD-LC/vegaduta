// Universal Request Lifecycle: the state machine's types, the default timeout /
// retry / circuit-breaker policies, the operation contract and the telemetry
// stub (split out of requestLifecycle.ts, which re-exports all of it).

// ---------------------------------------------------------------------------
// Core types
// ---------------------------------------------------------------------------

export type RequestLifecycleState =
  "IDLE" | "QUEUED" | "CONNECTING" | "PROCESSING" | "STREAMING" | "COMPLETED" | "TIMED_OUT" | "FAILED" | "CANCELLED";

/** Why an attempt didn't complete. Drives both retry eligibility and the
 * telemetry event's `failureClass`. */
export type RequestFailureClass =
  | "connect-timeout"
  | "stream-idle-timeout"
  | "absolute-timeout"
  | "connection-refused"
  | "http-error"
  | "stream-error"
  | "aborted"
  | "circuit-open"
  | "unknown";

export function isTerminalRequestLifecycleState(state: RequestLifecycleState): boolean {
  return state === "COMPLETED" || state === "TIMED_OUT" || state === "FAILED" || state === "CANCELLED";
}

export interface RequestLifecycleSnapshot {
  requestId: string;
  /** Logical target this request hit — a host, model id, or route name.
   * Also the circuit-breaker key: multiple concurrent requests to the same
   * target share failure counts. */
  target: string;
  /** Human-facing surface name for telemetry/logs, e.g. "chat-stream",
   * "edge-tier1", "camera-inference", "video-gen-poll". */
  surface: string;
  state: RequestLifecycleState;
  /** 1-indexed. Increments on each transparent retry. */
  attempt: number;
  maxAttempts: number;
  /** True while cancel() would actually do something. False once terminal. */
  cancellable: boolean;
  error: string | null;
  failureClass: RequestFailureClass | null;
  startedAt: number;
  updatedAt: number;
}

export type RequestLifecycleEvent = { type: "state"; snapshot: RequestLifecycleSnapshot };
export type RequestLifecycleListener = (event: RequestLifecycleEvent) => void;

export interface RequestLifecycleResult<T> {
  ok: boolean;
  value: T | null;
  snapshot: RequestLifecycleSnapshot;
}

// ---------------------------------------------------------------------------
// Policies — safe by default, configurable per call-site
// ---------------------------------------------------------------------------

export interface RequestLifecycleTimeoutPolicy {
  /** Ceiling on CONNECTING (before the operation calls markConnected()). 0
   * disables this specific timer (still bounded by absoluteTimeoutMs). */
  connectTimeoutMs: number;
  /** Ceiling on silence once connected — resets on every markStreaming()/
   * onChunk() call. 0 disables this specific timer. */
  streamIdleTimeoutMs: number;
  /** Hard ceiling on the WHOLE request (all retries included). Always
   * enforced — this is the backstop when the other two are misconfigured or
   * disabled. 0 disables it too, but that is never the default. */
  absoluteTimeoutMs: number;
}

/** Deliberately generous defaults — this is a safety net, not a latency
 * budget. Call-sites with a tighter SLA (e.g. camera inference) should pass
 * a stricter policy; nothing in this app should ever hang with NO ceiling. */
export const DEFAULT_TIMEOUT_POLICY: RequestLifecycleTimeoutPolicy = {
  connectTimeoutMs: 10_000,
  streamIdleTimeoutMs: 20_000,
  absoluteTimeoutMs: 120_000,
};

export interface RequestLifecycleRetryPolicy {
  /** Additional attempts beyond the first. Clamped to [0, 2] — "bounded
   * automatic retry (1-2 attempts)" per the audit, never unbounded. */
  maxRetries: number;
  /** Only these failure classes are retried, and ONLY when nothing was ever
   * emitted to the caller during that attempt (see header). Failure classes
   * that imply the target is reachable but broken (http-error, stream-error)
   * are deliberately excluded — retrying those wastes a full timeout on a
   * request that isn't going to succeed differently next time. */
  retryableFailureClasses: RequestFailureClass[];
}

export const DEFAULT_RETRY_POLICY: RequestLifecycleRetryPolicy = {
  maxRetries: 1,
  retryableFailureClasses: ["connect-timeout", "connection-refused"],
};

export interface CircuitBreakerPolicy {
  /** Consecutive failures (COMPLETED resets the count) against one target,
   * within windowMs, before the breaker opens. */
  failureThreshold: number;
  windowMs: number;
  /** How long the breaker stays open once tripped — a fast "circuit-open"
   * FAILED result instead of paying a full timeout again. */
  cooldownMs: number;
}

export const DEFAULT_CIRCUIT_BREAKER_POLICY: CircuitBreakerPolicy = {
  failureThreshold: 4,
  windowMs: 30_000,
  cooldownMs: 15_000,
};

// ---------------------------------------------------------------------------
// Operation contract
// ---------------------------------------------------------------------------

export interface RequestLifecycleOperationContext {
  /** Pass this to fetch()/whatever transport the operation uses. Fires on
   * timeout (connect/idle/absolute) AND on caller cancel() — the operation
   * never needs its own AbortController. */
  signal: AbortSignal;
  /** Call once the network connection is established / the first response
   * arrived (e.g. fetch()'s promise resolved to a Response). Moves
   * CONNECTING -> PROCESSING, stops the connect timer, and starts the
   * streaming-idle timer. Idempotent. */
  markConnected(): void;
  /** Call when the operation starts producing output the caller can see
   * (first streamed chunk / token). Moves -> STREAMING, resets the idle
   * timer, and PERMANENTLY disables retry for this attempt (see header). */
  markStreaming(): void;
  /** Call on every subsequent chunk. Resets the idle timer; also marks
   * output-emitted like markStreaming() if it wasn't called first. */
  onChunk(): void;
}

export type RequestLifecycleOperation<T> = (ctx: RequestLifecycleOperationContext) => Promise<T>;

export interface RequestLifecycleOptions {
  target: string;
  surface: string;
  requestId?: string;
  timeoutPolicy?: Partial<RequestLifecycleTimeoutPolicy>;
  /** false disables retry entirely for this call. */
  retryPolicy?: Partial<RequestLifecycleRetryPolicy> | false;
  /** false disables the circuit breaker entirely for this call. */
  circuitBreaker?: Partial<CircuitBreakerPolicy> | false;
  /** Caller-triggered cancel, e.g. a component unmounting or an existing
   * AbortSignal the surface already threads through (routeTurn's `signal`,
   * an unmount cleanup, a "Stop generating" button not yet wired to
   * controller.cancel()). Aborting this has the exact same effect as calling
   * controller.cancel(). */
  signal?: AbortSignal;
}

// ---------------------------------------------------------------------------
// Telemetry (stub — no backend sink yet, see reportEdgeTurn in
// app/lib/edge/telemetry.ts for the shape a real sink will likely follow)
// ---------------------------------------------------------------------------

export type RequestLifecycleOutcome = "completed" | "timed_out" | "failed" | "cancelled";

export interface RequestLifecycleTelemetryEvent {
  requestId: string;
  target: string;
  surface: string;
  outcome: RequestLifecycleOutcome;
  failureClass: RequestFailureClass | null;
  /** 1-indexed count of attempts actually made (retries included). */
  attempts: number;
  durationMs: number;
  startedAt: number;
  endedAt: number;
  /** Optional freeform context a call-site's own state machine can attach —
   * e.g. a multi-stage job's `{ stage: "RENDERING" }` at the point it failed
   * (video-gen polling has states this framework's own 9-state machine
   * doesn't model: QUEUED/PREPARING/PROCESSING/RENDERING/FINALIZING).
   * Existing callers omit this; it is never required. */
  meta?: Record<string, string>;
}

/**
 * Fire-and-forget, never throws, never awaited by any caller's critical
 * path — same contract as reportEdgeTurn. TODO(reliability): wire to a real
 * backend sink once one exists; for now this only logs in development so
 * the framework is fully adoptable without a backend dependency.
 */
export function reportRequestLifecycleEvent(event: RequestLifecycleTelemetryEvent): void {
  try {
    if (process.env.NODE_ENV !== "production") {
      console.debug("[requestLifecycle]", event);
    }
  } catch {
    // Best-effort — telemetry must never affect the caller.
  }
}
