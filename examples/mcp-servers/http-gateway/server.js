#!/usr/bin/env node
/**
 * Streamable-HTTP front end for this repo's first-party stdio MCP connectors.
 *
 * WHY THIS EXISTS
 * ---------------
 * Production's `mcp-stdio-runner` Container App has no volume mount, so
 * `/opt/mcp-servers/<name>/index.js` - the path every STDIO registration in
 * this repo advertises - does not exist there the way `deploy/docker-compose.yml`'s
 * bind mount provides it locally. `McpStdioCommandGuard` also blocks `-e`/`-c`,
 * so inlining a connector's source as a `node` argument is not a way round it.
 *
 * `DynamicMcpConnectionManager.buildRemoteTransport` does, however, build a real
 * `HttpClientStreamableHttpTransport` for a `STREAMABLE_HTTP` registration, and
 * `McpRegistryService.register` calls `markReviewNotRequired()` on that branch -
 * so a remote connector connects without the platform-admin staff review a
 * custom STDIO name sits behind. Hence: serve the same connectors over HTTP.
 *
 * WHAT IT DOES NOT DO
 * -------------------
 * It does not reimplement any connector. Each connector is spawned as the exact
 * same child process `DynamicMcpConnectionManager` would spawn, speaking the
 * exact same newline-delimited JSON-RPC over stdio, and this file is only a
 * transport adapter in front of it. That means a connector's behaviour - and its
 * guardrails - cannot drift between the stdio path and the HTTP path, because
 * there is only one implementation.
 *
 * Zero npm dependencies, same house rule as the connectors themselves.
 *
 * PER-TENANT CREDENTIALS (2026-09-17)
 * -----------------------------------
 * This gateway used to spawn every connector child with `env: {PATH, HOME}`
 * and nothing else, and its README said so plainly: "No per-tenant isolation.
 * Every caller with the token reaches the same connector children. That is
 * acceptable precisely because the connectors are read-only over synthetic
 * fixtures; it would not be acceptable for a connector holding real
 * credentials."
 *
 * That was correct, and it is exactly why `youtube-publish` and
 * `broll-generate` could not be served from here: one shared child would mean
 * one tenant's caller uploading to another tenant's YouTube channel.
 *
 * The fix reuses a mechanism that already exists rather than inventing one.
 * `DynamicMcpConnectionManager.remoteHeaders` copies a remote registration's
 * `env` map VERBATIM into request headers (audited in
 * core/MCP-SECURITY-PENTEST-2026-07-25.md: encrypted at rest, absent from
 * McpServerRegistrationResponse, never logged). So a registration can carry
 * its own credentials as headers, and this gateway keys a SEPARATE child
 * process per distinct credential set:
 *
 *     env: {
 *       "Authorization":             "Bearer <derived-token>",   // selects the connector
 *       "X-Connector-Env-FAL_KEY":   "<this tenant's fal key>"   // becomes the child's env
 *     }
 *
 * Two tenants with different credentials therefore never share a child, and a
 * tenant that sends no credential headers gets the same credential-less shared
 * child as before - which is what keeps the three sandbox connectors working
 * exactly as they did.
 *
 * What this does NOT claim to be: a secrets boundary against the gateway
 * itself. Whoever holds a connector's bearer token can send any env they like
 * and reach a child running with it. The isolation is BETWEEN tenants, not
 * between a tenant and this process. A connector whose blast radius is worse
 * than "acts on the credentials it was given" still does not belong here.
 *
 * Header-name limits, stated because they bite: HTTP header names are
 * case-insensitive and Node lower-cases them, so the suffix after the prefix
 * is UPPER-CASED to form the variable name. Every credential in this repo's
 * connectors is already upper-case (FAL_KEY, YOUTUBE_REFRESH_TOKEN, ...); a
 * connector needing a lower-case env var cannot be fed this way.
 *
 * Zero npm dependencies, same house rule as the connectors themselves.
 *
 * Env:
 *   PORT            - listen port (default 8099)
 *   GATEWAY_TOKEN   - if set, every /mcp/* request must carry
 *                     `Authorization: Bearer <token>`. For a remote MCP
 *                     registration this is supplied through the registration's
 *                     `env` map, which DynamicMcpConnectionManager.remoteHeaders
 *                     copies verbatim into request headers.
 *   SERVERS         - comma-separated connector names to expose
 *   CONNECTOR_ROOT  - directory holding the connector folders (default: ..)
 *   SLOW_CONNECTORS - connectors whose tools legitimately take minutes, given
 *                     SLOW_CALL_TIMEOUT_MS instead of CALL_TIMEOUT_MS
 *   MAX_CHILDREN_PER_CONNECTOR - cap on distinct credential sets held live
 *   CHILD_IDLE_MS   - evict a credentialed child after this long unused
 */

'use strict';

const http = require('http');
const path = require('path');
const fs = require('fs');
const { spawn } = require('child_process');
const crypto = require('crypto');

const PORT = Number(process.env.PORT || 8099);
const GATEWAY_TOKEN = (process.env.GATEWAY_TOKEN || '').trim();
const CONNECTOR_ROOT = path.resolve(
  process.env.CONNECTOR_ROOT || path.join(__dirname, '..')
);
// youtube-publish and broll-generate are here only because per-tenant
// credentials exist now. Before that they could not be: one shared
// credential-less child would have meant one tenant uploading to another
// tenant's YouTube channel. Do not add a credentialed connector to this
// list without checking that its credentials arrive per-request.
const SERVER_NAMES = (process.env.SERVERS
  || 'home-assistant')
  .split(',')
  .map((s) => s.trim())
  .filter(Boolean);

// A request that takes longer than this is a hung connector, not a slow one -
// every tool in the three SANDBOX connectors answers from an in-process
// fixture, so 20s is generous for them.
const CALL_TIMEOUT_MS = Number(process.env.CALL_TIMEOUT_MS || 20000);

// ...but that assumption does not hold for every connector, and applying it
// blindly was a real bug waiting to happen. `broll-generate`'s
// generate_broll submits N video generations and waits for all of them
// (30-180s each, deliberately - the workflow engine has no wait node and
// cannot join them), and `youtube-publish`'s upload_video PUTs a whole mp4.
// A 20s ceiling would abort both mid-flight, and for broll-generate that
// means paying for generations whose URLs are then thrown away.
const SLOW_CALL_TIMEOUT_MS = Number(process.env.SLOW_CALL_TIMEOUT_MS || 900000);
const SLOW_CONNECTORS = new Set(
  (process.env.SLOW_CONNECTORS || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
);

// Per-tenant children are held live so repeated calls do not re-spawn, but not
// forever: each one holds a tenant's credentials in its process environment,
// so an unused child is a liability with no benefit. The cap is a
// resource-exhaustion guard - a caller cycling credential values could
// otherwise spawn unbounded children.
const MAX_CHILDREN_PER_CONNECTOR = Number(process.env.MAX_CHILDREN_PER_CONNECTOR || 8);
const CHILD_IDLE_MS = Number(process.env.CHILD_IDLE_MS || 600000);

const MAX_BODY_BYTES = 1 << 20; // 1 MiB

// Credentials arrive as `X-Connector-Env-<VAR>: <value>` request headers. Node
// lower-cases header names, hence the lower-case prefix and the upper-casing
// of the suffix.
const CREDENTIAL_HEADER_PREFIX = 'x-connector-env-';
const ENV_NAME = /^[A-Z][A-Z0-9_]*$/;

// Same posture as McpStdioCommandGuard's env denylist, and for the same
// reason: these names do not configure a connector, they reconfigure the
// RUNTIME that runs it. NODE_OPTIONS alone can load arbitrary code into the
// child; PATH can redirect which binary `node` resolves to. Blocked outright
// rather than sanitised.
const ENV_DENYLIST = new Set([
  'PATH', 'HOME', 'NODE_OPTIONS', 'NODE_PATH', 'NODE_REPL_EXTERNAL_MODULE',
  'NPM_CONFIG_NODE_OPTIONS', 'NPM_CONFIG_PREFIX', 'NPM_CONFIG_REGISTRY',
  'LD_PRELOAD', 'LD_LIBRARY_PATH', 'LD_AUDIT', 'DYLD_INSERT_LIBRARIES',
  'DYLD_LIBRARY_PATH', 'PYTHONPATH', 'PYTHONSTARTUP', 'PYTHONHOME',
  'GATEWAY_TOKEN', 'CONNECTOR_ROOT', 'SERVERS',
]);

/**
 * Pull a connector's credential env out of the request headers.
 *
 * Returns a plain object. Rejected names are logged BY NAME ONLY and dropped
 * rather than failing the request - a registration carrying one stray header
 * should not become an unexplainable 500, and the connector's own fail-fast
 * will name whatever it still needs.
 */
function credentialEnvFrom(req) {
  const env = {};
  for (const [header, value] of Object.entries(req.headers)) {
    if (!header.startsWith(CREDENTIAL_HEADER_PREFIX)) continue;
    const name = header.slice(CREDENTIAL_HEADER_PREFIX.length).toUpperCase();
    if (!ENV_NAME.test(name)) {
      log(`rejected credential header with non-env name: ${name.slice(0, 40)}`);
      continue;
    }
    if (ENV_DENYLIST.has(name)) {
      log(`rejected credential header ${name}: reconfigures the runtime, not the connector`);
      continue;
    }
    // Never log the value. Array-valued headers (a duplicated header) are
    // ambiguous - take neither rather than guessing which the caller meant.
    if (Array.isArray(value)) {
      log(`rejected credential header ${name}: sent more than once`);
      continue;
    }
    env[name] = String(value);
  }
  return env;
}

/** Stable identity for a credential set. The VALUES never leave this function. */
function envFingerprint(env) {
  const names = Object.keys(env).sort();
  if (!names.length) return 'noenv';
  const material = names.map((n) => `${n}=${env[n]}`).join('\n');
  return crypto.createHash('sha256').update(material).digest('hex').slice(0, 16);
}

// What this GATEWAY can speak as a transport, newest first. Distinct from what
// the connector children speak over stdio (2024-11-05) - see the negotiation
// comment in the initialize branch of handleMcp for why the difference matters.
const SUPPORTED_PROTOCOL_VERSIONS = ['2025-06-18', '2025-03-26', '2024-11-05'];
const PROTOCOL_VERSION = '2024-11-05';

// Wire tracing. On unless explicitly disabled - see the trace block in handleMcp.
const TRACE = (process.env.GATEWAY_TRACE || '1') !== '0';

function log(...args) {
  console.error(`[gateway ${new Date().toISOString()}]`, ...args);
}

/**
 * One long-lived child process per (connector, credential set), with JSON-RPC
 * ids remapped so concurrent HTTP callers cannot collide on an id.
 *
 * Keyed by credential set, not just by connector name - that is what gives
 * per-tenant isolation. Two tenants with different credentials get two
 * children; callers sending no credentials share one, which is how the three
 * stateless sandbox connectors keep behaving exactly as they did.
 *
 * Still ONE child per credential set, shared by concurrent callers of that
 * set. Correct for these connectors because none holds per-SESSION state
 * (youtube-publish is explicitly stateless - no ledger, no volume - and
 * broll-generate holds nothing between calls). A connector that did hold
 * per-session state would need a child per session instead.
 */
class ConnectorProcess {
  constructor(name, env = {}, fingerprint = 'noenv') {
    this.name = name;
    this.env = env;
    this.fingerprint = fingerprint;
    this.key = `${name}:${fingerprint}`;
    this.entry = path.join(CONNECTOR_ROOT, name, 'index.js');
    this.child = null;
    this.buffer = '';
    this.pending = new Map(); // gatewayId -> {resolve, reject, timer}
    this.nextId = 1;
    this.initialized = false;
    this.starting = null;
    this.callTimeoutMs = SLOW_CONNECTORS.has(name) ? SLOW_CALL_TIMEOUT_MS : CALL_TIMEOUT_MS;
    this.lastUsed = Date.now();
  }

  touch() {
    this.lastUsed = Date.now();
  }

  dispose(reason) {
    log(`${this.key}: disposing (${reason})`);
    for (const [, waiter] of this.pending) {
      clearTimeout(waiter.timer);
      waiter.reject(new Error(`${this.name} connector disposed: ${reason}`));
    }
    this.pending.clear();
    if (this.child && !this.child.killed) this.child.kill();
    this.child = null;
    this.initialized = false;
    this.starting = null;
  }

  available() {
    return fs.existsSync(this.entry);
  }

  start() {
    if (this.child && !this.child.killed) return Promise.resolve();
    if (this.starting) return this.starting;

    this.starting = new Promise((resolve, reject) => {
      // Names only, never values - the same rule remoteHeaders follows on
      // core's side.
      const supplied = Object.keys(this.env);
      log(
        `spawning ${this.key} -> ${this.entry}`
          + (supplied.length ? ` with env [${supplied.join(', ')}]` : ' with no credentials')
      );
      const child = spawn(process.execPath, [this.entry], {
        stdio: ['pipe', 'pipe', 'pipe'],
        // Deliberately NOT inheriting the gateway's environment: the child
        // gets PATH, HOME and nothing except the credentials THIS request
        // supplied. So the gateway's own environment - GATEWAY_TOKEN
        // included - is never visible to a connector, and a connector can
        // never see another tenant's credentials, because a different
        // credential set is a different child.
        //
        // With no credential headers this is byte-identical to the previous
        // behaviour: every connector stays in its own sandbox mode.
        env: {
          PATH: process.env.PATH,
          HOME: process.env.HOME || process.env.USERPROFILE || '/tmp',
          ...this.env,
        },
      });

      child.stdout.setEncoding('utf8');
      child.stdout.on('data', (chunk) => this.onStdout(chunk));
      child.stderr.setEncoding('utf8');
      child.stderr.on('data', (d) => log(`${this.name} stderr:`, d.trim()));

      child.on('exit', (code, signal) => {
        // A credentialed connector exits code 1 immediately when a required
        // variable is missing (every connector in this repo fails fast on
        // stderr). That stderr is already logged above, and it names the
        // variable - which is the actionable part.
        log(`${this.key} exited code=${code} signal=${signal}`);
        this.child = null;
        this.initialized = false;
        this.starting = null;
        for (const [, p] of this.pending) {
          clearTimeout(p.timer);
          p.reject(new Error(`${this.name} connector exited`));
        }
        this.pending.clear();
      });

      child.on('error', (err) => {
        this.starting = null;
        reject(err);
      });

      this.child = child;
      resolve();
    });

    return this.starting;
  }

  onStdout(chunk) {
    this.buffer += chunk;
    let idx;
    while ((idx = this.buffer.indexOf('\n')) >= 0) {
      const line = this.buffer.slice(0, idx).trim();
      this.buffer = this.buffer.slice(idx + 1);
      if (!line) continue;
      let msg;
      try {
        msg = JSON.parse(line);
      } catch (e) {
        log(`${this.name}: unparseable line`, line.slice(0, 200));
        continue;
      }
      if (msg.id === undefined || msg.id === null) continue; // notification from server
      const waiter = this.pending.get(msg.id);
      if (!waiter) continue;
      this.pending.delete(msg.id);
      clearTimeout(waiter.timer);
      waiter.resolve(msg);
    }
  }

  writeRaw(obj) {
    if (!this.child || this.child.killed) throw new Error(`${this.name} not running`);
    this.child.stdin.write(JSON.stringify(obj) + '\n');
  }

  /** Send a request and await its matching response, remapping the id. */
  request(message) {
    return new Promise((resolve, reject) => {
      const gatewayId = this.nextId++;
      const timer = setTimeout(() => {
        this.pending.delete(gatewayId);
        reject(new Error(`${this.name}: no response within ${this.callTimeoutMs}ms`));
      }, this.callTimeoutMs);
      this.pending.set(gatewayId, { resolve, reject, timer });
      try {
        this.writeRaw({ ...message, id: gatewayId });
      } catch (err) {
        this.pending.delete(gatewayId);
        clearTimeout(timer);
        reject(err);
      }
    });
  }

  notify(message) {
    try {
      this.writeRaw(message);
    } catch (err) {
      log(`${this.name}: notification dropped -`, err.message);
    }
  }

  /**
   * The child is initialized once, by the gateway, using a fixed handshake. Each
   * HTTP client's own `initialize` is answered from that cached result rather
   * than re-handshaking a shared child, which would be a protocol error for
   * whichever client got there second.
   */
  async ensureInitialized() {
    await this.start();
    if (this.initialized) return this.initResult;
    const resp = await this.request({
      jsonrpc: '2.0',
      method: 'initialize',
      params: {
        protocolVersion: '2024-11-05',
        capabilities: {},
        clientInfo: { name: 'vegaduta-mcp-http-gateway', version: '0.1.0' },
      },
    });
    if (resp.error) throw new Error(`${this.name} initialize failed: ${JSON.stringify(resp.error)}`);
    this.initResult = resp.result;
    this.notify({ jsonrpc: '2.0', method: 'notifications/initialized' });
    this.initialized = true;
    log(`${this.name} initialized`);
    return this.initResult;
  }
}

/**
 * connectorKey -> ConnectorProcess, where the key is `<name>:<fingerprint>`.
 *
 * Pre-seeded with the credential-less child of every declared connector so
 * /health can report availability without spawning anything, exactly as
 * before. Credentialed children are created on first use.
 */
const connectors = new Map();
for (const name of SERVER_NAMES) {
  const c = new ConnectorProcess(name);
  if (!c.available()) {
    log(`WARNING: connector "${name}" not found at ${c.entry} - it will 404`);
  }
  connectors.set(c.key, c);
}

function declaredConnector(name) {
  return connectors.get(`${name}:noenv`);
}

/**
 * The child for this connector AND this credential set, creating it if needed.
 *
 * Over-cap eviction drops the least-recently-used child rather than refusing
 * the request: a refusal would make a legitimate ninth tenant fail for reasons
 * it cannot see or fix, whereas eviction only costs the evicted tenant one
 * re-spawn.
 */
function connectorFor(name, env) {
  const fingerprint = envFingerprint(env);
  const key = `${name}:${fingerprint}`;
  const existing = connectors.get(key);
  if (existing) {
    existing.touch();
    return existing;
  }

  const live = [...connectors.values()].filter(
    (c) => c.name === name && c.fingerprint !== 'noenv'
  );
  if (live.length >= MAX_CHILDREN_PER_CONNECTOR) {
    const oldest = live.sort((a, b) => a.lastUsed - b.lastUsed)[0];
    connectors.delete(oldest.key);
    oldest.dispose(`MAX_CHILDREN_PER_CONNECTOR=${MAX_CHILDREN_PER_CONNECTOR} reached`);
  }

  const created = new ConnectorProcess(name, env, fingerprint);
  connectors.set(key, created);
  return created;
}

// Evict idle credentialed children. The credential-less ones are never
// evicted - they hold no secret and re-spawning them buys nothing.
setInterval(() => {
  const now = Date.now();
  for (const [key, c] of [...connectors]) {
    if (c.fingerprint === 'noenv') continue;
    if (now - c.lastUsed < CHILD_IDLE_MS) continue;
    if (c.pending.size) continue;
    connectors.delete(key);
    c.dispose(`idle for more than CHILD_IDLE_MS=${CHILD_IDLE_MS}ms`);
  }
}, Math.max(30000, Math.floor(CHILD_IDLE_MS / 4))).unref();

function send(res, status, body, headers = {}) {
  const payload = body === null ? '' : JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(payload),
    ...headers,
  });
  res.end(payload);
}

function jsonRpcError(res, id, code, message, status = 200) {
  send(res, status, { jsonrpc: '2.0', id: id ?? null, error: { code, message } });
}

/**
 * Per-connector token, derived deterministically from the master token.
 *
 * This exists because of a hard constraint in the client, not by preference.
 * The MCP Java SDK's HttpClientStreamableHttpTransport **discards the path of
 * the URL it is given** and requests the origin plus its own default endpoint,
 * `/mcp`. Verified from this gateway's wire trace on 2026-09-17: a connector
 * registered at `https://host/mcp/office-ally` produced
 * `POST /mcp/office-ally` (core's own pre-probe, which uses a plain HTTP client
 * and honours the full URL) immediately followed by `POST /mcp` and `GET /mcp`
 * from the SDK itself.
 *
 * So every connector necessarily arrives on the SAME path, and the path cannot
 * identify which one is wanted. The one thing that IS per-registration is the
 * `Authorization` header, because `DynamicMcpConnectionManager.remoteHeaders`
 * copies a registration's `env` entries verbatim into request headers. Hence:
 * one derived token per connector, and the token selects the connector.
 *
 * Derived rather than configured so there is nothing extra to keep in sync -
 * the deploy script and bootstrap compute the same value with `sha256sum`.
 */
function tokenFor(name) {
  return crypto.createHash('sha256')
    .update(`${GATEWAY_TOKEN}:${name}`)
    .digest('hex')
    .slice(0, 40);
}

const TOKEN_TO_CONNECTOR = new Map();
if (GATEWAY_TOKEN) {
  for (const name of SERVER_NAMES) TOKEN_TO_CONNECTOR.set(tokenFor(name), name);
}

function bearer(req) {
  const header = req.headers['authorization'] || '';
  return header.startsWith('Bearer ') ? header.slice(7).trim() : '';
}

function safeEqual(a, b) {
  const x = Buffer.from(a);
  const y = Buffer.from(b);
  // Length check first: timingSafeEqual throws on a length mismatch.
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

/** The master token opens any connector; a derived token opens only its own. */
function authorizedFor(req, connectorName) {
  if (!GATEWAY_TOKEN) return true;
  const presented = bearer(req);
  if (!presented) return false;
  if (safeEqual(presented, GATEWAY_TOKEN)) return true;
  return safeEqual(presented, tokenFor(connectorName));
}

/** Which connector a path-less `/mcp` request is for - decided by its token. */
function connectorFromToken(req) {
  const presented = bearer(req);
  if (!presented) return null;
  for (const [token, name] of TOKEN_TO_CONNECTOR) {
    if (safeEqual(presented, token)) return name;
  }
  return null;
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY_BYTES) {
        reject(new Error('request body too large'));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

async function handleMcp(req, res, connector) {
  if (TRACE && req.method !== 'POST') {
    log(`${connector.name} <- ${req.method} ${req.url} `
      + `accept=${JSON.stringify(req.headers['accept'] || '')} `
      + `session=${JSON.stringify(req.headers['mcp-session-id'] || null)}`);
  }
  if (req.method === 'GET') {
    // The spec allows a server with no server-initiated messages to refuse the
    // SSE stream. These connectors are strictly request/response, so there is
    // nothing to stream and 405 is the honest answer.
    res.writeHead(405, { 'Content-Type': 'text/plain', Allow: 'POST, DELETE' });
    res.end('this gateway does not offer a server-initiated stream');
    return;
  }
  if (req.method === 'DELETE') {
    send(res, 200, { ok: true });
    return;
  }
  if (req.method !== 'POST') {
    res.writeHead(405, { Allow: 'POST, GET, DELETE' });
    res.end();
    return;
  }

  let raw;
  try {
    raw = await readBody(req);
  } catch (err) {
    return jsonRpcError(res, null, -32600, err.message, 413);
  }

  let msg;
  try {
    msg = JSON.parse(raw);
  } catch (e) {
    return jsonRpcError(res, null, -32700, 'parse error', 400);
  }

  // Batches are legal JSON-RPC. Handle them rather than failing obscurely.
  const batch = Array.isArray(msg);
  const messages = batch ? msg : [msg];

  // Wire-level tracing, on by default. This gateway's whole job is to satisfy
  // one specific client (core's MCP Java SDK), and when that handshake fails it
  // fails as a TIMEOUT with no detail on either side - there is nothing to debug
  // from unless the exact request/response shape is on the record. Cheap: a
  // couple of lines per connection attempt, and connections are rare.
  if (TRACE) {
    log(`${connector.name} <- ${req.method} ${req.url} `
      + `accept=${JSON.stringify(req.headers['accept'] || '')} `
      + `ct=${JSON.stringify(req.headers['content-type'] || '')} `
      + `session=${JSON.stringify(req.headers['mcp-session-id'] || null)} `
      + `mcpver=${JSON.stringify(req.headers['mcp-protocol-version'] || null)} `
      + `methods=${JSON.stringify(messages.map((m) => m.method))} `
      + `ids=${JSON.stringify(messages.map((m) => m.id ?? null))}`);
  }

  try {
    await connector.ensureInitialized();
  } catch (err) {
    log(`${connector.name}: init failed -`, err.message);
    return jsonRpcError(res, messages[0] && messages[0].id, -32603,
      `connector unavailable: ${err.message}`, 503);
  }

  const responses = [];
  for (const m of messages) {
    const isNotification = m.id === undefined || m.id === null;

    if (isNotification) {
      // Never forward a second initialized notification into the shared child.
      if (m.method !== 'notifications/initialized') connector.notify(m);
      continue;
    }

    if (m.method === 'initialize') {
      // Protocol-version negotiation is the gateway's job, not the connector's.
      //
      // The connectors all advertise 2024-11-05, which predates Streamable HTTP
      // (introduced 2025-03-26). A streamable-http client that is told
      // 2024-11-05 can reasonably conclude it is talking to a legacy HTTP+SSE
      // server and wait for a transport that never arrives - which is exactly
      // what "MCP initialization timed out" looked like from core's side.
      //
      // Since this gateway IS the streamable-http transport, it answers for the
      // transport: echo back whatever version the client asked for when it is
      // one we can honour, and fall back to the connector's own otherwise. The
      // child still speaks 2024-11-05 over stdio; nothing about the connectors
      // changes.
      const requested = (m.params && m.params.protocolVersion) || null;
      const negotiated = SUPPORTED_PROTOCOL_VERSIONS.includes(requested)
        ? requested
        : (connector.initResult && connector.initResult.protocolVersion) || PROTOCOL_VERSION;
      if (requested && requested !== negotiated) {
        log(`${connector.name}: client asked for protocolVersion ${requested}, answering ${negotiated}`);
      }
      responses.push({
        jsonrpc: '2.0',
        id: m.id,
        result: { ...connector.initResult, protocolVersion: negotiated },
      });
      continue;
    }

    try {
      const upstream = await connector.request({
        jsonrpc: '2.0',
        method: m.method,
        params: m.params,
      });
      responses.push({ ...upstream, id: m.id });
    } catch (err) {
      log(`${connector.name}: ${m.method} failed -`, err.message);
      responses.push({
        jsonrpc: '2.0',
        id: m.id,
        error: { code: -32603, message: err.message },
      });
    }
  }

  if (responses.length === 0) {
    // Notifications only: the spec's answer is 202 with no body.
    res.writeHead(202);
    res.end();
    return;
  }

  const headers = {};
  const isInit = messages.some((m) => m.method === 'initialize');
  if (isInit) headers['Mcp-Session-Id'] = crypto.randomUUID();

  const payload = batch ? responses : responses[0];

  // ALWAYS answer application/json. Do not "helpfully" switch to SSE when the
  // client's Accept header lists text/event-stream - it always does, and the
  // Java SDK's HttpClientStreamableHttpTransport (MCP SDK 0.18.2, what core
  // actually uses) REJECTS an SSE answer outright: "Connection failed: The
  // server answered with a server-sent-events stream." Both forms were tried
  // against production on 2026-09-17; only JSON is accepted. The spec permits
  // either, the client in front of us does not.
  if (TRACE) {
    const preview = JSON.stringify(payload);
    log(`${connector.name} -> 200 application/json `
      + `${Object.keys(headers).length ? JSON.stringify(headers) + ' ' : ''}`
      + `bytes=${Buffer.byteLength(preview)} body=${preview.slice(0, 300)}`);
  }
  send(res, 200, payload, headers);
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const p = url.pathname.replace(/\/+$/, '') || '/';

  if (p === '/health' || p === '/') {
    // The old response asserted "No real patient record, ERP or ad account is
    // reachable." That stopped being true the moment credentialed connectors
    // became servable, so it is reported per-connector now instead of as a
    // blanket claim. A health endpoint that overstates its safety posture is
    // worse than one that says nothing.
    return send(res, 200, {
      status: 'ok',
      servers: SERVER_NAMES.map((n) => {
        const base = declaredConnector(n);
        const children = [...connectors.values()].filter((c) => c.name === n);
        return {
          name: n,
          path: `/mcp/${n}`,
          available: Boolean(base && base.available()),
          credentialed: SLOW_CONNECTORS.has(n) || children.some((c) => c.fingerprint !== 'noenv'),
          liveChildren: children.filter((c) => Boolean(c.child)).length,
          distinctCredentialSets: children.filter((c) => c.fingerprint !== 'noenv').length,
          callTimeoutMs: base ? base.callTimeoutMs : CALL_TIMEOUT_MS,
        };
      }),
      note: 'Connectors reached with no X-Connector-Env-* headers run '
          + 'credential-less. A connector given credentials acts on them for '
          + 'real; each distinct credential set gets its own child process, so '
          + 'tenants are isolated from each other - not from this gateway.',
    });
  }

  // Accept BOTH /mcp/<server> and /mcp/<server>/mcp.
  //
  // Core's own pre-probe POSTs to the registered URL exactly. The MCP Java SDK's
  // HttpClientStreamableHttpTransport does NOT: it treats the URL it is given as
  // a BASE and appends its own endpoint path, which defaults to `/mcp`. So the
  // probe succeeded against /mcp/office-ally while the real client was asking
  // for /mcp/office-ally/mcp and getting a 404 - which the SDK surfaced only as
  // "MCP initialization timed out", with the 404 invisible on both sides.
  // (Diagnosed from the gateway's own wire trace, 2026-09-17: the probe arrived,
  // nothing else ever did.)
  //
  // Tolerating the suffix here is deliberately more forgiving than the spec
  // requires - it costs nothing, and it means the registered URL works whether a
  // client appends an endpoint or not.
  const m = p.match(/^\/mcp\/([A-Za-z0-9_-]+)(?:\/mcp)?$/);

  // Bare `/mcp` - what the MCP Java SDK actually requests, since it drops the
  // registered URL's path. Identify the connector by its derived token instead.
  let name = m ? m[1] : null;
  if (!name && p === '/mcp') {
    name = connectorFromToken(req);
    if (!name) {
      if (TRACE) {
        log(`/mcp with no matching connector token `
          + `(${req.method}, auth=${req.headers['authorization'] ? 'present' : 'absent'})`);
      }
      return send(res, 401, {
        error: 'unauthorized',
        hint: 'a bare /mcp request is routed by its bearer token - use the '
            + 'per-connector token, not the master token',
      }, { 'WWW-Authenticate': 'Bearer' });
    }
    if (TRACE) log(`/mcp routed to "${name}" by token`);
  }

  if (!name) {
    if (TRACE) {
      log(`UNMATCHED ${req.method} ${p} `
        + `accept=${JSON.stringify(req.headers['accept'] || '')} `
        + `auth=${req.headers['authorization'] ? 'present' : 'absent'}`);
    }
    return send(res, 404, { error: 'not found', hint: 'try /health or /mcp/<server>' });
  }

  if (!authorizedFor(req, name)) {
    return send(res, 401, { error: 'unauthorized' }, { 'WWW-Authenticate': 'Bearer' });
  }

  if (!SERVER_NAMES.includes(name)) {
    return send(res, 404, {
      error: `unknown connector "${name}"`,
      available: SERVER_NAMES,
    });
  }
  // Resolve the child by connector AND the credentials this request carries,
  // so two tenants never share one.
  const connector = connectorFor(name, credentialEnvFrom(req));
  if (!connector.available()) {
    return send(res, 503, { error: `connector "${name}" is not installed in this image` });
  }

  try {
    await handleMcp(req, res, connector);
  } catch (err) {
    log('unhandled error', err);
    if (!res.headersSent) send(res, 500, { error: 'internal error' });
  }
});

server.listen(PORT, () => {
  log(`listening on :${PORT}`);
  log(`connector root: ${CONNECTOR_ROOT}`);
  log(`exposing: ${SERVER_NAMES.join(', ')}`);
  log(
    `slow connectors (${SLOW_CALL_TIMEOUT_MS}ms call timeout): `
      + `${[...SLOW_CONNECTORS].filter((n) => SERVER_NAMES.includes(n)).join(', ') || 'none'}`
  );
  log(
    `per-tenant credentials: up to ${MAX_CHILDREN_PER_CONNECTOR} credential sets `
      + `per connector, evicted after ${CHILD_IDLE_MS}ms idle`
  );
  log(GATEWAY_TOKEN ? 'bearer token required' : 'NO TOKEN SET - endpoint is open');
  if (GATEWAY_TOKEN) {
    // Prefixes only. Enough to correlate a failing registration with a
    // connector in the logs, useless to anyone who reads the logs.
    for (const name of SERVER_NAMES) {
      log(`  ${name}: token ${tokenFor(name).slice(0, 8)}...`);
    }
  }
});

// Container Apps sends SIGTERM on revision drain. Close the listener and let
// the connector children go with the process; nothing here holds state worth
// draining, but exiting cleanly keeps the platform's logs honest.
for (const sig of ['SIGTERM', 'SIGINT']) {
  process.on(sig, () => {
    log(`${sig} received, shutting down`);
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 5000).unref();
  });
}
