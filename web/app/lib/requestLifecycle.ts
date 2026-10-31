"use client";

// Universal Request Lifecycle framework.
//
// Audit finding this closes: every long-running AI call in this app (chat
// streaming, on-device model calls, camera inference, video-gen polling) can
// hang forever with zero timeout, zero abort, zero user-visible failure
// state, and zero telemetry. This module is the ONE reusable seam every
// call-site hardening task (streamChat, tier0.ts, tier1.ts, video-gen
// polling, camera inference — NOT touched here, see call-site owners) is
// meant to route through, instead of each surface inventing its own ad-hoc
// AbortController + setTimeout.
//
// House style: modeled on app/lib/edge/downloadManager.ts's AssetStatus /
// subscribe-listener convention (read that file first if this looks
// unfamiliar) — a snapshot object, a Set<listener> pub-sub with subscribe()
// returning an unsubscribe function, and state transitions that always go
// through one small "patch + emit" chokepoint. Unlike downloadManager (one
// singleton tracking many assets), a RequestLifecycleController is a
// per-call instance — closer to tier0.ts/tier1.ts's per-conversation Session
// objects, constructed fresh for each request and discarded when it settles.
//
// Retry safety property (mirrors tierRouter.ts's `emittedTokens` guard — see
// that file's header for the original rationale): a transparent retry is
// only safe when NOTHING has reached the caller yet. Once the operation has
// called markStreaming()/onChunk() even once, a retry could duplicate or
// interleave output the caller already rendered, so retry is permanently
// disabled for that attempt regardless of failure class.
//
// State machine (9 states, exactly the audit's list):
//   IDLE -> QUEUED -> CONNECTING -> PROCESSING -> STREAMING -> COMPLETED
//                                                             -> TIMED_OUT
//                                                             -> FAILED
//                                                             -> CANCELLED
// CONNECTING/PROCESSING/STREAMING can also fail straight to TIMED_OUT/
// FAILED/CANCELLED — PROCESSING and STREAMING are optional way-points
// (an operation that never calls markStreaming() just goes CONNECTING ->
// PROCESSING -> COMPLETED, which is fine for non-streaming calls).

//
// Split: requestLifecycleTypes.ts (types, default policies, telemetry),
// requestCircuitBreaker.ts (the shared breaker) and requestLifecycleAttempt.ts
// (policy resolution, failure classification) are re-exported or used here;
// this file owns RequestLifecycleController and the React hook.

import { useCallback, useEffect, useState } from "react";
import {
  type RequestLifecycleState,
  type RequestFailureClass,
  isTerminalRequestLifecycleState,
  type RequestLifecycleSnapshot,
  type RequestLifecycleListener,
  type RequestLifecycleResult,
  type RequestLifecycleTimeoutPolicy,
  type RequestLifecycleRetryPolicy,
  type CircuitBreakerPolicy,
  type RequestLifecycleOperationContext,
  type RequestLifecycleOperation,
  type RequestLifecycleOptions,
  type RequestLifecycleOutcome,
  reportRequestLifecycleEvent,
} from "./requestLifecycleTypes";
import { requestCircuitBreaker } from "./requestCircuitBreaker";
import {
  makeRequestId,
  resolveTimeoutPolicy,
  resolveRetryPolicy,
  resolveCircuitBreakerPolicy,
  errorMessage,
  classifyThrown,
  type AttemptOutcome,
} from "./requestLifecycleAttempt";
export {
  type RequestLifecycleState,
  type RequestFailureClass,
  isTerminalRequestLifecycleState,
  type RequestLifecycleSnapshot,
  type RequestLifecycleEvent,
  type RequestLifecycleListener,
  type RequestLifecycleResult,
  type RequestLifecycleTimeoutPolicy,
  DEFAULT_TIMEOUT_POLICY,
  type RequestLifecycleRetryPolicy,
  DEFAULT_RETRY_POLICY,
  type CircuitBreakerPolicy,
  DEFAULT_CIRCUIT_BREAKER_POLICY,
  type RequestLifecycleOperationContext,
  type RequestLifecycleOperation,
  type RequestLifecycleOptions,
  type RequestLifecycleOutcome,
  type RequestLifecycleTelemetryEvent,
  reportRequestLifecycleEvent,
} from "./requestLifecycleTypes";
export {
  requestCircuitBreaker,
  getCircuitBreakerState,
  resetCircuitBreaker,
  registerCircuitBreakerPolicyOverride,
  getCircuitBreakerPolicyOverride,
} from "./requestCircuitBreaker";

// ---------------------------------------------------------------------------
// The controller
// ---------------------------------------------------------------------------

export class RequestLifecycleController<T> {
  readonly requestId: string;
  /** Resolves once the request reaches a terminal state. `value` is only
   * non-null when `ok` is true (COMPLETED). */
  readonly done: Promise<RequestLifecycleResult<T>>;

  private snapshot: RequestLifecycleSnapshot;
  private listeners = new Set<RequestLifecycleListener>();
  private masterAbort = new AbortController();
  private cancelled = false;
  private absoluteTimedOut = false;
  private resolveDone!: (result: RequestLifecycleResult<T>) => void;

  private readonly target: string;
  private readonly surface: string;
  private readonly timeoutPolicy: RequestLifecycleTimeoutPolicy;
  private readonly retryPolicy: RequestLifecycleRetryPolicy;
  private readonly circuitBreakerPolicy: CircuitBreakerPolicy | null;
  private readonly maxAttempts: number;

  constructor(
    private readonly operation: RequestLifecycleOperation<T>,
    options: RequestLifecycleOptions
  ) {
    this.requestId = options.requestId || makeRequestId();
    this.target = options.target;
    this.surface = options.surface;
    this.timeoutPolicy = resolveTimeoutPolicy(options.timeoutPolicy);
    this.retryPolicy = resolveRetryPolicy(options.retryPolicy);
    this.circuitBreakerPolicy = resolveCircuitBreakerPolicy(options.circuitBreaker);
    this.maxAttempts = 1 + this.retryPolicy.maxRetries;

    const now = Date.now();
    this.snapshot = {
      requestId: this.requestId,
      target: this.target,
      surface: this.surface,
      state: "IDLE",
      attempt: 0,
      maxAttempts: this.maxAttempts,
      cancellable: true,
      error: null,
      failureClass: null,
      startedAt: now,
      updatedAt: now,
    };

    this.done = new Promise<RequestLifecycleResult<T>>((resolve) => {
      this.resolveDone = resolve;
    });

    if (options.signal) {
      if (options.signal.aborted) {
        this.cancel("Cancelled before start");
      } else {
        options.signal.addEventListener("abort", () => this.cancel("Cancelled"), { once: true });
      }
    }

    void this.run();
  }

  // -- Public API (matches downloadManager's subscribe/unsubscribe shape) --

  subscribe(listener: RequestLifecycleListener): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  getSnapshot(): RequestLifecycleSnapshot {
    return this.snapshot;
  }

  getState(): RequestLifecycleState {
    return this.snapshot.state;
  }

  /** Caller-triggered cancel — safe to call multiple times or after the
   * request has already settled (no-op then). */
  cancel(_reason = "Cancelled"): void {
    if (this.cancelled || isTerminalRequestLifecycleState(this.snapshot.state)) return;
    this.cancelled = true;
    this.masterAbort.abort();
  }

  private emit(): void {
    for (const listener of this.listeners) {
      try {
        listener({ type: "state", snapshot: this.snapshot });
      } catch {
        // A broken listener must never take down the request.
      }
    }
  }

  private setState(
    patch: Partial<Omit<RequestLifecycleSnapshot, "requestId" | "target" | "surface" | "updatedAt">>
  ): void {
    const prev = this.snapshot;
    const next: RequestLifecycleSnapshot = {
      ...prev,
      ...patch,
      updatedAt: Date.now(),
    };
    if (patch.state) next.cancellable = !isTerminalRequestLifecycleState(patch.state);
    this.snapshot = next;
    const changed =
      next.state !== prev.state ||
      next.attempt !== prev.attempt ||
      next.error !== prev.error ||
      next.failureClass !== prev.failureClass;
    if (changed) this.emit();
  }

  // -- Driver ---------------------------------------------------------------

  private async run(): Promise<void> {
    const circuitPolicy = this.circuitBreakerPolicy;
    if (circuitPolicy && requestCircuitBreaker.isOpen(this.target)) {
      // Direct feedback (2026-08-12): this used to interpolate the raw
      // internal `surface`/`target` strings ("chat-stream (chat-stream:core)
      // is temporarily unavailable") straight into a user-facing message -
      // meaningless jargon to anyone outside this codebase, and easy to
      // mistake for a real backend outage (BACKEND_UNREACHABLE_MESSAGE in
      // app/lib/api.ts uses almost identical wording for that genuinely
      // different case). This is specifically THIS BROWSER TAB pausing
      // retries for a few seconds after several failed attempts in a row -
      // a local, temporary, self-clearing state (see cooldownMs on the
      // policy), not evidence the service itself is down. Wording says so
      // explicitly and gives the actual wait, rather than a vague "shortly".
      // Narrowed to a local const (not this.circuitBreakerPolicy inline)
      // since TS control-flow narrowing of a `this.field` isn't guaranteed
      // to survive into a template literal in every compiler config.
      this.finish(
        "FAILED",
        "circuit-open",
        `Pausing retries for ${Math.ceil(circuitPolicy.cooldownMs / 1000)}s after several failed attempts in a row - this is a local pause, not necessarily an outage. Try again in a moment.`
      );
      return;
    }

    this.setState({ state: "QUEUED" });

    const absoluteTimer =
      this.timeoutPolicy.absoluteTimeoutMs > 0
        ? setTimeout(() => {
            this.absoluteTimedOut = true;
            this.masterAbort.abort();
          }, this.timeoutPolicy.absoluteTimeoutMs)
        : null;

    let lastFailureClass: RequestFailureClass = "unknown";
    let lastError: string | null = null;

    for (let attempt = 1; attempt <= this.maxAttempts; attempt += 1) {
      if (this.cancelled || this.absoluteTimedOut) break;
      this.setState({ state: "CONNECTING", attempt });

      const outcome = await this.runAttempt();

      if (outcome.ok) {
        if (absoluteTimer) clearTimeout(absoluteTimer);
        this.setState({ state: "COMPLETED", error: null, failureClass: null });
        if (this.circuitBreakerPolicy) requestCircuitBreaker.recordSuccess(this.target);
        this.report("completed", null, attempt);
        this.resolveDone({ ok: true, value: outcome.value, snapshot: this.snapshot });
        return;
      }

      lastFailureClass = outcome.failureClass ?? "unknown";
      lastError = outcome.error;

      if (this.cancelled || lastFailureClass === "aborted" || outcome.absolute) break;

      const canRetry =
        !outcome.emittedOutput &&
        attempt < this.maxAttempts &&
        this.retryPolicy.retryableFailureClasses.includes(lastFailureClass);
      if (!canRetry) break;
      // Otherwise loop: a fresh CONNECTING attempt starts next iteration.
    }

    if (absoluteTimer) clearTimeout(absoluteTimer);

    if (this.circuitBreakerPolicy && lastFailureClass !== "aborted" && lastFailureClass !== "http-client-error") {
      requestCircuitBreaker.recordFailure(this.target, this.circuitBreakerPolicy);
    }

    if (this.cancelled) {
      this.finish("CANCELLED", "aborted", lastError || "Cancelled");
    } else if (
      lastFailureClass === "connect-timeout" ||
      lastFailureClass === "stream-idle-timeout" ||
      lastFailureClass === "absolute-timeout"
    ) {
      this.finish("TIMED_OUT", lastFailureClass, lastError);
    } else {
      this.finish("FAILED", lastFailureClass, lastError);
    }
  }

  /** Runs exactly one attempt against a fresh AbortController scoped to it
   * (chained to the shared masterAbort for cancel()/absolute-timeout), with
   * its own connect/idle timers. Never throws. */
  private async runAttempt(): Promise<AttemptOutcome<T>> {
    const attemptAbort = new AbortController();
    let emittedOutput = false;
    let connected = false;
    let timerFailureClass: RequestFailureClass | null = null;

    const onMasterAbort = () => attemptAbort.abort();
    if (this.masterAbort.signal.aborted) {
      attemptAbort.abort();
    } else {
      this.masterAbort.signal.addEventListener("abort", onMasterAbort, { once: true });
    }

    let connectTimer: ReturnType<typeof setTimeout> | null = null;
    let idleTimer: ReturnType<typeof setTimeout> | null = null;

    const clearConnectTimer = () => {
      if (connectTimer) {
        clearTimeout(connectTimer);
        connectTimer = null;
      }
    };
    const clearIdleTimer = () => {
      if (idleTimer) {
        clearTimeout(idleTimer);
        idleTimer = null;
      }
    };
    const resetIdleTimer = () => {
      clearIdleTimer();
      if (this.timeoutPolicy.streamIdleTimeoutMs > 0) {
        idleTimer = setTimeout(() => {
          timerFailureClass = "stream-idle-timeout";
          attemptAbort.abort();
        }, this.timeoutPolicy.streamIdleTimeoutMs);
      }
    };

    if (this.timeoutPolicy.connectTimeoutMs > 0) {
      connectTimer = setTimeout(() => {
        timerFailureClass = "connect-timeout";
        attemptAbort.abort();
      }, this.timeoutPolicy.connectTimeoutMs);
    }

    const markConnectedOnce = () => {
      if (connected) return;
      connected = true;
      clearConnectTimer();
      this.setState({ state: "PROCESSING" });
      resetIdleTimer();
    };

    const ctx: RequestLifecycleOperationContext = {
      signal: attemptAbort.signal,
      markConnected: markConnectedOnce,
      markStreaming: () => {
        markConnectedOnce();
        emittedOutput = true;
        this.setState({ state: "STREAMING" });
        resetIdleTimer();
      },
      onChunk: () => {
        markConnectedOnce();
        emittedOutput = true;
        if (this.snapshot.state !== "STREAMING") this.setState({ state: "STREAMING" });
        resetIdleTimer();
      },
    };

    try {
      const value = await this.operation(ctx);
      clearConnectTimer();
      clearIdleTimer();
      this.masterAbort.signal.removeEventListener("abort", onMasterAbort);
      return { ok: true, value, failureClass: null, error: null, emittedOutput, absolute: false };
    } catch (err) {
      clearConnectTimer();
      clearIdleTimer();
      this.masterAbort.signal.removeEventListener("abort", onMasterAbort);

      if (this.absoluteTimedOut) {
        return {
          ok: false,
          value: null,
          failureClass: "absolute-timeout",
          error: "Request exceeded its absolute time ceiling",
          emittedOutput,
          absolute: true,
        };
      }
      if (this.cancelled) {
        return {
          ok: false,
          value: null,
          failureClass: "aborted",
          error: "Cancelled by caller",
          emittedOutput,
          absolute: false,
        };
      }
      if (timerFailureClass) {
        const message =
          timerFailureClass === "connect-timeout"
            ? "Timed out establishing the connection"
            : "Timed out waiting for the next chunk";
        return {
          ok: false,
          value: null,
          failureClass: timerFailureClass,
          error: message,
          emittedOutput,
          absolute: false,
        };
      }
      return {
        ok: false,
        value: null,
        failureClass: classifyThrown(err),
        error: errorMessage(err),
        emittedOutput,
        absolute: false,
      };
    }
  }

  private finish(
    state: "TIMED_OUT" | "FAILED" | "CANCELLED",
    failureClass: RequestFailureClass,
    error: string | null
  ): void {
    this.setState({ state, error, failureClass });
    this.report(
      state === "TIMED_OUT" ? "timed_out" : state === "CANCELLED" ? "cancelled" : "failed",
      failureClass,
      this.snapshot.attempt
    );
    this.resolveDone({ ok: false, value: null, snapshot: this.snapshot });
  }

  private report(outcome: RequestLifecycleOutcome, failureClass: RequestFailureClass | null, attempts: number): void {
    const endedAt = Date.now();
    reportRequestLifecycleEvent({
      requestId: this.requestId,
      target: this.target,
      surface: this.surface,
      outcome,
      failureClass,
      attempts: Math.max(1, attempts),
      durationMs: endedAt - this.snapshot.startedAt,
      startedAt: this.snapshot.startedAt,
      endedAt,
    });
  }
}

/**
 * Start a fetch-and-stream (or any async) operation under the Universal
 * Request Lifecycle. Returns a controller with the current state, a
 * cancel() function, and a subscribe-to-state-changes API — wire cancel()
 * to a "Stop" button and subscribe() (or the useRequestLifecycle hook below)
 * to drive UI.
 *
 * Example (SSE-style streaming fetch):
 *
 *   const controller = runRequestLifecycle(async (ctx) => {
 *     const res = await fetch(url, { signal: ctx.signal });
 *     ctx.markConnected();
 *     const reader = res.body!.getReader();
 *     let text = "";
 *     for (;;) {
 *       const { done, value } = await reader.read();
 *       if (done) break;
 *       text += decode(value);
 *       ctx.onChunk();
 *     }
 *     return text;
 *   }, { target: "chat-stream:core", surface: "chat-stream" });
 *
 *   const result = await controller.done; // { ok, value, snapshot }
 */
export function runRequestLifecycle<T>(
  operation: RequestLifecycleOperation<T>,
  options: RequestLifecycleOptions
): RequestLifecycleController<T> {
  return new RequestLifecycleController<T>(operation, options);
}

// ---------------------------------------------------------------------------
// React hook — thin subscribe wrapper, same pattern components already use
// for downloadManager.subscribe() (see EdgeSetupChip.tsx) but packaged so
// new call-sites don't have to hand-roll the useEffect every time.
// ---------------------------------------------------------------------------

export interface UseRequestLifecycleResult {
  snapshot: RequestLifecycleSnapshot | null;
  state: RequestLifecycleState;
  cancel: (reason?: string) => void;
}

const IDLE_SNAPSHOT_STATE: RequestLifecycleState = "IDLE";

/** Subscribes to a controller for the lifetime of the component (or until
 * the controller changes). Pass `null`/`undefined` when no request is in
 * flight yet — the hook reports state "IDLE" and a no-op cancel(). */
export function useRequestLifecycle<T>(
  controller: RequestLifecycleController<T> | null | undefined
): UseRequestLifecycleResult {
  const [snapshot, setSnapshot] = useState<RequestLifecycleSnapshot | null>(
    controller ? controller.getSnapshot() : null
  );

  useEffect(() => {
    if (!controller) {
      setSnapshot(null);
      return undefined;
    }
    setSnapshot(controller.getSnapshot());
    return controller.subscribe((event) => setSnapshot(event.snapshot));
  }, [controller]);

  const cancel = useCallback(
    (reason?: string) => {
      controller?.cancel(reason);
    },
    [controller]
  );

  return { snapshot, state: snapshot?.state ?? IDLE_SNAPSHOT_STATE, cancel };
}
