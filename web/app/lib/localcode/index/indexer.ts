// Local-code index — the on-device index over a folder the user picked.
//
// This is the retrieval half of the browser-local code assistant: chunker.ts
// cuts each file into citable spans, embedder.worker.ts turns those spans into
// vectors, and this module stores them, keeps them fresh, and searches them.
//
// THE THREE PROPERTIES THIS FILE EXISTS TO HOLD (they are the feature, not
// implementation detail — change them and there is no reason to ship it):
//
//   1. 100% local. No file content, chunk, vector, question or answer leaves
//      the browser: this module contains no fetch() and does not import
//      app/lib/api. TWO network calls do exist on the install path and are
//      named here so nobody has to re-audit it later. (a) Inside the worker,
//      the Hugging Face library pulls the ONNX weights. (b) On an explicit
//      install click only, downloadManager.enqueue() reaches the edge model
//      MANIFEST (GET /api/edge/manifest, via downloadManager's own
//      startTransfer) so the /edge chip can show this asset — that is a public,
//      unauthenticated, metadata-only endpoint and it carries none of the
//      user's data. Neither call happens on the build or search path: warming
//      an already-installed model talks to nothing but the browser cache.
//   2. Nothing downloads by itself. installLocalEmbedder() is the ONLY function
//      here that may move bytes, and it must be called from an explicit user
//      click. buildLocalIndex() and searchLocalIndex() refuse — with a
//      display-safe sentence — unless isLocalEmbedderReady() answers true, and
//      that answer comes from the REAL browser cache (see
//      embedderWeightsOnDevice), not from a localStorage flag somebody set
//      once. The worker refuses to load a model on an `embed` message at all,
//      so even a caller that forgets the gate cannot start a download.
//   3. Free and signed-out. No auth, no tenant, no hosted quota is consulted
//      anywhere in this path.
//
// NEVER THROWS: every exported function resolves. A failure shows up as
// getLocalIndexStatus().state === "error" with a display-safe `detail`, and
// searchLocalIndex() returns [] — the same never-throw contract edge/face.ts
// and edge/vision.ts hold, so an answer surface can render the reason instead
// of exploding on a rejected promise. Callers that need to distinguish "found
// nothing" from "could not look" must read getLocalIndexStatus(); the exact
// refusal strings are exported so a UI can branch on identity rather than
// pattern-matching prose that will be reworded.
//
// Persistence: IndexedDB when the browser has it, an in-process Map when it
// doesn't (SSR, private modes that refuse the open, jsdom under vitest). The
// fallback is functionally identical for one page-load and simply forgets
// everything on reload — worth it to keep one code path instead of an
// availability branch in every caller.

import type { IndexStatus, LocalChunk, LocalRepoHandle, RetrievedChunk } from "../types";
import { chunkSource } from "./chunker";
import {
  KEY_SEP,
  chunkKey,
  fileKey,
  getStore,
  type ChunkRecord,
  type IndexStore,
} from "./store";
import {
  ACTIVE_REPO_KEY,
  EMBED_BATCH_SIZE,
  MAX_FILE_BYTES,
  DEFAULT_TOP_K,
  MODEL_CHANGED_DETAIL,
  safeLocalGet,
  safeLocalSet,
  safeLocalRemove,
  type FileAccess,
  getOverrides,
} from "./indexerShared";
export { LOCALCODE_EMBEDDER_ASSET_ID, LOCALCODE_EMBEDDER_DISPLAY_NAME, EMBEDDING_MODEL_ID, EMBEDDING_DIMENSIONS, EMBEDDER_NOT_READY_DETAIL, MODEL_CHANGED_DETAIL, EDGE_OFF_DETAIL, NO_WORKER_DETAIL, isLocalEmbedderInstalled, __setIndexerOverridesForTests } from "./indexerShared";
export type { EmbedFn } from "./indexerShared";
import {
  resolveEmbed,
} from "./indexerEmbedder";
export { isLocalEmbedderReady, installLocalEmbedder, ensureLocalEmbedderModel } from "./indexerEmbedder";
export type { EmbedderInstallProgress } from "./indexerEmbedder";


// ---------------------------------------------------------------------------
// Status
// ---------------------------------------------------------------------------

/** `files` is files actually INDEXED — not files looked at. A file skipped for
 * size or lost to a read error moves the progress bar (its count is in
 * `detail`, "Scanned 12 of 340 files") but never lands in this number, because
 * a terminal "Indexed 340 files" on a repo where two files failed to read is a
 * lie the person has no way to catch. */
let status: IndexStatus = { state: "idle", files: 0, chunks: 0, indexedAt: null };

function setStatus(next: IndexStatus, onProgress?: (s: IndexStatus) => void): void {
  status = next;
  if (!onProgress) return;
  try {
    onProgress({ ...next });
  } catch {
    // A broken listener never breaks an index build.
  }
}

/** Synchronous snapshot. Never touches storage, never downloads — safe to call
 * from a render. A copy, so a caller can't mutate the module's state. */
export function getLocalIndexStatus(): IndexStatus {
  return { ...status };
}

// ---------------------------------------------------------------------------
// File access (lazy — fsAccess.ts is only needed for a real build)
// ---------------------------------------------------------------------------

async function resolveFileAccess(): Promise<FileAccess> {
  const injected = getOverrides()?.files;
  if (injected) return injected;
  // Deliberately dynamic: the File System Access facade drags in permission
  // plumbing that a page showing a *stored* index (search only, after a
  // reload) has no use for.
  const mod = await import("../fsAccess");
  return { listCodeFiles: mod.listCodeFiles, readLocalFile: mod.readLocalFile };
}

// ---------------------------------------------------------------------------
// Content hashing (change detection, not security)
// ---------------------------------------------------------------------------

/**
 * FNV-1a over the file text, mixed with its length. Deliberately NOT SHA-256:
 * this only answers "did this file change since we embedded it", crypto.subtle
 * is async and absent in some environments this module must still work in, and
 * a collision costs one skipped re-embed of one file — not a security boundary.
 */
function contentHash(text: string): string {
  let h1 = 0x811c9dc5;
  let h2 = 0x01000193;
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    h1 ^= c;
    h1 = Math.imul(h1, 0x01000193);
    h2 = Math.imul(h2 ^ c, 0x85ebca6b);
  }
  return `${(h1 >>> 0).toString(36)}-${(h2 >>> 0).toString(36)}-${text.length.toString(36)}`;
}

// ---------------------------------------------------------------------------
// Vector cache (the thing cosine similarity actually runs over)
// ---------------------------------------------------------------------------

interface CachedVector {
  id: string;
  path: string;
  startLine: number;
  endLine: number;
  model: string;
  vector: Float32Array;
}

/** Vectors for the active repo, held in memory for query latency: 10,000 chunks
 * × 384 dims × 4 bytes is 15.36 MB, versus a full IndexedDB read per query.
 * Chunk TEXT is deliberately dropped here and re-read for the top-k only — text
 * is by far the bigger half and is needed for a handful of rows, not all of
 * them. */
let vectorCache: { repoId: string; rows: CachedVector[] } | null = null;

async function ensureVectorCache(repoId: string): Promise<CachedVector[]> {
  if (vectorCache && vectorCache.repoId === repoId) return vectorCache.rows;
  const store = await getStore();
  const records = await store.listChunks(repoId);
  const rows: CachedVector[] = records.map((r: ChunkRecord) => ({
    id: r.id,
    path: r.path,
    startLine: r.startLine,
    endLine: r.endLine,
    model: r.model,
    vector: r.vector,
  }));
  vectorCache = { repoId, rows };
  return rows;
}

/** Standard cosine similarity — the same form edge/face.ts uses. Vectors from
 * the worker arrive L2-normalized (so this is a dot product in all but name),
 * but the general form costs nothing and keeps an injected or older vector
 * honest instead of silently scoring it wrong.
 *
 * Mismatched widths score 0 rather than being truncated to the shorter one: two
 * vectors of different widths are from different models, and comparing their
 * overlapping prefix produces a confident number that means nothing. Callers
 * filter on the model stamp first; this is the backstop for whatever slips
 * past. */
export function cosineSimilarity(a: ArrayLike<number>, b: ArrayLike<number>): number {
  if (!a || !b || a.length !== b.length || a.length === 0) return 0;
  let dot = 0;
  let normA = 0;
  let normB = 0;
  for (let i = 0; i < a.length; i++) {
    dot += a[i] * b[i];
    normA += a[i] * a[i];
    normB += b[i] * b[i];
  }
  const denom = Math.sqrt(normA) * Math.sqrt(normB);
  return denom === 0 ? 0 : dot / denom;
}

// ---------------------------------------------------------------------------
// Build
// ---------------------------------------------------------------------------

let buildInFlight = false;

function errorStatus(detail: string, onProgress?: (s: IndexStatus) => void): IndexStatus {
  setStatus({ state: "error", files: status.files, chunks: status.chunks, indexedAt: status.indexedAt, detail }, onProgress);
  return getLocalIndexStatus();
}

/** What the store ACTUALLY holds for this repo, read back rather than counted
 * along the way. Every terminal status uses this: a running total can drift
 * past a skipped file, an unreadable one or an aborted write, and the number a
 * person reads at the end is the one they will trust. */
async function storedTotals(store: IndexStore, repoId: string): Promise<{ files: number; chunks: number }> {
  try {
    const records = await store.listFiles(repoId);
    let chunks = 0;
    for (const record of records) chunks += record.chunks;
    return { files: records.length, chunks };
  } catch {
    return { files: 0, chunks: 0 };
  }
}

/**
 * Indexes every code file in `repo`, embedding only what changed since last
 * time (per file path + content hash + embedding-model stamp), and leaves the
 * module in a searchable state. Resolves with the final IndexStatus — it never
 * rejects, and never downloads a model (see property 2 in the header): when the
 * embedder is not already on this device it returns immediately with
 * state "error" and detail === EMBEDDER_NOT_READY_DETAIL, before the folder is
 * even walked.
 *
 * Incremental: a file whose content hash AND model stamp match the stored ones
 * is not read for embedding again, its existing chunks stay, and it still
 * counts toward progress. Anything this run did not index — a file that left
 * the repo, and equally a file still present that is past the size cap or could
 * not be read — has its record and chunks pruned, so the stored totals only
 * ever describe files that are genuinely indexed right now.
 *
 * Abort: `signal` is checked between files and between embedding batches — a
 * batch already inside the worker runs to completion (a worker can't be
 * interrupted mid-inference), so the worst-case latency after an abort is one
 * batch of EMBED_BATCH_SIZE chunks, not the rest of the repo. Whatever was
 * FINISHED before the abort stays indexed and searchable; the file that was
 * mid-flight is removed whole — both its record and the chunks this run had
 * already written — so the store never holds chunks no file record accounts
 * for.
 */
export async function buildLocalIndex(
  repo: LocalRepoHandle,
  onProgress: (s: IndexStatus) => void,
  signal?: AbortSignal
): Promise<IndexStatus> {
  if (!repo || !repo.id) {
    return errorStatus("No folder was selected to index.", onProgress);
  }
  if (buildInFlight) {
    // Deliberately does NOT touch the module status: the build already running
    // owns it, and stamping "error" over its live "indexing" state would make
    // every other subscriber render a failure that did not happen.
    const refusal: IndexStatus = {
      ...getLocalIndexStatus(),
      state: "error",
      detail: "An index build is already running for this browser tab.",
    };
    try {
      onProgress(refusal);
    } catch {
      // A broken listener never breaks the caller.
    }
    return refusal;
  }
  buildInFlight = true;
  const repoId = repo.id;
  let seenCount = 0;
  let skipped = 0;
  try {
    setStatus({ state: "indexing", files: 0, chunks: 0, indexedAt: null, detail: "Reading the folder…" }, onProgress);

    // Order matters: refuse on the model BEFORE walking the folder, so a person
    // without the model installed gets the honest sentence immediately instead
    // of after a minute of directory traversal.
    const binding = await resolveEmbed();
    const access = await resolveFileAccess();
    const files = await access.listCodeFiles(repo);
    const total = files.length;
    const store = await getStore();
    safeLocalSet(ACTIVE_REPO_KEY, repoId);

    const seen = new Set<string>();
    let aborted = false;
    let indexedCount = 0;
    let chunkCount = 0;

    const progress = (detail: string): void => {
      setStatus({ state: "indexing", files: indexedCount, chunks: chunkCount, indexedAt: null, detail }, onProgress);
    };

    for (const file of files) {
      if (signal?.aborted) {
        aborted = true;
        break;
      }
      seenCount++;
      // "Scanned", not "Indexed": this counter includes files we are about to
      // skip, and the `files` field beside it counts only what really landed.
      const progressDetail = `Scanned ${seenCount} of ${total} files`;
      // NOT added to `seen` — see the prune pass below. A file this run cannot
      // index is a file that must not stay indexed.
      if (file.size > MAX_FILE_BYTES) {
        // Skipped, but counted as scanned — a progress bar that stalls on a
        // lockfile is a bug report waiting to happen.
        skipped++;
        progress(progressDetail);
        continue;
      }
      let text = "";
      try {
        text = await access.readLocalFile(repo, file.path);
      } catch {
        // One unreadable file (a permission quirk, a file deleted mid-walk)
        // must not end the build.
        skipped++;
        progress(progressDetail);
        continue;
      }
      // Past every skip: this file is being indexed on this run, so it is the
      // kind of file the prune pass must leave alone.
      seen.add(file.path);
      const hash = contentHash(text);
      const existing = await store.getFile(repoId, file.path);
      // The model stamp is half of "unchanged": same bytes embedded by a
      // different model is a different vector, and reusing it would mix two
      // spaces in one index.
      if (existing && existing.hash === hash && existing.model === binding.signature) {
        indexedCount++;
        chunkCount += existing.chunks;
        progress(progressDetail);
        continue;
      }

      const chunks = chunkSource(file.path, text, file.lang);
      // The old chunks are gone whatever happens next: their line numbers refer
      // to content that no longer exists (or their vectors to a model that no
      // longer runs), and a citation into stale lines is worse than none.
      await store.deleteChunksForFile(repoId, file.path);
      let embedded = 0;
      for (let i = 0; i < chunks.length; i += EMBED_BATCH_SIZE) {
        if (signal?.aborted) {
          aborted = true;
          break;
        }
        const batch = chunks.slice(i, i + EMBED_BATCH_SIZE);
        const vectors = await binding.embed(batch.map((c: LocalChunk) => c.text));
        const records: ChunkRecord[] = [];
        for (let j = 0; j < batch.length; j++) {
          const chunk = batch[j];
          const vector = vectors[j];
          if (!vector || vector.length === 0) continue;
          records.push({
            key: chunkKey(repoId, chunk.id),
            repoId,
            repoPath: repoId + KEY_SEP + chunk.path,
            id: chunk.id,
            path: chunk.path,
            startLine: chunk.startLine,
            endLine: chunk.endLine,
            text: chunk.text,
            model: binding.signature,
            vector,
          });
        }
        await store.putChunks(records);
        embedded += records.length;
      }
      if (aborted) {
        // A half-embedded file must not be recorded as current, or the next
        // build would skip its missing chunks forever — AND the partial chunks
        // this run already wrote have to go with it, or they stay in the store
        // with no file record to account for them (search would return them,
        // restoreLocalIndexStatus would not count them).
        await store.deleteChunksForFile(repoId, file.path);
        await store.deleteFile(repoId, file.path);
        break;
      }
      await store.putFile({
        key: fileKey(repoId, file.path),
        repoId,
        path: file.path,
        hash,
        model: binding.signature,
        chunks: embedded,
        indexedAt: Date.now(),
      });
      indexedCount++;
      chunkCount += embedded;
      progress(progressDetail);
    }

    if (!aborted) {
      // Prune everything this run did not index. Two kinds of record end up
      // here and both have to go:
      //   - a file that LEFT the repo, whose chunks would otherwise keep being
      //     cited at a path that no longer exists;
      //   - a file still on disk that this run could NOT index — past the size
      //     cap, or unreadable. Its old chunks describe content nothing will
      //     refresh, and leaving the record behind also keeps it inside the
      //     stored totals, so "Indexed N files (1 skipped)" would count the
      //     skipped file in N. A file that can no longer be indexed is not an
      //     indexed file.
      // Skipped in an aborted run on purpose: the walk stopped early, so
      // "not seen" there means "not reached yet", not "not indexable".
      const known = await store.listFiles(repoId);
      for (const record of known) {
        if (seen.has(record.path)) continue;
        await store.deleteChunksForFile(repoId, record.path);
        await store.deleteFile(repoId, record.path);
      }
    }

    const totals = await storedTotals(store, repoId);
    if (aborted) {
      // Cancellation isn't an error, and a partial index genuinely is
      // searchable — so say exactly that, and only fall back to "idle" when
      // there is nothing to search.
      if (totals.chunks > 0) {
        setStatus(
          {
            state: "ready",
            files: totals.files,
            chunks: totals.chunks,
            indexedAt: Date.now(),
            detail: `Cancelled after ${seenCount} of ${total} files — search covers only what was indexed.`,
          },
          onProgress
        );
      } else {
        setStatus({ state: "idle", files: 0, chunks: 0, indexedAt: null, detail: "Indexing cancelled." }, onProgress);
      }
      return getLocalIndexStatus();
    }

    const skippedNote = skipped > 0 ? ` (${skipped} skipped)` : "";
    setStatus(
      {
        state: "ready",
        files: totals.files,
        chunks: totals.chunks,
        indexedAt: Date.now(),
        detail: total === 0 ? "No code files found in that folder." : `Indexed ${totals.files} files${skippedNote}`,
      },
      onProgress
    );
    return getLocalIndexStatus();
  } catch (err) {
    return errorStatus(err instanceof Error ? err.message : String(err), onProgress);
  } finally {
    buildInFlight = false;
    // Every exit from a build invalidates the cache, the thrown one most of
    // all: by the time something fails mid-run, chunks have been written and a
    // file's old chunks deleted, so the in-memory rows describe an index that
    // no longer exists. Leaving them behind made a failed build return stale
    // hits and silently fewer than k of them.
    vectorCache = null;
  }
}

// ---------------------------------------------------------------------------
// Search
// ---------------------------------------------------------------------------

function activeRepoId(): string | null {
  return safeLocalGet(ACTIVE_REPO_KEY);
}

/**
 * Restores the status of an index built in an earlier session. The stored
 * index outlives the directory handle (chunk text lives in IndexedDB), so a
 * reloaded page can answer questions about the code without re-picking the
 * folder — this is what tells the UI that. Cheap, never downloads, never
 * throws; a no-op once a build has run in this tab.
 *
 * It does NOT check the embedding-model stamp: knowing today's signature means
 * loading the model, and this runs on mount. A stored index built by a
 * different model therefore shows as "ready" here and is caught at the first
 * search, which is the first moment the signature is knowable for free.
 */
export async function restoreLocalIndexStatus(): Promise<IndexStatus> {
  if (status.state !== "idle") return getLocalIndexStatus();
  const repoId = activeRepoId();
  if (!repoId) return getLocalIndexStatus();
  try {
    const store = await getStore();
    const files = await store.listFiles(repoId);
    if (files.length === 0) return getLocalIndexStatus();
    let chunks = 0;
    let indexedAt = 0;
    for (const record of files) {
      chunks += record.chunks;
      if (record.indexedAt > indexedAt) indexedAt = record.indexedAt;
    }
    setStatus({ state: "ready", files: files.length, chunks, indexedAt: indexedAt || null, detail: `Indexed ${files.length} files` });
  } catch {
    // A storage read failure just means "nothing restored" — the next build
    // rebuilds from scratch.
  }
  return getLocalIndexStatus();
}

/**
 * Top-k chunks for a query, by cosine similarity over the stored vectors. The
 * query is embedded on-device by the same worker that embedded the index; it
 * is never sent anywhere.
 *
 * Returns [] — never throws — when there is nothing to search OR when the
 * embedder can't run. Those are different situations and a caller must be able
 * to tell them apart: the second one sets getLocalIndexStatus() to state
 * "error" with a display-safe `detail` (EMBEDDER_NOT_READY_DETAIL when the
 * model isn't on the device, MODEL_CHANGED_DETAIL when the stored vectors came
 * from a different model), so an answer surface can show the reason instead of
 * claiming the code contains nothing relevant.
 */
export async function searchLocalIndex(query: string, k: number = DEFAULT_TOP_K): Promise<RetrievedChunk[]> {
  if (typeof query !== "string" || query.trim().length === 0) return [];
  const repoId = activeRepoId();
  if (!repoId) return [];
  const topK = Math.max(1, Math.floor(k) || DEFAULT_TOP_K);
  try {
    // Rows first: an empty index answers without warming a model at all.
    const rows = await ensureVectorCache(repoId);
    if (rows.length === 0) return [];
    const binding = await resolveEmbed();
    const usable = rows.filter((row: CachedVector) => row.model === binding.signature);
    if (usable.length === 0) {
      // There IS an index; it just isn't in this embedder's vector space.
      setStatus({
        state: "error",
        files: status.files,
        chunks: status.chunks,
        indexedAt: status.indexedAt,
        detail: MODEL_CHANGED_DETAIL,
      });
      return [];
    }
    const embedded = await binding.embed([query]);
    const queryVector = embedded && embedded[0];
    if (!queryVector || queryVector.length === 0) return [];
    const scored = usable.map((row: CachedVector) => ({ row, score: cosineSimilarity(queryVector, row.vector) }));
    scored.sort((a, b) => b.score - a.score);
    const top = scored.slice(0, topK);
    const store = await getStore();
    const records = await store.getChunks(repoId, top.map((entry) => entry.row.id));
    const byId = new Map<string, ChunkRecord>();
    for (const record of records) byId.set(record.id, record);
    const out: RetrievedChunk[] = [];
    for (const entry of top) {
      const record = byId.get(entry.row.id);
      if (!record) continue; // pruned between the cache read and now
      out.push({
        id: record.id,
        path: record.path,
        startLine: record.startLine,
        endLine: record.endLine,
        text: record.text,
        score: entry.score,
      });
    }
    return out;
  } catch (err) {
    setStatus({
      state: "error",
      files: status.files,
      chunks: status.chunks,
      indexedAt: status.indexedAt,
      detail: err instanceof Error ? err.message : String(err),
    });
    return [];
  }
}

/**
 * Deletes the whole on-device index (every repo) and resets the status. The
 * embedding model itself is NOT deleted — it is a separate, reusable install
 * that the /edge download console manages alongside every other on-device
 * model, and throwing the weights away because someone cleared an index would
 * make the next index start with a download. Never throws.
 */
export async function clearLocalIndex(): Promise<void> {
  vectorCache = null;
  safeLocalRemove(ACTIVE_REPO_KEY);
  try {
    const store = await getStore();
    await store.clearAll();
  } catch {
    // Best-effort: the status reset below still stops every read path.
  }
  setStatus({ state: "idle", files: 0, chunks: 0, indexedAt: null });
}
