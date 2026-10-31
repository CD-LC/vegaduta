// The dedicated on-device audio-generation worker.
//
// Runs MusicGen-small (text-to-audio) entirely in the page via transformers.js -
// the same runtime whisperWorker.ts and visionWorker.ts already carry, device:
// "webgpu" where available and wasm otherwise. The model is Xenova/musicgen-small
// (~700 MB fp16 / ~570 MB q4; fp16 needs an adapter with shader-f16).
//
// Output is encoded as a 16-bit PCM WAV ArrayBuffer here in the worker so the
// main thread only ever receives bytes - callers never touch the raw Float32
// tensor, and the artifact registry can hand the blob straight to <audio>.
//
// Worker contract (mirrors whisperWorker.ts / imagegenWorker.ts):
//   in:  { type: "load", requestId }                          -> progress* then loaded | error
//   in:  { type: "generate", requestId, prompt, duration_s }  -> progress* then result | error
//   out: progress { loaded, total, status } drives the shared download chip.

export interface AudioGenWorkerRequest {
  type: "load" | "generate";
  requestId: number;
  prompt?: string;
  durationSeconds?: number;
}

export type AudioGenWorkerResponse =
  | { type: "progress"; requestId: number; loaded: number; total: number; status: string }
  | { type: "loaded"; requestId: number }
  | {
      type: "result";
      requestId: number;
      wav: ArrayBuffer;
      sampleRate: number;
      durationSeconds: number;
    }
  | { type: "error"; requestId: number; reason: string; detail: string };

const MODEL_ID = "Xenova/musicgen-small";
/** MusicGen emits at 32kHz mono. */
const EXPECTED_SAMPLE_RATE = 32_000;
const MIN_SECONDS = 1;
const MAX_SECONDS = 30;

const workerScope = self as unknown as Worker & { navigator?: { gpu?: unknown } };

function post(msg: AudioGenWorkerResponse, transfer?: Transferable[]): void {
  workerScope.postMessage(msg, transfer as Transferable[]);
}

function reportProgress(requestId: number, loaded: number, total: number, status: string): void {
  try {
    post({ type: "progress", requestId, loaded, total, status });
  } catch {
    // Best-effort.
  }
}

// transformers.js' progress shape
type ProgressInfo = { status?: string; loaded?: number; total?: number; progress?: number };

interface LoadedAssets {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  generator: (prompt: string, opts: Record<string, unknown>) => Promise<any>;
}

let assets: LoadedAssets | null = null;
let loadInFlight: Promise<LoadedAssets> | null = null;

async function ensureAssets(requestId: number): Promise<LoadedAssets> {
  if (assets) return assets;
  if (loadInFlight !== null) return loadInFlight;
  loadInFlight = (async () => {
    const tf = (await import("@huggingface/transformers")) as {
      pipeline: (task: string, model: string, opts: Record<string, unknown>) => Promise<LoadedAssets["generator"]>;
    };
    const progress_callback = (info: ProgressInfo) => {
      try {
        if (info.status === "progress") {
          reportProgress(requestId, info.loaded || 0, info.total || 0, "downloading");
        }
      } catch {
        // Progress is best-effort.
      }
    };
    // Device + dtype follow the adapter, not just the API's presence: fp16
    // kernels need the adapter's "shader-f16" feature (SwiftShader and some
    // older/integrated adapters lack it — transformers.js refuses the load
    // outright). Without it the fp32 variant is NOT the fallback: MusicGen's
    // fp32 decoder alone is ~1.7 GB, which ONNX Runtime can't session-create
    // inside the browser's 4 GB wasm heap (protobuf parse + initializer
    // copies) — observed live as "Can't create a session". q4 is the supported
    // WebGPU-quantized path (~570 MB total via MatMulNBits); only a missing
    // adapter drops to wasm+q8.
    // wasm path also uses q4, not q8: the quantized decoder+encoder+codec at
    // q8 still strains the 4 GB wasm heap at session-init (the same
    // "Can't create a session" failure as fp32, just rarer) and q4 runs on the
    // wasm EP via MatMulNBits (JSEP) anyway - it is not a WebGPU-only dtype.
    let device: "webgpu" | "wasm" = "wasm";
    let dtype: "fp16" | "q4" | "q8" = "q4";
    const gpu = workerScope.navigator?.gpu;
    if (gpu) {
      try {
        const adapter = await (
          gpu as { requestAdapter?: () => Promise<{ features?: { has?: (f: string) => boolean } } | null> }
        ).requestAdapter?.();
        if (adapter) {
          device = "webgpu";
          dtype = adapter.features?.has?.("shader-f16") === true ? "fp16" : "q4";
        }
      } catch {
        // Adapter request failed — stay on the wasm path.
      }
    }
    // Retry ladder: if the chosen dtype's session-create fails (OOM during
    // graph init on the wasm heap, or a dtype the build can't load), drop to
    // the next-smaller variant rather than failing the whole download.
    const dtypeLadder: Array<"fp16" | "q4" | "q8"> =
      dtype === "fp16" ? ["fp16", "q4", "q8"] : dtype === "q4" ? ["q4", "q8"] : ["q8"];
    let lastErr: unknown = null;
    for (const d of dtypeLadder) {
      try {
        const generator = await tf.pipeline("text-to-audio", MODEL_ID, {
          device,
          dtype: d,
          progress_callback,
        });
        assets = { generator };
        return assets;
      } catch (err) {
        lastErr = err;
      }
    }
    throw lastErr;
  })().finally(() => {
    loadInFlight = null;
  });
  return loadInFlight;
}

/** Float32 mono PCM -> 16-bit WAV. Written inline because the worker has no
 * shared WAV encoder to reuse today (encode.ts is video). */
function encodeWav(samples: Float32Array, sampleRate: number): ArrayBuffer {
  const n = samples.length;
  const dataBytes = n * 2;
  const buf = new ArrayBuffer(44 + dataBytes);
  const view = new DataView(buf);
  const writeStr = (off: number, s: string) => {
    for (let i = 0; i < s.length; i++) view.setUint8(off + i, s.charCodeAt(i));
  };
  writeStr(0, "RIFF");
  view.setUint32(4, 36 + dataBytes, true);
  writeStr(8, "WAVE");
  writeStr(12, "fmt ");
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true); // PCM
  view.setUint16(22, 1, true); // mono
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true); // byte rate
  view.setUint16(32, 2, true); // block align
  view.setUint16(34, 16, true); // bits per sample
  writeStr(36, "data");
  view.setUint32(40, dataBytes, true);
  for (let i = 0; i < n; i++) {
    const s = Math.max(-1, Math.min(1, samples[i]));
    view.setInt16(44 + i * 2, s < 0 ? s * 0x8000 : s * 0x7fff, true);
  }
  return buf;
}

async function runGenerate(requestId: number, prompt: string, durationSeconds: number): Promise<void> {
  const { generator } = await ensureAssets(requestId);
  const out = await generator(prompt, { max_new_tokens: Math.ceil(durationSeconds * 50) });
  const item = Array.isArray(out) ? out[0] : out;
  const audio: Float32Array = item?.audio;
  const sampleRate: number = item?.sampling_rate ?? EXPECTED_SAMPLE_RATE;
  if (!(audio instanceof Float32Array) || audio.length === 0) {
    throw new Error("generator returned no audio");
  }
  const wav = encodeWav(audio, sampleRate);
  post(
    {
      type: "result",
      requestId,
      wav,
      sampleRate,
      durationSeconds: audio.length / sampleRate,
    },
    [wav]
  );
}

async function handleMessage(request: AudioGenWorkerRequest): Promise<void> {
  if (!request || typeof request !== "object") return;
  try {
    if (request.type === "load") {
      await ensureAssets(request.requestId);
      post({ type: "loaded", requestId: request.requestId });
      return;
    }
    if (request.type === "generate") {
      const duration = Math.min(MAX_SECONDS, Math.max(MIN_SECONDS, Math.floor(request.durationSeconds ?? 8)));
      await runGenerate(request.requestId, request.prompt ?? "", duration);
      return;
    }
  } catch (err) {
    post({
      type: "error",
      requestId: request.requestId,
      reason: request.type === "load" ? "load-failed" : "generate-failed",
      detail: err instanceof Error ? err.message : String(err),
    });
  }
}

workerScope.addEventListener("message", (event: MessageEvent) => {
  void handleMessage(event.data as AudioGenWorkerRequest);
});
