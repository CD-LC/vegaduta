// The "context shim": the object handed to an action's run(context).
//
// Shape follows ActionContext in @activepieces/pieces-framework (verified
// 2026-10-04: https://unpkg.com/@activepieces/pieces-framework/src/lib/context/index.d.ts).
// Only what an action needs to run standalone is real: auth, propsValue, an
// in-memory store, a file sink (opt-in) and the project/step identity. Flow
// engine facilities (waitpoints/pause, resume URLs, agent tools, flow listing,
// stored connections, tags, webhooks) do not exist in a tool-call setting and
// either no-op harmlessly or throw a clear error.

import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

const MAX_FILE_BYTES = 10 * 1024 * 1024;

/** Per-process, in-memory Store (put/get/delete). Keys are scoped; nothing is persisted. */
export function createStore() {
  const map = new Map();
  const keyOf = (key, scope) => `${scope === undefined ? '' : String(scope)}\u0000${key}`;
  return {
    async put(key, value, scope) {
      map.set(keyOf(key, scope), value);
      return value;
    },
    async get(key, scope) {
      const k = keyOf(key, scope);
      return map.has(k) ? map.get(k) : null;
    },
    async delete(key, scope) {
      map.delete(keyOf(key, scope));
    },
  };
}

function unsupported(what) {
  return () => {
    throw new Error(`${what} is not available in piece-host (it only exists inside the Activepieces flow engine)`);
  };
}

/**
 * FilesService: write({ fileName, data }) -> location string. Disabled unless
 * PIECE_HOST_FILES_DIR points at a writable directory (the image is
 * read-only-fs friendly, so by default nothing is ever written).
 */
export function createFilesService(filesDir) {
  return {
    async write({ fileName, data }) {
      if (!filesDir) {
        throw new Error('this action wants to write a file, but file output is disabled (set PIECE_HOST_FILES_DIR to enable it)');
      }
      const buf = Buffer.isBuffer(data) ? data : Buffer.from(data);
      if (buf.length > MAX_FILE_BYTES) throw new Error(`file too large (${buf.length} bytes, max ${MAX_FILE_BYTES})`);
      const safeName = path.basename(String(fileName || 'file')).replace(/[^A-Za-z0-9._-]/g, '_') || 'file';
      await mkdir(filesDir, { recursive: true });
      const target = path.join(filesDir, `${Date.now()}-${safeName}`);
      await writeFile(target, buf);
      return target;
    },
  };
}

export function buildActionContext({ actionName, auth, propsValue, store, files, runId }) {
  const id = runId || `piece-host-${Date.now()}`;
  return {
    executionType: 'BEGIN',
    auth,
    propsValue,
    store,
    files,
    project: { id: 'piece-host', externalId: async () => undefined },
    step: { name: actionName },
    flows: {
      list: async () => ({ data: [], next: null, previous: null }),
      current: { id: 'piece-host', version: { id: 'piece-host' } },
    },
    connections: { get: async () => null },
    tags: { add: async () => {} },
    server: { apiUrl: '', publicUrl: '', token: '' },
    output: { update: async () => {} },
    agent: { tools: async () => unsupported('agent tools')() },
    run: {
      id,
      stop: () => {},
      respond: () => {},
      createWaitpoint: async () => unsupported('waitpoints (pause/resume)')(),
      waitForWaitpoint: unsupported('waitpoints (pause/resume)'),
    },
  };
}
