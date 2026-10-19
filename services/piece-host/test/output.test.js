import assert from 'node:assert/strict';
import test from 'node:test';
import { createRedactor, stringifyResult, truncateText } from '../src/output.js';

test('redactor replaces every occurrence, longest secret first', () => {
  const redact = createRedactor(['abc123456', 'abc123456789']);
  assert.equal(redact('k=abc123456789 and abc123456'), 'k=[REDACTED] and [REDACTED]');
  assert.equal(createRedactor([])('untouched'), 'untouched');
  assert.equal(createRedactor(['', undefined, 5])('untouched'), 'untouched');
});

test('stringifyResult: strings as-is, objects pretty JSON, nothing -> explicit marker', () => {
  assert.equal(stringifyResult('plain'), 'plain');
  assert.equal(stringifyResult({ a: 1 }), '{\n  "a": 1\n}');
  assert.match(stringifyResult(undefined), /no data/);
  assert.match(stringifyResult(null), /no data/);
  assert.equal(stringifyResult(5), '5');
});

test('stringifyResult survives circular refs, BigInt, Buffers and Errors', () => {
  const a = { name: 'a' };
  a.self = a;
  assert.match(stringifyResult(a), /\[Circular\]/);
  assert.match(stringifyResult({ n: 10n }), /"10"/);
  assert.match(stringifyResult({ b: Buffer.from('abc') }), /binary 3 bytes omitted/);
  const e = stringifyResult({ err: new Error('nope') });
  assert.match(e, /"message": "nope"/);
});

test('stringifyResult does not mistake a repeated (shared) object for a cycle', () => {
  const shared = { v: 1 };
  const text = stringifyResult({ x: shared, y: shared });
  assert.ok(!text.includes('[Circular]'));
  assert.deepEqual(JSON.parse(text), { x: { v: 1 }, y: { v: 1 } });
});

test('truncateText leaves small text alone and caps big text with a marker', () => {
  assert.deepEqual(truncateText('hello', 100), { text: 'hello', truncated: false, bytes: 5 });
  const r = truncateText('x'.repeat(1000), 100);
  assert.equal(r.truncated, true);
  assert.equal(r.bytes, 1000);
  assert.ok(r.text.startsWith('x'.repeat(100)));
  assert.match(r.text, /truncated: showing 100 of 1000 bytes/);
});

test('truncateText never leaves a split multi-byte character', () => {
  const r = truncateText('€'.repeat(10), 10); // euro sign is 3 bytes
  assert.equal(r.truncated, true);
  assert.ok(!r.text.includes('�'));
  assert.ok(r.text.startsWith('€€€'));
});
