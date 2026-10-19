// Test kit for fsAccess*.test.ts: an in-memory filesystem behind fake
// File System Access handles, a fake directory picker and a hand-rolled
// indexedDB, plus the fresh-import helper. Not a test file itself; see
// fsAccess.test.ts's header for why the fakes are shaped this way.

import { vi } from "vitest";

import type { LocalRepoHandle } from "./types";

// --- in-memory filesystem ---------------------------------------------------

export interface FakeFileSpec {
  text?: string;
  /** Raw bytes, for the binary-sniff cases. */
  bytes?: number[];
  /** Override the reported size without allocating it — the >512 KB case would
   *  otherwise mean building a real half-megabyte string per test. */
  size?: number;
  /** Reading the first bytes fails: the file was deleted between the walk
   *  finding it and the sniff reading it, or the disk gave up on it. */
  headThrows?: boolean;
}

export interface FakeFileNode {
  kind: "file";
  spec: FakeFileSpec;
}

export interface FakeDirNode {
  kind: "dir";
  children: Record<string, FakeNode>;
}

export type FakeNode = FakeFileNode | FakeDirNode;

export function f(spec: FakeFileSpec = { text: "const a = 1;\n" }): FakeFileNode {
  return { kind: "file", spec };
}

export function d(children: Record<string, FakeNode>): FakeDirNode {
  return { kind: "dir", children };
}

export interface PermissionCall {
  method: "query" | "request";
  mode: string | undefined;
}

export interface FakeOptions {
  /** Omitted entirely => a handle with NO permission methods, i.e. a browser
   *  that is not Chromium's implementation. */
  permission?: { query?: string; request?: string };
  /** entries() throws synchronously — a folder that vanished under us. */
  throwOnEntries?: boolean;
  /** queryPermission() throws — what a handle restored from IndexedDB does once
   *  the folder behind it is gone. Requires `permission` to be set, since the
   *  method has to exist before it can throw. */
  throwOnQueryPermission?: boolean;
}

export function makeFakeFile(name: string, spec: FakeFileSpec): File {
  const bytes = spec.bytes ? Uint8Array.from(spec.bytes) : new TextEncoder().encode(spec.text || "");
  const size = typeof spec.size === "number" ? spec.size : bytes.length;
  const fake = {
    name,
    size,
    slice(start: number, end: number) {
      const part = bytes.slice(start, end);
      return {
        async arrayBuffer(): Promise<ArrayBuffer> {
          if (spec.headThrows) throw new Error("NotReadableError");
          return part.buffer as ArrayBuffer;
        },
      };
    },
    async text(): Promise<string> {
      return spec.text !== undefined ? spec.text : new TextDecoder().decode(bytes);
    },
  };
  return fake as unknown as File;
}

export function makeFileHandle(name: string, node: FakeFileNode): FileSystemFileHandle {
  const handle = {
    kind: "file",
    name,
    async getFile(): Promise<File> {
      return makeFakeFile(name, node.spec);
    },
    async createWritable() {
      return {
        async write(data: string): Promise<void> {
          node.spec.text = data;
          node.spec.bytes = undefined;
          node.spec.size = undefined;
        },
        async close(): Promise<void> {
          // no-op
        },
      };
    },
  };
  return handle as unknown as FileSystemFileHandle;
}

export function makeDirHandle(
  name: string,
  node: FakeDirNode,
  options: FakeOptions,
  calls: PermissionCall[]
): FileSystemDirectoryHandle {
  async function* iterate(): AsyncGenerator<[string, unknown]> {
    for (const [childName, child] of Object.entries(node.children)) {
      yield [
        childName,
        child.kind === "dir" ? makeDirHandle(childName, child, options, calls) : makeFileHandle(childName, child),
      ];
    }
  }

  const handle: Record<string, unknown> = {
    kind: "directory",
    name,
    entries() {
      if (options.throwOnEntries) throw new Error("NotFoundError");
      return iterate();
    },
    async getDirectoryHandle(childName: string, opts?: { create?: boolean }) {
      const child = node.children[childName];
      if (child && child.kind === "dir") return makeDirHandle(childName, child, options, calls);
      if (opts && opts.create === true) {
        const created = d({});
        node.children[childName] = created;
        return makeDirHandle(childName, created, options, calls);
      }
      throw new Error("NotFoundError");
    },
    async getFileHandle(childName: string, opts?: { create?: boolean }) {
      const child = node.children[childName];
      if (child && child.kind === "file") return makeFileHandle(childName, child);
      if (opts && opts.create === true) {
        const created = f({ text: "" });
        node.children[childName] = created;
        return makeFileHandle(childName, created);
      }
      throw new Error("NotFoundError");
    },
  };

  if (options.permission) {
    handle.queryPermission = async (descriptor?: { mode?: string }) => {
      calls.push({ method: "query", mode: descriptor ? descriptor.mode : undefined });
      if (options.throwOnQueryPermission) throw new Error("NotFoundError");
      return options.permission.query;
    };
    handle.requestPermission = async (descriptor?: { mode?: string }) => {
      calls.push({ method: "request", mode: descriptor ? descriptor.mode : undefined });
      return options.permission.request;
    };
  }

  return handle as unknown as FileSystemDirectoryHandle;
}

export function makeRepo(
  children: Record<string, FakeNode>,
  options: FakeOptions = {}
): { repo: LocalRepoHandle; calls: PermissionCall[]; root: FakeDirNode } {
  const calls: PermissionCall[] = [];
  const root = d(children);
  const dir = makeDirHandle("my-project", root, options, calls);
  return { repo: { id: "repo-1", name: "my-project", dir }, calls, root };
}

// --- picker installation (mirrors installModelContext in webmcp.test.ts) ----

export function installDirectoryPicker(impl: (options?: unknown) => Promise<unknown>): { options: unknown[] } {
  const options: unknown[] = [];
  Object.defineProperty(window, "showDirectoryPicker", {
    configurable: true,
    writable: true,
    value: (opts?: unknown) => {
      options.push(opts);
      return impl(opts);
    },
  });
  return { options };
}

export function removeDirectoryPicker(): void {
  // The SSR case stubs `window` away entirely, so this has to tolerate there
  // being no window to clean up.
  if (typeof window === "undefined" || !window) return;
  Reflect.deleteProperty(window as unknown as Record<string, unknown>, "showDirectoryPicker");
}

// --- a hand-rolled indexedDB ------------------------------------------------
//
// Enough of IndexedDB's event plumbing to run the persistence paths, and no
// more. It is NOT conformant, and two departures are worth naming so a passing
// test is not read as saying more than it does:
//
//  * real IndexedDB structured-clones the stored value; this keeps the same
//    object by reference. That is deliberate, not laziness — Chromium's clone
//    of a directory handle comes back as a working handle with live
//    queryPermission/entries methods, so storing by reference is the closer
//    model. What it cannot catch is a value that fails to clone at all.
//  * callbacks fire on microtasks, not real event-loop turns. The double
//    queueMicrotask below is what keeps a request's onsuccess ahead of its
//    transaction's oncomplete, which is the ordering the module relies on.

export type IdbHandler = (() => void) | null;

export interface FakeIdbOptions {
  /** open() reports failure — a private window, or storage denied for the origin. */
  failOpen?: boolean;
  /** Every readwrite transaction aborts — quota exceeded, or a closing db. */
  failWrites?: boolean;
}

export function installFakeIndexedDb(options: FakeIdbOptions = {}): { stores: Map<string, Map<string, unknown>> } {
  const stores = new Map<string, Map<string, unknown>>();
  let upgraded = false;

  function objectStore(name: string) {
    const store = stores.get(name);
    return {
      // failWrites models an aborted transaction, which rolls back — so the
      // mutation must not land, not merely be reported as failed.
      put(value: unknown, key: string) {
        if (options.failWrites) return;
        store.set(key, value);
      },
      delete(key: string) {
        if (options.failWrites) return;
        store.delete(key);
      },
      get(key: string) {
        const request: { result: unknown; onsuccess: IdbHandler; onerror: IdbHandler } = {
          result: undefined,
          onsuccess: null,
          onerror: null,
        };
        queueMicrotask(() => {
          request.result = store.get(key);
          if (request.onsuccess) request.onsuccess();
        });
        return request;
      },
    };
  }

  const db = {
    objectStoreNames: { contains: (name: string) => stores.has(name) },
    createObjectStore(name: string) {
      stores.set(name, new Map());
      upgraded = true;
      return objectStore(name);
    },
    transaction(name: string, mode?: string) {
      if (!stores.has(name)) throw new Error("NotFoundError: no such object store");
      const tx: {
        objectStore: (n: string) => unknown;
        oncomplete: IdbHandler;
        onerror: IdbHandler;
        onabort: IdbHandler;
      } = {
        objectStore,
        oncomplete: null,
        onerror: null,
        onabort: null,
      };
      const aborts = options.failWrites === true && mode === "readwrite";
      queueMicrotask(() =>
        queueMicrotask(() => {
          if (aborts) {
            if (tx.onabort) tx.onabort();
          } else if (tx.oncomplete) {
            tx.oncomplete();
          }
        })
      );
      return tx;
    },
    close() {
      // The module closes after every operation; reopening returns this same db,
      // which is the point — the data has to survive the close.
    },
  };

  vi.stubGlobal("indexedDB", {
    open() {
      const request: {
        result: unknown;
        onsuccess: IdbHandler;
        onerror: IdbHandler;
        onupgradeneeded: IdbHandler;
        onblocked: IdbHandler;
      } = { result: db, onsuccess: null, onerror: null, onupgradeneeded: null, onblocked: null };
      queueMicrotask(() => {
        if (options.failOpen) {
          if (request.onerror) request.onerror();
          return;
        }
        // Only the first open upgrades, exactly as a same-version reopen does.
        if (!upgraded && request.onupgradeneeded) request.onupgradeneeded();
        if (request.onsuccess) request.onsuccess();
      });
      return request;
    },
  });

  return { stores };
}

export async function importFsAccess() {
  return import("./fsAccess");
}
