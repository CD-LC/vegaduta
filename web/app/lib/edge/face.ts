// S-edge companion: on-device face recognition (main-thread facade).
//
// Design: mirrors vision.ts's E-5 architecture exactly (worker lifecycle,
// request/response protocol via postMessage, watchdog timers, download
// manager integration, localStorage cache marker, never-throw contract) —
// see vision.ts's header comment for the full rationale. This module is the
// seam biometric-consent-gated callers (enrollment upload, camera matching)
// route through; the models themselves run ONLY in the dedicated
// faceWorker.ts (E-0b rule 1 — never the main thread).
//
// Contract with callers (mirrors tierRouter's / vision.ts's):
//   - edgeFaceAvailable() answers capability WITHOUT downloading anything.
//   - ensureFaceModel() is the ONLY entry that may start the ~65 MB
//     acquisition, and the consuming surface guarantees it is called from an
//     explicit user gesture behind EDGE_BRANDING.faceRecognitionDownloadDetails
//     consent copy (E-0b rule 2). Importing this module, or calling
//     edgeFaceAvailable(), never moves a byte.
//   - enrollFaceFromDataUrl() / matchFaceFromDataUrl() NEVER throw — every
//     failure is { success: false, reason } and the caller decides what (if
//     anything) happens next. Faces analyzed here never leave the browser —
//     this module never escalates a photo/frame off-device itself (the same
//     "no silent escalation, ever" rule vision.ts's header documents for
//     camera frames).
//
// Download manager integration follows vision.ts's precedent for
// library-owned/manual transfers: the asset is enqueue()d into the shared
// queue (no URL — the manager's engine moves no bytes for the detector; the
// embedder's manual fetch happens inside the worker) and progress is
// mirrored in via reportExternalProgress, so the EdgeSetupChip shows this
// model exactly like every other on-device asset.

import { edgeEnabled } from "./capabilities";
import { downloadManager } from "./downloadManager";
import { EDGE_BRANDING } from "./branding";
import type { FaceWorkerRequest, FaceWorkerResponse } from "./faceWorker";

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Shared-queue asset id (the chip's row key for this model). */
export const FACE_ASSET_ID = "edge-face-recognition";
/** ArcFace ResNet100's output embedding length. */
export const FACE_EMBEDDING_DIMENSIONS = 512;
/** Cosine-similarity floor for a positive match. UNVALIDATED — this repo has
 * ZERO measured real-device face-matching accuracy data (same honesty norm
 * as visionWorker.ts's SmolVLM swap comment), so this is a reasonable
 * starting point for ArcFace-class embeddings, not a proven threshold. Needs
 * real-device measurement (false-accept / false-reject tuning against actual
 * enrolled faces) before being trusted at a security-relevant threshold. */
export const FACE_MATCH_THRESHOLD = 0.45;

/** Honest size estimate for the combined BlazeFace detector (wasm fileset +
 * tflite weights) and the ArcFace int8 ONNX embedder (~63 MB, the bulk of
 * this total) — what the consent copy and the storage headroom check are
 * based on. */
const FACE_MODEL_SIZE_BYTES = 65_000_000;
/** localStorage marker set only after both the detector and embedder fully
 * loaded once on this device. Same semantics (and the same known edge case)
 * as vision.ts's VISION_CACHED_KEY: absence reliably means "never downloaded
 * here"; a stale marker after the browser evicted the cache just makes the
 * next warm-up re-fetch. */
const FACE_CACHED_KEY = "edge.face.cached.mediapipe-arcface";
/** Per-detect-and-embed watchdog — a single-face turn is far lighter than a
 * VLM generation. */
const DETECT_TIMEOUT_MS = 15_000;
/** Load watchdog is idle-based (refreshed on every progress event): a real
 * download legitimately takes minutes, a silent stall should not. */
const LOAD_IDLE_TIMEOUT_MS = 120_000;

type FaceProgress = { loaded: number; total: number; status: string };

export type FaceEnrollResult = { success: true; embedding: number[] } | { success: false; reason: string };

export interface FaceMatchCandidate {
  id: string;
  personName: string;
  embedding: number[];
}

export type FaceMatchResult =
  | { success: true; matched: true; personName: string; similarity: number }
  | { success: true; matched: false }
  | { success: false; reason: string };

type DetectResult = { success: true; embedding: number[] } | { success: false; reason: string };

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

function safeLocalRemove(key: string): void {
  try {
    if (typeof localStorage === "undefined") return;
    localStorage.removeItem(key);
  } catch {
    // Best-effort.
  }
}

/** Was the face model ever fully installed on this device? Same marker
 * semantics as vision.ts's isVisionModelCached (presence is a hint, absence
 * is definitive). */
export function isFaceModelCached(): boolean {
  return safeLocalGet(FACE_CACHED_KEY) === "1";
}

// ---------------------------------------------------------------------------
// Worker lifecycle + request plumbing (mirrors vision.ts exactly)
// ---------------------------------------------------------------------------

interface PendingRequest {
  resolve: (response: FaceWorkerResponse) => void;
  onProgress?: (p: FaceProgress) => void;
  timer: ReturnType<typeof setTimeout> | null;
  timeoutMs: number;
  /** Idle watchdogs (loads) re-arm on progress; fixed ones (detect) don't. */
  idleWatchdog: boolean;
  timeoutReason: string;
}

let worker: Worker | null = null;
let workerModelLoaded = false;
let nextRequestId = 1;
const pending = new Map<number, PendingRequest>();
let loadInFlight: Promise<void> | null = null;
const loadProgressListeners = new Set<(p: FaceProgress) => void>();
/** Detect-and-embed turns are serialized — one at a time keeps the timeout
 * accounting per-call and the worker's memory bounded (same rationale as
 * vision.ts's analysisChain). */
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

function handleWorkerMessage(response: FaceWorkerResponse): void {
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
    worker = new Worker(new URL("./faceWorker.ts", import.meta.url));
    worker.addEventListener("message", (event: MessageEvent) => {
      handleWorkerMessage(event.data as FaceWorkerResponse);
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
  message: FaceWorkerRequest,
  timeoutMs: number,
  timeoutReason: string,
  idleWatchdog: boolean,
  onProgress?: (p: FaceProgress) => void
): Promise<FaceWorkerResponse> {
  const target = getWorker();
  if (!target) {
    return Promise.resolve({
      type: "error",
      requestId: message.requestId,
      reason: "worker-unsupported",
      detail: "Web Workers are not available in this environment",
    });
  }
  return new Promise<FaceWorkerResponse>((resolve) => {
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
// Model load (shared by ensureFaceModel and the detect warm-up path)
// ---------------------------------------------------------------------------

function reportChipProgress(p: FaceProgress): void {
  // Mirror the worker-owned/manual transfer into the shared chip, vision.ts-
  // style. Detector-load pulses carry total=0 (MediaPipe exposes no
  // byte-level hook) and are skipped here on purpose — only the embedder's
  // real byte counts move the bar.
  if (p.status === "downloading" && p.total > 0) {
    downloadManager.reportExternalProgress(FACE_ASSET_ID, EDGE_BRANDING.faceRecognitionModelDisplayName, {
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
    const alreadyCached = isFaceModelCached();
    // E-0b rule 5: refuse politely when storage headroom is short — but only
    // for a first-time acquisition; a cached reload needs no new bytes.
    if (!alreadyCached && !(await downloadManager.hasStorageHeadroomFor(FACE_MODEL_SIZE_BYTES))) {
      downloadManager.reportExternalProgress(FACE_ASSET_ID, EDGE_BRANDING.faceRecognitionModelDisplayName, {
        state: "error",
        error: EDGE_BRANDING.insufficientStorageError,
      });
      throw new Error(EDGE_BRANDING.insufficientStorageError);
    }
    // Enter the shared queue/state machine so the chip shows this download
    // alongside every other on-device asset.
    downloadManager.enqueue({
      id: FACE_ASSET_ID,
      displayName: EDGE_BRANDING.faceRecognitionModelDisplayName,
      totalBytes: FACE_MODEL_SIZE_BYTES,
    });
    const onProgress = (p: FaceProgress) => {
      reportChipProgress(p);
      for (const listener of loadProgressListeners) {
        try {
          listener(p);
        } catch {
          // Best-effort.
        }
      }
    };

    // Detector first (small, no real byte progress), then the embedder (the
    // bulk of the ~65 MB, real byte-level progress) — reported to the caller
    // as one logical load.
    const detectorRequestId = nextRequestId++;
    const detectorResponse = await request(
      { type: "load-detector", requestId: detectorRequestId },
      LOAD_IDLE_TIMEOUT_MS,
      "load-timeout",
      true,
      onProgress
    );
    if (detectorResponse.type !== "loaded") {
      const detail = detectorResponse.type === "error" ? detectorResponse.detail : "unexpected worker reply";
      downloadManager.reportExternalProgress(FACE_ASSET_ID, EDGE_BRANDING.faceRecognitionModelDisplayName, {
        state: "error",
        error: alreadyCached ? "Could not start on-device face recognition" : "Download failed",
      });
      throw new Error(detail || "on-device face detector setup failed");
    }

    const embedderRequestId = nextRequestId++;
    const embedderResponse = await request(
      { type: "load-embedder", requestId: embedderRequestId },
      LOAD_IDLE_TIMEOUT_MS,
      "load-timeout",
      true,
      onProgress
    );
    if (embedderResponse.type !== "loaded") {
      const detail = embedderResponse.type === "error" ? embedderResponse.detail : "unexpected worker reply";
      downloadManager.reportExternalProgress(FACE_ASSET_ID, EDGE_BRANDING.faceRecognitionModelDisplayName, {
        state: "error",
        error: alreadyCached ? "Could not start on-device face recognition" : "Download failed",
      });
      throw new Error(detail || "on-device face embedder setup failed");
    }

    workerModelLoaded = true;
    safeLocalSet(FACE_CACHED_KEY, "1");
    downloadManager.reportExternalProgress(FACE_ASSET_ID, EDGE_BRANDING.faceRecognitionModelDisplayName, {
      state: "ready",
      receivedBytes: FACE_MODEL_SIZE_BYTES,
      totalBytes: FACE_MODEL_SIZE_BYTES,
    });
    // Ask the browser to protect the weights from cache eviction (rule 5).
    void downloadManager.requestPersistentStorage();
  })().finally(() => {
    loadInFlight = null;
  });
  return loadInFlight;
}

// ---------------------------------------------------------------------------
// Public contract (shared API contract — exactly these exports)
// ---------------------------------------------------------------------------

/**
 * Can this device run on-device face recognition at all? True only when the
 * edge flag is on and the environment can execute the models (WebGPU, or the
 * wasm fallback). Answers WITHOUT downloading — or fetching — anything.
 * Mirrors edgeVisionAvailable() in vision.ts exactly.
 */
export async function edgeFaceAvailable(): Promise<boolean> {
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
 * Acquire (or warm up) the on-device face detector + embedder through the
 * shared download manager. MUST only be called from an explicit user action
 * behind the EDGE_BRANDING.faceRecognitionDownloadDetails consent copy — this
 * function never prompts and never auto-runs (E-0b rule 2). Progress streams
 * to the callback AND to the EdgeSetupChip. Rejects with a display-safe Error
 * when the device can't run this feature, storage is short, or the load
 * fails.
 */
export async function ensureFaceModel(
  onProgress?: (p: { loaded: number; total: number; status: string }) => void
): Promise<void> {
  if (!(await edgeFaceAvailable())) {
    throw new Error("On-device face recognition is not available on this device.");
  }
  if (workerModelLoaded) {
    if (onProgress) {
      try {
        onProgress({ loaded: FACE_MODEL_SIZE_BYTES, total: FACE_MODEL_SIZE_BYTES, status: "ready" });
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
        onProgress({ loaded: FACE_MODEL_SIZE_BYTES, total: FACE_MODEL_SIZE_BYTES, status: "ready" });
      } catch {
        // Best-effort.
      }
    }
  } finally {
    if (onProgress) loadProgressListeners.delete(onProgress);
  }
}

async function doDetectAndEmbed(dataUrl: string, requireSingleFace: boolean): Promise<DetectResult> {
  if (!workerModelLoaded) {
    // Warm-up from the local cache (marker checked by the caller). Progress
    // surfaces in the chip as the standard "warming up" state.
    try {
      await runLoad();
    } catch {
      // The model was cached but a reload failed — same recovery action as
      // "never downloaded": the caller re-runs ensureFaceModel() to surface
      // the real error. Kept inside the closed reason vocabulary rather than
      // inventing a new "warmup-failed" string.
      return { success: false, reason: "model-not-ready" };
    }
  }
  const requestId = nextRequestId++;
  const response = await request(
    { type: "detect-and-embed", requestId, dataUrl, requireSingleFace },
    DETECT_TIMEOUT_MS,
    "detection-timeout",
    false
  );
  if (response.type === "detected") {
    return { success: true, embedding: response.embedding };
  }
  if (response.type === "error") {
    return { success: false, reason: response.reason || "detection-failed" };
  }
  return { success: false, reason: "detection-failed" };
}

/**
 * Runs face detection (MediaPipe) then embedding (ArcFace ONNX) on a single
 * enrollment photo. Requires exactly one face — ambiguous which one is "the
 * person" otherwise. NEVER throws.
 */
export async function enrollFaceFromDataUrl(dataUrl: string): Promise<FaceEnrollResult> {
  try {
    if (!edgeEnabled() || typeof window === "undefined") {
      return { success: false, reason: "edge-disabled" };
    }
    if (typeof Worker === "undefined") {
      return { success: false, reason: "worker-unsupported" };
    }
    if (typeof dataUrl !== "string" || !dataUrl.startsWith("data:")) {
      return { success: false, reason: "detection-failed" };
    }
    // No side-effect downloads: enrollment only proceeds when the model is
    // live in the worker or was fully installed on this device before (a
    // cache-hit warm-up, not an acquisition).
    if (!workerModelLoaded && !isFaceModelCached()) {
      return { success: false, reason: "model-not-ready" };
    }
    const run = analysisChain.then((): Promise<DetectResult> => doDetectAndEmbed(dataUrl, true));
    // The chain never rejects (doDetectAndEmbed doesn't throw), but keep it
    // unbreakable anyway — one bad turn must not wedge every later one.
    analysisChain = run.catch((): undefined => undefined);
    const result = await run;
    // strict:false in this project's tsconfig means the discriminated union
    // above only narrows on an explicit === true/false comparison, not a
    // truthy/falsy check (ternary condition here previously failed to
    // narrow `result` in the else branch) - see tierRouter.ts for the same
    // pattern elsewhere in this codebase.
    if (result.success === true) {
      return { success: true, embedding: result.embedding };
    }
    return { success: false, reason: result.reason };
  } catch {
    return { success: false, reason: "detection-failed" };
  }
}

/**
 * Runs face detection+embedding on a camera frame, then cosine-similarity
 * against every candidate, returning the best match if its similarity >=
 * FACE_MATCH_THRESHOLD. An empty candidates array is a normal state (nobody
 * enrolled yet), not an error. If multiple faces are in frame, matches
 * against the largest/most-central detected face only — a scope decision for
 * v1 (a camera turn showing several people still returns at most one match;
 * good enough for now, not a silent limitation — see faceWorker.ts's
 * handleDetectAndEmbed). NEVER throws.
 */
export async function matchFaceFromDataUrl(
  dataUrl: string,
  candidates: FaceMatchCandidate[]
): Promise<FaceMatchResult> {
  try {
    if (!edgeEnabled() || typeof window === "undefined") {
      return { success: false, reason: "edge-disabled" };
    }
    if (typeof Worker === "undefined") {
      return { success: false, reason: "worker-unsupported" };
    }
    if (typeof dataUrl !== "string" || !dataUrl.startsWith("data:")) {
      return { success: false, reason: "detection-failed" };
    }
    if (!workerModelLoaded && !isFaceModelCached()) {
      return { success: false, reason: "model-not-ready" };
    }
    const run = analysisChain.then((): Promise<DetectResult> => doDetectAndEmbed(dataUrl, false));
    analysisChain = run.catch((): undefined => undefined);
    const result = await run;
    // Same strict:false narrowing gotcha as enrollFaceFromDataUrl above -
    // explicit === false, not a truthy/falsy check.
    if (result.success === false) {
      return { success: false, reason: result.reason };
    }
    if (!candidates || candidates.length === 0) {
      return { success: true, matched: false };
    }
    let best: { personName: string; similarity: number } | null = null;
    for (const candidate of candidates) {
      if (!candidate || !Array.isArray(candidate.embedding)) continue;
      const similarity = cosineSimilarity(result.embedding, candidate.embedding);
      if (!best || similarity > best.similarity) {
        best = { personName: candidate.personName, similarity };
      }
    }
    if (best && best.similarity >= FACE_MATCH_THRESHOLD) {
      return { success: true, matched: true, personName: best.personName, similarity: best.similarity };
    }
    return { success: true, matched: false };
  } catch {
    return { success: false, reason: "detection-failed" };
  }
}

// ---------------------------------------------------------------------------
// Name-introduction detection (2026-08-07) — "if I say my name is Alex, it
// should remember my face" — a small, deliberately HIGH-PRECISION heuristic,
// not an LLM call: only the two near-unambiguous trigger phrases ("my name
// is X" / "call me X") are matched. "I'm X"/"I am X" are deliberately
// EXCLUDED from v1 - too prone to false positives ("I'm not sure", "I'm
// trying") that would silently mis-enroll a face under a garbage name like
// "not" or "trying". A small stopword filter is a second safety net for the
// two phrases that ARE matched. Trade-off: lower recall (won't catch every
// natural way of saying your name) for near-zero false-positive risk, which
// matters more here than for an ordinary NLU feature given the biometric
// stakes of a wrong auto-enrollment.
// ---------------------------------------------------------------------------

const NAME_INTRO_PATTERN = /\b(?:my name is|call me)\s+([A-Za-z][A-Za-z'-]{1,20}(?:\s+[A-Za-z][A-Za-z'-]{1,20}){0,2})/i;
const NAME_INTRO_STOPWORDS = new Set([
  "not", "sure", "here", "fine", "good", "great", "happy", "sorry", "trying", "looking",
  "going", "just", "so", "very", "still", "done", "ready", "okay", "ok", "actually",
]);

/**
 * Extracts a candidate person name from "my name is X" / "call me X" style
 * text, or null when no such phrase is present (the normal case for almost
 * every message — this runs on every Show-mode send, so it must be cheap
 * and side-effect-free). Never throws.
 */
export function detectNameIntroduction(text: string | null | undefined): string | null {
  if (!text || typeof text !== "string") return null;
  const match = text.match(NAME_INTRO_PATTERN);
  if (!match) return null;
  const candidate = match[1].trim().replace(/[.,!?;:]+$/, "");
  const firstWord = candidate.split(/\s+/)[0].toLowerCase();
  if (NAME_INTRO_STOPWORDS.has(firstWord)) return null;
  return candidate;
}

/**
 * Standard cosine similarity. Assumes both vectors are already the same
 * length — caller's responsibility; this never silently truncates/pads.
 */
export function cosineSimilarity(a: number[], b: number[]): number {
  let dot = 0;
  let normA = 0;
  let normB = 0;
  for (let i = 0; i < a.length; i++) {
    dot += a[i] * b[i];
    normA += a[i] * a[i];
    normB += b[i] * b[i];
  }
  const denom = Math.sqrt(normA) * Math.sqrt(normB);
  return denom === 0 ? 0 : dot / denom;
}

// ---------------------------------------------------------------------------
// Installed-model management (the /edge console's delete surface)
// ---------------------------------------------------------------------------

/** Dedicated Cache Storage bucket the worker stores the ArcFace ONNX weights
 * under — MUST match faceWorker.ts's EMBEDDER_CACHE_NAME exactly. Duplicated
 * here (no runtime import: the worker file must never reach the main bundle)
 * since Cache Storage is shared across the origin between the worker and the
 * page that spawned it. */
const EMBEDDER_CACHE_NAME = "vegaduta-face-embedder-cache";

/**
 * Delete the on-device face model from this device: terminates a live worker
 * first (releasing the in-memory model), then best-effort evicts the ArcFace
 * embedder's Cache Storage bucket, clears the installed marker, and removes
 * the shared chip's status row. User-initiated only — nothing in the
 * enroll/match path ever calls this. Never rejects.
 */
export async function deleteFaceModel(): Promise<void> {
  // A live worker holds the model in memory — drop it so "deleted" is true
  // immediately, not only after the next page load.
  destroyWorker("deleted", "model deleted by the user");
  try {
    if (typeof caches !== "undefined" && (await caches.has(EMBEDDER_CACHE_NAME))) {
      await caches.delete(EMBEDDER_CACHE_NAME);
    }
  } catch {
    // Best-effort — the marker removal below still stops the warm-up path.
  }
  safeLocalRemove(FACE_CACHED_KEY);
  try {
    await downloadManager.deleteAsset(FACE_ASSET_ID);
  } catch {
    // Never rejects — the marker + worker are already gone above.
  }
}
