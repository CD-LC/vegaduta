// Executes one tool call: validate -> resolve credentials -> run the piece
// action in-process with the context shim -> stringify -> redact -> cap.
//
// Failure model: a tool's own failure (bad arguments, missing credentials,
// the piece throwing, a timeout) is returned as an MCP result with
// isError:true so the model can read it and recover; only protocol-level
// problems are JSON-RPC errors (see mcp.js). Error text is redacted against
// every credential value that was handed to the action.

import { resolveAuth } from './auth.js';
import { buildActionContext, createFilesService, createStore } from './context.js';
import { createRedactor, stringifyResult, truncateText } from './output.js';
import { coerceArgs, finalizeProps, validateArgs } from './schema.js';

const MAX_ERROR_CHARS = 2000;

export const DEFAULTS = Object.freeze({
  maxResultBytes: 64 * 1024,
  callTimeoutMs: 60_000,
  maxConcurrency: 8,
});

function errorResult(message) {
  const text = String(message).length > MAX_ERROR_CHARS ? `${String(message).slice(0, MAX_ERROR_CHARS)}...` : String(message);
  return { content: [{ type: 'text', text: `Error: ${text}` }], isError: true };
}

function withTimeout(promise, ms) {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`action timed out after ${ms} ms`)), ms);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

/** Message for a thrown value, enriched with the HTTP status/body of axios-style errors (headers/config are never read). */
function describeError(err) {
  if (!err || typeof err !== 'object') return String(err);
  let message = typeof err.message === 'string' && err.message ? err.message : String(err.name || 'Error');
  const response = err.response;
  if (response && typeof response === 'object' && response.status !== undefined) {
    let body = '';
    try {
      body = stringifyResult(response.data).replace(/\s+/g, ' ').slice(0, 500);
    } catch {
      body = '';
    }
    message = `${message} (HTTP ${response.status}${body ? `: ${body}` : ''})`;
  }
  return message;
}

/** Positive integer from a possibly-unset env string, else the fallback. */
export function intFromEnv(value, fallback) {
  const n = Number.parseInt(value, 10);
  return Number.isFinite(n) && n > 0 ? n : fallback;
}

export function createExecutor({ registry, env = process.env, config = {}, log = () => {} }) {
  const maxResultBytes = config.maxResultBytes || DEFAULTS.maxResultBytes;
  const callTimeoutMs = config.callTimeoutMs || DEFAULTS.callTimeoutMs;
  const maxConcurrency = config.maxConcurrency || DEFAULTS.maxConcurrency;
  const store = createStore();
  const files = createFilesService(env.PIECE_HOST_FILES_DIR || '');
  let inFlight = 0;
  let counter = 0;

  async function callTool(name, rawArgs) {
    const started = Date.now();
    const entry = registry.byName.get(name);
    if (!entry) {
      if (registry.denied.has(name)) {
        log('warn', 'tool_denied', { tool: name });
        return errorResult(`Tool '${name}' is disabled by this piece host's policy (${registry.denied.get(name)}).`);
      }
      return errorResult(`Unknown tool '${String(name).slice(0, 100)}'.`);
    }
    if (inFlight >= maxConcurrency) {
      return errorResult(`piece-host is handling ${inFlight} calls already; retry shortly.`);
    }

    inFlight += 1;
    let redact = (t) => t;
    let ok = false;
    try {
      const args = rawArgs === undefined || rawArgs === null ? {} : rawArgs;
      if (typeof args !== 'object' || Array.isArray(args)) return errorResult('arguments must be a JSON object');

      const coerced = coerceArgs(entry.props, args);
      const problems = validateArgs(entry.inputSchema, coerced);
      if (problems.length > 0) return errorResult(`Invalid arguments: ${problems.join('; ')}`);
      const propsValue = finalizeProps(entry.props, coerced);

      let auth;
      if (entry.authRequired) {
        // Read the environment per call (not at startup) so the shim never holds credentials longer than a call needs.
        const resolved = resolveAuth(entry.pieceKey, entry.piece.auth, env);
        if (!resolved.ok) return errorResult(resolved.message);
        auth = resolved.value;
        redact = createRedactor(resolved.secrets);
      }

      counter += 1;
      const ctx = buildActionContext({
        actionName: entry.actionName,
        auth,
        propsValue,
        store,
        files,
        runId: `piece-host-${process.pid}-${counter}`,
      });

      const result = await withTimeout(
        Promise.resolve().then(() => entry.action.run(ctx)),
        callTimeoutMs,
      );
      const redacted = redact(stringifyResult(result));
      const capped = truncateText(redacted, maxResultBytes);
      ok = true;
      return { content: [{ type: 'text', text: capped.text }] };
    } catch (err) {
      return errorResult(redact(describeError(err)));
    } finally {
      inFlight -= 1;
      // Never log arguments or results: they can hold PII and credentials.
      log('info', 'tool_call', { tool: name, ok, ms: Date.now() - started });
    }
  }

  return { callTool, get inFlight() { return inFlight; } };
}
