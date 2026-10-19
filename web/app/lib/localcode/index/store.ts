// IndexedDB (with in-memory fallback) storage for the local code index,
// extracted verbatim from indexer.ts. No dependency on the embedder.

// ---------------------------------------------------------------------------
// Storage
// ---------------------------------------------------------------------------

export interface FileRecord {
  key: string;
  repoId: string;
  path: string;
  hash: string;
  /** modelSignature() of the embedder that produced this file's chunks. A file
   * whose stamp doesn't match today's embedder is re-embedded, never reused. */
  model: string;
  chunks: number;
  indexedAt: number;
}

export interface ChunkRecord {
  key: string;
  repoId: string;
  repoPath: string;
  id: string;
  path: string;
  startLine: number;
  endLine: number;
  text: string;
  /** Same stamp as FileRecord.model, carried per row because the vector cache
   * is built from chunks and has to filter on it without a second read. */
  model: string;
  vector: Float32Array;
}

export interface IndexStore {
  getFile(repoId: string, path: string): Promise<FileRecord | null>;
  putFile(record: FileRecord): Promise<void>;
  listFiles(repoId: string): Promise<FileRecord[]>;
  deleteFile(repoId: string, path: string): Promise<void>;
  putChunks(records: ChunkRecord[]): Promise<void>;
  deleteChunksForFile(repoId: string, path: string): Promise<void>;
  listChunks(repoId: string): Promise<ChunkRecord[]>;
  getChunks(repoId: string, ids: string[]): Promise<ChunkRecord[]>;
  clearAll(): Promise<void>;
}

const DB_NAME = "vegaduta-localcode-index";
/** Bump this whenever the RECORD SHAPE changes (and when the embedding model
 * changes, to reclaim the space the stamp would otherwise only orphan). The
 * upgrade drops both stores: every row is a derived artefact of files the user
 * still has, so rebuilding is cheap and keeping a shape we no longer
 * understand is not. v2 added FileRecord.model / ChunkRecord.model. */
const DB_VERSION = 2;
const FILES_STORE = "files";
const CHUNKS_STORE = "chunks";
/** NUL can't appear in a path, so it is a collision-free compound-key joiner
 * and keeps the key a plain string (IDB compound keys can't be used with the
 * simple get/put calls below without an array keyPath). */
export const KEY_SEP = "\u0000";

export function fileKey(repoId: string, path: string): string {
  return repoId + KEY_SEP + path;
}

export function chunkKey(repoId: string, chunkId: string): string {
  return repoId + KEY_SEP + chunkId;
}

function createMemoryStore(): IndexStore {
  const files = new Map<string, FileRecord>();
  const chunks = new Map<string, ChunkRecord>();
  return {
    async getFile(repoId: string, path: string): Promise<FileRecord | null> {
      return files.get(fileKey(repoId, path)) ?? null;
    },
    async putFile(record: FileRecord): Promise<void> {
      files.set(record.key, record);
    },
    async listFiles(repoId: string): Promise<FileRecord[]> {
      return Array.from(files.values()).filter((f: FileRecord) => f.repoId === repoId);
    },
    async deleteFile(repoId: string, path: string): Promise<void> {
      files.delete(fileKey(repoId, path));
    },
    async putChunks(records: ChunkRecord[]): Promise<void> {
      for (const record of records) chunks.set(record.key, record);
    },
    async deleteChunksForFile(repoId: string, path: string): Promise<void> {
      const prefix = repoId + KEY_SEP + path;
      for (const [key, record] of chunks) {
        if (record.repoPath === prefix) chunks.delete(key);
      }
    },
    async listChunks(repoId: string): Promise<ChunkRecord[]> {
      return Array.from(chunks.values()).filter((c: ChunkRecord) => c.repoId === repoId);
    },
    async getChunks(repoId: string, ids: string[]): Promise<ChunkRecord[]> {
      const out: ChunkRecord[] = [];
      for (const id of ids) {
        const record = chunks.get(chunkKey(repoId, id));
        if (record) out.push(record);
      }
      return out;
    },
    async clearAll(): Promise<void> {
      files.clear();
      chunks.clear();
    },
  };
}

function idbRequest<T>(req: IDBRequest<T>): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error || new Error("IndexedDB request failed"));
  });
}

function txDone(tx: IDBTransaction): Promise<void> {
  return new Promise<void>((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onabort = () => reject(tx.error || new Error("IndexedDB transaction aborted"));
    tx.onerror = () => reject(tx.error || new Error("IndexedDB transaction failed"));
  });
}

function openDatabase(): Promise<IDBDatabase> {
  return new Promise<IDBDatabase>((resolve, reject) => {
    const open = indexedDB.open(DB_NAME, DB_VERSION);
    open.onupgradeneeded = () => {
      const db = open.result;
      // Drop and recreate rather than migrate: see DB_VERSION. Rows written by
      // an older shape carry no model stamp, and a row whose vector space is
      // unknown is worse than no row.
      if (db.objectStoreNames.contains(FILES_STORE)) db.deleteObjectStore(FILES_STORE);
      if (db.objectStoreNames.contains(CHUNKS_STORE)) db.deleteObjectStore(CHUNKS_STORE);
      const filesStore = db.createObjectStore(FILES_STORE, { keyPath: "key" });
      filesStore.createIndex("repo", "repoId", { unique: false });
      const chunksStore = db.createObjectStore(CHUNKS_STORE, { keyPath: "key" });
      chunksStore.createIndex("repo", "repoId", { unique: false });
      chunksStore.createIndex("repoPath", "repoPath", { unique: false });
    };
    open.onsuccess = () => resolve(open.result);
    open.onerror = () => reject(open.error || new Error("IndexedDB open failed"));
    open.onblocked = () => reject(new Error("IndexedDB open blocked by another tab"));
  });
}

function createIdbStore(db: IDBDatabase): IndexStore {
  return {
    async getFile(repoId: string, path: string): Promise<FileRecord | null> {
      const tx = db.transaction(FILES_STORE, "readonly");
      const record = await idbRequest<FileRecord>(tx.objectStore(FILES_STORE).get(fileKey(repoId, path)));
      return record ?? null;
    },
    async putFile(record: FileRecord): Promise<void> {
      const tx = db.transaction(FILES_STORE, "readwrite");
      tx.objectStore(FILES_STORE).put(record);
      await txDone(tx);
    },
    async listFiles(repoId: string): Promise<FileRecord[]> {
      const tx = db.transaction(FILES_STORE, "readonly");
      return (await idbRequest<FileRecord[]>(tx.objectStore(FILES_STORE).index("repo").getAll(repoId))) || [];
    },
    async deleteFile(repoId: string, path: string): Promise<void> {
      const tx = db.transaction(FILES_STORE, "readwrite");
      tx.objectStore(FILES_STORE).delete(fileKey(repoId, path));
      await txDone(tx);
    },
    async putChunks(records: ChunkRecord[]): Promise<void> {
      if (records.length === 0) return;
      const tx = db.transaction(CHUNKS_STORE, "readwrite");
      const store = tx.objectStore(CHUNKS_STORE);
      for (const record of records) store.put(record);
      await txDone(tx);
    },
    async deleteChunksForFile(repoId: string, path: string): Promise<void> {
      const tx = db.transaction(CHUNKS_STORE, "readwrite");
      const store = tx.objectStore(CHUNKS_STORE);
      const keys = await idbRequest<IDBValidKey[]>(store.index("repoPath").getAllKeys(repoId + KEY_SEP + path));
      for (const key of keys || []) store.delete(key);
      await txDone(tx);
    },
    async listChunks(repoId: string): Promise<ChunkRecord[]> {
      const tx = db.transaction(CHUNKS_STORE, "readonly");
      return (await idbRequest<ChunkRecord[]>(tx.objectStore(CHUNKS_STORE).index("repo").getAll(repoId))) || [];
    },
    async getChunks(repoId: string, ids: string[]): Promise<ChunkRecord[]> {
      const tx = db.transaction(CHUNKS_STORE, "readonly");
      const store = tx.objectStore(CHUNKS_STORE);
      // Every get() is issued SYNCHRONOUSLY, in this task, and only then
      // awaited. Awaiting them one at a time is the classic IndexedDB footgun:
      // a transaction stays alive only while requests are pending or its own
      // events are firing, so an await that lands anywhere else lets it go
      // inactive and the next get() throws TransactionInactiveError. Issuing
      // first and awaiting after cannot hit that window at all.
      const results = ids.map((id: string) => idbRequest<ChunkRecord>(store.get(chunkKey(repoId, id))));
      const records = await Promise.all(results);
      return records.filter((record: ChunkRecord) => !!record);
    },
    async clearAll(): Promise<void> {
      const tx = db.transaction([FILES_STORE, CHUNKS_STORE], "readwrite");
      tx.objectStore(FILES_STORE).clear();
      tx.objectStore(CHUNKS_STORE).clear();
      await txDone(tx);
    },
  };
}

let storePromise: Promise<IndexStore> | null = null;

export function getStore(): Promise<IndexStore> {
  if (storePromise !== null) return storePromise;
  storePromise = (async (): Promise<IndexStore> => {
    try {
      if (typeof indexedDB === "undefined" || !indexedDB) return createMemoryStore();
      return createIdbStore(await openDatabase());
    } catch {
      // A private-mode refusal or a blocked upgrade is not a reason to fail the
      // feature — this session just won't survive a reload.
      return createMemoryStore();
    }
  })();
  return storePromise;
}
