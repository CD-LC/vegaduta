// On-device vision (main-thread facade).
//
// Design: docs/LIVE-VISION-SENSE-RESEARCH-2026-08-05.md §3 Option D — a
// SmolVLM-class local VLM analyzing camera/screen frames entirely on-device
// (the FREE-tier row of the vision ladder). This module is the seam the
// CameraPanel routes through; the model itself runs ONLY in the dedicated
// visionWorker.ts (E-0b rule 1 — never the main thread).
//
// Contract with callers (mirrors tierRouter's):
//   - edgeVisionAvailable() answers capability WITHOUT downloading anything.
//   - ensureVisionModel() is the ONLY entry that may start the ~280 MB
//     acquisition, and the consuming panel guarantees it is called from an
//     explicit user gesture behind EDGE_BRANDING.visionDownloadDetails
//     consent copy (E-0b rule 2). Importing this module, or calling
//     edgeVisionAvailable(), never moves a byte.
//   - analyzeFramesLocally() NEVER throws — every failure is
//     { handled: false, reason } and the caller decides what (if anything)
//     goes off-device; this module never escalates frames itself (the
//     research doc's binding "no silent escalation of camera frames
//     off-device, ever").
//
// Download manager integration follows the tier0/tier1 precedent for
// library-owned transfers: the asset is enqueue()d into the shared queue (no
// URL — the manager's engine moves no bytes) and transformers.js's own
// download progress is mirrored in via reportExternalProgress, so the
// EdgeSetupChip shows this model exactly like every other on-device asset.

import { edgeEnabled } from "./capabilities";
import { downloadManager } from "./downloadManager";
import { EDGE_BRANDING } from "./branding";
import type { VisionWorkerRequest, VisionWorkerResponse } from "./visionWorker";

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Shared-queue asset id (the chip's row key for this model). Exported for
 * the management surfaces (/edge console) — install state + delete must key
 * off the same id this module reports progress under. */
export const VISION_ASSET_ID = "edge-vision-smolvlm";
/** Honest size estimate for the SmolVLM2-500M ONNX weights (q4 decoder +
 * vision encoder + fp16 embeddings) — roughly double SmolVLM-256M's, in line
 * with the ~2x parameter count. What the consent copy and the storage
 * headroom check are based on. Exported for the same management surfaces. */
export const VISION_MODEL_SIZE_BYTES = 500_000_000;
/** localStorage marker set only after the model fully loaded once on this
 * device. Same semantics (and same known edge case) as tier1's
 * TIER1_CACHED_KEY_PREFIX: its absence reliably means "never downloaded
 * here"; a stale marker after the browser evicted the cache just makes the
 * next warm-up re-fetch — seconds on a cache hit, honest re-download
 * otherwise. */
const VISION_CACHED_KEY = "edge.vision.cached.smolvlm2-500m-video-instruct";
/** Honest ceiling: kept at 3 across the SmolVLM-256M -> SmolVLM2-500M swap.
 * SmolVLM2 is trained with native video/multi-frame understanding (rather
 * than SmolVLM1's batched-stills workaround), so this ceiling could likely
 * be relaxed - but that also touches buildInstruction's frame-by-frame
 * prompt template and the caller's frame-capture cadence (CameraPanel), so
 * it's left as a flagged follow-up rather than changed opportunistically
 * here alongside the model-id swap. */
const MAX_FRAMES = 3;
/** Per-analysis watchdog — a hang terminates and recreates the worker. */
const ANALYZE_TIMEOUT_MS = 30_000;
/** Load watchdog is idle-based (refreshed on every progress event): a real
 * download legitimately takes minutes, a silent stall should not. */
const LOAD_IDLE_TIMEOUT_MS = 120_000;

type VisionProgress = { loaded: number; total: number; status: string };
type AnalyzeResult = { handled: true; text: string } | { handled: false; reason: string };

// ---------------------------------------------------------------------------
// localStorage helpers (same guarded shape as the sibling edge modules)
// ---------------------------------------------------------------------------

function safeLocalGet(key: string): string | null {
  try {
    if (typeof localStorage === "undefined") return null;
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function safeLocalSet(key: string, value: string): void {
  try {
    if (typeof localStorage === "undefined") return;
    localStorage.setItem(key, value);
  } catch {
    // Best-effort.
  }
}

/** Was the vision model ever fully installed on this device? Same marker
 * semantics as tier1's isTier1ModelCached (presence is a hint, absence is
 * definitive). Exported for the /edge console's installed-models list. */
export function isVisionModelCached(): boolean {
  return safeLocalGet(VISION_CACHED_KEY) === "1";
}

function safeLocalRemove(key: string): void {
  try {
    if (typeof localStorage === "undefined") return;
    localStorage.removeItem(key);
  } catch {
    // Best-effort.
  }
}

// ---------------------------------------------------------------------------
// Worker lifecycle + request plumbing
// ---------------------------------------------------------------------------

interface PendingRequest {
  resolve: (response: VisionWorkerResponse) => void;
  onProgress?: (p: VisionProgress) => void;
  timer: ReturnType<typeof setTimeout> | null;
  timeoutMs: number;
  /** Idle watchdogs (loads) re-arm on progress; fixed ones (analysis) don't. */
  idleWatchdog: boolean;
  timeoutReason: string;
}

let worker: Worker | null = null;
let workerModelLoaded = false;
let nextRequestId = 1;
const pending = new Map<number, PendingRequest>();
let loadInFlight: Promise<void> | null = null;
const loadProgressListeners = new Set<(p: VisionProgress) => void>();
/** Analyses are serialized — one VLM turn at a time keeps the timeout
 * accounting per-call and the worker's memory bounded. */
let analysisChain: Promise<unknown> = Promise.resolve();

function failAllPending(reason: string, detail: string): void {
  for (const [requestId, entry] of pending) {
    if (entry.timer) clearTimeout(entry.timer);
    entry.resolve({ type: "error", requestId, reason, detail });
  }
  pending.clear();
}

/** Tear the worker down (hang / hard error). The cached weights and the
 * localStorage marker survive — the next call recreates the worker and
 * reloads from cache. */
function destroyWorker(reason: string, detail: string): void {
  failAllPending(reason, detail);
  if (worker) {
    try {
      worker.terminate();
    } catch {
      // Best-effort.
    }
  }
  worker = null;
  workerModelLoaded = false;
}

function handleWorkerMessage(response: VisionWorkerResponse): void {
  if (!response || typeof response.requestId !== "number") return;
  const entry = pending.get(response.requestId);
  if (!entry) return;
  if (response.type === "progress") {
    if (entry.idleWatchdog) armTimer(response.requestId, entry); // stall watchdog re-arms
    if (entry.onProgress) {
      try {
        entry.onProgress({ loaded: response.loaded, total: response.total, status: response.status });
      } catch {
        // A broken listener never breaks the pipeline.
      }
    }
    return;
  }
  if (entry.timer) clearTimeout(entry.timer);
  pending.delete(response.requestId);
  entry.resolve(response);
}

function armTimer(requestId: number, entry: PendingRequest): void {
  if (entry.timer) clearTimeout(entry.timer);
  entry.timer = setTimeout(() => {
    pending.delete(requestId);
    entry.resolve({
      type: "error",
      requestId,
      reason: entry.timeoutReason,
      detail: `no response within ${entry.timeoutMs}ms`,
    });
    // A hung worker can't be trusted for the next call either.
    destroyWorker(entry.timeoutReason, "worker terminated after timeout");
  }, entry.timeoutMs);
}

function getWorker(): Worker | null {
  if (worker) return worker;
  if (typeof window === "undefined" || typeof Worker === "undefined") return null;
  try {
    // Next.js/Turbopack-compatible worker pattern: the inline `new URL(...,
    // import.meta.url)` is statically analyzed and bundled as its own chunk.
    worker = new Worker(new URL("./visionWorker.ts", import.meta.url));
    worker.addEventListener("message", (event: MessageEvent) => {
      handleWorkerMessage(event.data as VisionWorkerResponse);
    });
    worker.addEventListener("error", (event: ErrorEvent) => {
      destroyWorker("worker-failed", event?.message || "worker error");
    });
    return worker;
  } catch {
    worker = null;
    return null;
  }
}

/** Post one request and await its terminal response. Never rejects. */
function request(
  message: VisionWorkerRequest,
  timeoutMs: number,
  timeoutReason: string,
  idleWatchdog: boolean,
  onProgress?: (p: VisionProgress) => void
): Promise<VisionWorkerResponse> {
  const target = getWorker();
  if (!target) {
    return Promise.resolve({
      type: "error",
      requestId: message.requestId,
      reason: "worker-unsupported",
      detail: "Web Workers are not available in this environment",
    });
  }
  return new Promise<VisionWorkerResponse>((resolve) => {
    const entry: PendingRequest = {
      resolve,
      onProgress,
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

// ---------------------------------------------------------------------------
// Model load (shared by ensureVisionModel and the analyze warm-up path)
// ---------------------------------------------------------------------------

function reportChipProgress(p: VisionProgress): void {
  // Mirror the worker-owned transfer into the shared chip, tier1-style.
  if (p.status === "downloading" && p.total > 0) {
    downloadManager.reportExternalProgress(VISION_ASSET_ID, EDGE_BRANDING.visionModelDisplayName, {
      state: p.loaded >= p.total ? "verifying" : "downloading",
      receivedBytes: p.loaded,
      totalBytes: p.total,
    });
  }
}

function runLoad(): Promise<void> {
  if (loadInFlight !== null) return loadInFlight;
  if (workerModelLoaded) return Promise.resolve();
  loadInFlight = (async () => {
    const alreadyCached = isVisionModelCached();
    // E-0b rule 5: refuse politely when storage headroom is short — but only
    // for a first-time acquisition; a cached reload needs no new bytes.
    if (!alreadyCached && !(await downloadManager.hasStorageHeadroomFor(VISION_MODEL_SIZE_BYTES))) {
      downloadManager.reportExternalProgress(VISION_ASSET_ID, EDGE_BRANDING.visionModelDisplayName, {
        state: "error",
        error: EDGE_BRANDING.insufficientStorageError,
      });
      throw new Error(EDGE_BRANDING.insufficientStorageError);
    }
    // Enter the shared queue/state machine so the chip shows this download
    // alongside every other on-device asset (no URL — the transfer itself is
    // library-owned inside the worker).
    downloadManager.enqueue({
      id: VISION_ASSET_ID,
      displayName: EDGE_BRANDING.visionModelDisplayName,
      totalBytes: VISION_MODEL_SIZE_BYTES,
    });
    const requestId = nextRequestId++;
    const response = await request(
      { type: "load", requestId },
      LOAD_IDLE_TIMEOUT_MS,
      "load-timeout",
      true,
      (p) => {
        reportChipProgress(p);
        for (const listener of loadProgressListeners) {
          try {
            listener(p);
          } catch {
            // Best-effort.
          }
        }
      }
    );
    if (response.type !== "loaded") {
      const detail = response.type === "error" ? response.detail : "unexpected worker reply";
      downloadManager.reportExternalProgress(VISION_ASSET_ID, EDGE_BRANDING.visionModelDisplayName, {
        state: "error",
        error: alreadyCached ? "Could not start on-device vision" : "Download failed",
      });
      throw new Error(detail || "on-device vision setup failed");
    }
    workerModelLoaded = true;
    safeLocalSet(VISION_CACHED_KEY, "1");
    downloadManager.reportExternalProgress(VISION_ASSET_ID, EDGE_BRANDING.visionModelDisplayName, {
      state: "ready",
      receivedBytes: VISION_MODEL_SIZE_BYTES,
      totalBytes: VISION_MODEL_SIZE_BYTES,
    });
    // Ask the browser to protect the weights from cache eviction (rule 5).
    void downloadManager.requestPersistentStorage();
  })().finally(() => {
    loadInFlight = null;
  });
  return loadInFlight;
}

// ---------------------------------------------------------------------------
// Public contract (shared API contract item 7 — exactly these three exports)
// ---------------------------------------------------------------------------

/**
 * Can this device run on-device vision at all? True only when the edge flag
 * is on and the environment can execute the model (WebGPU, or the wasm
 * fallback). Answers WITHOUT downloading — or fetching — anything.
 */
export async function edgeVisionAvailable(): Promise<boolean> {
  if (!edgeEnabled()) return false;
  if (typeof window === "undefined" || typeof Worker === "undefined") return false;
  try {
    const nav = navigator as any;
    if (nav?.gpu) return true;
    return typeof WebAssembly !== "undefined";
  } catch {
    return false;
  }
}

/**
 * Acquire (or warm up) the on-device vision model through the shared
 * download manager. MUST only be called from an explicit user action behind
 * the EDGE_BRANDING.visionDownloadDetails consent copy — this function never
 * prompts and never auto-runs (E-0b rule 2). Progress streams to the
 * callback AND to the EdgeSetupChip. Rejects with a display-safe Error when
 * the device can't run vision, storage is short, or the load fails.
 */
export async function ensureVisionModel(
  onProgress?: (p: { loaded: number; total: number; status: string }) => void
): Promise<void> {
  if (!(await edgeVisionAvailable())) {
    throw new Error("On-device vision is not available on this device.");
  }
  if (workerModelLoaded) {
    if (onProgress) {
      try {
        onProgress({ loaded: VISION_MODEL_SIZE_BYTES, total: VISION_MODEL_SIZE_BYTES, status: "ready" });
      } catch {
        // Best-effort.
      }
    }
    return;
  }
  if (onProgress) loadProgressListeners.add(onProgress);
  try {
    await runLoad();
    if (onProgress) {
      try {
        onProgress({ loaded: VISION_MODEL_SIZE_BYTES, total: VISION_MODEL_SIZE_BYTES, status: "ready" });
      } catch {
        // Best-effort.
      }
    }
  } finally {
    if (onProgress) loadProgressListeners.delete(onProgress);
  }
}

/**
 * Analyze up to MAX_FRAMES frames fully on-device. NEVER throws and NEVER
 * downloads: without a ready (or previously-installed) model it answers
 * { handled: false, reason: "model-not-ready" } — the caller's consent flow
 * decides what happens next. Reason strings follow tierRouter's kebab-case
 * convention; the caller treats every { handled: false } the same way it
 * treats a hosted fall-through.
 */
export async function analyzeFramesLocally(
  frames: { dataUrl: string }[],
  prompt: string
): Promise<{ handled: true; text: string } | { handled: false; reason: string }> {
  try {
    if (!edgeEnabled() || typeof window === "undefined") {
      return { handled: false, reason: "edge-disabled" };
    }
    if (typeof Worker === "undefined") {
      return { handled: false, reason: "worker-unsupported" };
    }
    const usable = (frames || [])
      .filter((f) => f && typeof f.dataUrl === "string" && f.dataUrl.startsWith("data:"))
      .slice(0, MAX_FRAMES);
    if (usable.length === 0) {
      return { handled: false, reason: "no-frames" };
    }
    // No side-effect downloads: analysis only proceeds when the model is live
    // in the worker or was fully installed on this device before (a cache-hit
    // warm-up, not an acquisition).
    if (!workerModelLoaded && !isVisionModelCached()) {
      return { handled: false, reason: "model-not-ready" };
    }
    const run = analysisChain.then(
      (): Promise<AnalyzeResult> => doAnalyze(usable.map((f) => f.dataUrl), prompt || "")
    );
    // The chain never rejects (doAnalyze doesn't throw), but keep it
    // unbreakable anyway — one bad turn must not wedge every later one.
    analysisChain = run.catch((): undefined => undefined);
    return await run;
  } catch (err) {
    return {
      handled: false,
      reason: "vision-failed",
    };
  }
}

async function doAnalyze(dataUrls: string[], prompt: string): Promise<AnalyzeResult> {
  if (!workerModelLoaded) {
    // Warm-up from the local cache (marker checked by the caller). Progress
    // surfaces in the chip as the standard "warming up" state.
    try {
      await runLoad();
    } catch {
      return { handled: false, reason: "warmup-failed" };
    }
  }
  const requestId = nextRequestId++;
  const response = await request(
    { type: "analyze", requestId, frames: dataUrls, prompt },
    ANALYZE_TIMEOUT_MS,
    "analysis-timeout",
    false
  );
  if (response.type === "result") {
    const text = (response.text || "").trim();
    // An empty reply is a failure, not an answer (tierRouter's rule) — the
    // caller falls through rather than rendering a blank analysis.
    if (!text) return { handled: false, reason: "empty-vision-reply" };
    return { handled: true, text };
  }
  if (response.type === "error") {
    return { handled: false, reason: response.reason || "analysis-failed" };
  }
  return { handled: false, reason: "analysis-failed" };
}

// ---------------------------------------------------------------------------
// Installed-model management (the /edge console's delete surface)
// ---------------------------------------------------------------------------

/** transformers.js's default browser Cache API bucket — where the worker's
 * pipeline stores the SmolVLM weights. Verified against the installed
 * @huggingface/transformers package; re-verify on upgrades. */
const TRANSFORMERS_CACHE_NAME = "transformers-cache";

/**
 * Delete the on-device vision model from this device: terminates a live
 * worker first (releasing the in-memory model), then best-effort evicts the
 * SmolVLM entries from transformers.js's cache bucket, clears the installed
 * marker, and removes the shared chip's status row. User-initiated only —
 * nothing in the analysis path ever calls this. Never rejects.
 */
export async function deleteVisionModel(): Promise<void> {
  // A live worker holds the model in memory — drop it so "deleted" is true
  // immediately, not only after the next page load.
  destroyWorker("deleted", "model deleted by the user");
  try {
    if (typeof caches !== "undefined" && (await caches.has(TRANSFORMERS_CACHE_NAME))) {
      const cache = await caches.open(TRANSFORMERS_CACHE_NAME);
      for (const req of await cache.keys()) {
        if (/smolvlm/i.test(req.url)) await cache.delete(req);
      }
    }
  } catch {
    // Best-effort — the marker removal below still stops the warm-up path.
  }
  safeLocalRemove(VISION_CACHED_KEY);
  await downloadManager.deleteAsset(VISION_ASSET_ID);
}
