// Shared test helpers (not a *.test.js file, so `node --test "test/*.test.js"` does not run it).

import { cpSync, mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createExecutor } from '../src/executor.js';
import { createImporter, loadPieces, parseAllowlist } from '../src/loader.js';
import { createPolicy } from '../src/policy.js';
import { buildRegistry } from '../src/registry.js';

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const FAKE_PKG_DIR = path.join(HERE, 'fixtures', 'fake-piece-pkg');
export const SECRET = 'sk_test_SECRET_VALUE_12345';

/** Copies the fixture into <tmp>/node_modules/@activepieces/piece-fake (node_modules is git-ignored, so it is built at test time). */
export function installFixture() {
  const dir = mkdtempSync(path.join(tmpdir(), 'piece-host-test-'));
  const dest = path.join(dir, 'node_modules', '@activepieces', 'piece-fake');
  mkdirSync(path.dirname(dest), { recursive: true });
  cpSync(FAKE_PKG_DIR, dest, { recursive: true });
  const allowlistPath = path.join(dir, 'pieces.txt');
  writeFileSync(allowlistPath, '# test allow-list\n@activepieces/piece-fake@1.0.0\n');
  return { dir, piecesDir: dir, allowlistPath };
}

export async function loadFake() {
  const fx = installFixture();
  const allowlist = parseAllowlist('@activepieces/piece-fake@1.0.0');
  const result = await loadPieces({ allowlist, requested: ['fake'], importer: createImporter(fx.piecesDir) });
  return { ...fx, ...result, allowlist };
}

/** A ready registry + executor over the fake piece. */
export async function fakeHarness({ policy = createPolicy(), env = { PIECE_AUTH_FAKE: SECRET }, config = {} } = {}) {
  const loadedResult = await loadFake();
  if (loadedResult.errors.length > 0) throw new Error(`fixture failed to load: ${JSON.stringify(loadedResult.errors)}`);
  const registry = buildRegistry(loadedResult.loaded, policy);
  const logs = [];
  const executor = createExecutor({ registry, env, config, log: (level, msg, fields) => logs.push({ level, msg, ...fields }) });
  return { ...loadedResult, registry, executor, logs };
}

export function textOf(result) {
  return result.content.map((c) => c.text).join('');
}
