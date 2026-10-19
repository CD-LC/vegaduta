// The dedicated on-device speech-to-text worker.
//
// ALL Whisper loading AND inference happen in this worker — never on the main
// thread (E-0b rule 1), mirroring visionWorker.ts exactly. The main-thread
// facade is whisper.ts; the message protocol below is the entire coupling
// between the two (whisper.ts imports these types type-only, so nothing from
// this file reaches the main bundle).
//
// Bundle rule (same as vision/tier1): @huggingface/transformers arrives ONLY
// via dynamic import() on the first load/transcribe message — worker boot
// itself is instant and pulls nothing. The SAME package/version already used
// by vision.ts/visionWorker.ts (no second transformers dependency). The
// library downloads the ONNX weights from the Hugging Face hub and caches
// them in the browser Cache API itself; progress is forwarded to the main
// thread, which mirrors it into the shared downloadManager the same way
// tier0/tier1/vision do for downloads this manager doesn't drive byte-by-byte.
//
// Audio decode (Blob -> 16kHz mono Float32 PCM) happens on the MAIN THREAD in
// whisper.ts before the samples are transferred in here — OfflineAudioContext
// is not reliably available in Worker scope across browsers yet, and decoding
// a short push-to-talk utterance is a few milliseconds, not a main-thread
// hazard. Everything CPU/GPU-heavy (model load + inference) stays in here.

import type { ProgressInfo } from "@huggingface/transformers";

// ---------------------------------------------------------------------------
// Message protocol (type-only export — consumed by whisper.ts)
// ---------------------------------------------------------------------------

export type WhisperWorkerRequest =
  | { type: "load"; requestId: number }
  | { type: "transcribe"; requestId: number; audio: Float32Array; language?: string };

export type WhisperWorkerResponse =
  | { type: "progress"; requestId: number; loaded: number; total: number; status: string }
  | { type: "loaded"; requestId: number }
  | { type: "result"; requestId: number; text: string }
  | { type: "error"; requestId: number; reason: string; detail: string };

// ---------------------------------------------------------------------------
// Constants (honest ceiling for a tiny-class Whisper model)
// ---------------------------------------------------------------------------

/** English-only tiny Whisper — the id the plan's E-4 task named as the
 * default. The plan doc did not itself call out broader-language scope for
 * this wave, so this is the literal default; swapping to the multilingual
 * "Xenova/whisper-tiny" id is a one-line change here (see whisper.ts's header
 * comment) whenever the platform's Indian-language posture wants it. */
const MODEL_ID = "Xenova/whisper-tiny.en";
/** Whisper's own training window; transcribeAudioLocally in whisper.ts caps
 * input at this length before it ever reaches the worker — chunk_length_s
 * below lets the pipeline internally window audio up to that cap in <=30s
 * slices (chunked, NOT true streaming — matches Whisper's real capability). */
const CHUNK_LENGTH_S = 30;
const STRIDE_LENGTH_S = 5;

// ---------------------------------------------------------------------------
// Worker scope (typed loosely — tsconfig lib is "dom", not "webworker")
// ---------------------------------------------------------------------------

const workerScope = self as unknown as {
  postMessage(message: WhisperWorkerResponse, transfer?: Transferable[]): void;
  addEventListener(type: "message", listener: (event: MessageEvent) => void): void;
  navigator?: { gpu?: unknown };
};

function post(message: WhisperWorkerResponse): void {
  try {
    workerScope.postMessage(message);
  } catch {
    // A dead port means the main thread already terminated us — nothing to do.
  }
}

// ---------------------------------------------------------------------------
// Model lifecycle (one pipeline per worker)
// ---------------------------------------------------------------------------

type TransformersModule = typeof import("@huggingface/transformers");

interface LoadedAssets {
  tf: TransformersModule;
  transcriber: any;
}

let assets: LoadedAssets | null = null;
let loadInFlight: Promise<LoadedAssets> | null = null;

/** from_pretrained progress → protocol progress messages — identical mapping
 * to visionWorker.ts's forwardProgress. */
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
    const hasWebGpu = !!workerScope.navigator?.gpu;
    const transcriber = await (tf as any).pipeline("automatic-speech-recognition", MODEL_ID, {
      device: hasWebGpu ? "webgpu" : "wasm",
      dtype: hasWebGpu ? "fp16" : "q8",
      progress_callback,
    });
    assets = { tf, transcriber };
    return assets;
  })().finally(() => {
    loadInFlight = null;
  });
  return loadInFlight;
}

// ---------------------------------------------------------------------------
// The transcription turn
// ---------------------------------------------------------------------------

async function runTranscription(
  requestId: number,
  audio: Float32Array,
  language: string | undefined
): Promise<string> {
  const { transcriber } = await ensureAssets(requestId);
  const output = await transcriber(audio, {
    chunk_length_s: CHUNK_LENGTH_S,
    stride_length_s: STRIDE_LENGTH_S,
    ...(language ? { language } : {}),
  });
  if (Array.isArray(output)) {
    return output.map((o: any) => String(o?.text ?? "")).join(" ").trim();
  }
  return String(output?.text ?? "").trim();
}

// ---------------------------------------------------------------------------
// Message loop
// ---------------------------------------------------------------------------

async function handleMessage(request: WhisperWorkerRequest): Promise<void> {
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
  if (request.type === "transcribe") {
    try {
      const text = await runTranscription(request.requestId, request.audio, request.language);
      post({ type: "result", requestId: request.requestId, text });
    } catch (err) {
      post({
        type: "error",
        requestId: request.requestId,
        reason: "transcribe-failed",
        detail: err instanceof Error ? err.message : String(err),
      });
    }
  }
}

workerScope.addEventListener("message", (event: MessageEvent) => {
  void handleMessage(event.data as WhisperWorkerRequest);
});
