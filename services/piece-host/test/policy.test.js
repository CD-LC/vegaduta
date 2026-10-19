import assert from 'node:assert/strict';
import test from 'node:test';
import { createPolicy, DEFAULT_DENY, parsePatternList, policyFromEnv } from '../src/policy.js';

test('custom_api_call is denied by default for every piece', () => {
  const p = createPolicy();
  assert.deepEqual(DEFAULT_DENY, ['*__custom_api_call']);
  assert.equal(p.check('slack', 'custom_api_call').allowed, false);
  assert.equal(p.check('google-sheets', 'CUSTOM_API_CALL').allowed, false); // case-insensitive
  assert.equal(p.check('slack', 'send_message').allowed, true);
  assert.match(p.check('slack', 'custom_api_call').reason, /deny pattern/);
});

test('operator deny patterns use * globs and are anchored', () => {
  const p = createPolicy({ deny: ['slack__delete_*', 'stripe__refund'] });
  assert.equal(p.check('slack', 'delete_message').allowed, false);
  assert.equal(p.check('slack', 'send_delete_notice').allowed, true);
  assert.equal(p.check('stripe', 'refund').allowed, false);
  assert.equal(p.check('stripe', 'refund_all').allowed, true);
});

test('regex metacharacters in patterns are literal', () => {
  const p = createPolicy({ deny: ['a.b__x(1)'], useDefaults: false });
  assert.equal(p.check('a.b', 'x(1)').allowed, false);
  assert.equal(p.check('aXb', 'x(1)').allowed, true);
});

test('allow list restricts to matching actions; deny still wins', () => {
  const p = createPolicy({ allow: ['slack__send_*', 'slack__custom_api_call'] });
  assert.equal(p.check('slack', 'send_message').allowed, true);
  assert.equal(p.check('slack', 'list_channels').allowed, false);
  assert.match(p.check('slack', 'list_channels').reason, /allow list/);
  assert.equal(p.check('slack', 'custom_api_call').allowed, false); // default deny beats allow
});

test('defaults can be switched off', () => {
  const p = createPolicy({ useDefaults: false });
  assert.equal(p.check('slack', 'custom_api_call').allowed, true);
});

test('parsePatternList and policyFromEnv', () => {
  assert.deepEqual(parsePatternList('a, b  c,,d'), ['a', 'b', 'c', 'd']);
  assert.deepEqual(parsePatternList(undefined), []);
  const p = policyFromEnv({ PIECE_HOST_DENY: 'x__y', PIECE_HOST_DENY_DEFAULTS: 'off' });
  assert.equal(p.check('x', 'y').allowed, false);
  assert.equal(p.check('s', 'custom_api_call').allowed, true);
  const q = policyFromEnv({});
  assert.equal(q.check('s', 'custom_api_call').allowed, false);
});
