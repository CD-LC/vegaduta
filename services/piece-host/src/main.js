// CLI / process wiring for the piece host.
//
//   node server.js --pieces slack,notion        serve those allow-listed pieces over stdio MCP
//   node server.js                              serve every piece in pieces.txt
//   node server.js --describe [--strict]        print the tool + credential inventory as JSON and exit
//   node server.js --help
//
// Environment (all optional):
//   PIECE_HOST_PIECES               same as --pieces
//   PIECE_HOST_ALLOWLIST            path to pieces.txt             (default: <host dir>/pieces.txt)
//   PIECE_HOST_PIECES_DIR           dir containing node_modules/   (default: <host dir>/pieces)
//   PIECE_HOST_BARE_NAMES           auto | on | off                (default auto: tool names are just
//                                   "<action>" when one piece is served, "<piece>__<action>" otherwise;
//                                   core already prefixes "mcp__piece-<key>__")
//   PIECE_HOST_TOOL_NAME_MAX        host-side tool-name cap, 24..64 (default 64 - len("mcp__piece-<key>__"))
//   PIECE_HOST_MAX_RESULT_BYTES     result size cap                (default 65536)
//   PIECE_HOST_CALL_TIMEOUT_MS      per-call timeout               (default 60000)
//   PIECE_HOST_MAX_CONCURRENCY      concurrent calls               (default 8)
//   PIECE_HOST_DENY / _ALLOW / _DENY_DEFAULTS   see policy.js
//   PIECE_HOST_ALLOW_PRIVATE_EGRESS=true        disable the SSRF guard (see egress.js)
//   PIECE_HOST_FILES_DIR            enable file output into this dir
//   PIECE_HOST_LOG                  error | warn | info | debug    (default info)
//   PIECE_AUTH_<PIECE>[_FIELD]      credentials, see auth.js

import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { installEgressGuard } from './egress.js';
import { createExecutor, intFromEnv } from './executor.js';
import { createImporter, loadPieces, readAllowlist } from './loader.js';
import { createMcpHandler, serveStdio } from './mcp.js';
import { parsePatternList, policyFromEnv } from './policy.js';
import { buildRegistry, describeRegistry, toMcpTool } from './registry.js';

export const HOST_VERSION = '0.1.0';
const HOST_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const LEVELS = { error: 0, warn: 1, info: 2, debug: 3 };

/** JSON-lines logger on stderr. Callers must only pass non-sensitive fields. */
export function createLogger(stream, level = 'info') {
  const threshold = LEVELS[level] === undefined ? LEVELS.info : LEVELS[level];
  return (lvl, msg, fields = {}) => {
    if ((LEVELS[lvl] === undefined ? LEVELS.info : LEVELS[lvl]) > threshold) return;
    stream.write(`${JSON.stringify({ ts: new Date().toISOString(), level: lvl, src: 'piece-host', msg, ...fields })}\n`);
  };
}

/** Parses argv into { pieces, describe, strict, help, unknown }. Accepts `--pieces a,b` and `--pieces=a,b`. */
export function parseArgs(argv) {
  const out = { pieces: null, describe: false, strict: false, help: false, unknown: [] };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--describe') out.describe = true;
    else if (arg === '--strict') out.strict = true;
    else if (arg === '--help' || arg === '-h') out.help = true;
    else if (arg === '--pieces') {
      out.pieces = parsePatternList(argv[i + 1] || '');
      i += 1;
    } else if (arg.startsWith('--pieces=')) out.pieces = parsePatternList(arg.slice('--pieces='.length));
    else out.unknown.push(arg);
  }
  return out;
}

/** PIECE_HOST_BARE_NAMES: on/off force it, anything else (default) means "when exactly one piece is served". */
export function bareNamesFromEnv(value, loadedCount) {
  const v = typeof value === 'string' ? value.trim().toLowerCase() : '';
  if (['on', 'true', '1', 'yes'].includes(v)) return true;
  if (['off', 'false', '0', 'no'].includes(v)) return false;
  return loadedCount === 1;
}

const HELP = `piece-host ${HOST_VERSION} - Activepieces community pieces as a stdio MCP server

usage: node server.js [--pieces a,b] [--describe [--strict]]
see piece-host/README.md
`;

/**
 * Runs the host. Resolves with a process exit code once stdin has ended (serve
 * mode) or the inventory has been printed (--describe).
 */
export async function main(argv, env, io = {}) {
  const stdin = io.stdin || process.stdin;
  const stdout = io.stdout || process.stdout;
  const stderr = io.stderr || process.stderr;
  const log = createLogger(stderr, env.PIECE_HOST_LOG || 'info');
  const args = parseArgs(argv);

  if (args.help) {
    stdout.write(HELP);
    return 0;
  }
  if (args.unknown.length > 0) {
    stderr.write(`piece-host: unknown argument(s): ${args.unknown.join(' ').slice(0, 200)}\n${HELP}`);
    return 2;
  }

  const requested = args.pieces !== null ? args.pieces : parsePatternList(env.PIECE_HOST_PIECES);
  const allowlistPath = env.PIECE_HOST_ALLOWLIST || path.join(HOST_DIR, 'pieces.txt');
  const piecesDir = env.PIECE_HOST_PIECES_DIR || path.join(HOST_DIR, 'pieces');

  let allowlist;
  try {
    allowlist = await readAllowlist(allowlistPath);
  } catch (err) {
    stderr.write(`piece-host: cannot read allow-list ${allowlistPath}: ${String((err && err.message) || err).slice(0, 200)}\n`);
    return 1;
  }
  if (allowlist.errors.length > 0) {
    stderr.write(`piece-host: invalid allow-list:\n  ${allowlist.errors.join('\n  ')}\n`);
    return 1;
  }

  const { loaded, errors } = await loadPieces({ allowlist, requested, importer: createImporter(piecesDir) });
  for (const e of errors) log('error', 'piece_load_failed', { piece: e.pieceKey, error: e.message });

  const policy = policyFromEnv(env);
  const registry = buildRegistry(loaded, policy, {
    bareNames: bareNamesFromEnv(env.PIECE_HOST_BARE_NAMES, loaded.length),
    maxToolNameLength: intFromEnv(env.PIECE_HOST_TOOL_NAME_MAX, undefined),
  });

  if (args.describe) {
    stdout.write(`${JSON.stringify({ host: { version: HOST_VERSION, node: process.version, deny: policy.denyPatterns, allow: policy.allowPatterns }, ...describeRegistry(loaded, registry, errors) }, null, 2)}\n`);
    return args.strict && errors.length > 0 ? 1 : 0;
  }

  if (loaded.length === 0) {
    stderr.write('piece-host: no piece could be loaded, nothing to serve.\n');
    return 1;
  }
  if (args.strict && errors.length > 0) return 1;

  installEgressGuard({ allowPrivate: String(env.PIECE_HOST_ALLOW_PRIVATE_EGRESS || '').toLowerCase() === 'true' });

  const executor = createExecutor({
    registry,
    env,
    log,
    config: {
      maxResultBytes: intFromEnv(env.PIECE_HOST_MAX_RESULT_BYTES, undefined),
      callTimeoutMs: intFromEnv(env.PIECE_HOST_CALL_TIMEOUT_MS, undefined),
      maxConcurrency: intFromEnv(env.PIECE_HOST_MAX_CONCURRENCY, undefined),
    },
  });
  const handler = createMcpHandler({
    serverInfo: { name: 'piece-host', version: HOST_VERSION },
    listTools: () => registry.tools.map(toMcpTool),
    callTool: executor.callTool,
    log,
  });

  log('info', 'serving', {
    pieces: loaded.map((l) => `${l.pieceKey}@${l.version}`),
    tools: registry.tools.length,
    denied: registry.denied.size,
  });
  await serveStdio({ input: stdin, output: stdout, handler, log });
  return 0;
}
