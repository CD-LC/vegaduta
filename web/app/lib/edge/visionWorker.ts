// The dedicated on-device vision worker.
//
// ALL SmolVLM loading AND inference happen in this worker — never on the main
// thread (E-0b rule 1). The main-thread facade is vision.ts; the message
// protocol below is the entire coupling between the two (vision.ts imports
// these types type-only, so nothing from this file reaches the main bundle).
//
// Bundle rule (same as tier1.ts): @huggingface/transformers arrives ONLY via
// dynamic import() on the first load/analyze message — worker boot itself is
// instant and pulls nothing. The library downloads the ONNX weights from the
// Hugging Face hub and caches them in the browser Cache API itself; progress
// is forwarded to the main thread, which mirrors it into the shared
// downloadManager (the same reportExternalProgress pattern tier0/tier1 use
// for downloads this manager doesn't drive byte-by-byte).

import type { ProgressInfo } from "@huggingface/transformers";

// ---------------------------------------------------------------------------
// Message protocol (type-only export — consumed by vision.ts)
// ---------------------------------------------------------------------------

export type VisionWorkerRequest =
  | { type: "load"; requestId: number }
  | { type: "analyze"; requestId: number; frames: string[]; prompt: string };

export type VisionWorkerResponse =
  | { type: "progress"; requestId: number; loaded: number; total: number; status: string }
  | { type: "loaded"; requestId: number }
  | { type: "result"; requestId: number; text: string }
  | { type: "error"; requestId: number; reason: string; detail: string };

// ---------------------------------------------------------------------------
// Constants (honest ceilings for a 500M-class VLM)
// ---------------------------------------------------------------------------

/** The E-5 model (research doc §3 Option D). Reverted 2026-08-07: the
 * 2026-08-06 bake-off swap to SmolVLM2-256M-Video-Instruct was explicitly
 * flagged at the time as "an unvalidated lowest-risk candidate, not a
 * proven win... needs real-device measurement before being trusted" - a
 * live user report ("not detecting objects in the screen") is consistent
 * with exactly that risk materializing (a 256M-class VLM is markedly
 * weaker at object detection/description than the 500M variant). Reverting
 * to the larger, previously-shipped model rather than debugging the
 * smaller one's accuracy further; re-attempt the size-down only alongside
 * real accuracy measurement, not as a blind swap. */
const MODEL_ID = "HuggingFaceTB/SmolVLM2-500M-Video-Instruct";
/** Frames are downscaled so their longest edge is at most this many pixels —
 * a 256M model gains nothing from more, and prefill cost grows fast. */
const MAX_IMAGE_EDGE_PX = 512;
/** Reply length cap: captioning/Q&A answers, not essays. */
const MAX_NEW_TOKENS = 384;

// ---------------------------------------------------------------------------
// Worker scope (typed loosely — tsconfig lib is "dom", not "webworker")
// ---------------------------------------------------------------------------

const workerScope = self as unknown as {
  postMessage(message: VisionWorkerResponse): void;
  addEventListener(type: "message", listener: (event: MessageEvent) => void): void;
  navigator?: { gpu?: unknown };
};

function post(message: VisionWorkerResponse): void {
  try {
    workerScope.postMessage(message);
  } catch {
    // A dead port means the main thread already terminated us — nothing to do.
  }
}

// ---------------------------------------------------------------------------
// Model lifecycle (one processor+model pair per worker)
// ---------------------------------------------------------------------------

type TransformersModule = typeof import("@huggingface/transformers");

interface LoadedAssets {
  tf: TransformersModule;
  processor: any;
  model: any;
}

let assets: LoadedAssets | null = null;
let loadInFlight: Promise<LoadedAssets> | null = null;

/** from_pretrained progress → protocol progress messages. Aggregate byte
 * counts come from the "progress_total" events; the other statuses are
 * forwarded as zero-byte status pulses so the chip never reads as hung. */
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

async function ensureAssets(requestId: number): Promise<LoadedAssets> {
  if (assets) return assets;
  if (loadInFlight !== null) return loadInFlight;
  loadInFlight = (async (): Promise<LoadedAssets> => {
    // THE dynamic import — the only place the library is pulled in.
    const tf = (await import("@huggingface/transformers")) as TransformersModule;
    const progress_callback = (info: ProgressInfo) => forwardProgress(requestId, info);
    // WebGPU when the worker has it, wasm otherwise; q4 weights on GPU
    // (fp16 embeddings — the q4 embed variant degrades noticeably), q8 on
    // wasm where fp16 buffers aren't a win.
    const hasWebGpu = !!workerScope.navigator?.gpu;
    const device = hasWebGpu ? "webgpu" : "wasm";
    const dtype = hasWebGpu
      ? { embed_tokens: "fp16", vision_encoder: "q4", decoder_model_merged: "q4" }
      : "q8";
    const processor = await (tf.AutoProcessor as any).from_pretrained(MODEL_ID, {
      progress_callback,
    });
    const model = await (tf.AutoModelForVision2Seq as any).from_pretrained(MODEL_ID, {
      device,
      dtype,
      progress_callback,
    });
    assets = { tf, processor, model };
    return assets;
  })().finally(() => {
    loadInFlight = null;
  });
  return loadInFlight;
}

// ---------------------------------------------------------------------------
// Frame preparation + the analysis turn
// ---------------------------------------------------------------------------

/** dataURL → RawImage, downscaled to the honest ceiling. All decoding stays
 * in the worker (RawImage.fromURL fetches the data: URL — no DOM needed). */
async function prepareFrame(tf: TransformersModule, dataUrl: string): Promise<any> {
  const image = await tf.RawImage.fromURL(dataUrl);
  const longEdge = Math.max(image.width, image.height);
  if (longEdge <= MAX_IMAGE_EDGE_PX) return image;
  const scale = MAX_IMAGE_EDGE_PX / longEdge;
  return image.resize(
    Math.max(1, Math.round(image.width * scale)),
    Math.max(1, Math.round(image.height * scale))
  );
}

/** Concise instruction template: single frame answers directly; a burst is
 * described frame-by-frame first (motion questions), then answered. */
function buildInstruction(frameCount: number, prompt: string): string {
  const question = prompt.trim() || "Describe what you see.";
  if (frameCount <= 1) {
    return `Answer concisely based on the image. ${question}`;
  }
  return (
    `These ${frameCount} images are frames captured moments apart. ` +
    `Briefly note what each frame shows, then answer concisely: ${question}`
  );
}

async function runAnalysis(requestId: number, frames: string[], prompt: string): Promise<string> {
  const { tf, processor, model } = await ensureAssets(requestId);
  const images = [];
  for (const dataUrl of frames) {
    images.push(await prepareFrame(tf, dataUrl));
  }
  // SmolVLM chat shape: one user message carrying N image slots + the text.
  const messages = [
    {
      role: "user",
      content: [...images.map(() => ({ type: "image" })), { type: "text", text: buildInstruction(images.length, prompt) }],
    },
  ];
  const text = processor.apply_chat_template(messages, { add_generation_prompt: true });
  const inputs = await processor(text, images);
  const generated = await model.generate({
    ...inputs,
    max_new_tokens: MAX_NEW_TOKENS,
    do_sample: false,
  });
  // Decode only the newly generated tokens (drop the prompt prefix).
  const inputLength = inputs.input_ids.dims.at(-1);
  const decoded = processor.batch_decode(generated.slice(null, [inputLength, null]), {
    skip_special_tokens: true,
  });
  return String(decoded?.[0] ?? "").trim();
}

// ---------------------------------------------------------------------------
// Message loop
// ---------------------------------------------------------------------------

async function handleMessage(request: VisionWorkerRequest): Promise<void> {
  if (!request || typeof request !== "object") return;
  if (request.type === "load") {
    try {
      await ensureAssets(request.requestId);
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
  if (request.type === "analyze") {
    try {
      const text = await runAnalysis(request.requestId, request.frames || [], request.prompt || "");
      post({ type: "result", requestId: request.requestId, text });
    } catch (err) {
      post({
        type: "error",
        requestId: request.requestId,
        reason: "analysis-failed",
        detail: err instanceof Error ? err.message : String(err),
      });
    }
  }
}

workerScope.addEventListener("message", (event: MessageEvent) => {
  void handleMessage(event.data as VisionWorkerRequest);
});
