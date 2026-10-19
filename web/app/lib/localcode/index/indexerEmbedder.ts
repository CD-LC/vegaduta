// The on-device embedder: the Cache-API readiness probe, the worker bridge, the
// explicit install and the embed binding. Split out of indexer.ts; see that
// file's header for the guarantees (no silent download, no network from a
// build).
import type { EmbedderDtype, EmbedWorkerRequest, EmbedWorkerResponse } from "./embedder.worker";
import { edgeEnabled } from "../../edge/capabilities";
import { downloadManager, type AssetStatus } from "../../edge/downloadManager";
import {
  LOCALCODE_EMBEDDER_ASSET_ID,
  LOCALCODE_EMBEDDER_DISPLAY_NAME,
  EMBEDDING_MODEL_ID,
  EMBEDDING_DIMENSIONS,
  TRANSFORMERS_CACHE_KEY,
  REQUIRED_MODEL_FILES,
  EMBEDDER_SIZE_BYTES,
  EMBEDDER_CACHED_KEY,
  LOAD_IDLE_TIMEOUT_MS,
  EMBED_TIMEOUT_MS,
  EMBEDDER_NOT_READY_DETAIL,
  EDGE_OFF_DETAIL,
  NO_WORKER_DETAIL,
  INSTALL_CANCELLED_DETAIL,
  NO_SPACE_DETAIL,
  safeLocalSet,
  safeLocalRemove,
  type EmbedFn,
  type EmbedderBinding,
  getOverrides,
  TEST_EMBEDDER_SIGNATURE,
  modelSignature,
} from "./indexerShared";


// ---------------------------------------------------------------------------
// Is the model REALLY on this device? (the Cache API, not a flag)
// ---------------------------------------------------------------------------

/** Which weights file a load on THIS device would fetch. Mirrors the dtype the
 * load message sends, which the worker uses verbatim — the two must agree or
 * the readiness check would verify a file the load then ignores. WebGPU gets
 * fp32 because an embedding IS the output and quantization error lands
 * straight in the cosine scores; everything else gets the q8 build. */
export function embedderDtype(): EmbedderDtype {
  try {
    return typeof navigator !== "undefined" && (navigator as { gpu?: unknown }).gpu ? "fp32" : "q8";
  } catch {
    return "q8";
  }
}

export function weightsFileFor(dtype: EmbedderDtype): string {
  // transformers.js appends the dtype suffix from its DEFAULT_DTYPE_SUFFIX_MAPPING
  // ("" for fp32, "_quantized" for q8 — read off the installed package).
  return dtype === "fp32" ? "onnx/model.onnx" : "onnx/model_quantized.onnx";
}

/**
 * Walks the real Cache API entries for the model's files — modelled on
 * edge/tier1.ts's checkWeightsOnDevice(), which does the same for WebLLM.
 *
 * true  = every required file is cached, so a load moves no bytes.
 * false = definitively missing (a load WOULD download).
 * null  = could not tell (no Cache API, a private mode that throws). Callers
 *         treat null as "not ready": one extra click costs nothing when the
 *         files turn out to be present, and guessing the other way is the
 *         unattended download this whole check exists to prevent.
 */
export async function embedderWeightsOnDevice(): Promise<boolean | null> {
  try {
    if (typeof caches === "undefined") return null;
    if (!(await caches.has(TRANSFORMERS_CACHE_KEY))) return false;
    const cache = await caches.open(TRANSFORMERS_CACHE_KEY);
    const keys = await cache.keys();
    // Entries are keyed by full hub URL
    // (https://huggingface.co/<model>/resolve/<revision>/<file>), so a
    // substring on the model id plus a suffix on the file name identifies them
    // without this module having to rebuild the library's URL template.
    const needle = `/${EMBEDDING_MODEL_ID}/`;
    const urls: string[] = [];
    for (const request of keys) {
      if (request.url.includes(needle)) urls.push(request.url);
    }
    if (urls.length === 0) return false;
    const required = [...REQUIRED_MODEL_FILES, weightsFileFor(embedderDtype())];
    for (const file of required) {
      if (!urls.some((url: string) => url.endsWith(`/${file}`))) return false;
    }
    return true;
  } catch {
    return null;
  }
}

/** Session-memoized, because a build and a search both ask and cache.keys() is
 * not free. Cleared after an install so the answer flips without a reload. */
let readinessProbe: Promise<boolean> | null = null;

/**
 * Can an index build or a search run right now WITHOUT downloading anything?
 *
 * This is the gate the UI reads to decide between "Index this folder" and
 * "Enable local search". It never downloads, never prompts and never throws.
 */
export async function isLocalEmbedderReady(): Promise<boolean> {
  // Already loaded in this tab: the strongest possible evidence.
  if (workerModelLoaded) return true;
  // The test seam supplies its own embedder; there is nothing to install.
  if (getOverrides()?.embed) return true;
  if (typeof window === "undefined" || typeof Worker === "undefined") return false;
  if (!edgeEnabled()) return false;
  if (readinessProbe === null) {
    readinessProbe = (async () => {
      const present = await embedderWeightsOnDevice();
      if (present === false) {
        // The marker outlived the weights. Clear it so nothing downstream
        // trusts it (tier1.ts does exactly this for the same reason).
        safeLocalRemove(EMBEDDER_CACHED_KEY);
        return false;
      }
      return present === true;
    })().catch(() => false);
  }
  return readinessProbe;
}

// ---------------------------------------------------------------------------
// Embedder worker facade (mirrors edge/face.ts's worker plumbing)
// ---------------------------------------------------------------------------

export interface PendingRequest {
  resolve: (response: EmbedWorkerResponse) => void;
  onProgress?: (p: { loaded: number; total: number; status: string }) => void;
  timer: ReturnType<typeof setTimeout> | null;
  timeoutMs: number;
  /** Idle watchdogs (loads) re-arm on progress; fixed ones (embed) don't. */
  idleWatchdog: boolean;
  timeoutReason: string;
}

let worker: Worker | null = null;
let workerModelLoaded = false;
/** Set from the worker's own `loaded` reply, so the stamp written into the
 * store describes what actually ran — not what this file hoped would run. */
let workerSignature: string | null = null;
let nextRequestId = 1;
export const pending = new Map<number, PendingRequest>();
let loadInFlight: Promise<void> | null = null;

export function failAllPending(reason: string, detail: string): void {
  for (const [requestId, entry] of pending) {
    if (entry.timer) clearTimeout(entry.timer);
    entry.resolve({ type: "error", requestId, reason, detail });
  }
  pending.clear();
}

/** Tear the worker down (hang / hard error / a cancelled install). Terminating
 * is what actually cancels an in-flight model download: the fetches belong to
 * the worker. Cached files survive, so a retry resumes at whole-file
 * granularity — the library only writes a file to the cache once it has the
 * whole response, so a killed transfer leaves no half-file behind. */
export function destroyWorker(reason: string, detail: string): void {
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
  workerSignature = null;
}

export function armTimer(requestId: number, entry: PendingRequest): void {
  if (entry.timer) clearTimeout(entry.timer);
  entry.timer = setTimeout(() => {
    pending.delete(requestId);
    entry.resolve({
      type: "error",
      requestId,
      reason: entry.timeoutReason,
      detail: `no response within ${entry.timeoutMs}ms`,
    });
    // A hung worker can't be trusted for the next batch either.
    destroyWorker(entry.timeoutReason, "worker terminated after timeout");
  }, entry.timeoutMs);
}

export function handleWorkerMessage(response: EmbedWorkerResponse): void {
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

export function getWorker(): Worker | null {
  if (worker) return worker;
  if (typeof window === "undefined" || typeof Worker === "undefined") return null;
  try {
    // Next.js/Turbopack-compatible worker pattern: the inline `new URL(...,
    // import.meta.url)` is statically analyzed and bundled as its own chunk.
    worker = new Worker(new URL("./embedder.worker.ts", import.meta.url));
    worker.addEventListener("message", (event: MessageEvent) => {
      handleWorkerMessage(event.data as EmbedWorkerResponse);
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
export function request(
  message: EmbedWorkerRequest,
  timeoutMs: number,
  timeoutReason: string,
  idleWatchdog: boolean,
  onProgress?: (p: { loaded: number; total: number; status: string }) => void
): Promise<EmbedWorkerResponse> {
  const target = getWorker();
  if (!target) {
    return Promise.resolve({
      type: "error",
      requestId: message.requestId,
      reason: "worker-unsupported",
      detail: NO_WORKER_DETAIL,
    });
  }
  return new Promise<EmbedWorkerResponse>((resolve) => {
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

/**
 * Load the model into the worker. Deliberately says NOTHING about consent: the
 * two callers differ on exactly that point and each handles it.
 *   - installLocalEmbedder() calls this behind a user click, having checked
 *     storage headroom, and it is the path that may move bytes.
 *   - resolveEmbed() calls this only after isLocalEmbedderReady() answered
 *     true, i.e. when every file is already cached and this is a warm-up that
 *     touches the network for nothing.
 * Rejects with a display-safe Error.
 */
export function runLoad(onProgress?: (p: { loaded: number; total: number; status: string }) => void): Promise<void> {
  if (workerModelLoaded) return Promise.resolve();
  if (loadInFlight !== null) return loadInFlight;
  loadInFlight = (async () => {
    const requestId = nextRequestId++;
    const response = await request(
      {
        type: "load",
        requestId,
        modelId: EMBEDDING_MODEL_ID,
        dtype: embedderDtype(),
      },
      LOAD_IDLE_TIMEOUT_MS,
      "load-timeout",
      true,
      onProgress
    );
    if (response.type !== "loaded") {
      throw new Error(
        (response.type === "error" ? response.detail : "") || "on-device embedder setup failed"
      );
    }
    // WHAT THIS CHECK CAN AND CANNOT SEE. `response.dimensions` is measured by
    // the worker — it runs one throwaway input through the freshly built
    // pipeline and reports the width of the tensor that came back (see
    // embedder.worker.ts loadAssets) — so the width half of this comparison is
    // a real check against the real model and it is why the load message no
    // longer carries an expectedDimensions for the worker to echo back.
    // `response.modelId` is NOT verified: the worker reports the id it was
    // asked to load, because the library hands it nothing independent to check
    // that id against. So: a model whose output width is not
    // EMBEDDING_DIMENSIONS is caught here; a model that answers to this id with
    // a *different* model of the same width is not.
    if (response.modelId !== EMBEDDING_MODEL_ID || response.dimensions !== EMBEDDING_DIMENSIONS) {
      // Refusing is the whole point of stamping: a mismatched width scored
      // against stored rows returns confident noise.
      destroyWorker("model-mismatch", "unexpected model identity from the embedding worker");
      throw new Error(
        `The embedding worker loaded ${response.modelId} (${response.dimensions} dimensions), not ${EMBEDDING_MODEL_ID} (${EMBEDDING_DIMENSIONS}).`
      );
    }
    workerModelLoaded = true;
    workerSignature = modelSignature(response.modelId, response.dimensions);
    safeLocalSet(EMBEDDER_CACHED_KEY, "1");
  })().finally(() => {
    loadInFlight = null;
  });
  return loadInFlight;
}

// ---------------------------------------------------------------------------
// Install (the ONE path that may download — explicit user click only)
// ---------------------------------------------------------------------------

export interface EmbedderInstallProgress {
  /** Bytes transferred so far, as reported by the library doing the transfer.
   * 0 until it reports any. */
  loaded: number;
  /** Bytes the transfer is expected to move, as reported by the library doing
   * the transfer — never an estimate made here.
   *
   * 0 means UNKNOWN, and a renderer must show no size at all rather than
   * "0 MB" or a full bar: it is what a warm-up of an already-cached model
   * reports throughout, and what a real download reports until its first
   * progress event. The number can also grow while a download runs, because the
   * library totals the files it has started rather than the files it will
   * eventually fetch. */
  total: number;
  /** Display-safe line for the button/row. When installLocalEmbedder resolves
   * false, the last `detail` it reported says why. */
  detail: string;
}

/**
 * Downloads and warms the on-device embedding model. THE only function in the
 * local-code feature that may move bytes, and it MUST be called from an
 * explicit user action (E-0b rule 2) — it never prompts and never auto-runs.
 *
 * Resolves true when the model is loaded and ready, false otherwise; it never
 * throws, and the reason for a false is the last `detail` passed to onProgress
 * (also mirrored into the shared /edge download chip).
 *
 * Cancellation is real, not cosmetic: aborting terminates the worker, which
 * kills its in-flight fetches. Resumable at whole-file granularity — files the
 * library already finished stay in the browser cache, so a retry re-fetches
 * only what was missing. A single large file that was mid-flight starts over,
 * because the library only caches a complete response.
 */
export async function installLocalEmbedder(
  onProgress: (p: EmbedderInstallProgress) => void,
  signal?: AbortSignal
): Promise<boolean> {
  const report = (loaded: number, total: number, detail: string): void => {
    if (typeof onProgress !== "function") return;
    try {
      onProgress({ loaded, total, detail });
    } catch {
      // A broken listener never breaks an install.
    }
  };
  const chip = (
    patch: Partial<Pick<AssetStatus, "state" | "receivedBytes" | "totalBytes" | "error">>
  ): void => {
    try {
      downloadManager.reportExternalProgress(
        LOCALCODE_EMBEDDER_ASSET_ID,
        LOCALCODE_EMBEDDER_DISPLAY_NAME,
        patch
      );
    } catch {
      // The chip is a convenience; never fail an install over it.
    }
  };

  if (!edgeEnabled()) {
    report(0, 0, EDGE_OFF_DETAIL);
    return false;
  }
  if (typeof window === "undefined" || typeof Worker === "undefined") {
    report(0, 0, NO_WORKER_DETAIL);
    return false;
  }

  // THE ABORT LISTENER GOES ON BEFORE THE FIRST AWAIT. It used to be attached
  // only after the Cache API walk and the storage-headroom check, so a cancel
  // that arrived while either of those was in flight hit no listener at all and
  // the download it was meant to stop started anyway a moment later. Attaching
  // here closes that window; the aborted checks after each await below close
  // the matching one where the abort fires with no worker yet to terminate.
  const onAbort = (): void => {
    destroyWorker("install-cancelled", INSTALL_CANCELLED_DETAIL);
  };
  signal?.addEventListener("abort", onAbort, { once: true });

  /** True once the asset has a row in the shared /edge queue — a cancellation
   * before that should not conjure one just to mark it cancelled. */
  let queued = false;
  /** Reports the cancellation and tells the caller to stop. Called after every
   * await, and once before the first, so an abort is honoured wherever it
   * lands. */
  const abortedNow = (): boolean => {
    if (signal?.aborted !== true) return false;
    if (queued) chip({ state: "error", error: "Cancelled" });
    report(0, 0, INSTALL_CANCELLED_DETAIL);
    return true;
  };

  try {
    if (abortedNow()) return false;

    // Already cached? Then this click is a warm-up, and it must not be allowed
    // to fail on a storage check or announce a download that isn't happening.
    const alreadyOnDevice = (await embedderWeightsOnDevice()) === true;
    if (abortedNow()) return false;

    if (!alreadyOnDevice) {
      // E-0b rule 5: refuse politely when there is no room, rather than filling
      // the disk and failing mid-write. EMBEDDER_SIZE_BYTES is a guessed floor
      // and this is the only thing it is allowed to feed — see its comment.
      const hasRoom = await downloadManager.hasStorageHeadroomFor(EMBEDDER_SIZE_BYTES);
      if (abortedNow()) return false;
      if (!hasRoom) {
        chip({ state: "error", error: "Not enough free space on this device" });
        report(0, 0, NO_SPACE_DETAIL);
        return false;
      }
      // Registers the asset with the shared /edge queue so the download chip
      // can show it. This is the call that reaches GET /api/edge/manifest
      // (property 1 in the header) — on this click and nowhere else.
      // totalBytes 0 is the queue's own encoding of "size not known yet"; the
      // real one is patched in from the library's first progress event.
      try {
        downloadManager.enqueue({
          id: LOCALCODE_EMBEDDER_ASSET_ID,
          displayName: LOCALCODE_EMBEDDER_DISPLAY_NAME,
          totalBytes: 0,
        });
        queued = true;
      } catch {
        // Same as chip(): the queue row is a convenience.
      }
    }

    // Byte counts reported from here come from the library doing the
    // transfer, never from a local estimate. Both stay 0 for a warm-up, which
    // is the truth — a warm-up transfers nothing — and 0 is what tells a
    // renderer to show no size.
    let movedBytes = 0;
    let transferBytes = 0;

    report(
      0,
      0,
      alreadyOnDevice ? "Starting on-device code search…" : "Downloading the on-device embedding model…"
    );
    try {
      await runLoad((p) => {
        if (p.status === "downloading" && p.total > 0) {
          movedBytes = p.loaded;
          transferBytes = p.total;
          // Mirror the library-owned transfer into the shared chip, exactly as
          // vision.ts/face.ts do for downloads the manager doesn't drive itself.
          chip({
            state: p.loaded >= p.total ? "verifying" : "downloading",
            receivedBytes: p.loaded,
            totalBytes: p.total,
          });
          report(p.loaded, p.total, "Downloading the on-device embedding model…");
          return;
        }
        // A non-transfer step (decompress, compile, the load check). Carry the
        // last real counts rather than zeroing them, so the readout doesn't
        // drop the size it has already earned.
        report(movedBytes, transferBytes, "Preparing the on-device embedding model…");
      });
    } catch (err) {
      const cancelled = signal?.aborted === true;
      const detail = cancelled
        ? INSTALL_CANCELLED_DETAIL
        : err instanceof Error && err.message
          ? err.message
          : "The on-device embedding model could not be set up.";
      chip({
        state: "error",
        error: cancelled
          ? "Cancelled"
          : alreadyOnDevice
            ? "Could not start on-device code search"
            : "Download failed",
      });
      report(0, 0, detail);
      return false;
    }

    // A cancel that landed between the worker's last reply and here: the model
    // is loaded, but the person asked to stop, and saying "ready" would make
    // the Cancel button look ignored.
    if (abortedNow()) return false;

    // The probe answered "not ready" a moment ago; make the next caller see the
    // truth without waiting for a reload or re-walking the cache.
    readinessProbe = Promise.resolve(true);
    // Bytes only when the transfer reported some. Omitting them leaves the
    // chip's existing counts alone, which is what a warm-up should do.
    chip(
      transferBytes > 0
        ? { state: "ready", receivedBytes: transferBytes, totalBytes: transferBytes }
        : { state: "ready" }
    );
    void downloadManager.requestPersistentStorage();
    report(transferBytes, transferBytes, "On-device code search is ready.");
    return true;
  } finally {
    signal?.removeEventListener("abort", onAbort);
  }
}

/**
 * @deprecated Call installLocalEmbedder() instead — it reports a display-safe
 * `detail`, takes an AbortSignal, and resolves false rather than throwing.
 *
 * This is a compatibility shape, not a second way in: it IS
 * installLocalEmbedder with the older throw-on-failure signature bolted on, so
 * the "exactly one function may move bytes" property still holds. It exists
 * because a caller outside this agent's scope (LocalCodePanel) still names it
 * as its fallback path. Delete it once that caller is on the new contract.
 */
export async function ensureLocalEmbedderModel(
  onProgress?: (p: { loaded: number; total: number; status: string }) => void
): Promise<void> {
  let last = "";
  const installed = await installLocalEmbedder((p: EmbedderInstallProgress) => {
    last = p.detail;
    if (!onProgress) return;
    try {
      // The old shape's `status` was a raw worker state ("downloading"); the
      // new one's `detail` is a sentence meant for a person. Passing the
      // sentence is strictly better for anything that renders it.
      onProgress({ loaded: p.loaded, total: p.total, status: p.detail });
    } catch {
      // A broken listener never breaks an install.
    }
  });
  if (!installed) throw new Error(last || "The on-device embedding model could not be set up.");
}

/** Resolves the embedder for a build/search, or throws a display-safe Error
 * naming what the person has to do. Warms an already-cached model; it never
 * acquires one — see property (2) in this file's header. */
export async function resolveEmbed(): Promise<EmbedderBinding> {
  const injected = getOverrides();
  if (injected?.embed) {
    return { embed: injected.embed, signature: injected.embedSignature || TEST_EMBEDDER_SIGNATURE };
  }
  if (!edgeEnabled()) throw new Error(EDGE_OFF_DETAIL);
  if (typeof window === "undefined" || typeof Worker === "undefined") throw new Error(NO_WORKER_DETAIL);
  // THE gate. A real cache check, not a marker — and it runs before the worker
  // is even constructed, so the refusal costs nothing.
  if (!(await isLocalEmbedderReady())) throw new Error(EMBEDDER_NOT_READY_DETAIL);
  await runLoad();
  const signature = workerSignature;
  if (!signature) throw new Error(EMBEDDER_NOT_READY_DETAIL);
  const embed: EmbedFn = async (texts: string[]): Promise<Float32Array[]> => {
    const requestId = nextRequestId++;
    const response = await request(
      { type: "embed", requestId, modelId: EMBEDDING_MODEL_ID, texts },
      EMBED_TIMEOUT_MS,
      "embed-timeout",
      false
    );
    if (response.type !== "result") {
      throw new Error(response.type === "error" ? response.detail : "unexpected worker reply");
    }
    const { vectors, count, dimensions } = response;
    if (count !== texts.length) {
      throw new Error(`The embedding model returned ${count} vectors for ${texts.length} chunks.`);
    }
    if (modelSignature(response.modelId, dimensions) !== signature) {
      throw new Error("The embedding worker changed models mid-run.");
    }
    const out: Float32Array[] = [];
    for (let i = 0; i < count; i++) {
      // slice(), not subarray(): each row must own its bytes so the batch's
      // big buffer can be released once the rows are stored.
      out.push(vectors.slice(i * dimensions, (i + 1) * dimensions));
    }
    return out;
  };
  return { embed, signature };
}

