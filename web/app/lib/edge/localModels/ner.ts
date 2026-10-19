// Track 4 — a token-classification NER for the on-device redactor.
//
// WHAT IT FIXES. redactor.ts's "model" mode asks the chat model (tier 1, a
// 1-3B generative model held in WebLLM) to list the personal-data spans in the
// text. That works, but it spends a whole generative model on span extraction:
// it is the largest thing on the device, it is grammar-constrained rather than
// trained for this, and it can only run when a chat engine happens to be warm.
// A token-classification model is trained for exactly this task and is a
// fraction of the size. This module is that third path; redactor.ts keeps the
// pattern tier below it and the chat-model pass behind it.
//
// THE VERBATIM RULE SURVIVES INTACT, and is the reason this file exists rather
// than a call site somewhere. A token classifier does not return character
// offsets in this version of the library — the installed package's
// src/pipelines/token-classification.js carries a literal
// "TODO: Add support for start and end" where the offsets would go, and the
// grouped path rebuilds a `word` by decoding the token ids. A decoded word is
// NOT guaranteed to be a substring of the input (sub-word joins, dropped
// punctuation spacing, casing on an uncased model). So every span this module
// returns is resolved back to a REAL SUBSTRING OF THE SOURCE before it is
// handed on, and a span that cannot be resolved is dropped. A model must never
// be able to rewrite text the person did not write.
//
// FOUR PROPERTIES, and how this file holds them — identical in shape to its
// sibling rerank.ts, deliberately:
//
//  1. NO SPEND. No platform endpoint is contacted at all, not even
//     GET /api/edge/manifest: the /edge chip is driven through
//     downloadManager.reportExternalProgress(), which makes no request.
//
//  2. NO SURPRISE DOWNLOAD. extractEntitiesLocal() can never load a model. The
//     single loader is installNerModel(), which a UI must call from an
//     explicit click; it walks the Cache API for the exact files a load would
//     fetch first, so an already-cached model is reported as a warm-up rather
//     than announced as a download.
//
//  3. NO SILENT NETWORK. Two hosts, named in NER_NETWORK_HOSTS and repeated in
//     the copy shown before the click: huggingface.co (model files —
//     env.remoteHost in the installed package's src/env.js) and
//     cdn.jsdelivr.net (onnxruntime-web's WASM binaries — the wasmPaths
//     default in src/backends/onnx.js). Inference sends nothing anywhere.
//
//  4. HONEST DEGRADATION. Not installed, edge off, no browser, a model that
//     throws, output that cannot be read — each is a distinct typed reason on
//     the result, never a silently empty span list. An empty list from this
//     module means "the model ran and found nothing", and callers can tell
//     that apart from "the model could not look" (see NerOutcome.reason).
//
// WHERE IT RUNS: the main thread, via dynamic import(). See the same section
// in rerank.ts for why (track scope), and for the two consequences that are
// handled rather than hidden.

//
// Layout: nerSpans.ts (result shape, label mapping, verbatim resolver, chunker)
// and nerModel.ts (identity, test seam, cache probe, load lifecycle) hold the
// pieces and are re-exported here; this file owns install and extraction.

import { edgeEnabled } from "../capabilities";
import { downloadManager, type AssetStatus } from "../downloadManager";
import {
  NER_ASSET_ID,
  NER_DISPLAY_NAME,
  NER_NETWORK_HOSTS,
  classifyChunk,
  loadNer,
  markNerOnDevice,
  nerAvailable,
  nerFilesOnDevice,
  unloadNerModel,
  type RawNerSpan,
} from "./nerModel";
import {
  chunkForNer,
  mapNerLabel,
  refusal,
  resolveVerbatimSpan,
  type LocalEntity,
  type LocalEntityCategory,
  type NerOutcome,
} from "./nerSpans";
export {
  NER_ASSET_ID,
  NER_DISPLAY_NAME,
  NER_MODEL_ID,
  NER_NETWORK_HOSTS,
  __setNerOverridesForTests,
  isNerModelOnDevice,
  nerAvailable,
  nerDevice,
  unloadNerModel,
  type NerClassifyFn,
  type RawNerSpan,
} from "./nerModel";
export {
  chunkForNer,
  mapNerLabel,
  resolveVerbatimSpan,
  type LocalEntity,
  type LocalEntityCategory,
  type NerOutcome,
  type NerReason,
} from "./nerSpans";

// ---------------------------------------------------------------------------
// Limits — policy, not measurements
// ---------------------------------------------------------------------------

/** Total characters this module will classify in one call. A person pasting a
 * whole document into a chat box must not freeze the tab; past this the caller
 * is told (reason "truncated") rather than quietly given a partial answer. */
const MAX_TOTAL_CHARS = 20_000;
/** Wall-clock ceiling for one extract call. On expiry the spans found so far
 * are returned WITH reason "partial" — unlike the reranker, a partial result
 * here is strictly safer than none (it masks more, never less). */
const DEFAULT_BUDGET_MS = 5_000;
/** Idle watchdog for a load, re-armed on every progress event. */
const LOAD_IDLE_TIMEOUT_MS = 120_000;
/** Input to the storage-headroom question and nothing else. A GUESS, never
 * displayed — this module has not weighed the model and must not print a size
 * it does not know. Erring small is the dangerous direction here. */
const STORAGE_PROBE_BYTES = 100_000_000;

/** Confidence floors, by category.
 *
 * PER/ORG are the categories this model is squarely trained for and the ones
 * the redactor most needs. LOC sits higher on purpose: mapped to the
 * redactor's "address" placeholder it would otherwise mask ordinary place
 * names ("flights to London"), which breaks the legitimate task without
 * protecting anything the person did not already publish. MISC is not mapped
 * at all — it covers nationalities, events and product names, none of which
 * are personal data, and masking them is pure task damage.
 *
 * These numbers are a POSTURE CHOICE, not a measured operating point: this
 * repo has no labelled redaction set, so nothing here claims a precision or a
 * recall figure. They are exported so a caller can move them deliberately. */
export const NER_DEFAULT_THRESHOLDS: Readonly<Record<string, number>> = {
  person: 0.7,
  organization: 0.7,
  address: 0.9,
};

// ---------------------------------------------------------------------------
// Display-safe strings
// ---------------------------------------------------------------------------

export const NER_EDGE_OFF_DETAIL =
  "On-device AI is turned off for this deployment, so the on-device name detector can't run here.";
export const NER_NO_BROWSER_DETAIL = "The on-device name detector needs a browser to run in.";
export const NER_NOT_INSTALLED_DETAIL =
  "The on-device name detector isn't loaded, so names and organisations aren't being masked. " +
  "Turn it on once to download it — nothing is downloaded automatically.";
export const NER_CANCELLED_DETAIL = "The name-detector download was cancelled.";
export const NER_NO_SPACE_DETAIL = "There isn't enough free space on this device for the on-device name detector.";
export const NER_DOWNLOAD_DISCLOSURE =
  `Downloading the on-device name detector fetches model files from ${NER_NETWORK_HOSTS.join(" and ")}. ` +
  "That is the only network this feature uses: the text it reads never leaves this device.";

// ---------------------------------------------------------------------------
// Install — the ONLY function in this module that may move bytes
// ---------------------------------------------------------------------------

export interface NerInstallProgress {
  /** Bytes moved so far, as reported by the library. */
  loaded: number;
  /** Bytes the transfer expects to move, as reported by the library. 0 means
   * UNKNOWN and must render as no size at all — it is what a warm-up reports
   * throughout, and what a download reports until its first progress event.
   * Never an estimate made here. */
  total: number;
  detail: string;
}

/**
 * Downloads (if needed) and warms the token classifier. MUST be called from an
 * explicit user action: it is the only path in this module that can reach the
 * network. Resolves true when extractEntitiesLocal() will work. Never throws.
 *
 * Cancellation is partial and says so: from_pretrained takes no AbortSignal in
 * @huggingface/transformers 4.2.0 (verified — no signal/abort parameter in its
 * src/utils/hub.js), so an abort stops the install from becoming ready and
 * from reporting, but bytes already in flight finish into the browser cache.
 */
export async function installNerModel(
  onProgress: (p: NerInstallProgress) => void,
  signal?: AbortSignal
): Promise<boolean> {
  const report = (loadedBytes: number, total: number, detail: string): void => {
    if (typeof onProgress !== "function") return;
    try {
      onProgress({ loaded: loadedBytes, total, detail });
    } catch {
      // A broken listener never breaks an install.
    }
  };
  const chip = (patch: Partial<Pick<AssetStatus, "state" | "receivedBytes" | "totalBytes" | "error">>): void => {
    try {
      // reportExternalProgress, not enqueue(): same visible row in the shared
      // /edge console, but no request — enqueue() would reach
      // GET /api/edge/manifest. Property 1.
      downloadManager.reportExternalProgress(NER_ASSET_ID, NER_DISPLAY_NAME, patch);
    } catch {
      // The chip is a convenience.
    }
  };

  if (!edgeEnabled()) {
    report(0, 0, NER_EDGE_OFF_DETAIL);
    return false;
  }
  if (typeof window === "undefined") {
    report(0, 0, NER_NO_BROWSER_DETAIL);
    return false;
  }
  if (nerAvailable()) {
    chip({ state: "ready" });
    report(0, 0, "The on-device name detector is ready.");
    return true;
  }

  const aborted = (): boolean => signal?.aborted === true;
  if (aborted()) {
    report(0, 0, NER_CANCELLED_DETAIL);
    return false;
  }

  const alreadyOnDevice = (await nerFilesOnDevice()) === true;
  if (aborted()) {
    report(0, 0, NER_CANCELLED_DETAIL);
    return false;
  }

  if (!alreadyOnDevice) {
    const hasRoom = await downloadManager.hasStorageHeadroomFor(STORAGE_PROBE_BYTES);
    if (aborted()) {
      report(0, 0, NER_CANCELLED_DETAIL);
      return false;
    }
    if (!hasRoom) {
      chip({ state: "error", error: "Not enough free space on this device" });
      report(0, 0, NER_NO_SPACE_DETAIL);
      return false;
    }
  }

  let movedBytes = 0;
  let transferBytes = 0;
  let lastActivity = Date.now();
  const startingLine = alreadyOnDevice
    ? "Starting the on-device name detector…"
    : `Downloading the on-device name detector. ${NER_DOWNLOAD_DISCLOSURE}`;
  report(0, 0, startingLine);
  chip({ state: alreadyOnDevice ? "verifying" : "downloading", receivedBytes: 0, totalBytes: 0 });

  const onLibraryProgress = (info: unknown): void => {
    lastActivity = Date.now();
    if (aborted()) return;
    const status = (info as { status?: unknown })?.status;
    const loadedNow = Number((info as { loaded?: unknown })?.loaded) || 0;
    const totalNow = Number((info as { total?: unknown })?.total) || 0;
    if (status === "progress_total" && totalNow > 0) {
      movedBytes = loadedNow;
      transferBytes = totalNow;
      chip({
        state: loadedNow >= totalNow ? "verifying" : "downloading",
        receivedBytes: loadedNow,
        totalBytes: totalNow,
      });
      report(loadedNow, totalNow, startingLine);
      return;
    }
    report(movedBytes, transferBytes, "Preparing the on-device name detector…");
  };

  let watchdog: ReturnType<typeof setTimeout> | null = null;
  const idleGuard = new Promise<never>((_resolve, reject) => {
    const tick = (): void => {
      if (Date.now() - lastActivity >= LOAD_IDLE_TIMEOUT_MS) {
        reject(new Error("The on-device name detector stopped responding while loading."));
        return;
      }
      watchdog = setTimeout(tick, 5_000);
    };
    watchdog = setTimeout(tick, 5_000);
  });

  try {
    await Promise.race([loadNer(onLibraryProgress), idleGuard]);
  } catch (err) {
    const cancelled = aborted();
    const detail = cancelled
      ? NER_CANCELLED_DETAIL
      : err instanceof Error && err.message
        ? err.message
        : "The on-device name detector could not be set up.";
    chip({
      state: "error",
      error: cancelled ? "Cancelled" : alreadyOnDevice ? "Could not start the name detector" : "Download failed",
    });
    report(0, 0, detail);
    return false;
  } finally {
    if (watchdog !== null) clearTimeout(watchdog);
  }

  if (aborted()) {
    unloadNerModel();
    chip({ state: "error", error: "Cancelled" });
    report(0, 0, NER_CANCELLED_DETAIL);
    return false;
  }

  markNerOnDevice();
  chip(
    transferBytes > 0 ? { state: "ready", receivedBytes: transferBytes, totalBytes: transferBytes } : { state: "ready" }
  );
  void downloadManager.requestPersistentStorage();
  report(transferBytes, transferBytes, "On-device name detection is on.");
  return true;
}

// ---------------------------------------------------------------------------
// Extraction
// ---------------------------------------------------------------------------

/** Why a function and not `options.signal?.aborted === true` inline: after the
 * first such check, TypeScript narrows the readonly `aborted` property to
 * `false` for the rest of the function and flags every later re-check as a
 * comparison that cannot be true (TS2367). At RUNTIME it very much can be —
 * an abort fires between awaits, which is the only reason to re-check at all.
 * Reading it through a parameter keeps the check live in both worlds. */
function signalAborted(signal: AbortSignal | undefined): boolean {
  return signal?.aborted === true;
}

function yieldToEventLoop(): Promise<void> {
  return new Promise<void>((resolve) => setTimeout(resolve, 0));
}

export interface ExtractEntitiesOptions {
  signal?: AbortSignal;
  budgetMs?: number;
  /** Per-category confidence floors, merged over NER_DEFAULT_THRESHOLDS. */
  thresholds?: Partial<Record<LocalEntityCategory, number>>;
}

/**
 * Find the personal-data spans in `text` using the on-device token classifier.
 *
 * NEVER loads a model and NEVER downloads: with nothing warm this returns
 * reason "not-loaded" and an empty list, and the only loader in this file is
 * installNerModel(), which this function does not call.
 *
 * Every returned `text` is a verbatim substring of the `text` argument — see
 * resolveVerbatimSpan. Never throws.
 */
export async function extractEntitiesLocal(text: string, options: ExtractEntitiesOptions = {}): Promise<NerOutcome> {
  if (typeof text !== "string" || text.trim().length === 0) {
    return refusal("no-input", "There was no text to scan.");
  }
  if (!edgeEnabled()) return refusal("edge-off", NER_EDGE_OFF_DETAIL);
  if (!nerAvailable()) return refusal("not-loaded", NER_NOT_INSTALLED_DETAIL);
  if (signalAborted(options.signal)) {
    return refusal("aborted", "The on-device scan was cancelled before it started.");
  }

  const truncated = text.length > MAX_TOTAL_CHARS;
  const scanned = truncated ? text.slice(0, MAX_TOTAL_CHARS) : text;
  const chunks = chunkForNer(scanned);
  if (chunks.length === 0) return refusal("no-input", "There was no text to scan.");

  const thresholds: Record<string, number> = { ...NER_DEFAULT_THRESHOLDS, ...(options.thresholds || {}) };
  const budgetMs = Number(options.budgetMs) > 0 ? Number(options.budgetMs) : DEFAULT_BUDGET_MS;
  const deadline = Date.now() + budgetMs;

  const found: LocalEntity[] = [];
  const seen = new Set<string>();
  let stoppedEarly = false;

  for (let i = 0; i < chunks.length; i += 1) {
    if (signalAborted(options.signal) || Date.now() > deadline) {
      stoppedEarly = true;
      break;
    }
    let spans: RawNerSpan[];
    try {
      spans = await classifyChunk(chunks[i]);
    } catch {
      // A thrown chunk must not lose the spans already found: those are real,
      // and dropping them would mask LESS than the run had already earned.
      return {
        entities: found,
        reason: "failed",
        detail:
          found.length > 0
            ? "The on-device name detector stopped partway; what it had already found is still masked."
            : "The on-device name detector couldn't run, so no names were masked.",
      };
    }
    for (const span of spans) {
      const category = mapNerLabel(span?.entity_group ?? span?.entity);
      if (category === null) continue;
      const score = Number(span?.score);
      if (!Number.isFinite(score)) continue;
      const floor = typeof thresholds[category] === "number" ? thresholds[category] : 1;
      if (score < floor) continue;
      // Resolved against the WHOLE original text, not the chunk: the caller
      // masks the whole text, so the span has to be verbatim there.
      const resolved = resolveVerbatimSpan(text, span?.word);
      if (resolved === null) continue;
      const key = `${category}:${resolved}`;
      if (seen.has(key)) continue;
      seen.add(key);
      found.push({ text: resolved, category, score });
    }
    if (i + 1 < chunks.length) await yieldToEventLoop();
  }

  // Longest first, so a span nested inside another ("Acme" inside "Acme
  // Holdings") cannot blank out the middle of its parent's mask. Same ordering
  // rule redactor.ts applies to the chat-model path.
  found.sort((a: LocalEntity, b: LocalEntity) => b.text.length - a.text.length);

  if (stoppedEarly) {
    return {
      entities: found,
      reason: "partial",
      detail: "The on-device scan ran out of time, so only part of the message was checked for names.",
    };
  }
  if (truncated) {
    return {
      entities: found,
      reason: "truncated",
      detail: `Only the first ${MAX_TOTAL_CHARS} characters were checked for names on this device.`,
    };
  }
  return {
    entities: found,
    reason: "ok",
    detail:
      found.length > 0
        ? "The on-device name detector found spans to mask."
        : "The on-device name detector checked the whole message and found nothing to mask.",
  };
}
