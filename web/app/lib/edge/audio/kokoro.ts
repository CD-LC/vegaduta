// On-device text-to-speech (main-thread facade).
//
// Design: docs/EDGE-CAPABILITIES-IMPLEMENTATION-PLAN-2026-08-04.md §E-4 —
// "Local TTS: Kokoro-82M (kokoro-js, WebGPU). Slots in as a new *first*
// preference in CallPanel's existing per-chunk fallback chain (before server
// audio, before playBrowserTTS speechSynthesis fallback — the chain shape
// already exists). Paid tiers keep ElevenLabs/Sarvam premium voices
// unchanged. Every Kokoro synth is a Sarvam/ElevenLabs bill that never
// happens." CallPanel.tsx's makeAudioChunkQueue is the wiring point; this
// module only exposes the on-device synth primitive.
//
// Manifest decision (recorded here since it's a deliberate divergence from
// the RIFE precedent, per the task that added this module): Kokoro is NOT
// published as a server /api/edge/manifest entry. RIFE's manifest entry
// (kind: "video-interpolation") exists because RIFE genuinely has an
// unpublished/not-yet-available state the client must honor (url: null =
// "isn't published yet", a real not-published UI state). Kokoro has no such
// state — like on-device vision (SmolVLM, see ../vision.ts, which ALSO has
// no manifest entry), it is a fixed, always-available utility model resolved
// by id against its own library's default CDN (kokoro-js -> the Hugging Face
// hub), the same "Phase 1 uses the library's own upstream CDN, zero infra"
// posture the plan's E-0 section states for WebLLM. The vision precedent is
// the closer analog for "~82M-parameter always-available utility model" and
// is what this module follows: a hardcoded asset id/size, downloadManager
// used purely as the shared progress/storage/cache surface (enqueue with no
// url, reportExternalProgress for the real transfer kokoro-js drives itself).
//
// Contract with callers (mirrors vision.ts's / whisper.ts's):
//   - edgeTtsAvailable() answers capability WITHOUT downloading anything.
//   - ensureTtsModel() is the ONLY entry that may start the acquisition,
//     called from an explicit user gesture behind
//     EDGE_BRANDING.ttsDownloadDetails consent copy (E-0b rule 2).
//     ONE exception since 2026-09-19, when Kokoro became the default call
//     voice (consumers have no ElevenLabs/Sarvam key and were hearing the
//     robotic Web Speech voice or nothing). The request was "make Kokoro the
//     default voice"; starting the download without a tap is an engineering
//     choice made to deliver that, NOT something anyone signed off as such -
//     to require a tap for everyone, make decideVoiceModelFetch return
//     "offer" where it returns "auto". As built, CallPanel (the signed-in
//     call panel, and nothing else) may call ensureTtsModel without a button
//     press ONLY when all of these hold: the person started a call or a
//     hold; the server itself said the reply has no voice (method
//     "text-only-fallback") or the turn took a path with no server voice;
//     that reply is in a script Kokoro can speak; the browser positively
//     reports a connection that does not look metered (navigator.connection
//     present, no Save-Data, not 2g/3g, not cellular, not a phone); it is the
//     first attempt of this page load. Size and progress are on screen
//     throughout. Everyone else - including every Firefox and Safari, which
//     report no connection info - gets a one-tap offer, and the public share
//     pages only ever offer. The rule lives in lib/voice/voiceOutput.ts
//     decideVoiceModelFetch.
//   - synthesizeSpeechLocally() NEVER throws — every failure is
//     { handled: false, reason }, and NEVER downloads anything: without a
//     ready (or previously-installed) model it answers
//     { handled: false, reason: "model-not-ready" } so the caller's existing
//     fallback chain (server audio, then playBrowserTTS) runs unchanged.

import { edgeEnabled } from "../capabilities";
import { downloadManager } from "../downloadManager";
import { EDGE_BRANDING } from "../branding";
import type { KokoroWorkerRequest, KokoroWorkerResponse } from "./kokoroWorker";

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Shared-queue asset id (the chip's row key for this model). */
export const TTS_ASSET_ID = "edge-tts-kokoro";
/** Honest size estimate for Kokoro-82M's quantized ONNX weights (q8 on
 * wasm / fp16 on webgpu) — an 82M-parameter model, so tens of MB, not the
 * hundreds-of-MB-to-GB scale of the vision/text models. Used for consent
 * copy and the storage headroom check. */
export const TTS_MODEL_SIZE_BYTES = 90_000_000;
/** localStorage marker set only after the model fully loaded once on this
 * device. Same semantics as vision's / whisper's cached markers. */
const TTS_CACHED_KEY = "edge.tts.cached.kokoro-82m";
/** Per-synthesis watchdog — one sentence fragment should synth in well under
 * this on any device that got this far. */
const SYNTHESIZE_TIMEOUT_MS = 30_000;
/** Load watchdog is idle-based (refreshed on every progress event). */
const LOAD_IDLE_TIMEOUT_MS = 120_000;

type TtsProgress = { loaded: number; total: number; status: string };
export type SynthesizeResult =
  | { handled: true; audioBlob: Blob; sampleRate: number }
  | { handled: false; reason: string };

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

/** Was the TTS model ever fully installed on this device? Exported so
 * CallPanel can check readiness WITHOUT ever triggering a download from the
 * automatic playback path (E-0b rule 2 — never a side-effect download). */
export function isTtsModelCached(): boolean {
  return safeLocalGet(TTS_CACHED_KEY) === "1";
}

// ---------------------------------------------------------------------------
// Worker lifecycle + request plumbing (same shape as vision.ts / whisper.ts)
// ---------------------------------------------------------------------------

interface PendingRequest {
  resolve: (response: KokoroWorkerResponse) => void;
  onProgress?: (p: TtsProgress) => void;
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
const loadProgressListeners = new Set<(p: TtsProgress) => void>();
/** Synth calls are serialized — one Kokoro turn at a time, same reasoning as
 * vision's analysisChain / whisper's transcribeChain. */
let synthesizeChain: Promise<unknown> = Promise.resolve();

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

function handleWorkerMessage(response: KokoroWorkerResponse): void {
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
    worker = new Worker(new URL("./kokoroWorker.ts", import.meta.url));
    worker.addEventListener("message", (event: MessageEvent) => {
      handleWorkerMessage(event.data as KokoroWorkerResponse);
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
  message: KokoroWorkerRequest,
  timeoutMs: number,
  timeoutReason: string,
  idleWatchdog: boolean,
  onProgress?: (p: TtsProgress) => void
): Promise<KokoroWorkerResponse> {
  const target = getWorker();
  if (!target) {
    return Promise.resolve({
      type: "error",
      requestId: message.requestId,
      reason: "worker-unsupported",
      detail: "Web Workers are not available in this environment",
    });
  }
  return new Promise<KokoroWorkerResponse>((resolve) => {
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
// Model load (shared by ensureTtsModel and the synth warm-up path)
// ---------------------------------------------------------------------------

function reportChipProgress(p: TtsProgress): void {
  if (p.status === "downloading" && p.total > 0) {
    downloadManager.reportExternalProgress(TTS_ASSET_ID, EDGE_BRANDING.ttsModelDisplayName, {
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
    const alreadyCached = isTtsModelCached();
    if (!alreadyCached && !(await downloadManager.hasStorageHeadroomFor(TTS_MODEL_SIZE_BYTES))) {
      downloadManager.reportExternalProgress(TTS_ASSET_ID, EDGE_BRANDING.ttsModelDisplayName, {
        state: "error",
        error: EDGE_BRANDING.insufficientStorageError,
      });
      throw new Error(EDGE_BRANDING.insufficientStorageError);
    }
    downloadManager.enqueue({
      id: TTS_ASSET_ID,
      displayName: EDGE_BRANDING.ttsModelDisplayName,
      totalBytes: TTS_MODEL_SIZE_BYTES,
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
      downloadManager.reportExternalProgress(TTS_ASSET_ID, EDGE_BRANDING.ttsModelDisplayName, {
        state: "error",
        error: alreadyCached ? "Could not start on-device voice" : "Download failed",
      });
      throw new Error(detail || "on-device voice setup failed");
    }
    workerModelLoaded = true;
    safeLocalSet(TTS_CACHED_KEY, "1");
    downloadManager.reportExternalProgress(TTS_ASSET_ID, EDGE_BRANDING.ttsModelDisplayName, {
      state: "ready",
      receivedBytes: TTS_MODEL_SIZE_BYTES,
      totalBytes: TTS_MODEL_SIZE_BYTES,
    });
    void downloadManager.requestPersistentStorage();
  })().finally(() => {
    loadInFlight = null;
  });
  return loadInFlight;
}

// ---------------------------------------------------------------------------
// Public contract
// ---------------------------------------------------------------------------

/** Can this device run on-device TTS at all? True only when the edge flag is
 * on and the environment can execute the model. Answers WITHOUT downloading
 * — or fetching — anything. */
export async function edgeTtsAvailable(): Promise<boolean> {
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
 * Acquire (or warm up) the on-device TTS model through the shared download
 * manager. This function never prompts, so consent is the CALLER'S job: call
 * it from an explicit user action behind EDGE_BRANDING.ttsDownloadDetails
 * consent copy (E-0b rule 2). Two callers do not follow that, both in
 * CallPanel and both described in this file's header: the automatic
 * default-voice fetch (decideVoiceModelFetch === "auto"), and a warm-up of an
 * ALREADY-INSTALLED model inside the hold-to-talk gesture, which loads from
 * the browser cache (if the browser has evicted that cache since, the load
 * fetches the files again - the same is true of synthesizeSpeechLocally's
 * own warm-up). Progress streams to the
 * callback AND to the EdgeSetupChip. Rejects with a display-safe Error when
 * the device can't run TTS, storage is short, or the load fails.
 */
export async function ensureTtsModel(
  onProgress?: (p: { loaded: number; total: number; status: string }) => void
): Promise<void> {
  if (!(await edgeTtsAvailable())) {
    throw new Error("On-device voice is not available on this device.");
  }
  if (workerModelLoaded) {
    if (onProgress) {
      try {
        onProgress({ loaded: TTS_MODEL_SIZE_BYTES, total: TTS_MODEL_SIZE_BYTES, status: "ready" });
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
        onProgress({ loaded: TTS_MODEL_SIZE_BYTES, total: TTS_MODEL_SIZE_BYTES, status: "ready" });
      } catch {
        // Best-effort.
      }
    }
  } finally {
    if (onProgress) loadProgressListeners.delete(onProgress);
  }
}

/**
 * Synthesize speech for one short piece of text fully on-device. NEVER
 * throws and NEVER downloads: without a ready (or previously-installed)
 * model it answers { handled: false, reason: "model-not-ready" } — the
 * caller's existing fallback chain (server audio, then browser
 * speechSynthesis) runs exactly as it does today.
 */
export async function synthesizeSpeechLocally(
  text: string,
  options?: { voice?: string }
): Promise<SynthesizeResult> {
  try {
    if (!edgeEnabled() || typeof window === "undefined") {
      return { handled: false, reason: "edge-disabled" };
    }
    if (typeof Worker === "undefined") {
      return { handled: false, reason: "worker-unsupported" };
    }
    const trimmed = (text || "").trim();
    if (!trimmed) {
      return { handled: false, reason: "no-text" };
    }
    if (!workerModelLoaded && !isTtsModelCached()) {
      return { handled: false, reason: "model-not-ready" };
    }
    const run = synthesizeChain.then(
      (): Promise<SynthesizeResult> => doSynthesize(trimmed, options?.voice)
    );
    synthesizeChain = run.catch((): undefined => undefined);
    return await run;
  } catch {
    return { handled: false, reason: "tts-failed" };
  }
}

async function doSynthesize(text: string, voice: string | undefined): Promise<SynthesizeResult> {
  if (!workerModelLoaded) {
    try {
      await runLoad();
    } catch {
      return { handled: false, reason: "warmup-failed" };
    }
  }
  const requestId = nextRequestId++;
  const response = await request(
    { type: "synthesize", requestId, text, voice },
    SYNTHESIZE_TIMEOUT_MS,
    "synthesize-timeout",
    false
  );
  if (response.type === "result") {
    if (!response.audioBlob || response.audioBlob.size === 0) {
      return { handled: false, reason: "empty-audio" };
    }
    return { handled: true, audioBlob: response.audioBlob, sampleRate: response.sampleRate };
  }
  if (response.type === "error") {
    return { handled: false, reason: response.reason || "synthesize-failed" };
  }
  return { handled: false, reason: "synthesize-failed" };
}

// ---------------------------------------------------------------------------
// Installed-model management (the /edge console's delete surface)
// ---------------------------------------------------------------------------

/** transformers.js's default browser Cache API bucket. kokoro-js delegates
 * ONNX acquisition to its own bundled transformers.js, which uses the same
 * library-wide cache bucket name (not per-model/per-version), so this is the
 * same constant vision.ts / whisper.ts filter against. */
const TRANSFORMERS_CACHE_NAME = "transformers-cache";

/**
 * Are Kokoro's weight files in the browser cache RIGHT NOW? isTtsModelCached()
 * is only a localStorage marker ("it was fully installed once"); the browser
 * may have evicted the files since, and a synth would then fetch all ~90 MB
 * again without anyone having asked. Surfaces that must never cause a
 * download on their own (the public share pages) check this first. True when
 * the model is already loaded in the worker. Never rejects; any doubt (no
 * Cache API, an error) answers false.
 */
export async function ttsModelFilesPresent(): Promise<boolean> {
  if (workerModelLoaded) return true;
  if (!isTtsModelCached()) return false;
  try {
    if (typeof caches === "undefined" || !(await caches.has(TRANSFORMERS_CACHE_NAME))) return false;
    const cache = await caches.open(TRANSFORMERS_CACHE_NAME);
    const keys = await cache.keys();
    return keys.some((req) => /kokoro/i.test(req.url) && /\.onnx(\?|$)/i.test(req.url));
  } catch {
    return false;
  }
}

/**
 * Delete the on-device TTS model from this device: terminates a live worker
 * first, then best-effort evicts the Kokoro entries from transformers.js's
 * cache bucket, clears the installed marker, and removes the shared chip's
 * status row. User-initiated only — nothing in the synth path ever calls
 * this. Never rejects.
 */
export async function deleteTtsModel(): Promise<void> {
  destroyWorker("deleted", "model deleted by the user");
  try {
    if (typeof caches !== "undefined" && (await caches.has(TRANSFORMERS_CACHE_NAME))) {
      const cache = await caches.open(TRANSFORMERS_CACHE_NAME);
      for (const req of await cache.keys()) {
        if (/kokoro/i.test(req.url)) await cache.delete(req);
      }
    }
  } catch {
    // Best-effort.
  }
  safeLocalRemove(TTS_CACHED_KEY);
  await downloadManager.deleteAsset(TTS_ASSET_ID);
}
