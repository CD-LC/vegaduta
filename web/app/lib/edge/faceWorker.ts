// S-edge companion: on-device face recognition worker.
//
// ALL MediaPipe + onnxruntime-web work happens ONLY in this worker, never the
// main thread (E-0b rule 1 — see vision.ts's header comment "E-0b rule 1").
// The main-thread facade is face.ts; the message protocol below is the
// entire coupling between the two (face.ts imports these types type-only, so
// nothing from this file reaches the main bundle) — the same shape as
// visionWorker.ts/vision.ts.
//
// Bundle rule (same as tier1.ts / visionWorker.ts): @mediapipe/tasks-vision
// and onnxruntime-web arrive ONLY via dynamic import() on the first
// load-detector / load-embedder message — worker boot itself is instant and
// pulls nothing.
//
// Two independent on-device assets, loaded independently but reported to the
// caller as ONE logical download (face.ts aggregates their progress under
// FACE_ASSET_ID):
//   - detector: MediaPipe BlazeFace (short-range). Both the WASM fileset AND
//     the .tflite weights are fetched by the BROWSER directly from Google's
//     own CDN/storage — never our servers. MediaPipe's public API exposes no
//     byte-level progress hook for this load, so it only pulses
//     "initiate"/"done" (the same treatment visionWorker.ts gives
//     transformers.js's non-progress_total statuses).
//   - embedder: ONNX Model Zoo ArcFace ResNet100 (int8), fetched directly
//     from the Hugging Face hub via a manual streamed fetch (real byte-level
//     progress — onnxruntime-web's InferenceSession.create has no progress
//     hook of its own), cached in a dedicated Cache Storage bucket so a
//     later warm-up needs no network, then handed to onnxruntime-web as raw
//     bytes.
//
// Tensor names (input/output) are read from the loaded session at runtime —
// NEVER hardcoded. A wrong guessed name would silently fail or throw at
// inference time for every single user (same defensive precedent as
// rife.ts's ONNX input-name introspection for a different model).

// ---------------------------------------------------------------------------
// Message protocol (type-only export — consumed by face.ts)
// ---------------------------------------------------------------------------

export type FaceWorkerRequest =
  | { type: "load-detector"; requestId: number }
  | { type: "load-embedder"; requestId: number }
  | { type: "detect-and-embed"; requestId: number; dataUrl: string; requireSingleFace: boolean };

export type FaceWorkerResponse =
  | { type: "progress"; requestId: number; loaded: number; total: number; status: string }
  | { type: "loaded"; requestId: number }
  | { type: "detected"; requestId: number; embedding: number[] }
  | { type: "error"; requestId: number; reason: string; detail: string };

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** MediaPipe's official CDN-hosted WASM fileset for vision tasks. */
const DETECTOR_WASM_BASE = "https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@latest/wasm";
/** BlazeFace short-range detector weights, Google's own storage bucket. */
const DETECTOR_MODEL_URL =
  "https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/float16/1/blaze_face_short_range.tflite";
/** ONNX Model Zoo ArcFace ResNet100, int8-quantized variant (~63 MB —
 * substantially smaller than the fp32 ~249 MB variant), hosted on the
 * Hugging Face hub. */
const EMBEDDER_MODEL_URL =
  "https://huggingface.co/onnxmodelzoo/arcfaceresnet100-8/resolve/main/model/arcfaceresnet100-11-int8.onnx";
/** Dedicated Cache Storage bucket for the embedder weights — kept separate
 * from transformers.js's own "transformers-cache" bucket (vision.ts) since
 * this fetch is manual, not library-owned. face.ts's deleteFaceModel opens
 * this exact name to evict on delete (duplicated there — see its comment). */
const EMBEDDER_CACHE_NAME = "vegaduta-face-embedder-cache";
/** ArcFace's standard input convention. */
const EMBEDDER_INPUT_SIZE = 112;
/** Bounding-box margin added on each side before the 112x112 crop, clamped
 * to image bounds — standard, well-documented ArcFace preprocessing
 * convention. */
const FACE_MARGIN_RATIO = 0.2;

// ---------------------------------------------------------------------------
// Worker scope (typed loosely — tsconfig lib is "dom", not "webworker", same
// as visionWorker.ts)
// ---------------------------------------------------------------------------

const workerScope = self as unknown as {
  postMessage(message: FaceWorkerResponse): void;
  addEventListener(type: "message", listener: (event: MessageEvent) => void): void;
  navigator?: { gpu?: unknown };
};

function post(message: FaceWorkerResponse): void {
  try {
    workerScope.postMessage(message);
  } catch {
    // A dead port means the main thread already terminated us — nothing to do.
  }
}

// ---------------------------------------------------------------------------
// Detector lifecycle (MediaPipe BlazeFace)
// ---------------------------------------------------------------------------

/** Loosely typed — @mediapipe/tasks-vision publishes its own types, but this
 * worker only touches a handful of methods and the package arrives via
 * dynamic import only (mirrors visionWorker.ts's `processor: any; model:
 * any` precedent for the same reason: no static top-level import of the
 * library). */
let faceDetector: any = null;
let detectorLoadInFlight: Promise<any> | null = null;

async function ensureDetector(requestId: number): Promise<any> {
  if (faceDetector) return faceDetector;
  if (detectorLoadInFlight !== null) return detectorLoadInFlight;
  detectorLoadInFlight = (async () => {
    post({ type: "progress", requestId, loaded: 0, total: 0, status: "initiate" });
    // THE dynamic import — the only place @mediapipe/tasks-vision is pulled in.
    const tasksVision: any = await import("@mediapipe/tasks-vision");
    const vision = await tasksVision.FilesetResolver.forVisionTasks(DETECTOR_WASM_BASE);
    const detector = await tasksVision.FaceDetector.createFromOptions(vision, {
      baseOptions: { modelAssetPath: DETECTOR_MODEL_URL },
      runningMode: "IMAGE",
    });
    post({ type: "progress", requestId, loaded: 0, total: 0, status: "done" });
    faceDetector = detector;
    return detector;
  })().finally(() => {
    detectorLoadInFlight = null;
  });
  return detectorLoadInFlight;
}

// ---------------------------------------------------------------------------
// Embedder lifecycle (ArcFace ONNX via onnxruntime-web)
// ---------------------------------------------------------------------------

type OrtModule = typeof import("onnxruntime-web");

let embedderSession: any = null;
let embedderInputName: string | null = null;
let embedderOutputName: string | null = null;
let embedderLoadInFlight: Promise<void> | null = null;

/** Streamed fetch with real byte-level progress — onnxruntime-web's own
 * InferenceSession.create has no progress hook, so this is the only source
 * of real numbers for the chip during the embedder's (bulk of the) load. */
async function fetchWithProgress(url: string, requestId: number): Promise<Uint8Array> {
  const res = await fetch(url);
  if (!res.ok) {
    throw new Error(`Model download failed with HTTP ${res.status}`);
  }
  if (!res.body) {
    const buf = new Uint8Array(await res.arrayBuffer());
    post({ type: "progress", requestId, loaded: buf.byteLength, total: buf.byteLength, status: "downloading" });
    return buf;
  }
  const totalHeader = res.headers.get("content-length");
  const total = totalHeader ? Number(totalHeader) : 0;
  const reader = res.body.getReader();
  const chunks: Uint8Array[] = [];
  let loaded = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    if (value) {
      chunks.push(value);
      loaded += value.byteLength;
      post({ type: "progress", requestId, loaded, total, status: "downloading" });
    }
  }
  const out = new Uint8Array(loaded);
  let offset = 0;
  for (const chunk of chunks) {
    out.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return out;
}

/** Cache-first byte fetch: a prior successful load leaves the weights in the
 * dedicated Cache Storage bucket, so a later warm-up needs no network at all
 * (matches transformers.js's own behavior for the vision model). */
async function fetchModelBytesCached(requestId: number): Promise<Uint8Array> {
  const cacheAvailable = typeof caches !== "undefined";
  if (cacheAvailable) {
    try {
      const cache = await caches.open(EMBEDDER_CACHE_NAME);
      const cached = await cache.match(EMBEDDER_MODEL_URL);
      if (cached) {
        const buf = new Uint8Array(await cached.arrayBuffer());
        post({ type: "progress", requestId, loaded: buf.byteLength, total: buf.byteLength, status: "downloading" });
        return buf;
      }
    } catch {
      // Cache read failed — fall through to a real network fetch.
    }
  }
  const bytes = await fetchWithProgress(EMBEDDER_MODEL_URL, requestId);
  if (cacheAvailable) {
    try {
      const cache = await caches.open(EMBEDDER_CACHE_NAME);
      await cache.put(EMBEDDER_MODEL_URL, new Response(bytes.slice()));
    } catch {
      // Best-effort — inference still proceeds from the in-memory bytes.
    }
  }
  return bytes;
}

async function ensureEmbedder(requestId: number): Promise<void> {
  if (embedderSession) return;
  if (embedderLoadInFlight !== null) return embedderLoadInFlight;
  embedderLoadInFlight = (async () => {
    // THE dynamic import — the only place onnxruntime-web is pulled in.
    const ort: OrtModule = (await import("onnxruntime-web")) as OrtModule;
    const modelBytes = await fetchModelBytesCached(requestId);
    post({
      type: "progress",
      requestId,
      loaded: modelBytes.byteLength,
      total: modelBytes.byteLength,
      status: "verifying",
    });
    // WebGPU EP when the worker has it, WASM fallback otherwise — same
    // preference order rife.ts uses for its own ONNX models.
    const hasWebGpu = !!workerScope.navigator?.gpu;
    let session: any = null;
    if (hasWebGpu) {
      try {
        session = await ort.InferenceSession.create(modelBytes, { executionProviders: ["webgpu"] });
      } catch {
        session = null; // WebGPU EP unavailable/failed — WASM below.
      }
    }
    if (!session) {
      session = await ort.InferenceSession.create(modelBytes, { executionProviders: ["wasm"] });
    }
    // Defensive: read the REAL tensor names rather than guessing — a wrong
    // hardcoded name would silently fail or throw at inference time for
    // every single user (contract requirement, see file header).
    const inputName = session.inputNames?.[0];
    const outputName = session.outputNames?.[0];
    if (!inputName || !outputName) {
      throw new Error("ArcFace ONNX session exposed no input/output tensor names.");
    }
    embedderSession = session;
    embedderInputName = inputName;
    embedderOutputName = outputName;
    post({
      type: "progress",
      requestId,
      loaded: modelBytes.byteLength,
      total: modelBytes.byteLength,
      status: "done",
    });
  })().finally(() => {
    embedderLoadInFlight = null;
  });
  return embedderLoadInFlight;
}

// ---------------------------------------------------------------------------
// Frame preparation + the detect-and-embed turn
// ---------------------------------------------------------------------------

/** dataURL → ImageBitmap. All decoding stays in the worker — fetch() of a
 * data: URL and createImageBitmap() are both worker-safe, no DOM needed. */
async function decodeDataUrl(dataUrl: string): Promise<ImageBitmap> {
  const res = await fetch(dataUrl);
  const blob = await res.blob();
  return await createImageBitmap(blob);
}

interface FaceBox {
  originX: number;
  originY: number;
  width: number;
  height: number;
}

/** Crop the detected face (± FACE_MARGIN_RATIO margin, clamped to image
 * bounds), resize to 112x112, and normalize to ArcFace's (value - 127.5) /
 * 128.0 CHW float32 convention. */
function cropAndResizeFace(image: ImageBitmap, box: FaceBox): Float32Array {
  const marginX = box.width * FACE_MARGIN_RATIO;
  const marginY = box.height * FACE_MARGIN_RATIO;
  const sx = Math.max(0, Math.floor(box.originX - marginX));
  const sy = Math.max(0, Math.floor(box.originY - marginY));
  const ex = Math.min(image.width, Math.ceil(box.originX + box.width + marginX));
  const ey = Math.min(image.height, Math.ceil(box.originY + box.height + marginY));
  const sw = Math.max(1, ex - sx);
  const sh = Math.max(1, ey - sy);

  const canvas = new OffscreenCanvas(EMBEDDER_INPUT_SIZE, EMBEDDER_INPUT_SIZE);
  const ctx = canvas.getContext("2d");
  if (!ctx) throw new Error("Couldn't get a 2D context for face preprocessing.");
  ctx.drawImage(image, sx, sy, sw, sh, 0, 0, EMBEDDER_INPUT_SIZE, EMBEDDER_INPUT_SIZE);
  const { data } = ctx.getImageData(0, 0, EMBEDDER_INPUT_SIZE, EMBEDDER_INPUT_SIZE);

  const plane = EMBEDDER_INPUT_SIZE * EMBEDDER_INPUT_SIZE;
  const chw = new Float32Array(3 * plane);
  for (let i = 0; i < plane; i++) {
    const j = i * 4;
    chw[i] = (data[j] - 127.5) / 128.0;
    chw[plane + i] = (data[j + 1] - 127.5) / 128.0;
    chw[2 * plane + i] = (data[j + 2] - 127.5) / 128.0;
  }
  return chw;
}

async function embedFace(chw: Float32Array): Promise<number[]> {
  const ort: OrtModule = (await import("onnxruntime-web")) as OrtModule;
  const tensor = new ort.Tensor("float32", chw, [1, 3, EMBEDDER_INPUT_SIZE, EMBEDDER_INPUT_SIZE]);
  const feeds: Record<string, any> = { [embedderInputName as string]: tensor };
  const results = await embedderSession.run(feeds);
  const output = results[embedderOutputName as string];
  if (!output) throw new Error("The embedding model produced no output.");
  const raw = Array.from(output.data as Float32Array);
  // L2-normalize so cosine similarity and dot product become equivalent
  // downstream (face.ts's cosineSimilarity / FACE_MATCH_THRESHOLD).
  let sumSq = 0;
  for (const v of raw) sumSq += v * v;
  const norm = Math.sqrt(sumSq) || 1;
  return raw.map((v) => v / norm);
}

async function handleDetectAndEmbed(requestId: number, dataUrl: string, requireSingleFace: boolean): Promise<void> {
  let image: ImageBitmap | null = null;
  try {
    let detector: any;
    try {
      detector = await ensureDetector(requestId);
    } catch (err) {
      post({
        type: "error",
        requestId,
        reason: "detection-failed",
        detail: err instanceof Error ? err.message : String(err),
      });
      return;
    }
    try {
      image = await decodeDataUrl(dataUrl);
    } catch (err) {
      post({
        type: "error",
        requestId,
        reason: "detection-failed",
        detail: err instanceof Error ? err.message : String(err),
      });
      return;
    }
    let result: any;
    try {
      result = detector.detect(image);
    } catch (err) {
      post({
        type: "error",
        requestId,
        reason: "detection-failed",
        detail: err instanceof Error ? err.message : String(err),
      });
      return;
    }
    const detections = Array.isArray(result?.detections) ? result.detections : [];
    if (detections.length === 0) {
      post({ type: "error", requestId, reason: "no-face-detected", detail: "No face was detected in the image." });
      return;
    }
    if (requireSingleFace && detections.length > 1) {
      post({
        type: "error",
        requestId,
        reason: "multiple-faces-detected",
        detail: "More than one face was detected — enrollment needs exactly one.",
      });
      return;
    }
    // Matching path (requireSingleFace=false): pick the largest/most-central
    // face when several are in frame. Scope decision for v1 — a camera turn
    // showing several people still returns at most one match; good enough
    // for now, not a silent limitation (see face.ts's matchFaceFromDataUrl
    // doc comment).
    const chosen = detections.reduce((best: any, d: any) =>
      d.boundingBox.width * d.boundingBox.height > best.boundingBox.width * best.boundingBox.height ? d : best
    );

    let chw: Float32Array;
    try {
      chw = cropAndResizeFace(image, chosen.boundingBox);
    } catch (err) {
      post({
        type: "error",
        requestId,
        reason: "detection-failed",
        detail: err instanceof Error ? err.message : String(err),
      });
      return;
    }

    try {
      await ensureEmbedder(requestId);
      const embedding = await embedFace(chw);
      post({ type: "detected", requestId, embedding });
    } catch (err) {
      post({
        type: "error",
        requestId,
        reason: "embedding-failed",
        detail: err instanceof Error ? err.message : String(err),
      });
    }
  } finally {
    try {
      image?.close();
    } catch {
      // Already closed.
    }
  }
}

// ---------------------------------------------------------------------------
// Message loop
// ---------------------------------------------------------------------------

async function handleMessage(request: FaceWorkerRequest): Promise<void> {
  if (!request || typeof request !== "object") return;
  if (request.type === "load-detector") {
    try {
      await ensureDetector(request.requestId);
      post({ type: "loaded", requestId: request.requestId });
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
  if (request.type === "load-embedder") {
    try {
      await ensureEmbedder(request.requestId);
      post({ type: "loaded", requestId: request.requestId });
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
  if (request.type === "detect-and-embed") {
    await handleDetectAndEmbed(request.requestId, request.dataUrl, request.requireSingleFace);
  }
}

workerScope.addEventListener("message", (event: MessageEvent) => {
  void handleMessage(event.data as FaceWorkerRequest);
});
