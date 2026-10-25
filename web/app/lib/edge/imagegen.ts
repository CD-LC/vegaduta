// On-device text-to-image (main-thread facade).
//
// The edge story's second generation surface, after vision: an SD-Turbo ONNX
// export runs entirely inside imagegenWorker.ts on onnxruntime-web's WebGPU
// execution provider, served from the Hugging Face hub - the model bytes never
// ship inside this repository. The orchestration contract is the same as
// whisper.ts and vision.ts:
//
//   - edgeImageGenAvailable() answers capability WITHOUT downloading anything
//     (WebGPU is the hard requirement - the wasm path exists in the worker but
//     a 512px diffusion on wasm is a multi-minute experience and the tool's
//     honest answer there is "no GPU, hosted path instead").
//   - ensureImageGenModel() is the ONLY entry that may start the ~2.4 GB
//     acquisition, called from an explicit user gesture behind
//     EDGE_BRANDING.imageGenDownloadDetails consent copy (E-0b rule 2). The
//     tool path (local_generate_image) never calls it - it refuses with a
//     "turn it on in the Edge console" pointer, the same contract every other
//     gated tool follows.
//   - generateImageLocally() NEVER throws - every failure is
//     { handled: false, reason }, and NEVER downloads anything on its own:
//     with no ready model it answers { handled: false, reason: "model-not-ready" }.
//   - The image comes back as an encoded PNG Blob plus an artifact id; the
//     caller (the local tool, a future panel) decides what happens to it -
//     the facade itself writes nothing to disk.

import { edgeEnabled, detectCapabilities } from "./capabilities";
import { downloadManager } from "./downloadManager";
import { EDGE_BRANDING } from "./branding";
import { addMediaArtifact, type MediaArtifact } from "./mediaRegistry";
import type { ImageGenWorkerRequest, ImageGenWorkerResponse } from "./imagegenWorker";

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

export const IMAGE_GEN_ASSET_ID = "edge-imagegen-sd-turbo";
/** ~2.4 GB of ONNX weights (unet 640MB + text_encoder 1.7GB + vae_decoder 95MB,
 * plus the tokenizer). Honest number for the consent copy and headroom check. */
export const IMAGE_GEN_MODEL_SIZE_BYTES = 2_500_000_000;
const IMAGE_GEN_CACHED_KEY = "edge.imagegen.cached.sd-turbo";
/** Per-generation watchdog - a first-run generation on integrated graphics can
 * legitimately run to a few minutes; the ceiling exists for the stuck case. */
const GENERATE_TIMEOUT_MS = 300_000;
const LOAD_IDLE_TIMEOUT_MS = 300_000;
const MAX_PROMPT_CHARS = 500;

export type ImageGenResult =
  | { handled: true; blob: Blob; artifact: MediaArtifact; width: number; height: number }
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

/** Was the image model ever fully installed on this device? */
export function isImageGenModelCached(): boolean {
  return safeLocalGet(IMAGE_GEN_CACHED_KEY) === "1";
}

// ---------------------------------------------------------------------------
// Capability gate
// ---------------------------------------------------------------------------

/**
 * Can this device even think about it? Answers without touching the network.
 * WebGPU is the hard requirement: the wasm path in the worker exists as a
 * last resort but a single-step SD-Turbo still takes minutes there, which is
 * a worse answer than "no local image gen" — so the gate says webgpu-or-nothing.
 */
export async function edgeImageGenAvailable(): Promise<boolean> {
  try {
    if (!edgeEnabled()) return false;
    if (typeof window === "undefined") return false;
    if (!("gpu" in navigator) || !(navigator as Navigator & { gpu?: unknown }).gpu) return false;
    const caps = await detectCapabilities();
    return caps?.webgpu?.available === true;
  } catch {
    return false;
  }
}

// ---------------------------------------------------------------------------
// Worker lifecycle + request plumbing (same shape as whisper.ts)
// ---------------------------------------------------------------------------

interface PendingRequest {
  resolve: (response: ImageGenWorkerResponse) => void;
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
/** Generations are serialized - one diffusion run at a time keeps the worker's
 * memory bounded and the timeout accounting per-call. */
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

function handleWorkerMessage(response: ImageGenWorkerResponse): void {
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
    worker = new Worker(new URL("./imagegenWorker.ts", import.meta.url));
    worker.addEventListener("message", (event: MessageEvent) => {
      handleWorkerMessage(event.data as ImageGenWorkerResponse);
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
  message: ImageGenWorkerRequest,
  timeoutMs: number,
  timeoutReason: string,
  idleWatchdog: boolean,
  onProgress?: (p: { loaded: number; total: number; status: string }) => void
): Promise<ImageGenWorkerResponse> {
  const target = getWorker();
  if (!target) {
    return Promise.resolve({
      type: "error",
      requestId: message.requestId,
      reason: "worker-unsupported",
      detail: "Web Workers are not available in this environment",
    });
  }
  return new Promise<ImageGenWorkerResponse>((resolve) => {
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
    downloadManager.reportExternalProgress(IMAGE_GEN_ASSET_ID, EDGE_BRANDING.imageGenModelDisplayName, {
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
    const alreadyCached = isImageGenModelCached();
    if (!alreadyCached && !(await downloadManager.hasStorageHeadroomFor(IMAGE_GEN_MODEL_SIZE_BYTES))) {
      downloadManager.reportExternalProgress(IMAGE_GEN_ASSET_ID, EDGE_BRANDING.imageGenModelDisplayName, {
        state: "error",
        error: EDGE_BRANDING.insufficientStorageError,
      });
      throw new Error(EDGE_BRANDING.insufficientStorageError);
    }
    downloadManager.enqueue({
      id: IMAGE_GEN_ASSET_ID,
      displayName: EDGE_BRANDING.imageGenModelDisplayName,
      totalBytes: IMAGE_GEN_MODEL_SIZE_BYTES,
    });
    const requestId = nextRequestId++;
    const response = await request({ type: "load", requestId }, LOAD_IDLE_TIMEOUT_MS, "load-timeout", true, (p) =>
      reportChipProgress(p)
    );
    if (response.type !== "loaded") {
      const detail = response.type === "error" ? response.detail : "unexpected worker reply";
      downloadManager.reportExternalProgress(IMAGE_GEN_ASSET_ID, EDGE_BRANDING.imageGenModelDisplayName, {
        state: "error",
        error: alreadyCached ? "Could not start on-device image generation" : "Download failed",
      });
      throw new Error(detail || "on-device image generation setup failed");
    }
    workerModelLoaded = true;
    safeLocalSet(IMAGE_GEN_CACHED_KEY, "1");
    downloadManager.reportExternalProgress(IMAGE_GEN_ASSET_ID, EDGE_BRANDING.imageGenModelDisplayName, {
      state: "ready",
      receivedBytes: IMAGE_GEN_MODEL_SIZE_BYTES,
      totalBytes: IMAGE_GEN_MODEL_SIZE_BYTES,
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
export async function ensureImageGenModel(): Promise<void> {
  await runLoad();
}

/**
 * Generate one 512x512 image from a text prompt. Never throws; never
 * downloads. The returned artifact is registered in the in-page media gallery
 * so the Edge console (and the tool's reply text) can point at it.
 */
export async function generateImageLocally(prompt: string, seed?: number): Promise<ImageGenResult> {
  const trimmed = (prompt || "").trim();
  if (!trimmed) return { handled: false, reason: "empty-prompt" };
  if (trimmed.length > MAX_PROMPT_CHARS) return { handled: false, reason: "prompt-too-long" };
  if (!(await edgeImageGenAvailable())) return { handled: false, reason: "unsupported" };
  if (!workerModelLoaded) {
    return { handled: false, reason: "model-not-ready" };
  }

  const requestId = nextRequestId++;
  const run = generateChain.then(async (): Promise<ImageGenResult> => {
    const response = await request(
      { type: "generate", requestId, prompt: trimmed, seed },
      GENERATE_TIMEOUT_MS,
      "generate-timeout",
      false
    );
    if (response.type === "result") {
      const blob = new Blob([response.png], { type: "image/png" });
      const artifact = addMediaArtifact({
        kind: "image",
        mime: "image/png",
        blob,
        description: trimmed,
        seed,
        width: response.width,
        height: response.height,
      });
      return { handled: true, blob, artifact, width: response.width, height: response.height };
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

/**
 * Remove the model from Cache Storage + clear the cached marker. Called from
 * the Edge console's delete action, mirroring deleteSttModel/deleteVisionModel.
 */
export async function deleteImageGenModel(): Promise<void> {
  destroyWorker("deleted", "model removed by user");
  safeLocalRemove(IMAGE_GEN_CACHED_KEY);
  try {
    if (typeof caches !== "undefined") {
      // The worker's bucket name is its own; the facade asks it to clear rather
      // than reaching into worker-scoped state itself.
      await caches.delete("vegaduta-edge-imagegen");
    }
  } catch {
    // Best-effort.
  }
}
