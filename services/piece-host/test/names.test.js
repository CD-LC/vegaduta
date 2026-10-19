import assert from 'node:assert/strict';
import test from 'node:test';
import {
  CORE_NAMESPACE_PREFIX,
  envToken,
  isValidPieceKey,
  packageForPieceKey,
  pieceKeyForPackage,
  TOOL_NAME_MAX,
  toolName,
  toolNameBudget,
} from '../src/names.js';

test('piece key <-> package name', () => {
  assert.equal(pieceKeyForPackage('@activepieces/piece-google-sheets'), 'google-sheets');
  assert.equal(packageForPieceKey('slack'), '@activepieces/piece-slack');
  assert.equal(pieceKeyForPackage('@evil/piece-slack'), null);
  assert.equal(pieceKeyForPackage('@activepieces/pieces-framework'), null);
  assert.equal(pieceKeyForPackage('@activepieces/piece-../x'), null);
});

test('isValidPieceKey rejects anything that could escape module resolution or env naming', () => {
  for (const ok of ['slack', 'google-sheets', 'zoho-crm', 'a1']) assert.equal(isValidPieceKey(ok), true, ok);
  for (const bad of ['', 'Slack', '../x', 'a/b', '-a', 'a b', 'a_b', 'x'.repeat(70), null, undefined, 5]) {
    assert.equal(isValidPieceKey(bad), false, String(bad));
  }
});

test('envToken', () => {
  assert.equal(envToken('google-sheets'), 'GOOGLE_SHEETS');
  assert.equal(envToken('instanceUrl'), 'INSTANCE_URL');
  assert.equal(envToken('api_key'), 'API_KEY');
  assert.equal(envToken('---'), 'X');
});

test('toolName is <piece>__<action>, sanitised', () => {
  assert.equal(toolName('google-sheets', 'insert_row'), 'google-sheets__insert_row');
  assert.equal(toolName('p', 'foo bar.baz'), 'p__foo_bar_baz');
});

test('toolName caps length and stays unique', () => {
  const taken = new Set();
  const long = 'a'.repeat(80);
  const first = toolName('p', long, taken);
  assert.ok(first.length <= TOOL_NAME_MAX);
  taken.add(first);
  const second = toolName('p', long, taken);
  assert.ok(second.length <= TOOL_NAME_MAX);
  assert.notEqual(first, second);
  assert.match(first, /^[A-Za-z0-9_-]+$/);
  // Deterministic.
  assert.equal(toolName('p', long, new Set()), first);
});

test('toolName de-duplicates sanitised collisions', () => {
  const taken = new Set();
  const a = toolName('p', 'a.b', taken);
  taken.add(a);
  const b = toolName('p', 'a b', taken);
  assert.notEqual(a, b);
});

test('toolNameBudget leaves room for the mcp__piece-<key>__ prefix core adds', () => {
  assert.equal(toolNameBudget('slack'), TOOL_NAME_MAX - (CORE_NAMESPACE_PREFIX.length + 'slack'.length + 2));
  assert.equal(toolNameBudget('microsoft-onedrive'), 33);
  // Never below the floor, even for absurd keys; an explicit override wins when in range.
  assert.equal(toolNameBudget('x'.repeat(60)), 24);
  assert.equal(toolNameBudget('slack', 40), 40);
  assert.equal(toolNameBudget('slack', 3), toolNameBudget('slack'));
  assert.equal(toolNameBudget('slack', 500), toolNameBudget('slack'));
});

test('the FULL namespaced name (mcp__piece-<key>__<tool>) stays within 64 chars for long actions', () => {
  for (const key of ['slack', 'google-sheets', 'microsoft-onedrive', 'telegram-bot']) {
    for (const bare of [true, false]) {
      const name = toolName(key, `a_very_long_action_name_${'x'.repeat(80)}`, new Set(), { bare });
      const full = `${CORE_NAMESPACE_PREFIX}${key}__${name}`;
      assert.ok(full.length <= TOOL_NAME_MAX, `${full} (${full.length})`);
      assert.match(name, /^[A-Za-z0-9_-]+$/);
    }
  }
});

test('bare names drop the piece prefix and still de-duplicate', () => {
  const taken = new Set();
  const a = toolName('slack', 'send message', taken, { bare: true });
  assert.equal(a, 'send_message');
  taken.add(a);
  const b = toolName('slack', 'send.message', taken, { bare: true });
  assert.notEqual(a, b);
});
