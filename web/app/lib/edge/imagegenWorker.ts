// The dedicated on-device image-generation worker.
//
// Runs Stable Diffusion Turbo (single-step distilled Stable Diffusion) entirely
// in the page via onnxruntime-web on the WebGPU execution provider. The ONNX
// export is the same one microsoft/onnxruntime-inference-examples/js/sd-turbo
// ships against, served from the Hugging Face hub - no bytes ship inside this
// repository or this image.
//
// The scheduler is the single-step EulerAncestral approximation from that
// reference implementation, faithfully ported: sigma = 14.6146, gamma = 0,
// timestep = 999, latent shape [1,4,64,64], vae_scaling_factor = 0.18215. SD-Turbo
// is distilled to answer in one UNet pass, so the full multi-step scheduler would
// buy nothing here.
//
// Tokenisation uses transformers.js's AutoTokenizer against the public
// Xenova/clip-vit-base-patch16 tokenizer files (the exact tokenizer the ONNX
// text_encoder was exported against, per the reference example). That tokenizer
// download is tens of KB; the ~2.4GB is the three ONNX model files.
//
// Worker contract (mirrors whisperWorker.ts):
//   in:  { type: "load", requestId }                      -> progress* then loaded | error
//   in:  { type: "generate", requestId, prompt, seed? }   -> progress* then result | error
//   out: progress { loaded, total, status } drives the shared download chip.
//
// The image is posted back as PNG bytes (already-encoded), not raw pixels: the
// worker owns an OffscreenCanvas for the VAE decode -> RGBA -> PNG conversion so
// the main thread never sees a raw tensor.

import type * as Ort from "onnxruntime-web";
import type { InferenceSession, Tensor } from "onnxruntime-web";

export interface ImageGenWorkerRequest {
  type: "load" | "generate";
  requestId: number;
  prompt?: string;
  seed?: number;
}

export type ImageGenWorkerResponse =
  | { type: "progress"; requestId: number; loaded: number; total: number; status: string }
  | { type: "loaded"; requestId: number }
  | { type: "result"; requestId: number; png: ArrayBuffer; width: number; height: number }
  | { type: "error"; requestId: number; reason: string; detail: string };

/** The exact export the ORT sd-turbo example runs against. */
const MODEL_BASE_URL = "https://huggingface.co/schmuell/sd-turbo-ort-web/resolve/main";
/** Cache Storage bucket name - kept separate from transformers.js's cache so an
 * "uninstall" of this asset cannot evict another model's entries. */
const CACHE_NAME = "vegaduta-edge-imagegen";
const TOKENIZER_ID = "Xenova/clip-vit-base-patch16";

/** Sizes used by the reference example; honest byte counts shown for progress
 * and (by the facade) for the storage-headroom check. */
const MODEL_FILES = [
  { key: "unet", url: "unet/model.onnx", approxBytes: 640_000_000 },
  { key: "text_encoder", url: "text_encoder/model.onnx", approxBytes: 1_700_000_000 },
  { key: "vae_decoder", url: "vae_decoder/model.onnx", approxBytes: 95_000_000 },
] as const;

// Single-step scheduler constants (see header).
const SIGMA = 14.6146;
const VAE_SCALING_FACTOR = 0.18215;
const TIMESTEP = 999n;
const LATENT_SHAPE = [1, 4, 64, 64] as const;

type OrtModule = typeof Ort;

const workerScope = self as unknown as Worker & { navigator?: { gpu?: unknown } };

function post(msg: ImageGenWorkerResponse, transfer?: Transferable[]): void {
  workerScope.postMessage(msg, transfer as Transferable[]);
}

function reportProgress(requestId: number, loaded: number, total: number, status: string): void {
  try {
    post({ type: "progress", requestId, loaded, total, status });
  } catch {
    // Progress is best-effort.
  }
}

/** Deterministic gaussian latents (Box-Muller over a mulberry32 PRNG) so a seed
 * reproduces the image - the reference pipeline's Math.random() latents make
 * every call different even for the same prompt. */
function seededGaussian(count: number, seed: number): Float32Array {
  const out = new Float32Array(count);
  let s = seed >>> 0 || 1;
  const next = () => {
    // mulberry32
    s = (s + 0x6d2b79f5) >>> 0;
    let t = s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  for (let i = 0; i < count; i += 2) {
    const u1 = Math.max(next(), 1e-9);
    const u2 = next();
    const r = Math.sqrt(-2 * Math.log(u1)) * SIGMA;
    out[i] = r * Math.cos(2 * Math.PI * u2);
    if (i + 1 < count) out[i + 1] = r * Math.sin(2 * Math.PI * u2);
  }
  return out;
}

/** fetch -> Cache Storage with byte progress; a cache hit costs no network. */
async function fetchModelBytes(url: string, onBytes: (n: number) => void): Promise<Uint8Array> {
  const cache = await caches.open(CACHE_NAME);
  const hit = await cache.match(url);
  if (hit) {
    const buf = new Uint8Array(await hit.arrayBuffer());
    onBytes(buf.byteLength);
    return buf;
  }
  const res = await fetch(url);
  if (!res.ok || !res.body) throw new Error(`fetch ${url}: HTTP ${res.status}`);
  const reader = res.body.getReader();
  const chunks: Uint8Array[] = [];
  let received = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(value);
    received += value.byteLength;
    onBytes(value.byteLength);
  }
  const body = new Uint8Array(received);
  let offset = 0;
  for (const c of chunks) {
    body.set(c, offset);
    offset += c.byteLength;
  }
  // Persist under the URL key exactly like the reference example, so a later
  // session never re-downloads. Best-effort: a quota failure means the next
  // boot re-fetches, which is a UX cost, not a correctness one.
  try {
    await cache.put(url, new Response(body.slice(0)));
  } catch {
    // Best-effort.
  }
  return body;
}

interface LoadedSessions {
  ort: OrtModule;
  unet: InferenceSession;
  textEncoder: InferenceSession;
  vaeDecoder: InferenceSession;
}

let sessions: LoadedSessions | null = null;
let loadInFlight: Promise<LoadedSessions> | null = null;

function ortSessionOptions() {
  const hasWebGpu = !!workerScope.navigator?.gpu;
  return {
    executionProviders: hasWebGpu ? ["webgpu"] : ["wasm"],
    enableMemPattern: false,
    enableCpuMemArena: false,
    extra: {
      session: {
        disable_prepacking: "1",
        use_device_allocator_for_initializers: "1",
        use_ort_model_bytes_directly: "1",
        use_ort_model_bytes_for_initializers: "1",
      },
    },
  } as Parameters<OrtModule["InferenceSession"]["create"]>[1];
}

async function ensureSessions(requestId: number): Promise<LoadedSessions> {
  if (sessions) return sessions;
  if (loadInFlight !== null) return loadInFlight;
  loadInFlight = (async () => {
    const ort = await import("onnxruntime-web");
    // The vendored wasm assets live at /vendored/ort/ (see rife.ts and
    // scripts/vendor-*); point the loader there so the same-origin policy
    // holds even if the npm default drifts to a CDN.
    try {
      (ort.env.wasm as { wasmPaths?: string }).wasmPaths = "/vendored/ort/";
    } catch {
      // Read-only env in some bundlings — the bundled defaults still apply.
    }
    const opt = ortSessionOptions();
    const totalBytes = MODEL_FILES.reduce((s, f) => s + f.approxBytes, 0);
    let receivedBytes = 0;
    const out = {} as Record<string, InferenceSession>;
    for (const file of MODEL_FILES) {
      const bytes = await fetchModelBytes(`${MODEL_BASE_URL}/${file.url}`, (n) => {
        receivedBytes += n;
        reportProgress(requestId, receivedBytes, totalBytes, "downloading");
      });
      out[file.key] = await ort.InferenceSession.create(bytes, opt);
    }
    sessions = { ort, unet: out.unet, textEncoder: out.text_encoder, vaeDecoder: out.vae_decoder };
    return sessions;
  })().finally(() => {
    loadInFlight = null;
  });
  return loadInFlight;
}

/** CLIP tokenize via transformers.js — the small tokenizer.json download is
 * part of the first ensureSessions call's warmup. */
async function tokenize(prompt: string): Promise<{ ids: BigInt64Array | number[] }> {
  const tf = (await import("@huggingface/transformers")) as unknown as {
    AutoTokenizer: {
      from_pretrained: (
        id: string
      ) => Promise<(t: string, o: object) => Promise<{ input_ids: { data: unknown; length: number } }>>;
    };
  };
  const tokenizer = await tf.AutoTokenizer.from_pretrained(TOKENIZER_ID);
  (tokenizer as unknown as { pad_token_id?: number }).pad_token_id = 0;
  const encoded = await tokenizer(prompt, { padding: true, max_length: 77, truncation: true, return_tensor: false });
  const data = encoded.input_ids.data as ArrayLike<number | bigint>;
  const ids = new BigInt64Array(data.length ?? 0);
  for (let i = 0; i < ids.length; i++) ids[i] = BigInt((data as ArrayLike<number | bigint>)[i]);
  return { ids };
}

/** The EulerAncestral-ish single step from the reference example, ported
 * verbatim: scale inputs by sqrt(sigma^2+1), predict epsilon, then step the
 * sample to x_0 in latent space. */
function eulerStep(outSample: Float32Array, sample: Float32Array): Float32Array {
  const sigmaHat = SIGMA; // gamma = 0
  const prev = new Float32Array(sample.length);
  for (let i = 0; i < sample.length; i++) {
    const predOriginal = sample[i] - sigmaHat * outSample[i];
    const derivative = (sample[i] - predOriginal) / sigmaHat;
    const dt = 0 - sigmaHat;
    prev[i] = (sample[i] + derivative * dt) / VAE_SCALING_FACTOR;
  }
  return prev;
}

async function runGenerate(requestId: number, prompt: string, seed: number): Promise<void> {
  const loaded = await ensureSessions(requestId);
  const { ort, unet, textEncoder, vaeDecoder } = loaded;

  const { ids } = await tokenize(prompt);
  const inputIds = new ort.Tensor("int64", ids, [1, ids.length]);
  const { last_hidden_state } = (await textEncoder.run({ input_ids: inputIds })) as Record<string, Tensor>;

  const latentCount = LATENT_SHAPE[0] * LATENT_SHAPE[1] * LATENT_SHAPE[2] * LATENT_SHAPE[3];
  const latents = seededGaussian(latentCount, seed);
  // scale_model_inputs: divide by sqrt(sigma^2 + 1)
  const scale = Math.sqrt(SIGMA ** 2 + 1);
  const scaled = new Float32Array(latents.length);
  for (let i = 0; i < latents.length; i++) scaled[i] = latents[i] / scale;

  const { out_sample } = (await unet.run({
    sample: new ort.Tensor("float32", scaled, [...LATENT_SHAPE]),
    timestep: new ort.Tensor("int64", [TIMESTEP], [1]),
    encoder_hidden_states: last_hidden_state,
  })) as Record<string, Tensor>;

  const prevSample = eulerStep(out_sample.data as Float32Array, latents);
  const { sample } = (await vaeDecoder.run({
    latent_sample: new ort.Tensor("float32", prevSample, [...LATENT_SHAPE]),
  })) as Record<string, Tensor>;

  // VAE outputs [-1,1] pixels; map to [0,255] RGBA and PNG-encode on the
  // worker's OffscreenCanvas so the main thread only ever sees bytes.
  const px = sample.data as Float32Array;
  const w = 512;
  const h = 512;
  const rgba = new Uint8ClampedArray(w * h * 4);
  const plane = w * h;
  for (let i = 0; i < plane; i++) {
    const r = Math.max(0, Math.min(255, Math.round((px[i] / 2 + 0.5) * 255)));
    const g = Math.max(0, Math.min(255, Math.round((px[i + plane] / 2 + 0.5) * 255)));
    const b = Math.max(0, Math.min(255, Math.round((px[i + 2 * plane] / 2 + 0.5) * 255)));
    rgba[i * 4] = r;
    rgba[i * 4 + 1] = g;
    rgba[i * 4 + 2] = b;
    rgba[i * 4 + 3] = 255;
  }
  const canvas = new OffscreenCanvas(w, h);
  const ctx = canvas.getContext("2d");
  if (!ctx) throw new Error("OffscreenCanvas 2d unavailable in worker");
  ctx.putImageData(new ImageData(rgba, w, h), 0, 0);
  const blob = await canvas.convertToBlob({ type: "image/png" });
  const png = await blob.arrayBuffer();
  post({ type: "result", requestId, png, width: w, height: h }, [png]);
}

async function handleMessage(request: ImageGenWorkerRequest): Promise<void> {
  if (!request || typeof request !== "object") return;
  try {
    if (request.type === "load") {
      await ensureSessions(request.requestId);
      post({ type: "loaded", requestId: request.requestId });
      return;
    }
    if (request.type === "generate") {
      await runGenerate(request.requestId, request.prompt ?? "", request.seed ?? Math.floor(Math.random() * 2 ** 31));
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
  void handleMessage(event.data as ImageGenWorkerRequest);
});
