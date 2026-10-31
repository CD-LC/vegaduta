// On-device text-to-audio (main-thread facade).
//
// MusicGen-small via transformers.js in audiogenWorker.ts - short music/SFX
// clips generated entirely in the page. Same contract family as
// imagegen.ts / whisper.ts / kokoro.ts:
//
//   - edgeAudioGenAvailable() answers capability WITHOUT downloading anything.
//   - ensureAudioGenModel() is the ONLY entry that may start the ~1.1 GB
//     acquisition, called from an explicit user gesture behind
//     EDGE_BRANDING.audioGenDownloadDetails consent copy (E-0b rule 2).
//   - generateAudioLocally() NEVER throws; without a ready model it answers
//     { handled: false, reason: "model-not-ready" } so the tool's honest
//     refusal can name what the person needs to turn on.

import { edgeEnabled } from "./capabilities";
import { downloadManager } from "./downloadManager";
import { EDGE_BRANDING } from "./branding";
import { addMediaArtifact, type MediaArtifact } from "./mediaRegistry";
import type { AudioGenWorkerRequest, AudioGenWorkerResponse } from "./audiogenWorker";

export const AUDIO_GEN_ASSET_ID = "edge-audiogen-musicgen";
/** MusicGen-small ONNX per runtime path: ~1.13 GB fp16 (WebGPU + shader-f16),
 * ~570 MB q4 (WebGPU without it), ~600 MB q8 (wasm). The fp32 files are NOT
 * usable in a browser — the 1.7 GB decoder alone can't session-create in the
 * 4 GB wasm heap. Largest real path is fp16; honest number for the consent
 * copy and storage-headroom check. */
export const AUDIO_GEN_MODEL_SIZE_BYTES = 1_200_000_000;
const AUDIO_GEN_CACHED_KEY = "edge.audiogen.cached.musicgen-small";
const GENERATE_TIMEOUT_MS = 300_000;
const LOAD_IDLE_TIMEOUT_MS = 300_000;
const MAX_PROMPT_CHARS = 500;

export type AudioGenResult =
  { handled: true; blob: Blob; artifact: MediaArtifact; durationSeconds: number } | { handled: false; reason: string };

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

export function isAudioGenModelCached(): boolean {
  return safeLocalGet(AUDIO_GEN_CACHED_KEY) === "1";
}

// ---------------------------------------------------------------------------
// Capability gate
// ---------------------------------------------------------------------------

/**
 * Audio gen runs on wasm too, but ~600 MB of weights plus a wasm inference loop
 * on an 4GB laptop is a worse answer than honest refusal. Gate on WebGPU OR on
 * the honest "8GB+ device" path - either way it's a device question, not a
 * network one.
 */
export async function edgeAudioGenAvailable(): Promise<boolean> {
  try {
    if (!edgeEnabled()) return false;
    if (typeof window === "undefined") return false;
    const hasWebGpu = !!("gpu" in navigator && (navigator as Navigator & { gpu?: unknown }).gpu);
    if (hasWebGpu) return true;
    const mem = (navigator as Navigator & { deviceMemory?: number }).deviceMemory;
    return typeof mem === "number" && mem >= 8;
  } catch {
    return false;
  }
}

// ---------------------------------------------------------------------------
// Worker lifecycle + request plumbing (same shape as imagegen.ts / whisper.ts)
// ---------------------------------------------------------------------------

interface PendingRequest {
  resolve: (response: AudioGenWorkerResponse) => void;
  onProgress?: (p: { loaded: number; total: number; status: string }) => void;
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
let generateChain: Promise<unknown> = Promise.resolve();

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

function handleWorkerMessage(response: AudioGenWorkerResponse): void {
  if (!response || typeof response.requestId !== "number") return;
  const entry = pending.get(response.requestId);
  if (!entry) return;
  if (response.type === "progress") {
    if (entry.idleWatchdog) armTimer(response.requestId, entry);
    if (entry.onProgress) {
      try {
        entry.onProgress({ loaded: response.loaded, total: response.total, status: response.status });
      } catch {
        // Best-effort.
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
    worker = new Worker(new URL("./audiogenWorker.ts", import.meta.url));
    worker.addEventListener("message", (event: MessageEvent) => {
      handleWorkerMessage(event.data as AudioGenWorkerResponse);
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

function request(
  message: AudioGenWorkerRequest,
  timeoutMs: number,
  timeoutReason: string,
  idleWatchdog: boolean,
  onProgress?: (p: { loaded: number; total: number; status: string }) => void
): Promise<AudioGenWorkerResponse> {
  const target = getWorker();
  if (!target) {
    return Promise.resolve({
      type: "error",
      requestId: message.requestId,
      reason: "worker-unsupported",
      detail: "Web Workers are not available in this environment",
    });
  }
  return new Promise<AudioGenWorkerResponse>((resolve) => {
    const entry: PendingRequest = { resolve, onProgress, timer: null, timeoutMs, idleWatchdog, timeoutReason };
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
// Model load (consent-gated)
// ---------------------------------------------------------------------------

function reportChipProgress(p: { loaded: number; total: number; status: string }): void {
  if (p.status === "downloading" && p.total > 0) {
    downloadManager.reportExternalProgress(AUDIO_GEN_ASSET_ID, EDGE_BRANDING.audioGenModelDisplayName, {
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
    const alreadyCached = isAudioGenModelCached();
    if (!alreadyCached && !(await downloadManager.hasStorageHeadroomFor(AUDIO_GEN_MODEL_SIZE_BYTES))) {
      downloadManager.reportExternalProgress(AUDIO_GEN_ASSET_ID, EDGE_BRANDING.audioGenModelDisplayName, {
        state: "error",
        error: EDGE_BRANDING.insufficientStorageError,
      });
      throw new Error(EDGE_BRANDING.insufficientStorageError);
    }
    downloadManager.enqueue({
      id: AUDIO_GEN_ASSET_ID,
      displayName: EDGE_BRANDING.audioGenModelDisplayName,
      totalBytes: AUDIO_GEN_MODEL_SIZE_BYTES,
    });
    // Session-create failures ("Can't create a session") most often mean a
    // truncated/corrupt .onnx survived an interrupted download in the shared
    // transformers.js cache - the weights stay cached even after "delete"
    // (see deleteAudioGenModel), so without eviction the same bad bytes fail
    // forever. Evict the musicgen entries once and retry the load clean.
    let requestId = nextRequestId++;
    let response = await request({ type: "load", requestId }, LOAD_IDLE_TIMEOUT_MS, "load-timeout", true, (p) =>
      reportChipProgress(p)
    );
    if (response.type === "error" && /create a session|InitializeState|protobuf/i.test(response.detail || "")) {
      await evictAudioGenCache();
      requestId = nextRequestId++;
      response = await request({ type: "load", requestId }, LOAD_IDLE_TIMEOUT_MS, "load-timeout", true, (p) =>
        reportChipProgress(p)
      );
    }
    if (response.type !== "loaded") {
      const detail = response.type === "error" ? response.detail : "unexpected worker reply";
      const label = alreadyCached ? "Could not start on-device audio generation" : "Download failed";
      downloadManager.reportExternalProgress(AUDIO_GEN_ASSET_ID, EDGE_BRANDING.audioGenModelDisplayName, {
        state: "error",
        error: detail ? `${label}: ${String(detail).slice(0, 160)}` : label,
      });
      throw new Error(detail || "on-device audio generation setup failed");
    }
    workerModelLoaded = true;
    safeLocalSet(AUDIO_GEN_CACHED_KEY, "1");
    downloadManager.reportExternalProgress(AUDIO_GEN_ASSET_ID, EDGE_BRANDING.audioGenModelDisplayName, {
      state: "ready",
      receivedBytes: AUDIO_GEN_MODEL_SIZE_BYTES,
      totalBytes: AUDIO_GEN_MODEL_SIZE_BYTES,
    });
    void downloadManager.requestPersistentStorage();
  })().finally(() => {
    loadInFlight = null;
  });
  return loadInFlight;
}

// ---------------------------------------------------------------------------
// Public surface
// ---------------------------------------------------------------------------

/**
 * Consent-gated model acquisition. Called ONLY from an explicit user gesture
 * (the Edge console's download row), never from a tool handler.
 */
export async function ensureAudioGenModel(): Promise<void> {
  await runLoad();
}

/**
 * Generate one audio clip from a text prompt (MusicGen). Never throws; never
 * downloads. The artifact lands in the media registry.
 */
export async function generateAudioLocally(prompt: string, durationSeconds?: number): Promise<AudioGenResult> {
  const trimmed = (prompt || "").trim();
  if (!trimmed) return { handled: false, reason: "empty-prompt" };
  if (trimmed.length > MAX_PROMPT_CHARS) return { handled: false, reason: "prompt-too-long" };
  if (!(await edgeAudioGenAvailable())) return { handled: false, reason: "unsupported" };
  // No side-effect downloads: generation only proceeds when the model is live
  // in the worker or was fully installed on this device before (a cache-hit
  // warm-up, not an acquisition) - same contract vision.ts's analyzeFramesLocally
  // already follows.
  if (!workerModelLoaded && !isAudioGenModelCached()) {
    return { handled: false, reason: "model-not-ready" };
  }

  const requestId = nextRequestId++;
  const run = generateChain.then(async (): Promise<AudioGenResult> => {
    if (!workerModelLoaded) {
      // Warm-up from the local cache (marker checked above): the weights are
      // already on this device, so runLoad moves no bytes and the no-download
      // consent rule is not implicated.
      try {
        await runLoad();
      } catch {
        return { handled: false, reason: "warmup-failed" };
      }
    }
    const response = await request(
      { type: "generate", requestId, prompt: trimmed, durationSeconds },
      GENERATE_TIMEOUT_MS,
      "generate-timeout",
      false
    );
    if (response.type === "result") {
      const blob = new Blob([response.wav], { type: "audio/wav" });
      const artifact = addMediaArtifact({
        kind: "audio",
        mime: "audio/wav",
        blob,
        description: trimmed,
        durationSeconds: response.durationSeconds,
      });
      return { handled: true, blob, artifact, durationSeconds: response.durationSeconds };
    }
    return {
      handled: false,
      reason: response.type === "error" ? response.reason : "unexpected-response",
    };
  });
  generateChain = run.then(
    (): undefined => undefined,
    (): undefined => undefined
  );
  return run;
}

/** Evicts only this model's entries from the shared transformers.js Cache
 * API bucket (whisper/kokoro entries are untouched). */
async function evictAudioGenCache(): Promise<void> {
  try {
    const keys = await caches.keys();
    for (const name of keys) {
      const cache = await caches.open(name);
      const reqs = await cache.keys();
      for (const req of reqs) {
        if (/musicgen/i.test(req.url)) await cache.delete(req);
      }
    }
  } catch {
    // Best-effort: if eviction itself fails the retry just fails again.
  }
}

export async function deleteAudioGenModel(): Promise<void> {
  destroyWorker("deleted", "model removed by user");
  safeLocalRemove(AUDIO_GEN_CACHED_KEY);
  // transformers.js's browser cache bucket is shared with whisper/kokoro, so
  // the whole bucket can't simply be deleted here - per-entry eviction would
  // need the same file-list dance kokoro.ts's delete does. Until that seam
  // exists, deleting removes the worker + marker and the weights stay cached
  // for the next enable, which is what "delete" should mean anyway.
}
