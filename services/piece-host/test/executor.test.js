import assert from 'node:assert/strict';
import test from 'node:test';
import { createPolicy } from '../src/policy.js';
import { buildRegistry, describeRegistry, toMcpTool } from '../src/registry.js';
import { fakeHarness, SECRET, textOf } from './helpers.js';

test('registry: one tool per allowed action, named <piece>__<action>, denied ones withheld', async () => {
  const { registry, loaded } = await fakeHarness();
  const names = registry.tools.map((t) => t.name).sort();
  assert.ok(names.includes('fake__echo'));
  assert.ok(names.includes('fake__noauth'));
  assert.ok(!names.includes('fake__custom_api_call'));
  assert.equal(registry.denied.get('fake__custom_api_call') !== undefined, true);

  const echo = registry.byName.get('fake__echo');
  assert.equal(echo.description, 'Fake Service - Echo: Repeats text.');
  assert.deepEqual(echo.inputSchema.required, ['text']);
  assert.equal(echo.authRequired, true);
  assert.equal(registry.byName.get('fake__noauth').authRequired, false);

  const mcp = toMcpTool(echo);
  assert.deepEqual(Object.keys(mcp).sort(), ['description', 'inputSchema', 'name']);

  const inventory = describeRegistry(loaded, registry);
  assert.equal(inventory.pieces[0].auth.alternatives[0].env[0].name, 'PIECE_AUTH_FAKE');
  assert.equal(inventory.pieces[0].denied[0].name, 'fake__custom_api_call');
  assert.deepEqual(inventory.pieces[0].tools.find((t) => t.name === 'fake__echo').skippedProps.length, 1);
});

test('runs an action with coerced args, defaults and the context shim', async () => {
  const { executor } = await fakeHarness();
  const res = await executor.callTool('fake__echo', { text: 'ab', times: '2', mode: 'upper', loud: 'true' });
  assert.equal(res.isError, undefined);
  const out = JSON.parse(textOf(res));
  assert.equal(out.out, 'ABAB!');
  assert.equal(out.times, 2);
  assert.equal(out.authType, 'SECRET_TEXT');
  assert.equal(out.step, 'echo');
  assert.match(out.runId, /^piece-host-/);

  const defaulted = JSON.parse(textOf(await executor.callTool('fake__echo', { text: 'x' })));
  assert.equal(defaulted.times, 1);
});

test('invalid arguments come back as an isError result, not an exception', async () => {
  const { executor } = await fakeHarness();
  const res = await executor.callTool('fake__echo', {});
  assert.equal(res.isError, true);
  assert.match(textOf(res), /text is required/);
  const bad = await executor.callTool('fake__echo', { text: 'a', mode: 'sideways' });
  assert.match(textOf(bad), /mode must be one of/);
  const notObject = await executor.callTool('fake__echo', ['a']);
  assert.equal(notObject.isError, true);
});

test('missing credentials are reported by variable name and the action never runs', async () => {
  const { executor } = await fakeHarness({ env: {} });
  const res = await executor.callTool('fake__echo', { text: 'x' });
  assert.equal(res.isError, true);
  assert.match(textOf(res), /PIECE_AUTH_FAKE/);
});

test('actions that do not require auth run without credentials', async () => {
  const { executor } = await fakeHarness({ env: {} });
  const res = await executor.callTool('fake__noauth', {});
  assert.equal(res.isError, undefined);
  assert.equal(JSON.parse(textOf(res)).auth, 'none');
});

test('credential values are redacted from results and from error messages', async () => {
  const { executor } = await fakeHarness();
  const leaked = textOf(await executor.callTool('fake__leak', {}));
  assert.ok(!leaked.includes(SECRET));
  assert.match(leaked, /\[REDACTED\]/);

  const failed = await executor.callTool('fake__boom', {});
  assert.equal(failed.isError, true);
  assert.ok(!textOf(failed).includes(SECRET));
  assert.match(textOf(failed), /upstream rejected key \[REDACTED\]/);
});

test('results above the cap are truncated with a marker', async () => {
  const { executor } = await fakeHarness({ config: { maxResultBytes: 1000 } });
  const res = await executor.callTool('fake__big', { size: 5000 });
  const text = textOf(res);
  assert.match(text, /truncated: showing/);
  assert.ok(Buffer.byteLength(text, 'utf8') < 1400);
});

test('a call that exceeds the timeout fails with a clear message', async () => {
  const { executor } = await fakeHarness({ config: { callTimeoutMs: 40 } });
  const res = await executor.callTool('fake__sleep', { ms: 400 });
  assert.equal(res.isError, true);
  assert.match(textOf(res), /timed out after 40 ms/);
});

test('deny-listed and unknown tools are refused with distinct messages', async () => {
  const { executor } = await fakeHarness();
  const denied = await executor.callTool('fake__custom_api_call', { url: 'https://example.com' });
  assert.equal(denied.isError, true);
  assert.match(textOf(denied), /disabled by this piece host's policy/);
  const unknown = await executor.callTool('fake__nope', {});
  assert.match(textOf(unknown), /Unknown tool/);
});

test('an operator deny pattern removes an otherwise normal tool', async () => {
  const { registry, executor } = await fakeHarness({ policy: createPolicy({ deny: ['fake__big'] }) });
  assert.equal(registry.byName.has('fake__big'), false);
  assert.match(textOf(await executor.callTool('fake__big', { size: 1 })), /disabled/);
});

test('store and file shims work; file output is disabled unless configured', async () => {
  const { executor } = await fakeHarness();
  const stored = JSON.parse(textOf(await executor.callTool('fake__store_roundtrip', { value: 'v' })));
  assert.deepEqual(stored, { read: 'v', missing: null });

  const file = JSON.parse(
    textOf(await executor.callTool('fake__file_len', { file: { filename: 'x.txt', base64: Buffer.from('hello').toString('base64') } })),
  );
  assert.deepEqual(file, { filename: 'x.txt', extension: 'txt', bytes: 5 });
});

test('concurrency cap rejects the overflow call instead of queueing unboundedly', async () => {
  const { executor } = await fakeHarness({ config: { maxConcurrency: 1 } });
  const first = executor.callTool('fake__sleep', { ms: 80 });
  const second = await executor.callTool('fake__sleep', { ms: 1 });
  assert.equal(second.isError, true);
  assert.match(textOf(second), /retry shortly/);
  assert.equal((await first).isError, undefined);
});

test('logs record tool, outcome and duration only - never arguments, results or credentials', async () => {
  const { executor, logs } = await fakeHarness();
  await executor.callTool('fake__echo', { text: 'PRIVATE-ARGUMENT' });
  await executor.callTool('fake__leak', {});
  const dump = JSON.stringify(logs);
  assert.ok(!dump.includes('PRIVATE-ARGUMENT'));
  assert.ok(!dump.includes(SECRET));
  assert.ok(logs.some((l) => l.msg === 'tool_call' && l.tool === 'fake__echo' && l.ok === true));
});

test('registry in bare-name mode: action-only tool names, denied tracked per piece, inventory still lists them', async () => {
  const { loaded } = await fakeHarness();
  const registry = buildRegistry(loaded, createPolicy(), { bareNames: true });
  const names = registry.tools.map((t) => t.name);
  assert.ok(names.includes('echo'));
  assert.ok(!names.includes('fake__echo'));
  assert.ok(!names.includes('custom_api_call'));
  assert.equal(registry.denied.has('custom_api_call'), true);
  assert.equal(registry.deniedPiece.get('custom_api_call'), 'fake');
  const inventory = describeRegistry(loaded, registry);
  assert.equal(inventory.pieces[0].denied[0].name, 'custom_api_call');
  // The deny policy still keys on <piece>__<action>, independent of the exposed tool name.
  const strict = buildRegistry(loaded, createPolicy({ deny: ['fake__big'] }), { bareNames: true });
  assert.equal(strict.byName.has('big'), false);
  for (const t of registry.tools) assert.ok(`mcp__piece-fake__${t.name}`.length <= 64, t.name);
});
