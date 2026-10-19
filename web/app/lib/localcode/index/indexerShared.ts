// Shared constants, fixed sentences, best-effort localStorage helpers and the
// test injection seam of the on-device code index. Split out of indexer.ts,
// which re-exports the public names. See that file's header for the design.

import type { LocalFileRef, LocalRepoHandle } from "../types";
// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Shared-queue asset id — the /edge console's row key for this model. */
export const LOCALCODE_EMBEDDER_ASSET_ID = "edge-localcode-embedder";
/** Display name. Deliberately a local string rather than an EDGE_BRANDING key:
 * branding.ts belongs to another agent's scope in this run, so adding a key
 * there would be a cross-scope edit. Fold it into EDGE_BRANDING in a later
 * pass, next to sttModelDisplayName/ttsModelDisplayName. */
export const LOCALCODE_EMBEDDER_DISPLAY_NAME = "On-device code search (MiniLM embeddings)";

/** The embedding model, owned HERE rather than in the worker. Three things
 * have to agree on it and only one of them runs in the worker: the readiness
 * check (which looks for this exact repo's files in the browser cache), the
 * store stamp (which invalidates rows when it changes), and the load itself.
 * A second copy inside the worker would be the drift that turns a model swap
 * into silent garbage.
 *
 * Swapping this: MiniLM is a sentence embedder, not a code embedder, and a
 * code-specific model would very likely retrieve better — but this repo has
 * zero local-code retrieval measurements, so the swap should follow a
 * measurement, not a hunch. When it happens, change these two constants AND
 * bump DB_VERSION; modelSignature() makes stored rows self-invalidate either
 * way, but the version bump reclaims the space instead of leaving it. */
export const EMBEDDING_MODEL_ID = "Xenova/all-MiniLM-L6-v2";
/** all-MiniLM-L6-v2's output width, asserted against the real tensor on load. */
export const EMBEDDING_DIMENSIONS = 384;

/** The Cache API bucket @huggingface/transformers writes model files into
 * (env.cacheKey, verified against the installed package's src/env.js default).
 * Shared with the sibling edge workers, which is fine — entries are keyed by
 * their full hub URL. */
export const TRANSFORMERS_CACHE_KEY = "transformers-cache";
/** Files that must ALL be present for a load to complete without fetching
 * anything. The weights file is by far the biggest and is device-dependent
 * (see embedderWeightsForDtype). Deliberately a short list: a false NEGATIVE
 * costs one extra click on a button that then completes instantly from cache,
 * while a false POSITIVE is exactly the unasked-for multi-megabyte download
 * this check exists to prevent — so the list only names files this module is
 * certain a feature-extraction pipeline fetches. */
export const REQUIRED_MODEL_FILES = ["config.json", "tokenizer.json"];

/** Budget for the storage-headroom pre-check in installLocalEmbedder, and for
 * nothing else. It is a guess, NOT a measurement: nobody here has weighed the
 * actual download (the q8 weights plus tokenizer are the small case, the fp32
 * weights a WebGPU device pulls are larger), and a guess is acceptable for
 * "ask the browser whether there is room" because erring small is the
 * dangerous direction there.
 *
 * It deliberately no longer reaches a person. It used to be handed to the
 * install progress callback and to the /edge chip as the transfer's `total`,
 * while the library's own progress events reported a real byte total for the
 * same transfer — so the readout switched between a guess and the truth
 * mid-download. The install path now reports a size only once the library has
 * given it one, and reports 0 (meaning "unknown", which the card renders as no
 * size at all) until then. */
export const EMBEDDER_SIZE_BYTES = 30_000_000;
/** localStorage marker. Kept ONLY as a cheap, best-effort hint for the /edge
 * chip and cleared when the cache check proves it wrong. It is NOT what gates
 * a download any more — isLocalEmbedderReady() reads the Cache API, because a
 * marker survives an eviction that took the weights with it, and that gap is
 * exactly where an unattended re-fetch of the whole model used to live. */
export const EMBEDDER_CACHED_KEY = "edge.localcode.cached.all-minilm-l6-v2";
/** Which repo the stored index belongs to. searchLocalIndex takes no repo
 * argument (by contract), and after a reload the directory handle is gone
 * while the index itself is still perfectly usable — so the id has to survive
 * in its own right. */
export const ACTIVE_REPO_KEY = "localcode.index.activeRepo";

/** Chunks per worker round-trip. Big enough that per-message overhead is
 * noise, small enough that an abort lands within a fraction of a second and a
 * single batch's tensors stay small. */
export const EMBED_BATCH_SIZE = 16;
/** Belt-and-braces size guard. fsAccess.listCodeFiles already refuses anything
 * over its own MAX_LOCAL_FILE_BYTES (512 KB), so in the real pipeline this
 * never fires — it exists because a file that big is a bundle or a vendored
 * blob whose embedding is pure retrieval noise, and the indexer must not depend
 * on the walker's policy to stay out of that. Such a file moves the SCAN
 * counter (the progress bar keeps going) but is never INDEXED, and — because
 * it cannot be indexed — it is not treated as still-present either: whatever an
 * earlier build stored for it is pruned rather than carried forward. See the
 * scan/index counters and the prune pass in buildLocalIndex. */
export const MAX_FILE_BYTES = 1_000_000;
/** Load watchdog is idle-based (re-armed on every progress event): a real
 * download legitimately takes minutes, a silent stall should not. */
export const LOAD_IDLE_TIMEOUT_MS = 120_000;
/** One batch of EMBED_BATCH_SIZE short chunks — generous even on the wasm
 * fallback. */
export const EMBED_TIMEOUT_MS = 60_000;
/** Default top-k for searchLocalIndex. */
export const DEFAULT_TOP_K = 8;

/** The exact sentence shown when the model is not on this device. Exported so a
 * UI can compare identity instead of matching prose, which is the closest this
 * module can get to a typed refusal while IndexStatus (types.ts, owned
 * elsewhere) has no reason code. It names the action, not a console: the
 * install button lives next to the index button. */
export const EMBEDDER_NOT_READY_DETAIL =
  "On-device code search needs its embedding model on this device first. Use “Enable local search” to download it once — nothing is downloaded automatically.";
/** Shown when the stored index was built by a different embedding model, so its
 * vectors live in a different space and cannot be compared with today's. */
export const MODEL_CHANGED_DETAIL =
  "This index was built with a different embedding model, so its results would be meaningless. Re-index the folder to rebuild it.";
export const EDGE_OFF_DETAIL =
  "On-device AI is turned off for this deployment, so the local code index can't run here.";
export const NO_WORKER_DETAIL = "This browser can't run Web Workers, so the code index can't run here.";
export const INSTALL_CANCELLED_DETAIL = "The on-device model download was cancelled.";
export const NO_SPACE_DETAIL = "There isn't enough free space on this device for the on-device embedding model.";

// ---------------------------------------------------------------------------
// localStorage helpers (same guarded shape as the edge modules)
// ---------------------------------------------------------------------------

export function safeLocalGet(key: string): string | null {
  try {
    if (typeof localStorage === "undefined") return null;
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

export function safeLocalSet(key: string, value: string): void {
  try {
    if (typeof localStorage === "undefined") return;
    localStorage.setItem(key, value);
  } catch {
    // Best-effort.
  }
}

export function safeLocalRemove(key: string): void {
  try {
    if (typeof localStorage === "undefined") return;
    localStorage.removeItem(key);
  } catch {
    // Best-effort.
  }
}

/**
 * The localStorage marker, and nothing more. Kept exported only so the /edge
 * chip and older callers can render a hint without an await.
 *
 * DO NOT gate a download on this. It answers "a load finished on this device at
 * some point", which stays true after the browser evicts the weights — the
 * exact gap that let a build re-fetch the model unasked. isLocalEmbedderReady()
 * is the honest question.
 */
export function isLocalEmbedderInstalled(): boolean {
  return safeLocalGet(EMBEDDER_CACHED_KEY) === "1";
}

// ---------------------------------------------------------------------------
// Injection seam (tests only)
// ---------------------------------------------------------------------------

/** Embeds a batch of texts into one unit-length vector each. */
export type EmbedFn = (texts: string[]) => Promise<Float32Array[]>;

/** An embedder plus the identity of the vector space it produces. Nothing may
 * compare two vectors whose signatures differ — see modelSignature(). */
export interface EmbedderBinding {
  embed: EmbedFn;
  signature: string;
}

export interface FileAccess {
  listCodeFiles(repo: LocalRepoHandle): Promise<LocalFileRef[]>;
  readLocalFile(repo: LocalRepoHandle, path: string): Promise<string>;
}

export interface IndexerOverrides {
  embed?: EmbedFn;
  /** Lets a test pretend the model changed under a stored index. */
  embedSignature?: string;
  files?: FileAccess;
}

let overrides: IndexerOverrides | null = null;

/** The active test overrides, or null in production. */
export function getOverrides(): IndexerOverrides | null {
  return overrides;
}

export const TEST_EMBEDDER_SIGNATURE = "test-embedder";

/**
 * Test-only seam. Production code NEVER calls this; with an override set the
 * real worker is never constructed and the model-readiness gate is skipped
 * (there is no model to gate on), which is exactly what lets the unit tests
 * exercise status transitions and incremental re-indexing without downloading
 * any weights. Pass null to restore the real dependencies.
 */
export function __setIndexerOverridesForTests(next: IndexerOverrides | null): void {
  overrides = next;
}

/** The identity stamped into every stored row. Two vectors may only be compared
 * when these match: cosine similarity between two different embedding spaces
 * is a confident number with no meaning behind it. */
export function modelSignature(modelId: string, dimensions: number): string {
  return `${modelId}@${dimensions}`;
}

