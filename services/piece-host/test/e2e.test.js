// Spawns the real `node server.js` against the fixture piece and speaks MCP to it over stdio,
// exactly the way mcp-stdio-runner would: a child with a minimal env and nothing else.

import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { installFixture, SECRET } from './helpers.js';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SERVER_JS = path.join(HERE, '..', 'server.js');

function runHost(args, { env = {}, stdin = [] } = {}) {
  const fx = installFixture();
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [SERVER_JS, ...args], {
      env: {
        PATH: process.env.PATH,
        SystemRoot: process.env.SystemRoot, // Windows needs it to start node
        PIECE_HOST_ALLOWLIST: fx.allowlistPath,
        PIECE_HOST_PIECES_DIR: fx.piecesDir,
        ...env,
      },
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (c) => {
      stdout += c;
    });
    child.stderr.on('data', (c) => {
      stderr += c;
    });
    child.on('error', reject);
    child.on('close', (code) => resolve({ code, stdout, stderr }));
    for (const line of stdin) child.stdin.write(`${JSON.stringify(line)}\n`);
    child.stdin.end();
  });
}

const rpc = (id, method, params) => ({ jsonrpc: '2.0', id, method, params });

test('serves MCP over stdio: initialize, list, call; stdout carries only protocol JSON', { timeout: 30000 }, async () => {
  const { code, stdout, stderr } = await runHost(['--pieces', 'fake'], {
    env: { PIECE_AUTH_FAKE: SECRET },
    stdin: [
      rpc(1, 'initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 't', version: '0' } }),
      { jsonrpc: '2.0', method: 'notifications/initialized' },
      rpc(2, 'tools/list'),
      rpc(3, 'tools/call', { name: 'echo', arguments: { text: 'hi', times: 2 } }),
      rpc(4, 'tools/call', { name: 'leak', arguments: {} }),
      rpc(5, 'tools/call', { name: 'chatty', arguments: {} }),
      rpc(6, 'tools/call', { name: 'custom_api_call', arguments: { url: 'https://example.com' } }),
    ],
  });
  assert.equal(code, 0, stderr);

  const lines = stdout.split('\n').filter(Boolean);
  const msgs = lines.map((l) => JSON.parse(l)); // throws if anything but JSON reached stdout (e.g. the piece's console.log)
  const byId = new Map(msgs.map((m) => [m.id, m]));

  assert.equal(byId.get(1).result.serverInfo.name, 'piece-host');
  const names = byId.get(2).result.tools.map((t) => t.name);
  // One piece served -> bare action names (core adds the mcp__piece-fake__ prefix itself).
  assert.ok(names.includes('echo'));
  assert.ok(!names.includes('fake__echo'));
  assert.ok(!names.includes('custom_api_call'));
  assert.equal(JSON.parse(byId.get(3).result.content[0].text).out, 'hihi');
  assert.ok(!byId.get(4).result.content[0].text.includes(SECRET));
  assert.equal(byId.get(5).result.content[0].text, 'ok');
  assert.equal(byId.get(6).result.isError, true);

  // The noisy piece's console.log went to stderr; the credential appears nowhere in either stream.
  assert.match(stderr, /hello from a noisy piece/);
  assert.ok(!stdout.includes(SECRET));
  assert.ok(!stderr.includes(SECRET));
});

test('--describe prints the credential/tool inventory and exits 0', { timeout: 30000 }, async () => {
  const { code, stdout } = await runHost(['--describe', '--strict']);
  assert.equal(code, 0);
  const inv = JSON.parse(stdout);
  assert.equal(inv.pieces[0].key, 'fake');
  assert.equal(inv.pieces[0].auth.alternatives[0].env[0].name, 'PIECE_AUTH_FAKE');
  assert.ok(inv.host.deny.includes('*__custom_api_call'));
  assert.equal(inv.pieces[0].denied[0].name, 'custom_api_call');
});

test('refuses a piece that is not on the allow-list and exits non-zero', { timeout: 30000 }, async () => {
  const { code, stderr } = await runHost(['--pieces', 'not-installed']);
  assert.equal(code, 1);
  assert.match(stderr, /allow-list/);
  assert.match(stderr, /nothing to serve/);
});

test('--strict turns a partial load into a failure', { timeout: 30000 }, async () => {
  const { code } = await runHost(['--pieces', 'fake,ghost', '--describe', '--strict']);
  assert.equal(code, 1);
});

test('unknown arguments are rejected (exit 2) rather than silently ignored', { timeout: 30000 }, async () => {
  const { code, stderr } = await runHost(['--pieces', 'fake', '--bogus']);
  assert.equal(code, 2);
  assert.match(stderr, /unknown argument/);
});
