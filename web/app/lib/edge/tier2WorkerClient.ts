// Tier 2's worker plumbing (split out of tier2.ts): one module-level Web Worker,
// typed request/response correlation with idle and fixed watchdogs, and the
// test seam that swaps in a fake worker. Same shape as whisper.ts / indexer.ts.

import type { Tier2WorkerRequest, Tier2WorkerResponse, Tier2ErrorCode } from "./tier2Shared";

interface PendingRequest {
  settle: (response: Tier2WorkerResponse) => void;
  onToken?: (delta: string) => void;
  onProgress?: (p: { loaded: number; total: number; status: string }) => void;
  timer: ReturnType<typeof setTimeout> | null;
  timeoutMs: number;
  /** Idle watchdogs re-arm on progress/token; fixed ones do not. */
  idleWatchdog: boolean;
  timeoutReason: Tier2ErrorCode;
}

let worker: Worker | null = null;
let nextRequestId = 1;
const pending = new Map<number, PendingRequest>();

/** Worker-side facts tier2.ts reads and records: whether the model is loaded in
 * the worker, whether its runtime streamed, and the cache-only load in flight. */
export const tier2WorkerState: {
  modelLoaded: boolean;
  lastObservedStreaming: boolean;
  loadInFlight: Promise<void> | null;
} = { modelLoaded: false, lastObservedStreaming: true, loadInFlight: null };

/** A fresh request id for the next worker message. */
export function nextTier2RequestId(): number {
  return nextRequestId++;
}

/** Test seam (indexer.ts's `overrides` precedent): jsdom has no Worker, so
 * the unit tests inject a fake one. Production never calls this. */
type Tier2WorkerFactory = () => Worker | null;
let workerFactoryOverride: Tier2WorkerFactory | null = null;

export function __setTier2WorkerFactoryForTests(factory: Tier2WorkerFactory | null): void {
  workerFactoryOverride = factory;
  destroyTier2Worker("worker-failed", "test worker factory replaced");
}

function failAllPending(reason: Tier2ErrorCode, detail: string): void {
  for (const [requestId, entry] of pending) {
    if (entry.timer) clearTimeout(entry.timer);
    entry.settle({ type: "error", requestId, reason, detail });
  }
  pending.clear();
}

/** Terminating IS the cancel: it aborts whatever fetches or generation the
 * worker had in flight (the same mechanism indexer.ts relies on). */
export function destroyTier2Worker(reason: Tier2ErrorCode, detail: string): void {
  failAllPending(reason, detail);
  if (worker) {
    try {
      worker.terminate();
    } catch {
      // Best-effort.
    }
  }
  worker = null;
  tier2WorkerState.modelLoaded = false;
  tier2WorkerState.loadInFlight = null;
}

function armTimer(requestId: number, entry: PendingRequest): void {
  if (entry.timer) clearTimeout(entry.timer);
  entry.timer = setTimeout(() => {
    pending.delete(requestId);
    entry.settle({
      type: "error",
      requestId,
      reason: entry.timeoutReason,
      detail: `no response within ${entry.timeoutMs}ms`,
    });
    // A stuck worker is not trusted again — the next call builds a fresh one.
    destroyTier2Worker(entry.timeoutReason, "worker terminated after timeout");
  }, entry.timeoutMs);
}

function handleWorkerMessage(response: Tier2WorkerResponse): void {
  if (!response || typeof response.requestId !== "number") return;
  const entry = pending.get(response.requestId);
  if (!entry) return; // an abandoned (aborted) turn — drop its late replies
  if (response.type === "progress") {
    if (entry.idleWatchdog) armTimer(response.requestId, entry);
    if (entry.onProgress) {
      try {
        entry.onProgress({ loaded: response.loaded, total: response.total, status: response.status });
      } catch {
        // A broken listener never breaks the pipeline.
      }
    }
    return;
  }
  if (response.type === "token") {
    // Every token is proof of life — re-arm the idle watchdog.
    if (entry.idleWatchdog) armTimer(response.requestId, entry);
    if (entry.onToken) {
      try {
        entry.onToken(response.delta);
      } catch {
        // A broken listener never breaks the generation.
      }
    }
    return;
  }
  if (entry.timer) clearTimeout(entry.timer);
  pending.delete(response.requestId);
  entry.settle(response);
}

function getWorker(): Worker | null {
  if (worker) return worker;
  if (workerFactoryOverride) {
    worker = workerFactoryOverride();
  } else {
    if (typeof window === "undefined" || typeof Worker === "undefined") return null;
    try {
      // The worker is its own file (tier2Worker.ts) with no runtime import of
      // this module — that separation is load-bearing for `next build`, see
      // its header.
      worker = new Worker(new URL("./tier2Worker.ts", import.meta.url), { type: "module" });
    } catch {
      worker = null;
      return null;
    }
  }
  if (!worker) return null;
  worker.addEventListener("message", (event: MessageEvent) => {
    handleWorkerMessage(event.data as Tier2WorkerResponse);
  });
  worker.addEventListener("error", (event: ErrorEvent) => {
    destroyTier2Worker("worker-failed", event?.message || "worker error");
  });
  return worker;
}

/** Post one request and await its terminal response. NEVER rejects — every
 * failure arrives as a typed error response, exactly like whisper.ts's. */
export function request(
  message: Tier2WorkerRequest,
  timeoutMs: number,
  timeoutReason: Tier2ErrorCode,
  idleWatchdog: boolean,
  handlers?: {
    onToken?: (delta: string) => void;
    onProgress?: (p: { loaded: number; total: number; status: string }) => void;
  }
): Promise<Tier2WorkerResponse> {
  const target = getWorker();
  if (!target) {
    return Promise.resolve({
      type: "error",
      requestId: message.requestId,
      reason: "unsupported",
      detail: "Web Workers are not available in this environment",
    });
  }
  return new Promise<Tier2WorkerResponse>((resolve) => {
    const entry: PendingRequest = {
      settle: resolve,
      onToken: handlers?.onToken,
      onProgress: handlers?.onProgress,
      timer: null,
      timeoutMs,
      idleWatchdog,
      timeoutReason,
    };
    pending.set(message.requestId, entry);
    armTimer(message.requestId, entry);
    try {
      target.postMessage(message);
    } catch (err) {
      if (entry.timer) clearTimeout(entry.timer);
      pending.delete(message.requestId);
      resolve({
        type: "error",
        requestId: message.requestId,
        reason: "worker-failed",
        detail: err instanceof Error ? err.message : String(err),
      });
    }
  });
}

/** Stop waiting for one request and ask the worker to stop it at its next
 * token step; its eventual reply is dropped (handleWorkerMessage ignores
 * unknown ids). The worker is not terminated, so the loaded pipeline survives. */
export function abandonTier2Request(requestId: number, reason: Tier2ErrorCode, detail: string): void {
  const entry = pending.get(requestId);
  if (entry) {
    if (entry.timer) clearTimeout(entry.timer);
    pending.delete(requestId);
    entry.settle({ type: "error", requestId, reason, detail });
  }
  try {
    worker?.postMessage({ type: "interrupt", requestId } satisfies Tier2WorkerRequest);
  } catch {
    // Best-effort.
  }
}
