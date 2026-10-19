// On-device speech-to-text (main-thread facade).
//
// Design: docs/EDGE-CAPABILITIES-IMPLEMENTATION-PLAN-2026-08-04.md §E-4 —
// "Local STT: whisper-tiny/base via transformers.js. Integration point is
// surgical: CallPanel.tsx push-to-talk already produces a Blob; on edge
// tiers, transcribe locally and skip the server STT leg." This module is
// that surgical seam: it owns nothing about WHERE the blob came from or
// what happens to the text next — callers decide that. The model itself
// runs ONLY in the dedicated whisperWorker.ts (E-0b rule 1 — never the main
// thread); this file's own work (Blob -> 16kHz mono Float32 PCM decode) is a
// few milliseconds for a push-to-talk utterance, not a main-thread hazard,
// and OfflineAudioContext is not reliably available inside Worker scope yet
// across browsers, so it stays here rather than in the worker.
//
// Contract with callers (mirrors vision.ts's):
//   - edgeSttAvailable() answers capability WITHOUT downloading anything.
//   - ensureSttModel() is the ONLY entry that may start the acquisition, and
//     the consuming surface guarantees it is called from an explicit user
//     gesture behind EDGE_BRANDING.sttDownloadDetails consent copy (E-0b
//     rule 2). Importing this module, or calling edgeSttAvailable(), never
//     moves a byte.
//   - transcribeAudioLocally() NEVER throws — every failure is
//     { handled: false, reason } and the caller decides what (if anything)
//     goes off-device (typically: fall through to the existing server STT
//     leg unchanged). This module never escalates audio itself.
//   - Chunked, not streaming: Whisper answers a full transcript per call,
//     internally windowed into <=30s slices for anything longer (honest
//     about what the model actually does — no fake word-by-word streaming
//     UI should be built on top of this).
//
// Download manager integration follows the vision precedent for
// library-owned transfers: the asset is enqueue()d into the shared queue (no
// URL — the manager's own transfer engine moves no bytes for this asset) and
// transformers.js's own download progress is mirrored in via
// reportExternalProgress, so the EdgeSetupChip shows this model exactly like
// every other on-device asset.

import { edgeEnabled } from "../capabilities";
import { downloadManager } from "../downloadManager";
import { EDGE_BRANDING } from "../branding";
import type { WhisperWorkerRequest, WhisperWorkerResponse } from "./whisperWorker";

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Shared-queue asset id (the chip's row key for this model). */
export const STT_ASSET_ID = "edge-stt-whisper";
/** Honest size estimate for whisper-tiny.en's quantized ONNX weights
 * (encoder + decoder, q8/fp16 mix) — a few tens of MB, not the "1-2GB" scale
 * of the tier-1 text models. Used for the consent copy and the storage
 * headroom check. */
export const STT_MODEL_SIZE_BYTES = 45_000_000;
/** localStorage marker set only after the model fully loaded once on this
 * device. Same semantics as vision's VISION_CACHED_KEY. */
const STT_CACHED_KEY = "edge.stt.cached.whisper-tiny-en";
/** Honest ceiling: reject rather than silently truncate audio far outside
 * what a push-to-talk utterance or a short recording should ever be. */
const MAX_AUDIO_SECONDS = 300;
/** Per-transcription watchdog. */
const TRANSCRIBE_TIMEOUT_MS = 60_000;
/** Load watchdog is idle-based (refreshed on every progress event). */
const LOAD_IDLE_TIMEOUT_MS = 120_000;

type SttProgress = { loaded: number; total: number; status: string };
type TranscribeResult = { handled: true; text: string } | { handled: false; reason: string };

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

/** Was the STT model ever fully installed on this device? */
export function isSttModelCached(): boolean {
  return safeLocalGet(STT_CACHED_KEY) === "1";
}

// ---------------------------------------------------------------------------
// Worker lifecycle + request plumbing (same shape as vision.ts)
// ---------------------------------------------------------------------------

interface PendingRequest {
  resolve: (response: WhisperWorkerResponse) => void;
  onProgress?: (p: SttProgress) => void;
  timer: ReturnType<typeof setTimeout> | null;
  timeoutMs: number;
  idleWatchdog: boolean;
  timeoutReason: string;
}

let worker: Worker | null = null;
let workerModelLoaded = false;
let nextRequestId = 1;
const pending = new Map<number, PendingRequest>();
let loadInFlight: Promise<void> | null = null;
const loadProgressListeners = new Set<(p: SttProgress) => void>();
/** Transcriptions are serialized — one Whisper turn at a time keeps the
 * timeout accounting per-call and the worker's memory bounded. */
let transcribeChain: Promise<unknown> = Promise.resolve();

function failAllPending(reason: string, detail: string): void {
  for (const [requestId, entry] of pending) {
    if (entry.timer) clearTimeout(entry.timer);
    entry.resolve({ type: "error", requestId, reason, detail });
  }
  pending.clear();
}

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

function handleWorkerMessage(response: WhisperWorkerResponse): void {
  if (!response || typeof response.requestId !== "number") return;
  const entry = pending.get(response.requestId);
  if (!entry) return;
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
    destroyWorker(entry.timeoutReason, "worker terminated after timeout");
  }, entry.timeoutMs);
}

function getWorker(): Worker | null {
  if (worker) return worker;
  if (typeof window === "undefined" || typeof Worker === "undefined") return null;
  try {
    worker = new Worker(new URL("./whisperWorker.ts", import.meta.url));
    worker.addEventListener("message", (event: MessageEvent) => {
      handleWorkerMessage(event.data as WhisperWorkerResponse);
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

/** Post one request and await its terminal response. Never rejects. Audio
 * payloads transfer their buffer (zero-copy) rather than structured-clone. */
function request(
  message: WhisperWorkerRequest,
  timeoutMs: number,
  timeoutReason: string,
  idleWatchdog: boolean,
  onProgress?: (p: SttProgress) => void
): Promise<WhisperWorkerResponse> {
  const target = getWorker();
  if (!target) {
    return Promise.resolve({
      type: "error",
      requestId: message.requestId,
      reason: "worker-unsupported",
      detail: "Web Workers are not available in this environment",
    });
  }
  return new Promise<WhisperWorkerResponse>((resolve) => {
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
      const transfer = message.type === "transcribe" ? [message.audio.buffer] : undefined;
      target.postMessage(message, transfer as Transferable[] | undefined);
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
// Model load (shared by ensureSttModel and the transcribe warm-up path)
// ---------------------------------------------------------------------------

function reportChipProgress(p: SttProgress): void {
  if (p.status === "downloading" && p.total > 0) {
    downloadManager.reportExternalProgress(STT_ASSET_ID, EDGE_BRANDING.sttModelDisplayName, {
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
    const alreadyCached = isSttModelCached();
    if (!alreadyCached && !(await downloadManager.hasStorageHeadroomFor(STT_MODEL_SIZE_BYTES))) {
      downloadManager.reportExternalProgress(STT_ASSET_ID, EDGE_BRANDING.sttModelDisplayName, {
        state: "error",
        error: EDGE_BRANDING.insufficientStorageError,
      });
      throw new Error(EDGE_BRANDING.insufficientStorageError);
    }
    downloadManager.enqueue({
      id: STT_ASSET_ID,
      displayName: EDGE_BRANDING.sttModelDisplayName,
      totalBytes: STT_MODEL_SIZE_BYTES,
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
      downloadManager.reportExternalProgress(STT_ASSET_ID, EDGE_BRANDING.sttModelDisplayName, {
        state: "error",
        error: alreadyCached ? "Could not start on-device speech-to-text" : "Download failed",
      });
      throw new Error(detail || "on-device speech-to-text setup failed");
    }
    workerModelLoaded = true;
    safeLocalSet(STT_CACHED_KEY, "1");
    downloadManager.reportExternalProgress(STT_ASSET_ID, EDGE_BRANDING.sttModelDisplayName, {
      state: "ready",
      receivedBytes: STT_MODEL_SIZE_BYTES,
      totalBytes: STT_MODEL_SIZE_BYTES,
    });
    void downloadManager.requestPersistentStorage();
  })().finally(() => {
    loadInFlight = null;
  });
  return loadInFlight;
}

// ---------------------------------------------------------------------------
// Audio decode (main thread — see header comment for why)
// ---------------------------------------------------------------------------

/** Blob (any format the browser's decoder understands — WebM/Opus from
 * MediaRecorder, WAV, etc.) -> mono Float32 PCM at 16kHz, the shape Whisper's
 * feature extractor expects. Downmix to mono happens automatically: the Web
 * Audio spec sums/averages channels when a stereo source feeds a mono
 * destination. Returns null on any decode failure (unsupported codec,
 * corrupt blob) rather than throwing — callers treat that as "can't handle
 * this locally", not a hard error. */
async function decodeToMono16k(blob: Blob): Promise<Float32Array | null> {
  try {
    if (typeof window === "undefined") return null;
    const AudioCtxCtor: typeof AudioContext | undefined =
      window.AudioContext || (window as any).webkitAudioContext;
    if (!AudioCtxCtor) return null;
    const arrayBuffer = await blob.arrayBuffer();
    const scratchCtx = new AudioCtxCtor();
    let decoded: AudioBuffer;
    try {
      decoded = await scratchCtx.decodeAudioData(arrayBuffer.slice(0));
    } finally {
      try {
        await scratchCtx.close();
      } catch {
        // Best-effort.
      }
    }
    const targetSampleRate = 16_000;
    const targetLength = Math.max(1, Math.ceil(decoded.duration * targetSampleRate));
    const OfflineCtor: typeof OfflineAudioContext | undefined =
      (window as any).OfflineAudioContext || (window as any).webkitOfflineAudioContext;
    if (!OfflineCtor) return null;
    const offline = new OfflineCtor(1, targetLength, targetSampleRate);
    const source = offline.createBufferSource();
    source.buffer = decoded;
    source.connect(offline.destination);
    source.start(0);
    const rendered = await offline.startRendering();
    return rendered.getChannelData(0).slice();
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------
// Public contract
// ---------------------------------------------------------------------------

/** Can this device run on-device STT at all? True only when the edge flag is
 * on and the environment can execute the model. Answers WITHOUT downloading
 * — or fetching — anything. */
export async function edgeSttAvailable(): Promise<boolean> {
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
 * Acquire (or warm up) the on-device STT model through the shared download
 * manager. MUST only be called from an explicit user action behind the
 * EDGE_BRANDING.sttDownloadDetails consent copy — this function never
 * prompts and never auto-runs (E-0b rule 2). Progress streams to the
 * callback AND to the EdgeSetupChip. Rejects with a display-safe Error when
 * the device can't run STT, storage is short, or the load fails.
 */
export async function ensureSttModel(
  onProgress?: (p: { loaded: number; total: number; status: string }) => void
): Promise<void> {
  if (!(await edgeSttAvailable())) {
    throw new Error("On-device speech-to-text is not available on this device.");
  }
  if (workerModelLoaded) {
    if (onProgress) {
      try {
        onProgress({ loaded: STT_MODEL_SIZE_BYTES, total: STT_MODEL_SIZE_BYTES, status: "ready" });
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
        onProgress({ loaded: STT_MODEL_SIZE_BYTES, total: STT_MODEL_SIZE_BYTES, status: "ready" });
      } catch {
        // Best-effort.
      }
    }
  } finally {
    if (onProgress) loadProgressListeners.delete(onProgress);
  }
}

/**
 * Transcribe an audio Blob fully on-device. NEVER throws and NEVER
 * downloads: without a ready (or previously-installed) model it answers
 * { handled: false, reason: "model-not-ready" } — the caller's fallback
 * (typically the existing server STT leg) decides what happens next.
 * Chunked, not streaming (see header comment) — resolves once with the full
 * transcript.
 */
export async function transcribeAudioLocally(
  blob: Blob,
  options?: { language?: string }
): Promise<TranscribeResult> {
  try {
    if (!edgeEnabled() || typeof window === "undefined") {
      return { handled: false, reason: "edge-disabled" };
    }
    if (typeof Worker === "undefined") {
      return { handled: false, reason: "worker-unsupported" };
    }
    if (!blob || blob.size === 0) {
      return { handled: false, reason: "no-audio" };
    }
    if (!workerModelLoaded && !isSttModelCached()) {
      return { handled: false, reason: "model-not-ready" };
    }
    const samples = await decodeToMono16k(blob);
    if (!samples) {
      return { handled: false, reason: "decode-failed" };
    }
    if (samples.length / 16_000 > MAX_AUDIO_SECONDS) {
      return { handled: false, reason: "audio-too-long" };
    }
    const run = transcribeChain.then(
      (): Promise<TranscribeResult> => doTranscribe(samples, options?.language)
    );
    transcribeChain = run.catch((): undefined => undefined);
    return await run;
  } catch {
    return { handled: false, reason: "stt-failed" };
  }
}

async function doTranscribe(samples: Float32Array, language: string | undefined): Promise<TranscribeResult> {
  if (!workerModelLoaded) {
    try {
      await runLoad();
    } catch {
      return { handled: false, reason: "warmup-failed" };
    }
  }
  const requestId = nextRequestId++;
  const response = await request(
    { type: "transcribe", requestId, audio: samples, language },
    TRANSCRIBE_TIMEOUT_MS,
    "transcribe-timeout",
    false
  );
  if (response.type === "result") {
    const text = (response.text || "").trim();
    if (!text) return { handled: false, reason: "empty-transcript" };
    return { handled: true, text };
  }
  if (response.type === "error") {
    return { handled: false, reason: response.reason || "transcribe-failed" };
  }
  return { handled: false, reason: "transcribe-failed" };
}

// ---------------------------------------------------------------------------
// Installed-model management (the /edge console's delete surface)
// ---------------------------------------------------------------------------

/** transformers.js's default browser Cache API bucket — same bucket vision
 * uses, since it's a library-wide constant, not per-model. */
const TRANSFORMERS_CACHE_NAME = "transformers-cache";

/**
 * Delete the on-device STT model from this device: terminates a live worker
 * first, then best-effort evicts the whisper entries from transformers.js's
 * cache bucket, clears the installed marker, and removes the shared chip's
 * status row. User-initiated only — nothing in the transcribe path ever
 * calls this. Never rejects.
 */
export async function deleteSttModel(): Promise<void> {
  destroyWorker("deleted", "model deleted by the user");
  try {
    if (typeof caches !== "undefined" && (await caches.has(TRANSFORMERS_CACHE_NAME))) {
      const cache = await caches.open(TRANSFORMERS_CACHE_NAME);
      for (const req of await cache.keys()) {
        if (/whisper/i.test(req.url)) await cache.delete(req);
      }
    }
  } catch {
    // Best-effort.
  }
  safeLocalRemove(STT_CACHED_KEY);
  await downloadManager.deleteAsset(STT_ASSET_ID);
}
