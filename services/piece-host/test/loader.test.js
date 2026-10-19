import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { findPiece, loadPieces, parseAllowlist, readAllowlist } from '../src/loader.js';
import { loadFake } from './helpers.js';

const HERE = path.dirname(fileURLToPath(import.meta.url));

test('parseAllowlist: comments, blank lines, pinned piece lines', () => {
  const r = parseAllowlist('# header\n\n@activepieces/piece-slack@0.21.0  # chat\n@activepieces/piece-google-sheets@0.17.0\n');
  assert.deepEqual(r.errors, []);
  assert.deepEqual(r.pieces, [
    { key: 'slack', packageName: '@activepieces/piece-slack', version: '0.21.0' },
    { key: 'google-sheets', packageName: '@activepieces/piece-google-sheets', version: '0.17.0' },
  ]);
});

test('parseAllowlist rejects ranges, other scopes, bare names and duplicates', () => {
  const r = parseAllowlist(
    [
      '@activepieces/piece-slack@^0.21.0',
      '@activepieces/piece-slack@latest',
      '@evil/piece-slack@1.0.0',
      'lodash@4.17.21',
      '@activepieces/piece-ok@1.0.0',
      '@activepieces/piece-ok@1.0.1',
    ].join('\n'),
  );
  assert.equal(r.pieces.length, 1);
  assert.equal(r.errors.length, 5);
  assert.match(r.errors[4], /duplicate piece 'ok'/);
});

test('support packages are recognised but never treated as pieces', () => {
  const r = parseAllowlist('@activepieces/pieces-framework@0.32.0\n@activepieces/piece-a@1.0.0');
  assert.deepEqual(r.errors, []);
  assert.equal(r.support.length, 1);
  assert.equal(r.pieces.length, 1);
});

test('the shipped pieces.txt is valid, pinned and covers the 30 catalog pieces', async () => {
  const r = await readAllowlist(path.join(HERE, '..', 'pieces.txt'));
  assert.deepEqual(r.errors, []);
  assert.equal(r.pieces.length, 30);
  const keys = new Set(r.pieces.map((p) => p.key));
  for (const k of ['slack', 'google-sheets', 'gmail', 'notion', 'airtable', 'hubspot', 'trello', 'asana', 'jira-cloud', 'github', 'stripe', 'shopify', 'twilio', 'discord', 'telegram-bot', 'zoho-crm', 'salesforce', 'mailchimp', 'calendly', 'typeform', 'woocommerce', 'clickup', 'linear', 'zendesk', 'freshdesk', 'pipedrive', 'dropbox', 'microsoft-onedrive', 'zoom', 'intercom']) {
    assert.ok(keys.has(k), `pieces.txt is missing ${k}`);
  }
  // Raw text must hold no version ranges.
  assert.ok(!/@activepieces\/piece-[a-z0-9-]+@[\^~><*]/.test(readFileSync(path.join(HERE, '..', 'pieces.txt'), 'utf8')));
});

test('findPiece locates the piece among other exports, via namespace or default', () => {
  const piece = { displayName: 'P', actions: () => ({}) };
  assert.equal(findPiece({ helper: 1, thing: piece }), piece);
  assert.equal(findPiece({ default: { thing: piece } }), piece);
  assert.equal(findPiece(piece), piece);
  assert.equal(findPiece({ helper: 1 }), null);
  assert.equal(findPiece(null), null);
});

test('loadPieces loads the CommonJS fixture through real module resolution', async () => {
  const r = await loadFake();
  assert.deepEqual(r.errors, []);
  assert.equal(r.loaded.length, 1);
  assert.equal(r.loaded[0].pieceKey, 'fake');
  assert.equal(r.loaded[0].version, '1.0.0');
  assert.equal(typeof r.loaded[0].piece.actions().echo.run, 'function');
});

test('loadPieces refuses keys that are invalid or not on the allow-list, and reports load failures', async () => {
  const allowlist = parseAllowlist('@activepieces/piece-ghost@1.0.0');
  const importer = async () => {
    throw new Error("Cannot find module '@activepieces/piece-ghost'\nRequire stack: ...");
  };
  const r = await loadPieces({ allowlist, requested: ['../etc', 'unlisted', 'ghost'], importer });
  assert.equal(r.loaded.length, 0);
  assert.equal(r.errors.length, 3);
  assert.match(r.errors[0].message, /not a valid piece key/);
  assert.match(r.errors[1].message, /allow-list/);
  assert.match(r.errors[2].message, /failed to load: Cannot find module/);
  assert.ok(!r.errors[2].message.includes('Require stack'));
});

test('loadPieces with no explicit selection loads every allow-listed piece', async () => {
  const allowlist = parseAllowlist('@activepieces/piece-a@1.0.0\n@activepieces/piece-b@1.0.0');
  const importer = async (pkg) => ({ p: { displayName: pkg, actions: () => ({}) } });
  const r = await loadPieces({ allowlist, requested: null, importer });
  assert.deepEqual(r.loaded.map((l) => l.pieceKey), ['a', 'b']);
});
