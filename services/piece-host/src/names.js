// Naming helpers: piece keys, npm package names, MCP tool names, env var tokens.
//
// A "piece key" is the short id used everywhere in this host: the part of the
// npm package name after "@activepieces/piece-" (e.g. "google-sheets" for
// "@activepieces/piece-google-sheets"). Keys are validated against a strict
// pattern because they come from tenant-typed registration args (--pieces ...)
// and end up in module resolution and env var names.

import { createHash } from 'node:crypto';

export const PIECE_SCOPE_PREFIX = '@activepieces/piece-';

/** MCP tool names are limited to [A-Za-z0-9_-] and (in practice, for Claude and OpenAI) 64 chars. */
export const TOOL_NAME_MAX = 64;

/**
 * Core's NamespacedMcpToolCallback renames every MCP tool to
 * "mcp__<registration>__<tool>" before it reaches the model, and the catalog
 * registers each piece as "piece-<key>". The 64-char provider limit applies to
 * that FULL name, so the host-side name only gets what is left over:
 * 64 - len("mcp__piece-<key>__").
 */
export const CORE_NAMESPACE_PREFIX = 'mcp__piece-';

/** Never budget below this: the hash suffix alone takes 9 characters. */
export const TOOL_NAME_MIN_BUDGET = 24;

/** Host-side tool-name budget for a piece, after core's "mcp__piece-<key>__" prefix. */
export function toolNameBudget(pieceKey, override) {
  if (Number.isInteger(override) && override >= TOOL_NAME_MIN_BUDGET && override <= TOOL_NAME_MAX) return override;
  const reserve = CORE_NAMESPACE_PREFIX.length + String(pieceKey).length + 2;
  return Math.max(TOOL_NAME_MIN_BUDGET, TOOL_NAME_MAX - reserve);
}

const PIECE_KEY_RE = /^[a-z0-9][a-z0-9-]{0,63}$/;

export function isValidPieceKey(key) {
  return typeof key === 'string' && PIECE_KEY_RE.test(key);
}

export function packageForPieceKey(key) {
  return PIECE_SCOPE_PREFIX + key;
}

/** "@activepieces/piece-slack" -> "slack"; anything else -> null. */
export function pieceKeyForPackage(pkg) {
  if (typeof pkg !== 'string' || !pkg.startsWith(PIECE_SCOPE_PREFIX)) return null;
  const key = pkg.slice(PIECE_SCOPE_PREFIX.length);
  return isValidPieceKey(key) ? key : null;
}

/**
 * Turns a piece key / prop key into an env var token: "google-sheets" ->
 * "GOOGLE_SHEETS", "instanceUrl" -> "INSTANCE_URL". Always matches
 * [A-Z0-9_]+ (never empty: falls back to "X").
 */
export function envToken(text) {
  const token = String(text)
    .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
    .replace(/[^A-Za-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .toUpperCase();
  return token || 'X';
}

function hash8(text) {
  return createHash('sha1').update(text).digest('hex').slice(0, 8);
}

/**
 * MCP tool name for a piece action: "<piece>__<action>" (or just "<action>"
 * with `bare: true`, used when the host serves a single piece because core
 * already namespaces the tool with the piece's registration name), sanitised
 * to [A-Za-z0-9_-], capped at the piece's toolNameBudget() (long names keep a
 * hash suffix so they stay unique), and de-duplicated against `taken` (caller
 * adds the returned name to the set).
 *
 * options: { bare = false, maxLength } - maxLength overrides the computed budget.
 */
export function toolName(pieceKey, actionName, taken = new Set(), options = {}) {
  const max = toolNameBudget(pieceKey, options.maxLength);
  const raw = options.bare ? String(actionName) : `${pieceKey}__${actionName}`;
  let base = raw.replace(/[^A-Za-z0-9_-]/g, '_');
  if (base.length > max) {
    base = `${base.slice(0, max - 9)}_${hash8(base)}`;
  }
  let name = base;
  let n = 2;
  while (taken.has(name)) {
    const suffix = `_${n}`;
    name = base.slice(0, max - suffix.length) + suffix;
    n += 1;
  }
  return name;
}
