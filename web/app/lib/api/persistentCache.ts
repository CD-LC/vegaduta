// L2 of the GET cache: an IndexedDB copy of the in-memory SWR map in core.ts.
//
// Why it exists: the L1 map lives in JS memory, so every full page load
// (reload, new tab, opening the app from a bookmark) starts cold and the
// first paint waits on a network round trip per read. This layer survives
// the reload: core.ts seeds L1 from it at session boot, so a returning
// visitor renders last-known data at once and the normal stale-while-
// revalidate refresh corrects it in the background.
//
// Deliberately narrow, because persisted data outlives the session:
//  - ALLOWLIST, not denylist. Only plain tenant list/config reads are stored.
//    Anything that can carry a credential (providers, secrets, channel
//    credentials, tokens), personal data (account, admin users) or a
//    fast-moving state (system status, approvals) is never written to disk.
//  - Scoped by Keycloak user id. A different identity never reads another's
//    rows, and logout / a 401 wipes the whole store.
//  - Short life (PERSIST_MAX_AGE_MS). A row older than that is ignored and
//    deleted, so a day-old list is never painted as if current.
//  - Every call is best-effort: no IndexedDB (SSR, jsdom, private mode),
//    a quota error or a blocked upgrade all degrade to "no L2", never a
//    thrown error into the caller.

const DB_NAME = "vd-api-cache";
const STORE = "gets";
const DB_VERSION = 1;

export const PERSIST_MAX_AGE_MS = 30 * 60_000;

const PERSISTABLE = /^\/api\/(agents|workflows|knowledge\/collections|tenant\/settings|skills|templates|tools\/(scripts|openapi|code)|channels\/contacts|mcp\/registry)(\?.*)?$/;

export function isPersistablePath(path: string): boolean {
  return PERSISTABLE.test(path);
}

export type PersistedGet = {
  path: string;
  fetchedAt: number;
  status: number;
  contentType: string | null;
  body: string;
};

type Row = PersistedGet & { key: string; scope: string };

let dbPromise: Promise<IDBDatabase | null> | null = null;

function openDb(): Promise<IDBDatabase | null> {
  if (dbPromise !== null) return dbPromise;
  dbPromise = new Promise((resolve) => {
    try {
      if (typeof indexedDB === "undefined") return resolve(null);
      const req = indexedDB.open(DB_NAME, DB_VERSION);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains(STORE)) {
          db.createObjectStore(STORE, { keyPath: "key" }).createIndex("scope", "scope");
        }
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => resolve(null);
      req.onblocked = () => resolve(null);
    } catch {
      resolve(null);
    }
  });
  return dbPromise;
}

function done(tx: IDBTransaction): Promise<void> {
  return new Promise((resolve) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => resolve();
    tx.onabort = () => resolve();
  });
}

/** Rows saved for `scope` that are still young enough to show. Expired ones are deleted. */
export async function readPersisted(scope: string): Promise<PersistedGet[]> {
  const db = await openDb();
  if (!db) return [];
  try {
    const tx = db.transaction(STORE, "readwrite");
    const store = tx.objectStore(STORE);
    const rows = await new Promise<Row[]>((resolve) => {
      const req = store.index("scope").getAll(scope);
      req.onsuccess = () => resolve(req.result as Row[]);
      req.onerror = () => resolve([]);
    });
    const cutoff = Date.now() - PERSIST_MAX_AGE_MS;
    const fresh: PersistedGet[] = [];
    for (const r of rows) {
      if (r.fetchedAt >= cutoff) fresh.push(r);
      else store.delete(r.key);
    }
    await done(tx);
    return fresh;
  } catch {
    return [];
  }
}

export async function writePersisted(scope: string, row: PersistedGet): Promise<void> {
  if (!isPersistablePath(row.path)) return;
  const db = await openDb();
  if (!db) return;
  try {
    const tx = db.transaction(STORE, "readwrite");
    tx.objectStore(STORE).put({ ...row, scope, key: `${scope}|${row.path}` } satisfies Row);
    await done(tx);
  } catch {
    // quota / closed connection - L1 still holds the value
  }
}

/** Drop every persisted row (logout, 401, or any write - the same events that flush L1). */
export async function clearPersisted(): Promise<void> {
  const db = await openDb();
  if (!db) return;
  try {
    const tx = db.transaction(STORE, "readwrite");
    tx.objectStore(STORE).clear();
    await done(tx);
  } catch {
    // nothing to do
  }
}
