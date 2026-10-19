// The dedicated on-device text-to-speech worker.
//
// ALL Kokoro loading AND inference happen in this worker — never on the main
// thread (E-0b rule 1), mirroring visionWorker.ts/whisperWorker.ts. The
// main-thread facade is kokoro.ts; the message protocol below is the entire
// coupling between the two.
//
// Bundle rule (same as vision/tier1/whisper): `kokoro-js` arrives ONLY via
// dynamic import() on the first load/synthesize message — worker boot itself
// is instant and pulls nothing. kokoro-js bundles its OWN transformers.js
// dependency internally (it is not a peer of the app's own
// @huggingface/transformers — the two are independent npm resolutions); this
// is a known, unavoidable transitive duplication documented in kokoro.ts's
// header rather than hidden. The library downloads the ONNX weights from the
// Hugging Face hub and caches them in the browser Cache API itself, exactly
// like transformers.js does for vision/whisper; progress is forwarded to the
// main thread and mirrored into the shared downloadManager the same way.

import type { KokoroTTS as KokoroTTSType } from "kokoro-js";

// ---------------------------------------------------------------------------
// Message protocol (type-only export — consumed by kokoro.ts)
// ---------------------------------------------------------------------------

export type KokoroWorkerRequest =
  | { type: "load"; requestId: number }
  | { type: "synthesize"; requestId: number; text: string; voice?: string };

export type KokoroWorkerResponse =
  | { type: "progress"; requestId: number; loaded: number; total: number; status: string }
  | { type: "loaded"; requestId: number }
  | { type: "result"; requestId: number; audioBlob: Blob; sampleRate: number }
  | { type: "error"; requestId: number; reason: string; detail: string };

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** onnx-community's published Kokoro-82M ONNX export — the id kokoro-js's
 * own README examples use with KokoroTTS.from_pretrained(). */
const MODEL_ID = "onnx-community/Kokoro-82M-v1.0-ONNX";
/** A neutral, widely-used default voice (Kokoro ships many — see
 * tts.list_voices() at runtime). Callers may override per call. */
const DEFAULT_VOICE = "af_heart";
/** Honest ceiling: this is a per-sentence/short-utterance TTS chain (CallPanel
 * feeds it one sentence fragment at a time) — cap defensively so a runaway
 * caller can't hand Kokoro a whole essay in one on-device synth. */
const MAX_TEXT_CHARS = 600;

// ---------------------------------------------------------------------------
// Worker scope (typed loosely — tsconfig lib is "dom", not "webworker")
// ---------------------------------------------------------------------------

const workerScope = self as unknown as {
  postMessage(message: KokoroWorkerResponse): void;
  addEventListener(type: "message", listener: (event: MessageEvent) => void): void;
  navigator?: { gpu?: unknown };
};

function post(message: KokoroWorkerResponse): void {
  try {
    workerScope.postMessage(message);
  } catch {
    // A dead port means the main thread already terminated us — nothing to do.
  }
}

// ---------------------------------------------------------------------------
// Model lifecycle (one engine per worker)
// ---------------------------------------------------------------------------

interface LoadedAssets {
  tts: KokoroTTSType;
}

let assets: LoadedAssets | null = null;
let loadInFlight: Promise<LoadedAssets> | null = null;

/** kokoro-js forwards the same shaped progress events transformers.js's
 * from_pretrained does (it delegates to transformers.js internally for ONNX
 * acquisition) — same mapping as visionWorker.ts/whisperWorker.ts. */
function forwardProgress(requestId: number, info: any): void {
  try {
    if (info?.status === "progress_total" || (typeof info?.total === "number" && info.total > 0)) {
      post({
        type: "progress",
        requestId,
        loaded: info.loaded || 0,
        total: info.total || 0,
        status: "downloading",
      });
    } else if (info?.status === "initiate" || info?.status === "done") {
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
    // Loaded at RUNTIME from a same-origin static asset, never bundled.
    // The URL is held in a variable and carries both ignore comments so
    // neither Turbopack nor webpack follows it into the module graph — that
    // is the whole point: kokoro-js's web bundle is ~2 MB with ONNX runtime
    // inlined, and bundling it is what made `next build` unfinishable
    // (ACR runs cu14/cu16 both hit the 60-minute ceiling).
    // scripts/vendor-kokoro.mjs puts the file there on prebuild/predev, the
    // same arrangement public/webllm-sw.js already uses for web-llm.
    const kokoroUrl = "/vendored/kokoro.web.js";
    const { KokoroTTS, env } = await import(/* webpackIgnore: true */ /* turbopackIgnore: true */ kokoroUrl);
    // The bundled transformers.js would otherwise load its ONNX Runtime JSEP
    // loader from cdn.jsdelivr.net - connect-src only in this app's CSP, never
    // script-src, so that import dies as an inscrutable "Download failed".
    // scripts/vendor-kokoro.mjs copies the runtime files same-origin.
    if (env && typeof env === "object") {
      try {
        (env as any).wasmPaths = "/vendored/ort/";
      } catch {
        // Older/newer bundles without the wasmPaths setter fall back to the
        // CDN - same behaviour as before this pin.
      }
    }
    const hasWebGpu = !!workerScope.navigator?.gpu;
    const tts = await KokoroTTS.from_pretrained(MODEL_ID, {
      dtype: hasWebGpu ? "fp16" : "q8",
      device: hasWebGpu ? "webgpu" : "wasm",
      progress_callback: (info: any) => forwardProgress(requestId, info),
    } as any);
    assets = { tts };
    return assets;
  })().finally(() => {
    loadInFlight = null;
  });
  return loadInFlight;
}

// ---------------------------------------------------------------------------
// The synthesis turn
// ---------------------------------------------------------------------------

/**
 * Bug fix (2026-08-06, live user report: "Kokoro is not sounding properly,
 * scratching sound"). kokoro-js's RawAudio.toBlob() (bundled
 * @huggingface/transformers, see its audio.js) writes the vocoder's raw
 * Float32 samples directly into a 32-bit-float WAV with NO clamping - a
 * known neural-vocoder behavior is occasionally exceeding the nominal
 * [-1, 1] envelope on emphasized syllables, and how a browser's native
 * <audio> decoder handles those out-of-range floats is decoder-dependent,
 * commonly manifesting as exactly this crackling/scratching artifact. This
 * clamps every sample in place (mutating the array kokoro-js itself
 * allocated - never touching upstream node_modules code) before toBlob()
 * ever runs, matching what a correctly-behaved encoder would have done
 * itself. `.audio` is either one Float32Array or an array of per-chunk
 * Float32Arrays (RawAudio's own doc comment) - handle both shapes.
 */
function clampAudioSamplesInPlace(audio: any): void {
  const chunks: Float32Array[] = Array.isArray(audio.audio) ? audio.audio : [audio.audio];
  for (const chunk of chunks) {
    for (let i = 0; i < chunk.length; i++) {
      if (chunk[i] > 1) chunk[i] = 1;
      else if (chunk[i] < -1) chunk[i] = -1;
    }
  }
}

async function runSynthesis(
  requestId: number,
  text: string,
  voice: string | undefined
): Promise<{ audioBlob: Blob; sampleRate: number }> {
  const { tts } = await ensureAssets(requestId);
  const clipped = text.slice(0, MAX_TEXT_CHARS);
  const audio: any = await (tts as any).generate(clipped, { voice: voice || DEFAULT_VOICE });
  clampAudioSamplesInPlace(audio);
  const audioBlob: Blob = await audio.toBlob();
  const sampleRate: number = typeof audio.sampling_rate === "number" ? audio.sampling_rate : 24_000;
  return { audioBlob, sampleRate };
}

// ---------------------------------------------------------------------------
// Message loop
// ---------------------------------------------------------------------------

async function handleMessage(request: KokoroWorkerRequest): Promise<void> {
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
  if (request.type === "synthesize") {
    try {
      const { audioBlob, sampleRate } = await runSynthesis(request.requestId, request.text || "", request.voice);
      post({ type: "result", requestId: request.requestId, audioBlob, sampleRate });
    } catch (err) {
      post({
        type: "error",
        requestId: request.requestId,
        reason: "synthesize-failed",
        detail: err instanceof Error ? err.message : String(err),
      });
    }
  }
}

workerScope.addEventListener("message", (event: MessageEvent) => {
  void handleMessage(event.data as KokoroWorkerRequest);
});
