// Builds the MCP tool table from loaded pieces: one tool per action,
// named "<piece>__<action>" (or "<action>" when serving a single piece, see
// names.js), with a JSON Schema derived from the action props.

import { describeAuth } from './auth.js';
import { toolName } from './names.js';
import { buildInputSchema, publishSchema } from './schema.js';

const MAX_TOOL_DESCRIPTION = 300;

function oneLine(text) {
  return String(text || '').replace(/\s+/g, ' ').trim();
}

function toolDescription(piece, action) {
  const head = oneLine(action.displayName);
  const body = oneLine(action.description);
  let text = `${oneLine(piece.displayName)} - ${head}${body && body !== head ? `: ${body}` : ''}`;
  if (text.length > MAX_TOOL_DESCRIPTION) text = `${text.slice(0, MAX_TOOL_DESCRIPTION - 3)}...`;
  return text;
}

/**
 * @param loaded  [{ pieceKey, packageName, version, piece }]
 * @param policy  createPolicy() result
 * @param options { bareNames = false, maxToolNameLength } - see names.js toolName()
 * @returns { tools: [...], byName: Map, denied: Map<name, reason>, deniedPiece: Map<name, pieceKey> }
 */
export function buildRegistry(loaded, policy, options = {}) {
  const tools = [];
  const byName = new Map();
  const denied = new Map();
  const deniedPiece = new Map();
  const taken = new Set();
  const nameOptions = { bare: options.bareNames === true, maxLength: options.maxToolNameLength };

  for (const lp of loaded) {
    const actions = lp.piece.actions();
    for (const [key, action] of Object.entries(actions || {})) {
      const actionName = typeof action.name === 'string' && action.name ? action.name : key;
      const name = toolName(lp.pieceKey, actionName, taken, nameOptions);
      taken.add(name);

      const verdict = policy.check(lp.pieceKey, actionName);
      if (!verdict.allowed) {
        denied.set(name, verdict.reason);
        deniedPiece.set(name, lp.pieceKey);
        continue;
      }

      const props = action.props && typeof action.props === 'object' ? action.props : {};
      const { schema, skipped } = buildInputSchema(props);
      const authRequired = lp.piece.auth !== undefined && lp.piece.auth !== null && action.requireAuth !== false;
      const entry = {
        name,
        pieceKey: lp.pieceKey,
        actionName,
        action,
        piece: lp.piece,
        props,
        authRequired,
        inputSchema: schema,
        skipped,
        description: toolDescription(lp.piece, action),
      };
      tools.push(entry);
      byName.set(name, entry);
    }
  }
  return { tools, byName, denied, deniedPiece };
}

/** The `tools/list` projection of a registry entry. */
export function toMcpTool(entry) {
  return { name: entry.name, description: entry.description, inputSchema: publishSchema(entry.inputSchema) };
}

/** Machine-readable inventory for `--describe` (used to verify the catalog's credential env names). */
export function describeRegistry(loaded, registry, errors = []) {
  return {
    pieces: loaded.map((lp) => ({
      key: lp.pieceKey,
      package: lp.packageName,
      version: lp.version,
      displayName: lp.piece.displayName,
      auth: describeAuth(lp.pieceKey, lp.piece.auth),
      tools: registry.tools
        .filter((t) => t.pieceKey === lp.pieceKey)
        .map((t) => ({ name: t.name, authRequired: t.authRequired, skippedProps: t.skipped })),
      denied: [...registry.denied.entries()]
        .filter(([name]) => (registry.deniedPiece ? registry.deniedPiece.get(name) === lp.pieceKey : name.startsWith(`${lp.pieceKey}__`)))
        .map(([name, reason]) => ({ name, reason })),
    })),
    errors,
  };
}
