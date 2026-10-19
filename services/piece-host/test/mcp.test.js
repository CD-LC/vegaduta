import assert from 'node:assert/strict';
import { PassThrough } from 'node:stream';
import test from 'node:test';
import { createMcpHandler, serveStdio, SUPPORTED_PROTOCOL_VERSIONS } from '../src/mcp.js';

function makeHandler(overrides = {}) {
  return createMcpHandler({
    serverInfo: { name: 'piece-host', version: '9.9.9' },
    listTools: () => [{ name: 't__a', description: 'A', inputSchema: { type: 'object', properties: {} } }],
    callTool: async (name, args) => ({ content: [{ type: 'text', text: `${name}:${JSON.stringify(args)}` }] }),
    ...overrides,
  });
}

test('initialize negotiates a supported protocol version and advertises tools only', async () => {
  const h = makeHandler();
  const r = await h.handleMessage({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-03-26' } });
  assert.equal(r.id, 1);
  assert.equal(r.result.protocolVersion, '2025-03-26');
  assert.deepEqual(r.result.capabilities, { tools: { listChanged: false } });
  assert.deepEqual(r.result.serverInfo, { name: 'piece-host', version: '9.9.9' });

  const unknown = await h.handleMessage({ jsonrpc: '2.0', id: 2, method: 'initialize', params: { protocolVersion: '1999-01-01' } });
  assert.equal(unknown.result.protocolVersion, SUPPORTED_PROTOCOL_VERSIONS[0]);
});

test('ping, tools/list and tools/call', async () => {
  const h = makeHandler();
  assert.deepEqual((await h.handleMessage({ jsonrpc: '2.0', id: 1, method: 'ping' })).result, {});
  const list = await h.handleMessage({ jsonrpc: '2.0', id: 2, method: 'tools/list' });
  assert.equal(list.result.tools[0].name, 't__a');
  const call = await h.handleMessage({ jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 't__a', arguments: { x: 1 } } });
  assert.equal(call.result.content[0].text, 't__a:{"x":1}');
});

test('notifications and client responses get no reply', async () => {
  const h = makeHandler();
  assert.equal(await h.handleMessage({ jsonrpc: '2.0', method: 'notifications/initialized' }), null);
  assert.equal(await h.handleMessage({ jsonrpc: '2.0', id: 5, result: {} }), null);
});

test('protocol errors: unknown method, bad params, malformed request, dispatcher failure', async () => {
  const h = makeHandler({
    callTool: async () => {
      throw new Error('kaboom with internals');
    },
  });
  assert.equal((await h.handleMessage({ jsonrpc: '2.0', id: 1, method: 'resources/list' })).error.code, -32601);
  assert.equal((await h.handleMessage({ jsonrpc: '2.0', id: 2, method: 'tools/call', params: {} })).error.code, -32602);
  assert.equal((await h.handleMessage({ jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'x', arguments: [] } })).error.code, -32602);
  assert.equal((await h.handleMessage({ id: 4, method: 'ping' })).error.code, -32600);
  const failed = await h.handleMessage({ jsonrpc: '2.0', id: 6, method: 'tools/call', params: { name: 'x' } });
  assert.equal(failed.error.code, -32603);
  assert.ok(!failed.error.message.includes('kaboom')); // internals are not leaked to the client
});

function runStdio(handler, lines) {
  const input = new PassThrough();
  const written = [];
  const output = { write: (s) => written.push(s) };
  const done = serveStdio({ input, output, handler });
  for (const l of lines) input.write(l);
  input.end();
  return done.then(() => written.join('').split('\n').filter(Boolean).map((l) => JSON.parse(l)));
}

test('serveStdio: newline-delimited framing, one JSON object per line, parse errors answered', async () => {
  const h = makeHandler();
  const out = await runStdio(h, [
    '{"jsonrpc":"2.0","id":1,"method":"ping"}\n',
    'this is not json\n',
    '{"jsonrpc":"2.0","method":"notifications/initialized"}\n',
    '\n',
    '{"jsonrpc":"2.0","id":2,"method":"tools/list"}', // no trailing newline: flushed on end
  ]);
  assert.equal(out.length, 3);
  assert.ok(out.some((m) => m.id === 1 && m.result));
  assert.ok(out.some((m) => m.error && m.error.code === -32700 && m.id === null));
  assert.ok(out.some((m) => m.id === 2 && m.result.tools.length === 1));
});

test('serveStdio handles frames split across chunks and waits for in-flight calls before resolving', async () => {
  let release;
  const gate = new Promise((r) => {
    release = r;
  });
  const h = makeHandler({
    callTool: async () => {
      await gate;
      return { content: [{ type: 'text', text: 'late' }] };
    },
  });
  const input = new PassThrough();
  const written = [];
  const done = serveStdio({ input, output: { write: (s) => written.push(s) }, handler: h });
  input.write('{"jsonrpc":"2.0","id":7,"method":"tools/');
  input.write('call","params":{"name":"t__a"}}\n');
  input.end();
  let resolved = false;
  void done.then(() => {
    resolved = true;
  });
  await new Promise((r) => setTimeout(r, 30));
  assert.equal(resolved, false); // input ended, but a call is still running
  release();
  await done;
  const msg = JSON.parse(written.join('').trim());
  assert.equal(msg.id, 7);
  assert.equal(msg.result.content[0].text, 'late');
});

test('serveStdio answers a batch with a batch', async () => {
  const out = await runStdio(makeHandler(), ['[{"jsonrpc":"2.0","id":1,"method":"ping"},{"jsonrpc":"2.0","id":2,"method":"ping"}]\n']);
  assert.equal(out.length, 1);
  assert.equal(Array.isArray(out[0]), true);
  assert.equal(out[0].length, 2);
});
