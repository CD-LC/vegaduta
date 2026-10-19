'use strict';

// mcp-stdio-proxy - runs INSIDE `core` as the actual local child process
// DynamicMcpConnectionManager spawns for a STDIO MCP registration when
// mcp-stdio-sidecar.enabled=true (see DynamicMcpConnectionManager.wrapForSidecar
// and ../../docs/architecture/MCP-STDIO-SANDBOXING-DESIGN.md §8). It does nothing but forward
// bytes - the REAL interpreter/package (`npx <pkg>`, `uvx <pkg>`, ...) never
// runs in this container; it runs inside mcp-stdio-runner. That is what
// makes it safe for this one small, fixed, trusted script to be `core`'s own
// subprocess: there is nothing here for a malicious package to compromise,
// because the malicious package's own code runs somewhere else entirely.
//
// Deliberately zero npm dependencies - see framing.js's header comment for
// why. Every "MCP_SIDECAR_*" var below is read from THIS PROCESS'S OWN env,
// which - because DynamicMcpConnectionManager spawns this proxy through
// EnvIsolatedStdioClientTransport exactly like today's direct-spawn path -
// contains ONLY the five control vars below plus the SDK's non-secret
// inherited defaults (PATH/HOME/USER...). The registration's own env is NOT
// part of this process's env (SEC-030): it arrives as JSON data in
// MCP_SIDECAR_TARGET_ENV_JSON and is only ever sent on to the runner. No
// platform secret (APP_ENCRYPTION_KEY, datasource password, Keycloak client
// secret) is ever in this process's env.
//
// Stream contract with the MCP Java SDK's StdioClientTransport, which owns
// this process on the other end:
//   - our stdout carries ONLY the real child's stdout bytes, byte-for-byte
//     (StdioClientTransport parses newline-delimited JSON-RPC from it - we
//     must never write anything else there).
//   - our stderr carries ONLY the real child's stderr lines (StderrTailBuffer/
//     McpStdioSelfHealer read this verbatim), EXCEPT for the one case where
//     this proxy itself fails before/without a real child ever existing (bad
//     sidecar address, auth rejected, sidecar down) - those are relay-level
//     failures with nothing else to report them, so they're written to
//     stderr with a distinguishing "[mcp-stdio-proxy]" prefix so a human (or
//     McpConnectionDiagnoser) can still tell them apart from the real child's
//     own error output.

const net = require('net');
const { TYPE_CONTROL, TYPE_STDOUT, TYPE_STDIN, encodeFrame, FrameReader } = require('./framing');

const CONTROL_VARS = [
    'MCP_SIDECAR_URL',
    'MCP_SIDECAR_SECRET',
    'MCP_SIDECAR_TARGET_COMMAND',
    'MCP_SIDECAR_TARGET_ARGS_JSON',
    'MCP_SIDECAR_TARGET_ENV_JSON',
    // Optional: lets the runner enforce a per-tenant child cap. Absent today
    // unless core sets it; the runner treats a missing id as 'unknown'.
    'MCP_SIDECAR_TENANT_ID',
];

// Never forwarded even if present in the JSON: this process's own OS context
// (see the 2026-08-13 note in main()) - matched case-insensitively.
const ENV_CONTEXT_VARS = new Set(['PATH', 'HOME']);

/**
 * SEC-030 (2026-09-30): the env the runner should give the REAL child.
 *
 * It comes ONLY from MCP_SIDECAR_TARGET_ENV_JSON (a JSON object of strings
 * written by core's McpStdioParamsBuilder.wrapForSidecar), never from this
 * process's own environment. Before SEC-030 core put the tenant's env into
 * this relay's process env and the relay forwarded "everything except the
 * control vars" - which meant a tenant variable also configured THIS node
 * process running inside core. Now the relay's own env holds only the
 * control vars (plus the SDK's non-secret defaults), and nothing from it is
 * forwarded. Returns {env} or {error}. Pure - exported for tests.
 */
function buildForwardEnv(processEnv) {
    const raw = processEnv.MCP_SIDECAR_TARGET_ENV_JSON;
    if (raw === undefined || raw === '') return { env: {} };
    let parsed;
    try {
        parsed = JSON.parse(raw);
    } catch (_e) {
        return { error: 'MCP_SIDECAR_TARGET_ENV_JSON is not valid JSON' };
    }
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
        return { error: 'MCP_SIDECAR_TARGET_ENV_JSON must be a JSON object' };
    }
    const env = {};
    for (const [k, v] of Object.entries(parsed)) {
        if (typeof v !== 'string') return { error: `MCP_SIDECAR_TARGET_ENV_JSON value for '${k}' is not a string` };
        const upper = k.toUpperCase();
        if (ENV_CONTEXT_VARS.has(upper) || CONTROL_VARS.includes(upper)) continue;
        env[k] = v;
    }
    return { env };
}

function relayFail(message) {
    process.stderr.write(`[mcp-stdio-proxy] ${message}\n`);
    process.exitCode = 1;
    process.exit(1);
}

// M6 residual (2026-08-14): the SDK's own StdioClientTransport reads our
// stdout via an unbounded BufferedReader.readLine() - a single unterminated
// JSON-RPC line of unbounded size would grow unbounded inside `core`'s one
// shared 4GB JVM. We can't fix the SDK's reader (no override seam), but we
// own this relay, so we cap bytes-since-last-newline here and fail the
// connection cleanly rather than ever handing the SDK an unbounded line.
// 16 MiB matches framing.js's own MAX_FRAME_BYTES and sits comfortably
// above McpToolOutputSanitizer's 64KB post-parse cap plus JSON/escaping
// overhead, so no legitimate large tool result is at risk.
const MAX_STDOUT_LINE_BYTES = 16 * 1024 * 1024;
let unterminatedStdoutBytes = 0;

function feedStdout(payload) {
    let offset = 0;
    while (offset < payload.length) {
        const nl = payload.indexOf(0x0A, offset);
        const end = nl === -1 ? payload.length : nl + 1;
        unterminatedStdoutBytes += end - offset;
        if (unterminatedStdoutBytes > MAX_STDOUT_LINE_BYTES) {
            relayFail(`MCP server sent a single JSON-RPC line exceeding ${MAX_STDOUT_LINE_BYTES} bytes without a newline - refusing to forward further (malformed or malicious response)`);
            return;
        }
        process.stdout.write(payload.subarray(offset, end));
        if (nl !== -1) unterminatedStdoutBytes = 0;
        offset = end;
    }
}

// See the setKeepAlive call in main() for why this exists and why it must
// stay comfortably under Azure's 4-minute outbound-flow idle timeout.
const SOCKET_KEEPALIVE_IDLE_MS = 60 * 1000;

function parseAddress(raw) {
    // Accepts a bare "host:port" (the expected form) and tolerates an
    // accidental "tcp://host:port"/"http://host:port" scheme prefix rather
    // than failing on it - this is a raw TCP socket, not an HTTP endpoint,
    // despite the *_URL variable name (kept for consistency with this repo's
    // other *_BASE_URL config conventions - see McpStdioSidecarProperties).
    const withoutScheme = String(raw || '').replace(/^[a-z][a-z0-9+.-]*:\/\//i, '');
    const idx = withoutScheme.lastIndexOf(':');
    if (idx <= 0) return null;
    const host = withoutScheme.slice(0, idx);
    const port = Number(withoutScheme.slice(idx + 1));
    if (!host || !Number.isInteger(port) || port <= 0) return null;
    return { host, port };
}

function main() {
    const address = parseAddress(process.env.MCP_SIDECAR_URL);
    const secret = process.env.MCP_SIDECAR_SECRET || '';
    const command = process.env.MCP_SIDECAR_TARGET_COMMAND;
    let args;
    try {
        args = JSON.parse(process.env.MCP_SIDECAR_TARGET_ARGS_JSON || '[]');
    } catch (_e) {
        relayFail('MCP_SIDECAR_TARGET_ARGS_JSON is not valid JSON');
        return;
    }

    if (!address) {
        relayFail(`MCP_SIDECAR_URL is missing or unparseable: ${process.env.MCP_SIDECAR_URL}`);
        return;
    }
    if (!command) {
        relayFail('MCP_SIDECAR_TARGET_COMMAND is not set');
        return;
    }

    // SEC-030: the target env is carried as data in MCP_SIDECAR_TARGET_ENV_JSON,
    // never taken from this process's own env - see buildForwardEnv.
    //
    // Live bug, 2026-08-13: PATH/HOME are NOT part of that registration
    // config - they're THIS process's own OS-level context (this container's
    // `core`, e.g. HOME=/home/agentic). Forwarding them meant the sidecar
    // received `core`'s PATH/HOME instead of its own. They are still dropped
    // (any letter case) as defense-in-depth; server.js's own PATH/HOME also
    // can't be overridden by anything forwarded.
    const built = buildForwardEnv(process.env);
    if (built.error) {
        relayFail(built.error);
        return;
    }
    const forwardEnv = built.env;

    let receivedExit = false;

    const socket = net.createConnection(address.port, address.host);

    // 2026-09-10 live fix - the MCP-connection flap on Azure. An MCP STDIO
    // session is idle for as long as nobody calls one of its tools, and this
    // relay socket leaves `core`'s container, crosses Azure's SNAT/NAT layer
    // and lands on the mcp-stdio-runner VM. Azure Load Balancer silently
    // drops an outbound flow after 4 minutes with no packets - no FIN, no
    // RST, so BOTH ends still believe the socket is open. The next real use
    // then hangs until something times out rather than failing fast.
    //
    // Observed on prod as a perfect 15-minute sawtooth on one tenant's
    // puppeteer server: connect (7 tools advertised) -> idle -> the 5-minute
    // DynamicMcpConnectionManager.healthCheckLiveConnections listTools()
    // blocks for its full 60s and throws TimeoutException ("Did not observe
    // any item or terminal signal within 60000ms") -> registration marked
    // ERROR -> auto-retry reconnects -> repeat, forever. 47 such failures in
    // 12 hours. Nothing was actually wrong with the server or the package.
    //
    // TCP keepalive at 60s keeps the NAT flow's idle timer from ever
    // reaching 4 minutes, which is the whole fix; it also makes a genuinely
    // dead peer surface as a socket error (-> 'close' -> relayFail) instead
    // of a silent blackhole. Node maps the second argument to TCP_KEEPIDLE
    // on Linux. Applied on the accepted socket in mcp-stdio-runner's
    // server.js too - either end's keepalive traffic is enough to hold the
    // flow open, but neither side should depend on the other being fixed.
    socket.setKeepAlive(true, SOCKET_KEEPALIVE_IDLE_MS);

    const reader = new FrameReader(
        (type, payload) => {
            if (type === TYPE_STDOUT) {
                feedStdout(payload);
                return;
            }
            if (type !== TYPE_CONTROL) return;
            let msg;
            try {
                msg = JSON.parse(payload.toString('utf8'));
            } catch (_e) {
                return;
            }
            if (!msg || typeof msg.type !== 'string') return;

            if (msg.type === 'error') {
                relayFail(`sidecar refused this registration: ${msg.message}`);
                return;
            }
            if (msg.type === 'stderr') {
                process.stderr.write(`${msg.line}\n`);
                return;
            }
            if (msg.type === 'exit') {
                receivedExit = true;
                process.stdin.pause();
                // Give a queued stdout write a moment to flush before we exit -
                // mirrors the sidecar's own post-exit-frame grace period.
                setTimeout(() => process.exit(typeof msg.code === 'number' ? msg.code : 0), 50);
            }
            // 'spawned' is purely informational - nothing to do.
        },
        (err) => relayFail(`frame error: ${err.message}`)
    );

    socket.on('connect', () => {
        socket.write(encodeFrame(TYPE_CONTROL, JSON.stringify({ command, args, env: forwardEnv, secret, tenantId: process.env.MCP_SIDECAR_TENANT_ID || undefined })));
        process.stdin.on('data', (chunk) => {
            if (socket.writable) socket.write(encodeFrame(TYPE_STDIN, chunk));
        });
    });

    socket.on('data', (chunk) => reader.push(chunk));

    socket.on('error', (err) => {
        if (!receivedExit) relayFail(`cannot reach mcp-stdio-runner at ${process.env.MCP_SIDECAR_URL}: ${err.message}`);
    });

    socket.on('close', () => {
        if (!receivedExit) relayFail('connection to mcp-stdio-runner closed unexpectedly before the child exited');
    });

    const onSignal = () => {
        if (socket.writable) {
            try {
                socket.write(encodeFrame(TYPE_CONTROL, JSON.stringify({ type: 'kill' })));
            } catch (_e) {
                // Best-effort - the sidecar also reaps on plain socket close.
            }
        }
        socket.end();
        process.exit(0);
    };
    process.on('SIGTERM', onSignal);
    process.on('SIGINT', onSignal);
}

if (require.main === module) {
    main();
}

module.exports = { buildForwardEnv, parseAddress };
