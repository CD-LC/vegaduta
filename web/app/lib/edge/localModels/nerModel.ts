// Track 4 — the on-device NER model itself (split out of ner.ts, which re-exports
// the public parts): identity, the test seam, the Cache API presence check and
// the load/unload lifecycle. Nothing here downloads on its own: loadNer() is
// only reached through ner.ts's installNerModel(), from an explicit click.

import { edgeEnabled } from "../capabilities";

// ---------------------------------------------------------------------------
// Identity
// ---------------------------------------------------------------------------

/**
 * The token classifier.
 *
 * WHAT WAS VERIFIED against the installed @huggingface/transformers 4.2.0 in
 * node_modules — the runtime path, not the hub repo:
 *   - "token-classification" is a supported pipeline task and this exact model
 *     id is the one the installed package's own documentation uses for it
 *     (src/pipelines/token-classification.js, three worked examples).
 *   - BertForTokenClassification is registered in
 *     src/models/bert/modeling_bert.js.
 *   - aggregation_strategy:"simple" exists in this version and groups BIO tags
 *     into `entity_group` spans (same file, groupEntities()). Older
 *     transformers.js releases had no aggregation at all, which is why this is
 *     checked rather than assumed.
 *   - Those grouped results carry NO start/end offsets in this version — see
 *     the header. resolveVerbatimSpan() below is the consequence.
 *
 * WHAT WAS NOT VERIFIED: that the repo id resolves on the hub, or what its
 * files weigh. Neither is checkable offline, and this file claims neither. A
 * model id that does not resolve is a defined outcome: installNerModel()
 * reports the library's error and resolves false, and the redactor falls back
 * to the chat-model pass and then to the pattern tier.
 */
export const NER_MODEL_ID = "Xenova/bert-base-NER";

/** Row key in the shared /edge download console. */
export const NER_ASSET_ID = "edge-local-ner";
/** Display name; a local string for the same scope reason as rerank.ts's. */
export const NER_DISPLAY_NAME = "On-device name detector (BERT NER)";

/** Every host the install path can contact. Repeated verbatim in the copy a
 * person reads before they click (property 3). */
export const NER_NETWORK_HOSTS: readonly string[] = ["huggingface.co", "cdn.jsdelivr.net"];

/** env.cacheKey from the installed package's src/env.js. */
const TRANSFORMERS_CACHE_KEY = "transformers-cache";

/** One weights file for every device — same reasoning as rerank.ts: a
 * device-dependent file name would make the "already on this device?" check
 * answer differently on one machine depending on whether WebGPU happened to be
 * up, and that false positive is the surprise download. "_quantized" is the q8
 * suffix in the installed package's DEFAULT_DTYPE_SUFFIX_MAPPING. */
const NER_DTYPE = "q8";
const NER_WEIGHTS_FILE = "onnx/model_quantized.onnx";
const NER_REQUIRED_FILES = ["config.json", "tokenizer.json", NER_WEIGHTS_FILE];

// ---------------------------------------------------------------------------
// Test seam
// ---------------------------------------------------------------------------

/** One grouped result from the token classifier, as this module consumes it. */
export interface RawNerSpan {
  word: string;
  score: number;
  entity_group?: string;
  entity?: string;
}

/** Stands in for the loaded token-classification pipeline. */
export type NerClassifyFn = (text: string) => Promise<RawNerSpan[]>;

interface NerOverrides {
  classify?: NerClassifyFn;
  onDevice?: () => Promise<boolean | null>;
}

let overrides: NerOverrides | null = null;

/** Test-only seam, mirroring indexer.ts's __setIndexerOverridesForTests. */
export function __setNerOverridesForTests(next: NerOverrides | null): void {
  overrides = next;
  if (next === null) {
    loaded = null;
    onDeviceProbe = null;
  }
}

// ---------------------------------------------------------------------------
// Cache-presence check
// ---------------------------------------------------------------------------

/** Same contract as rerank.ts's twin: true = a load moves no bytes, false =
 * it would download, null = cannot tell (treated as "not ready"). The two are
 * deliberate copies rather than a shared helper — this track owns two leaf
 * modules and no shared runtime file, and a cross-import between them would
 * make the NER path depend on the reranker module for no reason. */
export async function nerFilesOnDevice(): Promise<boolean | null> {
  if (overrides?.onDevice) return overrides.onDevice();
  try {
    if (typeof caches === "undefined") return null;
    if (!(await caches.has(TRANSFORMERS_CACHE_KEY))) return false;
    const cache = await caches.open(TRANSFORMERS_CACHE_KEY);
    const keys = await cache.keys();
    const needle = `/${NER_MODEL_ID}/`;
    const urls: string[] = [];
    for (const request of keys) {
      if (request.url.includes(needle)) urls.push(request.url);
    }
    if (urls.length === 0) return false;
    for (const file of NER_REQUIRED_FILES) {
      if (!urls.some((url: string) => url.endsWith(`/${file}`))) return false;
    }
    return true;
  } catch {
    return null;
  }
}

let onDeviceProbe: Promise<boolean> | null = null;

/** Is the model already on this device, so a load would move no bytes? Never
 * downloads, never prompts, never throws. */
export async function isNerModelOnDevice(): Promise<boolean> {
  if (loaded !== null) return true;
  if (overrides?.classify) return true;
  if (typeof window === "undefined") return false;
  if (!edgeEnabled()) return false;
  if (onDeviceProbe === null) {
    onDeviceProbe = nerFilesOnDevice()
      .then((present: boolean | null) => present === true)
      .catch(() => false);
  }
  return onDeviceProbe;
}

// ---------------------------------------------------------------------------
// Model lifecycle
// ---------------------------------------------------------------------------

interface LoadedNer {
  classify: (text: string, options: Record<string, unknown>) => Promise<unknown>;
  device: "webgpu" | "wasm";
}

let loaded: LoadedNer | null = null;
let loadInFlight: Promise<LoadedNer> | null = null;

/** Synchronous: can extractEntitiesLocal() do anything right now? True ONLY
 * when a model is in memory — never "the files are cached", because that is
 * the answer that tempts a caller into an implicit load. redactor.ts reads
 * this to decide whether the NER path exists for a turn. */
export function nerAvailable(): boolean {
  if (overrides?.classify) return true;
  return loaded !== null;
}

/** Which device the loaded model is on, or null when none is loaded. */
export function nerDevice(): "webgpu" | "wasm" | null {
  return loaded === null ? null : loaded.device;
}

/** Drop the model; the cached files stay, so turning it back on is a warm-up
 * rather than a download. */
export function unloadNerModel(): void {
  loaded = null;
}

function hasWebGpu(): boolean {
  try {
    return typeof navigator !== "undefined" && !!(navigator as { gpu?: unknown }).gpu;
  } catch {
    return false;
  }
}

/** WebGPU first when the API is present, WASM if that load throws. The
 * fallback re-reads the SAME cached file (NER_DTYPE is device-independent by
 * design), so it can never become a second download. */
export async function loadNer(onProgress: (info: unknown) => void): Promise<LoadedNer> {
  if (loaded !== null) return loaded;
  if (loadInFlight !== null) return loadInFlight;
  loadInFlight = (async (): Promise<LoadedNer> => {
    // The dynamic import is deliberately left untyped and immediately narrowed
    // to the two members used below: a `typeof import(...)` alias would pull the
    // library's whole type surface in for nothing (and trips
    // @typescript-eslint/consistent-type-imports).
    const tf = await import("@huggingface/transformers");
    const factory = (
      tf as unknown as {
        pipeline: (task: string, model: string, opts: Record<string, unknown>) => Promise<unknown>;
      }
    ).pipeline;
    const devices: Array<"webgpu" | "wasm"> = hasWebGpu() ? ["webgpu", "wasm"] : ["wasm"];
    let lastError: unknown = null;
    for (const device of devices) {
      try {
        const classify = (await factory("token-classification", NER_MODEL_ID, {
          device,
          dtype: NER_DTYPE,
          progress_callback: onProgress,
        })) as LoadedNer["classify"];
        loaded = { classify, device };
        return loaded;
      } catch (err) {
        lastError = err;
      }
    }
    throw lastError instanceof Error ? lastError : new Error("The on-device name detector could not be loaded.");
  })().finally(() => {
    loadInFlight = null;
  });
  return loadInFlight;
}

/** After a successful install: a later presence check answers "on device". */
export function markNerOnDevice(): void {
  onDeviceProbe = Promise.resolve(true);
}

/** Classify one chunk with the override or the loaded model ([] when neither). */
export async function classifyChunk(chunk: string): Promise<RawNerSpan[]> {
  if (overrides?.classify) {
    const spans = await overrides.classify(chunk);
    return Array.isArray(spans) ? spans : [];
  }
  if (loaded === null) return [];
  // aggregation_strategy "simple" is what turns per-token BIO tags into whole
  // spans; without it "Priya Raman" arrives as two rows and the verbatim
  // resolver would only ever see half a name.
  const raw = await loaded.classify(chunk, { aggregation_strategy: "simple" });
  return Array.isArray(raw) ? (raw as RawNerSpan[]) : [];
}
