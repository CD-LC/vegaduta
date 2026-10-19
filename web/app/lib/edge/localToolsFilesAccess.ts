// The local folder tools' access layer (split out of localToolsFiles.ts, which
// re-exports its public parts): the dependency seam onto fsAccess and the
// indexer, and the cached, serialised folder walk.

import type {
  IndexStatus,
  LocalFileRef,
  LocalRepoHandle,
  LocalWalkSummary,
  RememberedLocalRepo,
  RetrievedChunk,
} from "../localcode/types";

/**
 * How long a completed folder walk is reused.
 *
 * The walk is the expensive call in this module: fsAccess.listCodeFiles does
 * one getFile() and one 8000-byte head read per candidate file (see its
 * classifyContents), and that work is unbounded with respect to runLocalTool's
 * 2000 ms ceiling — a large enough folder WILL be abandoned mid-walk and come
 * back as "did not finish within 2000ms". Caching is what makes that recoverable
 * rather than permanent: the abandoned walk keeps running on the shared chain
 * below and fills this cache, so the model's natural next move (ask again) is
 * answered from it. 60s is chosen to cover a multi-turn exchange about one
 * folder while still being short enough that a file the person adds mid-session
 * appears on their next question rather than after a reload. Whenever an answer
 * comes from the cache its age is printed, so staleness is visible instead of
 * silent.
 */
export const WALK_CACHE_TTL_MS = 60_000;

// ---------------------------------------------------------------------------
// Dependencies
//
// Everything this module touches outside itself goes through this record, for
// two reasons. (a) The real implementations are loaded with a dynamic import so
// that a tier-1 turn which never calls a folder tool never pulls the indexer —
// chunker, the vector store, the worker plumbing — into the page. (b) The tests
// hand a fake directory handle and a fake index through the same door, so what
// they exercise is this module's logic rather than a mock of it.
// ---------------------------------------------------------------------------

export interface LocalFolderToolDeps {
  isLocalCodeSupported(): boolean;
  getRememberedLocalRepo(): Promise<RememberedLocalRepo | null>;
  listCodeFiles(repo: LocalRepoHandle): Promise<LocalFileRef[]>;
  getLastWalkSummary(): LocalWalkSummary;
  readLocalFile(repo: LocalRepoHandle, path: string): Promise<string>;
  restoreLocalIndexStatus(): Promise<IndexStatus>;
  getLocalIndexStatus(): IndexStatus;
  searchLocalIndex(query: string, k: number): Promise<RetrievedChunk[]>;
  /** The repo id the stored index was built for, or null. See
   *  INDEXED_REPO_STORAGE_KEY for why this is read directly. */
  indexedRepoId(): string | null;
}

/**
 * The localStorage key indexer.ts writes the indexed repo's id under, copied
 * here rather than imported because it is private to that module (its
 * activeRepoId() is not exported either).
 *
 * The duplication buys a refusal that cannot be produced any other way. The
 * folder these tools resolve comes from fsAccess's IndexedDB record — "the
 * folder that was opened" — while searchLocalIndex silently searches whichever
 * repo id this key holds — "the folder that was indexed". Those diverge the
 * moment someone opens a second folder without indexing it, and the result is
 * the exact failure the localcode modules are written to avoid everywhere else:
 * confident citations into files the person is not looking at. Comparing the
 * two ids is the only way to catch it from outside the indexer.
 *
 * If the key is renamed there, this reads null and local_search_files refuses
 * with NO_INDEX_DETAIL — it degrades into asking for a re-index, never into
 * searching the wrong folder.
 */
const INDEXED_REPO_STORAGE_KEY = "localcode.index.activeRepo";

let deps: LocalFolderToolDeps | null = null;

async function loadRealDeps(): Promise<LocalFolderToolDeps> {
  // Dynamic on purpose — see the block comment above.
  const fs = await import("../localcode/fsAccess");
  const indexer = await import("../localcode/index/indexer");
  return {
    isLocalCodeSupported: fs.isLocalCodeSupported,
    getRememberedLocalRepo: fs.getRememberedLocalRepo,
    listCodeFiles: fs.listCodeFiles,
    getLastWalkSummary: fs.getLastWalkSummary,
    readLocalFile: fs.readLocalFile,
    restoreLocalIndexStatus: indexer.restoreLocalIndexStatus,
    getLocalIndexStatus: indexer.getLocalIndexStatus,
    searchLocalIndex: indexer.searchLocalIndex,
    indexedRepoId: (): string | null => {
      try {
        if (typeof localStorage === "undefined") return null;
        return localStorage.getItem(INDEXED_REPO_STORAGE_KEY);
      } catch {
        // A browser with storage blocked for this origin. "No index" is the
        // honest answer; it cannot be searched from here either way.
        return null;
      }
    },
  };
}

export async function getDeps(): Promise<LocalFolderToolDeps> {
  if (!deps) deps = await loadRealDeps();
  return deps;
}

/**
 * Test-only seam. Production code NEVER calls this. Passing null restores the
 * real dependencies; either way the walk cache is dropped, because a cached
 * listing taken through a previous fake would otherwise answer the next test.
 */
export function __setLocalFolderToolDepsForTests(next: LocalFolderToolDeps | null): void {
  deps = next;
  walkCache = null;
  walkChain = Promise.resolve();
}

// ---------------------------------------------------------------------------
// The walk, cached and serialised
// ---------------------------------------------------------------------------

export interface WalkResult {
  repoId: string;
  at: number;
  files: LocalFileRef[];
  summary: LocalWalkSummary;
}

let walkCache: WalkResult | null = null;
/** Every walk queues behind the previous one. Two concurrent walks would race
 *  on fsAccess's module-level walk summary (getLastWalkSummary returns whatever
 *  the LAST walk left), which would attach one folder's skip counts to another
 *  folder's file list. Serialising is cheaper than defending against that. */
let walkChain: Promise<unknown> = Promise.resolve();

export function walkFolder(d: LocalFolderToolDeps, repo: LocalRepoHandle): Promise<WalkResult> {
  const run = walkChain.then(async (): Promise<WalkResult> => {
    const cached = walkCache;
    if (cached && cached.repoId === repo.id && Date.now() - cached.at < WALK_CACHE_TTL_MS) {
      return cached;
    }
    const files = await d.listCodeFiles(repo);
    const summary = d.getLastWalkSummary();
    const fresh: WalkResult = { repoId: repo.id, at: Date.now(), files, summary };
    walkCache = fresh;
    return fresh;
  });
  // The chain must survive a failed walk, or one rejection wedges every later
  // call. The rejection still reaches THIS caller through `run`.
  walkChain = run.then(
    (): undefined => undefined,
    (): undefined => undefined
  );
  return run;
}
