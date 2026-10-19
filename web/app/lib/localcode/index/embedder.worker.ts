// Local-code index — the dedicated on-device text-embedding worker.
//
// ALL MiniLM loading AND inference happen in this worker — never on the main
// thread (edge rule: nothing heavy on the main thread), mirroring
// edge/visionWorker.ts and
// edge/audio/whisperWorker.ts exactly. The main-thread facade lives in
// indexer.ts; the message protocol below is the entire coupling between the two
// (indexer.ts imports these types type-only, so nothing from this file reaches
// the main bundle).
//
// WHO OWNS THE MODEL IDENTITY: indexer.ts does, and it sends it in every
// message. This worker is a generic feature-extraction runner. That inversion
// is not style — indexer.ts has to (a) check the browser cache for the exact
// files a load would fetch before it may promise "no download", and (b) stamp
// the vector space into every stored row so a model swap invalidates instead of
// silently cosine-comparing two different embedding spaces. Both need the model
// id and the dtype on the main thread, and a second copy of them here would be
// a drift waiting to happen.
//
// Bundle rule (same as vision/whisper/tier1): @huggingface/transformers arrives
// ONLY via dynamic import() on the first `load` message — worker boot is
// instant and pulls nothing. The SAME package/version the edge workers already
// use (no second transformers dependency). The library downloads the ONNX
// weights from the Hugging Face hub and caches them in the browser Cache API
// itself; progress is forwarded to the main thread, which mirrors it into the
// shared downloadManager exactly as vision/whisper/face do.
//
// NO IMPLICIT ACQUISITION: an `embed` message on a worker that has not been
// loaded is an error, never a load. That is the last line of defence for
// E-0b rule 2 (nothing downloads without an explicit user click): even if a
// future caller forgets the gate in indexer.ts, an embed can no longer pull
// weights as a side effect.
//
// PRIVACY — the point of the whole feature: the texts that arrive here are the
// user's own source code. This worker fetches model weights and nothing else.
// It never posts a chunk, a vector or a question anywhere but back to the page
// that spawned it, and indexer.ts never sends any of it to the platform API.

import type { ProgressInfo } from "@huggingface/transformers";

// ---------------------------------------------------------------------------
// Message protocol (type-only export — consumed by indexer.ts)
// ---------------------------------------------------------------------------

/** Which weight file to fetch. Decided on the MAIN thread, because the
 * readiness check there has to look for that exact file in the Cache API
 * before it may answer "already on this device". Device selection (WebGPU vs
 * wasm) stays here and deliberately does NOT change which bytes are fetched. */
export type EmbedderDtype = "fp32" | "q8";

export type EmbedWorkerRequest =
  | {
      type: "load";
      requestId: number;
      modelId: string;
      dtype: EmbedderDtype;
    }
  | { type: "embed"; requestId: number; modelId: string; texts: string[] };

export type EmbedWorkerResponse =
  | { type: "progress"; requestId: number; loaded: number; total: number; status: string }
  /** `dimensions` is MEASURED, not echoed: loadAssets runs one throwaway input
   * through the pipeline it just built and reports the width of the tensor that
   * came back. That is what makes the main thread's post-load width check a
   * real check — the load request deliberately no longer carries an expected
   * width for this worker to hand straight back. `modelId` IS an echo of the
   * request, and indexer.ts says so where it compares it. */
  | { type: "loaded"; requestId: number; modelId: string; dimensions: number }
  /** `vectors` is ONE flat Float32Array of count*dimensions — a transferable
   * buffer, so a whole batch is one zero-copy hand-off instead of one
   * structured clone per row. The main thread slices it back apart.
   * `modelId` rides along so the main thread can stamp the vector space into
   * the store rather than assuming it. */
  | {
      type: "result";
      requestId: number;
      modelId: string;
      vectors: Float32Array;
      count: number;
      dimensions: number;
    }
  | { type: "error"; requestId: number; reason: string; detail: string };

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Hard ceiling on one input string, in CHARACTERS — the only unit this worker
 * can measure without running the tokenizer. It is NOT the model's token
 * window: the tokenizer applies the model's own max_seq_length on top of this
 * and truncates whatever is left over, and this file has no way to verify that
 * number. chunker.ts is what actually keeps a chunk inside the window (its
 * DEFAULT_MAX_CHARS is far smaller than this); this constant exists only so a
 * caller that hands us a generated one-liner can't push a megabyte string
 * through the tokenizer. */
const MAX_INPUT_CHARS = 4000;

// ---------------------------------------------------------------------------
// Worker scope (typed loosely — tsconfig lib is "dom", not "webworker")
// ---------------------------------------------------------------------------

const workerScope = self as unknown as {
  postMessage(message: EmbedWorkerResponse, transfer?: Transferable[]): void;
  addEventListener(type: "message", listener: (event: MessageEvent) => void): void;
  navigator?: { gpu?: unknown };
};

function post(message: EmbedWorkerResponse, transfer?: Transferable[]): void {
  try {
    workerScope.postMessage(message, transfer);
  } catch {
    // A dead port means the main thread already terminated us — nothing to do.
  }
}

// ---------------------------------------------------------------------------
// Model lifecycle (one pipeline per worker)
// ---------------------------------------------------------------------------

type TransformersModule = typeof import("@huggingface/transformers");

interface LoadedAssets {
  extractor: any;
  modelId: string;
  dimensions: number;
}

let assets: LoadedAssets | null = null;
let loadInFlight: Promise<LoadedAssets> | null = null;

/** from_pretrained progress → protocol progress messages — identical mapping to
 * visionWorker.ts / whisperWorker.ts's forwardProgress. */
function forwardProgress(requestId: number, info: ProgressInfo): void {
  try {
    if (info.status === "progress_total") {
      post({
        type: "progress",
        requestId,
        loaded: info.loaded || 0,
        total: info.total || 0,
        status: "downloading",
      });
    } else if (info.status === "initiate" || info.status === "done") {
      post({ type: "progress", requestId, loaded: 0, total: 0, status: info.status });
    }
  } catch {
    // Progress is best-effort — never break a load over it.
  }
}

async function loadAssets(
  requestId: number,
  modelId: string,
  dtype: EmbedderDtype
): Promise<LoadedAssets> {
  if (assets) {
    if (assets.modelId !== modelId) {
      // One worker, one vector space. A swap has to go through a fresh worker
      // so the main thread's store-invalidation path runs with it.
      throw new Error(`This embedding worker is already loaded with ${assets.modelId}.`);
    }
    return assets;
  }
  if (loadInFlight !== null) return loadInFlight;
  loadInFlight = (async (): Promise<LoadedAssets> => {
    // THE dynamic import — the only place the library is pulled in.
    const tf = (await import("@huggingface/transformers")) as TransformersModule;
    const progress_callback = (info: ProgressInfo) => forwardProgress(requestId, info);
    const hasWebGpu = !!workerScope.navigator?.gpu;
    // dtype comes from the caller (see EmbedderDtype) and decides which file is
    // fetched; device is decided here and only decides where the maths runs.
    // fp32 rather than the fp16 the sibling generative workers use: an
    // embedding IS the output here, so quantization error lands directly in the
    // cosine scores that decide which lines get cited. A device without WebGPU
    // still runs the SAME weights on wasm — slower, but it never turns into a
    // surprise second download of a different file.
    const extractor = await (tf as any).pipeline("feature-extraction", modelId, {
      device: dtype === "fp32" && hasWebGpu ? "webgpu" : "wasm",
      dtype,
      progress_callback,
    });
    // MEASURE the output width instead of repeating back a number the caller
    // sent. The load reply used to carry the caller's own expectedDimensions
    // straight back, which made the main thread's "did it load what I asked
    // for?" check compare that number against itself — it could not fail, and a
    // guard that cannot fail reads as protection while providing none.
    // One short input through the pipeline produces a real tensor to measure,
    // and doubles as proof the model can run at all before anything is
    // declared ready.
    const probe = await extractor([" "], { pooling: "mean", normalize: true });
    const probeData: ArrayLike<number> | undefined = probe?.data;
    if (!probeData || typeof probeData.length !== "number" || probeData.length === 0) {
      throw new Error("The embedding model loaded but produced no output.");
    }
    // One input in, so the row IS the tensor.
    assets = { extractor, modelId, dimensions: probeData.length };
    return assets;
  })().finally(() => {
    loadInFlight = null;
  });
  return loadInFlight;
}

// ---------------------------------------------------------------------------
// The embedding turn
// ---------------------------------------------------------------------------

async function runEmbed(
  loaded: LoadedAssets,
  texts: string[]
): Promise<{ vectors: Float32Array; count: number; dimensions: number }> {
  const inputs = texts.map((t: string) => {
    const value = typeof t === "string" ? t : "";
    // An all-whitespace chunk tokenizes to nothing and comes back as NaNs
    // after mean pooling; a single space keeps the row finite and simply
    // uninteresting to cosine similarity.
    const trimmed = value.length > MAX_INPUT_CHARS ? value.slice(0, MAX_INPUT_CHARS) : value;
    return trimmed.trim().length === 0 ? " " : trimmed;
  });
  // mean pooling + L2 normalize: the standard sentence-transformers recipe, and
  // it makes every stored vector unit-length so cosine similarity on the main
  // thread is a dot product in all but name.
  const output = await loaded.extractor(inputs, { pooling: "mean", normalize: true });
  const data: ArrayLike<number> = output?.data;
  if (!data || typeof data.length !== "number" || data.length === 0) {
    throw new Error("The embedding model produced no output.");
  }
  const count = inputs.length;
  const dimensions = Math.floor(data.length / count);
  // This assert covers THIS batch only — it proves the tensor we just produced
  // is as wide as the one measured at load time, both real tensors from this
  // pipeline. It cannot say anything about rows already in the store;
  // cross-model safety is indexer.ts's job, and it does it by stamping
  // `modelId@dimensions` onto every stored row (see its modelSignature()).
  // Both halves are needed: this one catches a width that moved under a loaded
  // pipeline, that one catches a model swap.
  if (dimensions !== loaded.dimensions) {
    throw new Error(
      `Embedding width ${dimensions} does not match the expected ${loaded.dimensions} for ${loaded.modelId}.`
    );
  }
  // Copy into a fresh Float32Array: the pipeline's own buffer may be pooled or
  // wasm-heap-backed, and we are about to transfer ownership of this one away.
  const vectors = new Float32Array(count * dimensions);
  for (let i = 0; i < vectors.length; i++) vectors[i] = data[i];
  return { vectors, count, dimensions };
}

// ---------------------------------------------------------------------------
// Message loop
// ---------------------------------------------------------------------------

async function handleMessage(request: EmbedWorkerRequest): Promise<void> {
  if (!request || typeof request !== "object") return;
  if (request.type === "load") {
    try {
      if (!request.modelId) throw new Error("No model id was supplied to the embedding worker.");
      const loaded = await loadAssets(
        request.requestId,
        request.modelId,
        request.dtype === "fp32" ? "fp32" : "q8"
      );
      post({
        type: "loaded",
        requestId: request.requestId,
        modelId: loaded.modelId,
        dimensions: loaded.dimensions,
      });
    } catch (err) {
      post({
        type: "error",
        requestId: request.requestId,
        reason: "load-failed",
        detail: err instanceof Error ? err.message : String(err),
      });
    }
    return;
  }
  if (request.type === "embed") {
    try {
      // The E-0b rule 2 backstop described in the header: embedding never
      // acquires a model, it only uses one that an explicit load already put
      // on this device.
      const loaded = assets;
      if (!loaded) {
        post({
          type: "error",
          requestId: request.requestId,
          reason: "not-loaded",
          detail: "The embedding model is not loaded in this worker yet.",
        });
        return;
      }
      if (request.modelId && request.modelId !== loaded.modelId) {
        post({
          type: "error",
          requestId: request.requestId,
          reason: "model-mismatch",
          detail: `This worker is loaded with ${loaded.modelId}, not ${request.modelId}.`,
        });
        return;
      }
      const texts = Array.isArray(request.texts) ? request.texts : [];
      if (texts.length === 0) {
        post({
          type: "result",
          requestId: request.requestId,
          modelId: loaded.modelId,
          vectors: new Float32Array(0),
          count: 0,
          dimensions: loaded.dimensions,
        });
        return;
      }
      const { vectors, count, dimensions } = await runEmbed(loaded, texts);
      post(
        { type: "result", requestId: request.requestId, modelId: loaded.modelId, vectors, count, dimensions },
        [vectors.buffer as ArrayBuffer]
      );
    } catch (err) {
      post({
        type: "error",
        requestId: request.requestId,
        reason: "embed-failed",
        detail: err instanceof Error ? err.message : String(err),
      });
    }
  }
}

workerScope.addEventListener("message", (event: MessageEvent) => {
  void handleMessage(event.data as EmbedWorkerRequest);
});
