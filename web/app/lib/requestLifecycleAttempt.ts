// Universal Request Lifecycle: per-call policy resolution and failure
// classification used by RequestLifecycleController (split out of
// requestLifecycle.ts).
import { randomUuid } from "./randomId";
import {
  DEFAULT_CIRCUIT_BREAKER_POLICY,
  DEFAULT_RETRY_POLICY,
  DEFAULT_TIMEOUT_POLICY,
  type CircuitBreakerPolicy,
  type RequestFailureClass,
  type RequestLifecycleRetryPolicy,
  type RequestLifecycleTimeoutPolicy,
} from "./requestLifecycleTypes";

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

export function makeRequestId(): string {
  return randomUuid();
}

export function resolveTimeoutPolicy(patch?: Partial<RequestLifecycleTimeoutPolicy>): RequestLifecycleTimeoutPolicy {
  return { ...DEFAULT_TIMEOUT_POLICY, ...patch };
}

export function resolveRetryPolicy(patch?: Partial<RequestLifecycleRetryPolicy> | false): RequestLifecycleRetryPolicy {
  if (patch === false) return { maxRetries: 0, retryableFailureClasses: [] };
  const merged = { ...DEFAULT_RETRY_POLICY, ...patch };
  return { ...merged, maxRetries: Math.max(0, Math.min(2, merged.maxRetries)) };
}

export function resolveCircuitBreakerPolicy(
  patch?: Partial<CircuitBreakerPolicy> | false
): CircuitBreakerPolicy | null {
  if (patch === false) return null;
  return { ...DEFAULT_CIRCUIT_BREAKER_POLICY, ...patch };
}

export function errorMessage(err: unknown): string {
  if (err instanceof Error) return err.message;
  return String(err);
}

/** Best-effort classification of a rejected operation promise into a
 * RequestFailureClass. Only used when the rejection wasn't already
 * attributed to a timer/abort (those are classified at the point they fire,
 * see runAttempt below) — this covers the operation's own thrown errors. */
export function classifyThrown(err: unknown): RequestFailureClass {
  if (err instanceof Error) {
    if (err.name === "AbortError") return "aborted";
    // app/lib/api.ts's doFetch wraps every raw fetch()-level rejection
    // (TypeError "Failed to fetch", DNS failure, CORS-preflight failure,
    // etc.) in a friendly Error carrying `isBackendUnreachable: true` -
    // this codebase's established "the backend itself is unreachable"
    // convention, checked BEFORE the message-substring heuristics below
    // since the friendly message text ("...temporarily unavailable...")
    // never contains any of those substrings and would otherwise fall
    // through to "unknown" - silently disabling this failure class's
    // default retry eligibility for every apiFetch-based caller.
    if ((err as { isBackendUnreachable?: unknown }).isBackendUnreachable === true) {
      return "connection-refused";
    }
    const msg = err.message.toLowerCase();
    if (
      msg.includes("failed to fetch") ||
      msg.includes("network error") ||
      msg.includes("networkerror") ||
      msg.includes("econnrefused") ||
      msg.includes("network request failed")
    ) {
      return "connection-refused";
    }
    const httpStatus =
      (err as { httpStatus?: unknown; status?: unknown }).httpStatus ?? (err as { status?: unknown }).status;
    // 4xx is deterministic - retrying the identical request can never pass,
    // and counting it toward the circuit breaker is what turned one stale
    // selection into a 15s "pausing retries" outage for the whole surface.
    if (typeof httpStatus === "number")
      return httpStatus >= 400 && httpStatus < 500 ? "http-client-error" : "http-error";
    if (msg.includes("stream")) return "stream-error";
  }
  return "unknown";
}

export interface AttemptOutcome<T> {
  ok: boolean;
  value: T | null;
  failureClass: RequestFailureClass | null;
  error: string | null;
  /** True once markStreaming()/onChunk() fired during this attempt — retry
   * is unsafe regardless of failure class once this is true. */
  emittedOutput: boolean;
  /** True when the absolute ceiling — not this attempt's own timers — is
   * what ended the attempt. No further attempts are made either way. */
  absolute: boolean;
}
