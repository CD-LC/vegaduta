// Tests for the local-code indexer, group 3: "the IndexedDB store" against a
// small fake IndexedDB. Read the honesty note below before trusting it; see
// indexer.test.ts's header for the three groups.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { KEYWORDS, createEmbedSpy, REPO, createFileAccess, SOURCES, loadIndexer, poisonFetch } from "./indexer.testkit";

// ---------------------------------------------------------------------------
// The IndexedDB store
// ---------------------------------------------------------------------------
//
// HONESTY NOTE, read this before trusting the group below. jsdom has no
// IndexedDB, so these tests run against a fake written here. A fake proves the
// store's CONTRACT (keys, the repoPath index, delete-by-file, Float32Array
// round-tripping) and nothing about Chrome's real implementation — quota
// behaviour, version-change blocking across tabs and the actual structured
// clone are all still unexercised, and the browser path is still the least
// proven code in this module.
//
// It does model one real rule, because that rule is the module's live footgun:
// an IndexedDB transaction is only active during the task that created it and
// during its own event dispatch. `assertActive` below enforces it, and the last
// test in the group proves the enforcement has teeth — so a future refactor
// that slips a non-IDB await into the middle of a store method fails here
// instead of in a browser.

function structuredCloneish<T>(value: T): T {
  // The real thing when the runtime has it (that is the point of the round-trip
  // assertion); a Float32Array-aware deep copy otherwise.
  if (typeof structuredClone === "function") return structuredClone(value);
  if (value instanceof Float32Array) return new Float32Array(value) as unknown as T;
  if (Array.isArray(value)) return value.map(structuredCloneish) as unknown as T;
  if (value && typeof value === "object") {
    const out: any = {};
    for (const key of Object.keys(value as any)) out[key] = structuredCloneish((value as any)[key]);
    return out;
  }
  return value;
}

function createFakeIndexedDB() {
  interface FakeStore {
    keyPath: string;
    records: Map<any, any>;
    indexes: Map<string, string>;
  }
  const stores = new Map<string, FakeStore>();
  let version = 0;

  function makeRequest<T>(run: () => T, tx?: { note(): void; assertActive(): void }): any {
    if (tx) tx.assertActive();
    const request: any = { result: undefined, error: null, onsuccess: null, onerror: null };
    if (tx) tx.note();
    queueMicrotask(() => {
      try {
        request.result = run();
        if (request.onsuccess) request.onsuccess();
      } catch (err) {
        request.error = err;
        if (request.onerror) request.onerror();
      }
    });
    return request;
  }

  function makeTransaction(names: string | string[]): any {
    const wanted = Array.isArray(names) ? names : [names];
    let active = true;
    let outstanding = 0;
    let settled = false;
    const tx: any = { oncomplete: null, onerror: null, onabort: null, error: null };
    const maybeComplete = (): void => {
      if (settled || outstanding > 0) return;
      settled = true;
      // A macrotask, so anything issued later in the same turn still counts as
      // part of this transaction — which is the rule real IndexedDB follows.
      setTimeout(() => {
        active = false;
        if (tx.oncomplete) tx.oncomplete();
      }, 0);
    };
    const control = {
      note(): void {
        outstanding++;
        queueMicrotask(() => {
          outstanding--;
          maybeComplete();
        });
      },
      assertActive(): void {
        if (!active) {
          const err = new Error("TransactionInactiveError: the transaction is no longer active");
          err.name = "TransactionInactiveError";
          throw err;
        }
      },
    };
    maybeComplete();
    tx.objectStore = (name: string): any => {
      control.assertActive();
      if (!wanted.includes(name)) throw new Error(`store ${name} is not in this transaction`);
      const store = stores.get(name);
      if (!store) throw new Error(`no such store: ${name}`);
      const api: any = {
        get: (key: any) => makeRequest(() => structuredCloneish(store.records.get(key)), control),
        put: (value: any) =>
          makeRequest(() => {
            const copy = structuredCloneish(value);
            store.records.set(copy[store.keyPath], copy);
            return copy[store.keyPath];
          }, control),
        delete: (key: any) => makeRequest(() => store.records.delete(key), control),
        clear: () => makeRequest(() => store.records.clear(), control),
        index: (indexName: string) => {
          const field = store.indexes.get(indexName);
          if (!field) throw new Error(`no such index: ${indexName}`);
          const matching = (query: any): any[] =>
            Array.from(store.records.values()).filter((record: any) => record[field] === query);
          return {
            getAll: (query: any) => makeRequest(() => matching(query).map(structuredCloneish), control),
            getAllKeys: (query: any) =>
              makeRequest(() => matching(query).map((record: any) => record[store.keyPath]), control),
          };
        },
      };
      return api;
    };
    return tx;
  }

  const db: any = {
    objectStoreNames: { contains: (name: string) => stores.has(name) },
    createObjectStore(name: string, options: { keyPath: string }) {
      const store: FakeStore = { keyPath: options.keyPath, records: new Map(), indexes: new Map() };
      stores.set(name, store);
      return {
        createIndex(indexName: string, field: string) {
          store.indexes.set(indexName, field);
        },
      };
    },
    deleteObjectStore(name: string) {
      stores.delete(name);
    },
    transaction: (names: string | string[]) => makeTransaction(names),
  };

  return {
    stores,
    transaction: (names: string | string[]) => makeTransaction(names),
    api: {
      open(_name: string, requested: number): any {
        const request: any = {
          result: db,
          error: null,
          onsuccess: null,
          onupgradeneeded: null,
          onerror: null,
          onblocked: null,
        };
        queueMicrotask(() => {
          if (requested > version) {
            version = requested;
            if (request.onupgradeneeded) request.onupgradeneeded();
          }
          if (request.onsuccess) request.onsuccess();
        });
        return request;
      },
    },
  };
}

describe("localcode/indexer — the IndexedDB store", () => {
  beforeEach(() => {
    vi.resetModules();
    localStorage.clear();
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
    poisonFetch();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("round-trips an index through IndexedDB, vectors and all", async () => {
    const fake = createFakeIndexedDB();
    vi.stubGlobal("indexedDB", fake.api);
    const mod = await loadIndexer(createEmbedSpy(), createFileAccess({ ...SOURCES }));

    const final = await mod.buildLocalIndex(REPO, () => {});
    expect(final.state).toBe("ready");
    expect(final.files).toBe(3);
    expect(final.chunks).toBe(3);

    const hits = await mod.searchLocalIndex("where is the beta handler", 2);
    expect(hits[0].path).toBe("src/beta.ts");
    expect(hits[0].text).toContain("beta handler");
    // The vector survived a structured clone as a typed array — not an object
    // with numeric keys, which is what a naive clone turns it into and which
    // scores 0 against everything. Checked by brand rather than instanceof:
    // Node's structuredClone builds the copy in its own realm, so the copy is
    // genuinely a Float32Array but not jsdom's Float32Array. A browser has one
    // realm here and no such distinction.
    const stored = Array.from(fake.stores.get("chunks")!.records.values())[0] as any;
    expect(Object.prototype.toString.call(stored.vector)).toBe("[object Float32Array]");
    expect(stored.vector.length).toBe(KEYWORDS.length);
    expect(stored.model).toBe("test-embedder");
  });

  it("deletes a file's chunks through the repoPath index when the file changes", async () => {
    const fake = createFakeIndexedDB();
    vi.stubGlobal("indexedDB", fake.api);
    const access = createFileAccess({ ...SOURCES });
    const mod = await loadIndexer(createEmbedSpy(), access);
    await mod.buildLocalIndex(REPO, () => {});
    expect(fake.stores.get("chunks")!.records.size).toBe(3);

    // A rewrite that yields a different chunk id: the old row must not survive
    // beside the new one, or search cites lines that no longer exist.
    access.files["src/beta.ts"] = `export function beta() {\n${"  // beta handler\n".repeat(40)}}\n`;
    await mod.buildLocalIndex(REPO, () => {});
    const rows = Array.from(fake.stores.get("chunks")!.records.values()) as any[];
    const betaRows = rows.filter((r: any) => r.path === "src/beta.ts");
    expect(betaRows.length).toBeGreaterThan(1);
    for (const row of betaRows) expect(row.text).toContain("beta handler");

    // ...and a file that left the repo takes its chunks with it.
    delete access.files["src/gamma.ts"];
    await mod.buildLocalIndex(REPO, () => {});
    const after = Array.from(fake.stores.get("chunks")!.records.values()) as any[];
    expect(after.some((r: any) => r.path === "src/gamma.ts")).toBe(false);
  });

  it("survives a reload: restoreLocalIndexStatus reads the stored index back", async () => {
    const fake = createFakeIndexedDB();
    vi.stubGlobal("indexedDB", fake.api);
    const first = await loadIndexer(createEmbedSpy(), createFileAccess({ ...SOURCES }));
    await first.buildLocalIndex(REPO, () => {});

    // A new module instance against the SAME database and the same
    // localStorage: that is what a page reload looks like from here.
    vi.resetModules();
    const second = await loadIndexer(createEmbedSpy(), createFileAccess({ ...SOURCES }));
    const restored = await second.restoreLocalIndexStatus();
    expect(restored.state).toBe("ready");
    expect(restored.files).toBe(3);
    expect(restored.chunks).toBe(3);
  });

  it("the fake enforces transaction lifetime, so the tests above are not vacuous", async () => {
    // Proof that the group has teeth: issue a request, await something that is
    // NOT an IndexedDB event, then issue another one on the same transaction.
    // Real IndexedDB throws TransactionInactiveError here, and so does this.
    const fake = createFakeIndexedDB();
    vi.stubGlobal("indexedDB", fake.api);
    const mod = await loadIndexer(createEmbedSpy(), createFileAccess({ ...SOURCES }));
    await mod.buildLocalIndex(REPO, () => {});

    const tx = fake.transaction("chunks");
    tx.objectStore("chunks");
    await new Promise((resolve) => setTimeout(resolve, 1));
    expect(() => tx.objectStore("chunks")).toThrow(/TransactionInactiveError/);
  });
});
