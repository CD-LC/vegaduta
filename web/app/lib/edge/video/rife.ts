/**
 * Frame interpolation engines for Clip Studio "Motion+" (tier V1).
 *
 * Runs a small open frame-interpolation model on-device via onnxruntime-web:
 * WebGPU execution provider when the device has it, automatic WASM fallback
 * otherwise. onnxruntime-web is loaded with a dynamic `import()` ONLY when
 * interpolation actually runs — it is never in the route's initial bundle.
 * More than one engine can be published at once (today: a fast/small
 * RIFE-class model and a quality/larger FILM-class model — see
 * `listMotionEngines`) — `interpolateMidpoint`'s input-name introspection
 * below tolerates both without engine-specific code, since RIFE and FILM
 * ONNX exports share the same handful of shapes (two [1,3,H,W] inputs, or one
 * [1,6,H,W] concat input, plus an optional scalar timestep).
 *
 * Weights discovery: `GET /api/edge/manifest`, entries of kind
 * "video-interpolation" (there may be several — one per engine). NO weight
 * URL is hardcoded anywhere (binding rule) — if the endpoint is missing,
 * errors, or every entry's url is null, the whole Motion+ mode renders a calm
 * "isn't published yet" state and V0 keeps working untouched. The endpoint is
 * expected unauthenticated (same posture as /api/edge/config); a 401/403 is
 * treated as not-published, never as an error wall.
 *
 * Download & caching follow the E-0b contract via the SHARED download
 * manager's storage + progress surfaces (no ad-hoc downloader state): bytes
 * are cached whole through downloadManager.putShard (Cache API, survives
 * reloads), progress is mirrored into the manager with
 * reportExternalProgress so the EdgeSetupChip shows "Clip Studio motion
 * engine · 43%" alongside every other on-device asset, and a cached copy is
 * used without any network. (The manager's own Wave-2 worker transfer engine
 * is owned by the downloads agent; this module deliberately only uses the
 * manager's public API.)
 *
 * Model-shape tolerance: RIFE ONNX exports differ — some take one
 * [1,6,H,W] tensor (both frames concatenated on channels), some take two
 * [1,3,H,W] inputs, some add a scalar timestep input. Input names are read
 * from the session rather than hardcoded, and all three shapes are handled.
 * Spatial dims are padded to a multiple of 32 (RIFE's pyramid requirement)
 * and cropped back after inference.
 */

import { API_BASE } from "../../api";
import { EDGE_BRANDING } from "../branding";
import { downloadManager } from "../downloadManager";
import { drawCover } from "./encode";

// ---------------------------------------------------------------------------
// Availability (manifest)
// ---------------------------------------------------------------------------

/** localStorage — the user's picked Motion+ engine: "auto" | a manifest
 * video-interpolation entry id. Mirrors MODEL_OVERRIDE_KEY's contract in
 * capabilities.ts — a stored value always wins over auto-selection. */
export const MOTION_MODEL_OVERRIDE_KEY = "edge.motionModelOverride";

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

export function getStoredMotionModelOverride(): string {
  return safeLocalGet(MOTION_MODEL_OVERRIDE_KEY) || "auto";
}

export function setMotionModelOverride(id: string): void {
  safeLocalSet(MOTION_MODEL_OVERRIDE_KEY, id);
}

const MANIFEST_KIND = "video-interpolation";

/** One video-interpolation candidate as published by the manifest, before an
 * availability decision has been made about it. */
export interface MotionEngineOption {
  id: string;
  /** Brand-first display name: the model identity stays in
   * detailName, this is the capability-level label. */
  displayName: string;
  detailName: string | null;
  url: string | null;
  sizeBytes: number | null;
  runtimeBaseUrl: string | null;
}

export interface MotionEngineAvailability {
  available: boolean;
  /** The chosen engine's manifest id — null only when nothing is available at all. */
  id: string | null;
  displayName: string;
  detailName: string | null;
  /** Weight URL from the manifest — null when not published. */
  url: string | null;
  sizeBytes: number | null;
  /** Optional base URL for the ONNX runtime's .wasm assets, if the manifest
   * publishes one alongside the weights (same static route). */
  runtimeBaseUrl: string | null;
  /** Human-readable why-not, for logs/details copy — never an error wall. */
  reason?: string;
}

function unavailable(reason: string): MotionEngineAvailability {
  return {
    available: false,
    id: null,
    displayName: "Clip Studio motion engine",
    detailName: null,
    url: null,
    sizeBytes: null,
    runtimeBaseUrl: null,
    reason,
  };
}

/** Defensive pick of the entry list out of whatever shape the manifest has. */
function entriesOf(body: any): any[] {
  if (Array.isArray(body)) return body;
  if (Array.isArray(body?.assets)) return body.assets;
  if (Array.isArray(body?.models)) return body.models;
  if (Array.isArray(body?.entries)) return body.entries;
  return [];
}

function toOption(entry: any): MotionEngineOption | null {
  const id = typeof entry?.id === "string" && entry.id ? entry.id : null;
  if (!id) return null;
  const url: unknown = entry.url ?? entry.weightsUrl ?? null;
  const sizeBytes = typeof entry.sizeBytes === "number" && entry.sizeBytes > 0 ? entry.sizeBytes : null;
  const runtimeBaseUrl =
    typeof entry.runtimeBaseUrl === "string" && entry.runtimeBaseUrl ? entry.runtimeBaseUrl : null;
  return {
    id,
    displayName:
      typeof entry.displayName === "string" && entry.displayName ? entry.displayName : "Clip Studio motion engine",
    detailName: typeof entry.detailName === "string" && entry.detailName ? entry.detailName : null,
    url: typeof url === "string" && url ? url : null,
    sizeBytes,
    runtimeBaseUrl,
  };
}

/**
 * Every video-interpolation entry the manifest currently publishes, in
 * manifest order — regardless of whether each one's weights are published
 * yet (callers decide what to do with unpublished entries). Never rejects —
 * a missing/broken manifest resolves to an empty list.
 */
export async function listMotionEngines(): Promise<MotionEngineOption[]> {
  if (typeof fetch === "undefined") return [];
  let body: any = null;
  try {
    const res = await fetch(`${API_BASE}/api/edge/manifest`);
    if (!res.ok) return [];
    body = await res.json();
  } catch {
    return [];
  }
  const options: MotionEngineOption[] = [];
  for (const entry of entriesOf(body)) {
    if (!entry || (entry.kind !== MANIFEST_KIND && entry.type !== MANIFEST_KIND)) continue;
    const option = toOption(entry);
    if (option) options.push(option);
  }
  return options;
}

/**
 * Resolves ONE engine to actually use: an explicit `modelId` wins, then the
 * stored user override (if it still exists in the manifest), then the first
 * PUBLISHED (url != null) entry, then just the first entry at all (so the
 * calm "isn't published yet" copy still has a name to show). Never rejects —
 * every failure mode maps to the calm not-published state. One cheap GET per
 * call site (the Motion+ surface calls this once on mount).
 */
export async function checkMotionEngineAvailability(modelId?: string): Promise<MotionEngineAvailability> {
  const options = await listMotionEngines();
  if (options.length === 0) return unavailable("No video-interpolation entry in the manifest.");

  const wanted = modelId || getStoredMotionModelOverride();
  const chosen =
    (wanted && wanted !== "auto" && options.find((o) => o.id === wanted)) ||
    options.find((o) => o.url) ||
    options[0];

  if (!chosen.url) {
    return {
      available: false,
      id: chosen.id,
      displayName: chosen.displayName,
      detailName: chosen.detailName,
      url: null,
      sizeBytes: null,
      runtimeBaseUrl: null,
      reason: "The video-interpolation model isn't published yet (url is null).",
    };
  }
  return {
    available: true,
    id: chosen.id,
    displayName: chosen.displayName,
    detailName: chosen.detailName,
    url: chosen.url,
    sizeBytes: chosen.sizeBytes,
    runtimeBaseUrl: chosen.runtimeBaseUrl,
  };
}

// ---------------------------------------------------------------------------
// Weights acquisition (shared download-manager storage + progress surfaces)
// ---------------------------------------------------------------------------

async function acquireWeights(
  availability: MotionEngineAvailability,
  onProgress: ((receivedBytes: number, totalBytes: number) => void) | undefined,
  signal: AbortSignal | undefined
): Promise<ArrayBuffer> {
  const assetId = availability.id;
  const displayName = availability.displayName;
  if (!assetId) throw new Error("Motion+ interpolation model isn't published yet.");

  // Cached from an earlier session? (Whole file stored as shard 0.)
  const cached = await downloadManager.getShard(assetId, 0);
  if (cached && cached.byteLength > 0) {
    downloadManager.reportExternalProgress(assetId, displayName, {
      state: "ready",
      receivedBytes: cached.byteLength,
      totalBytes: cached.byteLength,
    });
    return cached;
  }

  const url = availability.url;
  if (!url) throw new Error("Motion+ interpolation model isn't published yet.");

  const res = await fetch(url, signal ? { signal } : undefined);
  if (!res.ok || !res.body) {
    // Chip-visible copy comes from the branding seam; the HTTP
    // status only goes to the console, never the primary string.
    console.warn(`Motion engine download failed with HTTP ${res.status}`);
    downloadManager.reportExternalProgress(assetId, displayName, {
      state: "error",
      error: EDGE_BRANDING.interruptedError,
    });
    throw new Error("Couldn't download the motion engine just now — try again in a moment.");
  }
  const headerLen = Number(res.headers.get("content-length"));
  const totalBytes =
    Number.isFinite(headerLen) && headerLen > 0 ? headerLen : availability.sizeBytes || 0;

  const reader = res.body.getReader();
  const chunks: Uint8Array[] = [];
  let received = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    if (value) {
      chunks.push(value);
      received += value.byteLength;
      onProgress?.(received, totalBytes);
      downloadManager.reportExternalProgress(assetId, displayName, {
        state: "downloading",
        receivedBytes: received,
        totalBytes: totalBytes || received,
      });
    }
  }

  const buffer = new ArrayBuffer(received);
  const view = new Uint8Array(buffer);
  let offset = 0;
  for (const chunk of chunks) {
    view.set(chunk, offset);
    offset += chunk.byteLength;
  }

  // Cache best-effort (quota shortage must not block this run's use).
  await downloadManager.putShard(assetId, 0, buffer);
  downloadManager.reportExternalProgress(assetId, displayName, {
    state: "ready",
    receivedBytes: received,
    totalBytes: received,
  });
  return buffer;
}

/** Evict a specific engine's cached weights ("Manage on-device models" hook). */
export async function deleteMotionEngineCache(assetId: string): Promise<void> {
  await downloadManager.deleteAsset(assetId);
}

// ---------------------------------------------------------------------------
// Engine
// ---------------------------------------------------------------------------

type OrtNamespace = typeof import("onnxruntime-web");
type OrtSession = import("onnxruntime-web").InferenceSession;
type OrtTensor = import("onnxruntime-web").Tensor;

export type MotionEngineProvider = "webgpu" | "wasm";

export interface LoadMotionEngineOptions {
  availability: MotionEngineAvailability;
  /** Prefer WebGPU (from capabilities.ts detection); falls back to WASM. */
  preferWebGpu: boolean;
  onDownloadProgress?: (receivedBytes: number, totalBytes: number) => void;
  signal?: AbortSignal;
}

export interface MotionEngine {
  provider: MotionEngineProvider;
  /** Interpolate the midpoint frame between a and b at width×height. */
  interpolateMidpoint(a: ImageBitmap, b: ImageBitmap, width: number, height: number): Promise<ImageBitmap>;
  dispose(): void;
}

function pad32(value: number): number {
  return Math.ceil(value / 32) * 32;
}

function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted) {
    const err = new Error("Interpolation was cancelled.");
    err.name = "AbortError";
    throw err;
  }
}

/**
 * Load weights (cache-first), boot onnxruntime-web (dynamic import) and
 * create the inference session — WebGPU EP first, WASM fallback.
 */
export async function loadMotionEngine(options: LoadMotionEngineOptions): Promise<MotionEngine> {
  const { availability, preferWebGpu, onDownloadProgress, signal } = options;
  const weights = await acquireWeights(availability, onDownloadProgress, signal);
  throwIfAborted(signal);

  // Dynamic import only when interpolation is actually used (binding rule).
  const ort: OrtNamespace = await import("onnxruntime-web");
  throwIfAborted(signal);
  if (availability.runtimeBaseUrl) {
    // The manifest may co-publish the runtime's .wasm assets on the same
    // static route — point the loader there when it does.
    try {
      ort.env.wasm.wasmPaths = availability.runtimeBaseUrl;
    } catch {
      // Read-only env in some bundling setups — defaults still apply.
    }
  }

  const weightBytes = new Uint8Array(weights);
  let session: OrtSession | null = null;
  let provider: MotionEngineProvider = "wasm";
  if (preferWebGpu) {
    try {
      session = await ort.InferenceSession.create(weightBytes, { executionProviders: ["webgpu"] });
      provider = "webgpu";
    } catch {
      session = null; // WebGPU EP unavailable/failed — WASM below.
    }
  }
  if (!session) {
    session = await ort.InferenceSession.create(weightBytes, { executionProviders: ["wasm"] });
    provider = "wasm";
  }
  throwIfAborted(signal);

  return createEngine(ort, session, provider);
}

function createEngine(ort: OrtNamespace, session: OrtSession, provider: MotionEngineProvider): MotionEngine {
  // One reusable scratch canvas for pixel IO (bitmap → tensor → bitmap).
  const scratch = typeof document !== "undefined" ? document.createElement("canvas") : null;

  function frameToTensorData(
    source: ImageBitmap,
    width: number,
    height: number,
    paddedW: number,
    paddedH: number
  ): Float32Array {
    if (!scratch) throw new Error("Interpolation needs a browser document.");
    scratch.width = paddedW;
    scratch.height = paddedH;
    const ctx = scratch.getContext("2d", { willReadFrequently: true });
    if (!ctx) throw new Error("Couldn't get a 2D context for frame conversion.");
    ctx.fillStyle = "#000";
    ctx.fillRect(0, 0, paddedW, paddedH);
    // Draw the frame cover-fit into the UNPADDED region; padding stays black
    // and is cropped away after inference.
    ctx.save();
    ctx.beginPath();
    ctx.rect(0, 0, width, height);
    ctx.clip();
    drawCover(ctx, source, width, height);
    ctx.restore();
    const image = ctx.getImageData(0, 0, paddedW, paddedH);
    const rgba = image.data;
    const plane = paddedW * paddedH;
    const chw = new Float32Array(3 * plane);
    for (let i = 0; i < plane; i++) {
      const j = i * 4;
      chw[i] = rgba[j] / 255;
      chw[plane + i] = rgba[j + 1] / 255;
      chw[2 * plane + i] = rgba[j + 2] / 255;
    }
    return chw;
  }

  async function tensorToBitmap(
    output: OrtTensor,
    width: number,
    height: number,
    paddedW: number,
    paddedH: number
  ): Promise<ImageBitmap> {
    const data = output.data as Float32Array;
    const plane = paddedW * paddedH;
    const rgba = new Uint8ClampedArray(plane * 4);
    for (let i = 0; i < plane; i++) {
      const j = i * 4;
      rgba[j] = Math.max(0, Math.min(255, Math.round(data[i] * 255)));
      rgba[j + 1] = Math.max(0, Math.min(255, Math.round(data[plane + i] * 255)));
      rgba[j + 2] = Math.max(0, Math.min(255, Math.round(data[2 * plane + i] * 255)));
      rgba[j + 3] = 255;
    }
    const image = new ImageData(rgba, paddedW, paddedH);
    // Crop the padding away while decoding to a bitmap.
    return await createImageBitmap(image, 0, 0, width, height);
  }

  return {
    provider,
    async interpolateMidpoint(a: ImageBitmap, b: ImageBitmap, width: number, height: number) {
      const paddedW = pad32(width);
      const paddedH = pad32(height);
      const dataA = frameToTensorData(a, width, height, paddedW, paddedH);
      const dataB = frameToTensorData(b, width, height, paddedW, paddedH);

      const inputNames = session.inputNames;
      const feeds: Record<string, OrtTensor> = {};
      const imageInputs = inputNames.filter((n) => !/time|scale|factor/i.test(n));
      if (imageInputs.length >= 2) {
        // Two [1,3,H,W] inputs.
        feeds[imageInputs[0]] = new ort.Tensor("float32", dataA, [1, 3, paddedH, paddedW]);
        feeds[imageInputs[1]] = new ort.Tensor("float32", dataB, [1, 3, paddedH, paddedW]);
      } else {
        // Single [1,6,H,W] concat input.
        const concat = new Float32Array(dataA.length + dataB.length);
        concat.set(dataA, 0);
        concat.set(dataB, dataA.length);
        feeds[imageInputs[0] || inputNames[0]] = new ort.Tensor("float32", concat, [
          1,
          6,
          paddedH,
          paddedW,
        ]);
      }
      // Optional scalar timestep input (midpoint = 0.5).
      const timeInput = inputNames.find((n) => /time/i.test(n));
      if (timeInput) {
        feeds[timeInput] = new ort.Tensor("float32", new Float32Array([0.5]), [1]);
      }

      const results = await session.run(feeds);
      const outName = session.outputNames[0];
      const output = results[outName];
      if (!output) throw new Error("The interpolation model produced no output.");
      return await tensorToBitmap(output, width, height, paddedW, paddedH);
    },
    dispose() {
      try {
        void session.release();
      } catch {
        // Already released.
      }
    },
  };
}

// ---------------------------------------------------------------------------
// Sequence building (keyframes → 24fps frame list via recursive subdivision)
// ---------------------------------------------------------------------------

export const MOTION_FPS = 24;
/** log2 of frames per keyframe segment: depth 5 → 32 frames/segment, so
 * 3/4/5 keyframes yield 65/97/129 frames ≈ 2.7s/4.0s/5.4s at 24fps — inside
 * the plan's "short (3–8s) stylised motion clip" envelope while keeping
 * bitmap memory bounded at the Motion+ output sizes. */
export const SUBDIVISION_DEPTH = 5;

export function interpolatedFrameCount(keyframeCount: number): number {
  const segments = Math.max(1, keyframeCount - 1);
  return segments * Math.pow(2, SUBDIVISION_DEPTH) + 1;
}

export function estimatedDurationSeconds(keyframeCount: number): number {
  return interpolatedFrameCount(keyframeCount) / MOTION_FPS;
}

export interface BuildFramesOptions {
  engine: MotionEngine;
  /** Decoded keyframes, in order. */
  keyframes: ImageBitmap[];
  width: number;
  height: number;
  /** 0..100 across all inferences. */
  onProgress?: (percent: number) => void;
  signal?: AbortSignal;
}

/**
 * Expand keyframes into the full frame sequence by recursive midpoint
 * subdivision (each level halves the temporal gap). Returns NEW bitmaps for
 * the keyframe slots too (rendered at output size), so the caller can close
 * everything in the returned array uniformly.
 */
export async function buildInterpolatedFrames(options: BuildFramesOptions): Promise<ImageBitmap[]> {
  const { engine, keyframes, width, height, onProgress, signal } = options;
  if (keyframes.length < 2) throw new Error("At least two keyframes are needed to interpolate.");

  // Normalize every keyframe to an output-sized bitmap first, so inference
  // always runs at one resolution.
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext("2d");
  if (!ctx) throw new Error("Couldn't get a 2D context for frame preparation.");
  const normalized: ImageBitmap[] = [];
  for (const frame of keyframes) {
    drawCover(ctx, frame, width, height);
    normalized.push(await createImageBitmap(canvas));
  }

  const segments = keyframes.length - 1;
  const totalInferences = segments * (Math.pow(2, SUBDIVISION_DEPTH) - 1);
  let doneInferences = 0;
  const bump = () => {
    doneInferences += 1;
    onProgress?.(Math.min(99, Math.round((doneInferences / totalInferences) * 100)));
  };

  // Everything allocated here, tracked so a failure/cancel mid-way frees ALL
  // of it (normalized keyframes AND every midpoint produced so far).
  const created: ImageBitmap[] = [...normalized];

  async function subdivide(a: ImageBitmap, b: ImageBitmap, depth: number): Promise<ImageBitmap[]> {
    if (depth <= 0) return [];
    throwIfAborted(signal);
    const mid = await engine.interpolateMidpoint(a, b, width, height);
    created.push(mid);
    bump();
    const left = await subdivide(a, mid, depth - 1);
    const right = await subdivide(mid, b, depth - 1);
    return [...left, mid, ...right];
  }

  try {
    const frames: ImageBitmap[] = [normalized[0]];
    for (let s = 0; s < segments; s++) {
      const mids = await subdivide(normalized[s], normalized[s + 1], SUBDIVISION_DEPTH);
      frames.push(...mids, normalized[s + 1]);
    }
    onProgress?.(100);
    return frames;
  } catch (err) {
    releaseFrames(created);
    throw err;
  }
}

/** Close a frame list produced by buildInterpolatedFrames. */
export function releaseFrames(frames: ImageBitmap[] | null): void {
  if (!frames) return;
  for (const frame of frames) {
    try {
      frame.close();
    } catch {
      // Already closed.
    }
  }
}
