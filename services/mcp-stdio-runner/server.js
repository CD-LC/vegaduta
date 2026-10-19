'use strict';

// mcp-stdio-runner - spawns the REAL interpreter/package for a dynamic MCP
// STDIO registration (`npx <pkg>`, `uvx <pkg>`, ...) inside THIS container,
// never inside `core`. See ../../docs/architecture/MCP-STDIO-SANDBOXING-DESIGN.md for the
// full threat model this exists to close, and README.md in this directory
// for the wire protocol summary.
//
// Deliberately zero npm dependencies: `net`/`http`/`child_process`/`crypto`
// only. A component built specifically to contain the blast radius of a
// compromised third-party package should not itself pull in a third-party
// package.
//
// Lifecycle model: a spawned child's entire lifetime is tied to the one TCP
// connection that spawned it. There is no heartbeat/TTL - when the socket
// closes (the relay's own process died, e.g. because the JVM that owns it as
// a local Process died, exactly as today's direct-spawn model), the child is
// killed immediately. See DynamicMcpConnectionManager.wrapForSidecar's
// javadoc for why that inherited-lifecycle chain is correct and requires no
// new orphan-reaping logic.

const net = require('net');
const http = require('http');
const crypto = require('crypto');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawn } = require('child_process');
const { TYPE_CONTROL, TYPE_STDOUT, TYPE_STDIN, encodeFrame, FrameReader } = require('./framing');

const TCP_PORT = Number(process.env.MCP_STDIO_RUNNER_PORT || 8097);
const HEALTH_PORT = Number(process.env.MCP_STDIO_RUNNER_HEALTH_PORT || 8098);
const KILL_GRACE_MS = Number(process.env.MCP_STDIO_RUNNER_KILL_GRACE_MS || 3000);

// ---------------------------------------------------------------------------
// Shared secret (SEC-012). Preferred source: MCP_STDIO_SIDECAR_SHARED_SECRET_FILE
// (a mounted secret file, so the value is never in any process environment).
// The plain env var still works; either way it is deleted from process.env
// right after it is read, so nothing that later copies process.env (a
// debugging `spawn` without `env`, a crash reporter, a dependency) can hand
// it on. HONEST LIMIT: deleting it from process.env does NOT erase it from
// /proc/<runner-pid>/environ (the kernel keeps the original env block), which
// a SAME-uid child can read. Only _FILE, or per-child uids (below), close that.
// ---------------------------------------------------------------------------
const SECRET_ENV = 'MCP_STDIO_SIDECAR_SHARED_SECRET';
const SECRET_FILE_ENV = 'MCP_STDIO_SIDECAR_SHARED_SECRET_FILE';

/**
 * Pure: resolves the shared secret from `env` ({secret, source, error}).
 * _FILE wins when both are set; an unreadable/empty file yields secret ''
 * (fail closed - every spawn is then refused), never a fallback to the env var.
 */
function loadSharedSecret(env, readFile = (p) => fs.readFileSync(p, 'utf8')) {
    const file = env[SECRET_FILE_ENV];
    if (file) {
        try {
            const secret = String(readFile(file)).replace(/\r?\n$/, '');
            if (!secret) return { secret: '', source: 'file', error: `${SECRET_FILE_ENV} (${file}) is empty` };
            return { secret, source: 'file', error: null };
        } catch (e) {
            return { secret: '', source: 'file', error: `cannot read ${SECRET_FILE_ENV} (${file}): ${e.message}` };
        }
    }
    return { secret: env[SECRET_ENV] || '', source: env[SECRET_ENV] ? 'env' : 'none', error: null };
}

const SECRET_LOAD = loadSharedSecret(process.env);
const SHARED_SECRET = SECRET_LOAD.secret;
delete process.env[SECRET_ENV];

function envInt(name, dflt) {
    const n = Number(process.env[name]);
    return Number.isFinite(n) && n >= 0 && process.env[name] !== '' ? Math.floor(n) : dflt;
}
// Resource limits. 0 disables a limit.
//
// ALL CAPS ARE PER-PROCESS: counters live in this process's memory, so with N
// runner replicas the effective ceiling is N x the cap, and a restart resets
// the counts. They are a blast-radius guard against runaway growth, not an
// exact fleet-wide quota.
//
// Defaults are deliberately non-lossy:
//  - MAX_CHILDREN=100. Each core replica holds one child per enabled
//    registration and reconnectEnabledOnStartup respawns all of them at boot;
//    during a rolling deploy old + new core replicas both hold children, so
//    demand briefly doubles. 100 is well above that burst. A request over the
//    cap is QUEUED (up to QUEUE_WAIT_MS) waiting for a slot instead of being
//    refused outright, so a boot burst drains rather than pushing
//    registrations into core's retry-exhausted ERROR state.
//  - MAX_CHILDREN_PER_TENANT=0 (off, opt-in): needs core to send
//    MCP_SIDECAR_TENANT_ID; requests without one share the 'unknown' bucket,
//    which is exempt from the per-tenant cap.
//  - NODE heap cap and idle reaper are OFF (opt-in): either can kill a
//    legitimate heavy or quiet MCP server. MAX_LIFETIME is also off.
const MAX_CHILDREN = envInt('MCP_STDIO_RUNNER_MAX_CHILDREN', 100);
const MAX_CHILDREN_PER_TENANT = envInt('MCP_STDIO_RUNNER_MAX_CHILDREN_PER_TENANT', 0);
const QUEUE_WAIT_MS = envInt('MCP_STDIO_RUNNER_QUEUE_WAIT_MS', 60 * 1000);
const IDLE_TTL_MS = envInt('MCP_STDIO_RUNNER_IDLE_TTL_MS', 0);
const MAX_LIFETIME_MS = envInt('MCP_STDIO_RUNNER_MAX_LIFETIME_MS', 0);
const SWEEP_INTERVAL_MS = envInt('MCP_STDIO_RUNNER_SWEEP_INTERVAL_MS', 30 * 1000);
const NODE_MAX_OLD_SPACE_MB = envInt('MCP_STDIO_RUNNER_NODE_MAX_OLD_SPACE_MB', 0);
const UNKNOWN_TENANT = 'unknown';

// Live children, keyed by a per-connection id. Process-local: each replica
// enforces its own caps.
const children = new Map();
let nextChildId = 1;
let draining = false;
// Spawn requests waiting for a free slot (FIFO). Each: {tenant, go, fail, timer}.
const waiters = [];

function releaseChild(id) {
    children.delete(id);
    pumpWaiters();
}

function pumpWaiters() {
    for (let i = 0; i < waiters.length; ) {
        const w = waiters[i];
        if (capViolation(w.tenant) === null) {
            waiters.splice(i, 1);
            clearTimeout(w.timer);
            w.go();
        } else {
            i++;
        }
    }
}

function enqueueWaiter(tenant, go, fail) {
    const w = { tenant, go, fail, timer: null };
    w.timer = setTimeout(() => {
        const i = waiters.indexOf(w);
        if (i >= 0) waiters.splice(i, 1);
        fail(capViolation(tenant) || 'timed out waiting for a runner slot');
    }, QUEUE_WAIT_MS);
    waiters.push(w);
    return () => {
        const i = waiters.indexOf(w);
        if (i >= 0) waiters.splice(i, 1);
        clearTimeout(w.timer);
    };
}

function normalizeTenant(raw) {
    if (typeof raw !== 'string') return UNKNOWN_TENANT;
    const t = raw.trim();
    return t && t.length <= 128 ? t : UNKNOWN_TENANT;
}

function tenantCounts() {
    const out = {};
    for (const c of children.values()) out[c.tenant] = (out[c.tenant] || 0) + 1;
    return out;
}

/** Returns an error message if a new child for this tenant must be refused, else null. */
function capViolation(tenant) {
    if (draining) return 'runner is shutting down';
    if (MAX_CHILDREN > 0 && children.size >= MAX_CHILDREN) {
        return `runner at capacity (${MAX_CHILDREN} concurrent MCP children)`;
    }
    if (MAX_CHILDREN_PER_TENANT > 0 && tenant !== UNKNOWN_TENANT) {
        let n = 0;
        for (const c of children.values()) if (c.tenant === tenant) n++;
        if (n >= MAX_CHILDREN_PER_TENANT) {
            return `tenant at capacity (${MAX_CHILDREN_PER_TENANT} concurrent MCP children)`;
        }
    }
    return null;
}

// ---------------------------------------------------------------------------
// Spawn policy - a defence-in-depth MIRROR of core's launcher guard,
// core/mcp/src/main/java/com/agenticai/core/mcp/McpStdioCommandGuard.java
// (tests: core/mcp/src/test/java/com/agenticai/core/mcp/McpStdioCommandGuardTest.java).
//
// Core's guard runs first (registration save time AND spawn time); this is
// the second, independent gate so a bug in - or a caller that bypasses -
// core's guard is not the only thing between a request and a spawn
// (finding SVC-004: the runner used to check the launcher name only, so any
// caller holding the shared secret could send `node -e <js>`, `python3 -c`,
// or NODE_OPTIONS=--import data:... and get arbitrary code run here).
//
// The rules below are the SAME policy as the Java class - launcher
// allowlist, shell-metacharacter ban on args, blocked long flags (exact +
// `--flag=value`, case-insensitive), the --inspect* prefix family, blocked
// short flags (-e/-c everywhere, -p/-r for node only; attached-value and
// short-cluster forms), env-name shape, env-name denylist (case-insensitive)
// and env prefix denylist (LD_/DYLD_/BASH_FUNC_), NUL in env values. A change
// to either side MUST be made to both, and both test suites extended.
//
// Deliberate divergences (all strictly stronger, or neutral):
//   - ALLOWED_COMMANDS is an exact, fixed set: no Windows .cmd/.exe suffix
//     tolerance (this runner is a Linux container) and no operator-extendable
//     list (core's app.mcp.stdio-launcher-allowlist has no equivalent here).
//   - PATH/HOME (any letter case) from the caller are NOT rejected but are
//     replaced by the runner's own values (PATH: this container's; HOME: the
//     connection's private sandbox, SEC-012 - same for the other
//     RUNNER_OWNED_ENV names) - core's guard rejects PATH in a
//     registration's env, but the SDK's default inherited env still reaches
//     the relay, and core/mcp-stdio-proxy/proxy.js only strips the exact-case
//     `PATH`/`HOME` (a Windows-hosted core would forward `Path`). Either way a
//     caller PATH/HOME never reaches the child. See handleSpawnRequest.
//   - A non-string env value is rejected (core rejects null; JSON can carry
//     numbers/objects here, which previously were silently dropped).
// ---------------------------------------------------------------------------
const ALLOWED_COMMANDS = new Set(['npx', 'node', 'uvx', 'uv', 'python3', 'python']);

// McpStdioCommandGuard.FORBIDDEN_CHARS
const FORBIDDEN_CHARS = ['`', '&', ';', '|', '<', '>', '\n', '\r', '\0'];

// McpStdioCommandGuard.BLOCKED_LONG_FLAGS - matched case-insensitively, on the
// part before the first '=' (so `--eval=...` and `--IMPORT` are both caught).
const BLOCKED_LONG_FLAGS = new Set([
    '--eval',
    '--command',
    '--print',
    '--import',
    '--require',
    '--loader',
    '--experimental-loader',
    '--node-options',
]);

// McpStdioCommandGuard.BLOCKED_LONG_FLAG_PREFIXES (--inspect, --inspect-brk, ...)
const BLOCKED_LONG_FLAG_PREFIXES = ['--inspect'];

// McpStdioCommandGuard.BLOCKED_SHORT_FLAGS - case-SENSITIVE (python -E/-P/-B
// are legitimate hardening flags).
const BLOCKED_SHORT_FLAGS = new Set(['e', 'c']);

// McpStdioCommandGuard.BLOCKED_SHORT_FLAGS_NODE_ONLY - node -p (--print) and
// -r (--require); NOT global because `npx -p <pkg>` / `uvx -p 3.12` are fine.
const BLOCKED_SHORT_FLAGS_NODE_ONLY = new Set(['p', 'r']);

// McpStdioCommandGuard.MAX_SHORT_CLUSTER_LENGTH
const MAX_SHORT_CLUSTER_LENGTH = 6;

// McpStdioCommandGuard.BLOCKED_ENV_NAMES - matched case-insensitively.
// Already covers NODE_OPTIONS, NODE_PATH, PYTHONPATH, PYTHONSTARTUP,
// PYTHONHOME; LD_PRELOAD / LD_LIBRARY_PATH / DYLD_* are covered by the prefix
// list below, exactly as in core.
const BLOCKED_ENV_NAMES = new Set([
    'NODE_OPTIONS',
    'NODE_REPL_EXTERNAL_MODULE',
    'NODE_PATH',
    'NPM_CONFIG_NODE_OPTIONS',
    'NPM_CONFIG_SCRIPT_SHELL',
    'PYTHONSTARTUP',
    'PYTHONPATH',
    'PYTHONHOME',
    'PYTHONUSERBASE',
    'PYTHONWARNINGS',
    'PYTHONINSPECT',
    'PYTHONEXECUTABLE',
    'BASH_ENV',
    'ENV',
    'SHELLOPTS',
    'PERL5OPT',
    'PERL5LIB',
    'RUBYOPT',
    'JAVA_TOOL_OPTIONS',
    '_JAVA_OPTIONS',
    'JDK_JAVA_OPTIONS',
    'GIT_SSH',
    'GIT_SSH_COMMAND',
    'GIT_EXTERNAL_DIFF',
    // 'PATH' is in core's list too - handled here by override instead of
    // rejection; see the divergence note above and RUNNER_OWNED_ENV.
]);

// McpStdioCommandGuard.BLOCKED_ENV_PREFIXES
const BLOCKED_ENV_PREFIXES = ['LD_', 'DYLD_', 'BASH_FUNC_'];

// McpStdioCommandGuard.ENV_NAME - a POSIX env name and nothing else.
const ENV_NAME = /^[A-Za-z_][A-Za-z0-9_]*$/;

function previewOf(s) {
    const flat = String(s).replace(/[\r\n]/g, ' ');
    return flat.length > 40 ? `${flat.slice(0, 40)}...` : flat;
}

function findForbidden(value) {
    for (const c of FORBIDDEN_CHARS) {
        if (value.includes(c)) {
            if (c === '\n' || c === '\r') return 'newline';
            if (c === '\0') return 'NUL';
            return `'${c}'`;
        }
    }
    return null;
}

/** Port of McpStdioCommandGuard.blockedFlagIn - the blocked flag inside `arg`, or null. */
function blockedFlagIn(arg, launcher) {
    if (arg.length < 2 || arg[0] !== '-') return null;
    const eq = arg.indexOf('=');
    const token = eq >= 0 ? arg.slice(0, eq) : arg;
    if (token.startsWith('--')) {
        const lower = token.toLowerCase();
        if (BLOCKED_LONG_FLAGS.has(lower)) return token;
        for (const prefix of BLOCKED_LONG_FLAG_PREFIXES) {
            if (lower.startsWith(prefix)) return token;
        }
        return null;
    }
    const blocked = launcher === 'node'
        ? new Set([...BLOCKED_SHORT_FLAGS, ...BLOCKED_SHORT_FLAGS_NODE_ONLY])
        : BLOCKED_SHORT_FLAGS;
    const run = /^-([A-Za-z]*)/.exec(token)[1];
    if (run.length === 0) return null;
    if (blocked.has(run[0])) return `-${run[0]}`;
    const wholeTokenIsLetters = run.length === token.length - 1;
    if (wholeTokenIsLetters && run.length <= MAX_SHORT_CLUSTER_LENGTH) {
        for (let i = 1; i < run.length; i++) {
            if (blocked.has(run[i])) return `-${run[i]} (in the cluster '${token}')`;
        }
    }
    return null;
}

/** Port of McpStdioCommandGuard.checkEnv. Null when allowed, else the reason. */
function checkEnv(env) {
    if (env === undefined || env === null) return null;
    if (typeof env !== 'object' || Array.isArray(env)) return 'env not allowed: env must be an object of string values';
    for (const [name, value] of Object.entries(env)) {
        if (!name || !name.trim()) return 'env not allowed: environment variable names must not be empty';
        if (!ENV_NAME.test(name)) {
            return `env not allowed: '${previewOf(name)}' is not a valid name (letters, digits and underscore only, not starting with a digit)`;
        }
        const upper = name.toUpperCase();
        if (BLOCKED_ENV_NAMES.has(upper) || BLOCKED_ENV_PREFIXES.some((p) => upper.startsWith(p))) {
            return `env not allowed: '${name}' controls how the runtime loads code - only pass the server's own credentials and configuration`;
        }
        if (typeof value !== 'string') return `env not allowed: '${name}' must have a string value`;
        if (value.includes('\0')) return `env not allowed: '${name}' contains a NUL character`;
    }
    return null;
}

/**
 * Full spawn-request policy (port of McpStdioCommandGuard.check). Returns null
 * when the request may be spawned, otherwise the error message sent back to
 * the caller. Pure - no I/O.
 */
function validateSpawnRequest(command, args, env) {
    if (typeof command !== 'string' || !ALLOWED_COMMANDS.has(command)) {
        return `command not allowed: ${command}`;
    }
    if (!Array.isArray(args) || !args.every((a) => typeof a === 'string')) {
        return 'args must be an array of strings';
    }
    for (const arg of args) {
        const bad = findForbidden(arg);
        if (bad) return `arg not allowed: '${previewOf(arg)}' contains a forbidden shell character (${bad})`;
        if (arg.includes('$(')) return `arg not allowed: '${previewOf(arg)}' contains a forbidden shell substitution ($()`;
        const flag = blockedFlagIn(arg, command);
        if (flag) {
            return `arg not allowed: '${previewOf(arg)}' is an inline-code/module-loading flag (${flag}); `
                + 'MCP servers must be a package or script entrypoint, not inline code';
        }
    }
    return checkEnv(env);
}

// Image-level (never secret) settings the browser variant of this image
// (Dockerfile WITH_BROWSERS=true) bakes in and every spawned child must see:
// where the pre-installed Playwright/Puppeteer browsers live, and
// server-puppeteer's Docker-aware launch switch (DOCKER_CONTAINER - see the
// Dockerfile). Handed to the child ONLY when set in this container's own env,
// as defaults a registration's env may override. Nothing else from this
// process's env ever reaches a child (see handleSpawnRequest).
const IMAGE_ENV_PASSTHROUGH = ['PLAYWRIGHT_BROWSERS_PATH', 'PUPPETEER_CACHE_DIR', 'DOCKER_CONTAINER'];

// ---------------------------------------------------------------------------
// Per-connection sandbox (SEC-012).
//
// Before: every child shared ONE $HOME (/home/mcprunner) and therefore one
// npm cache (~/.npm), one uv cache (~/.cache/uv) and one ~/.config. Tenant A's
// package could plant files there (poisoned cache entries, an .npmrc, a
// sitecustomize under a uv tool env) that tenant B's `npx`/`uvx` then
// executed, or read B's leftovers. Now each connection gets a fresh private
// directory (0700) holding its own HOME, TMPDIR, npm/uv caches and XDG dirs,
// deleted when the child goes away. The caller cannot redirect any of these:
// RUNNER_OWNED_ENV names in the caller's env (any letter case) are dropped.
//
// Cost (deliberate): no cache is shared between connections, so every
// `npx -y <pkg>` / `uvx <pkg>` downloads its package on each fresh spawn, and
// the browser image's build-time npx pre-warm (Dockerfile) is no longer used.
//
// Optional per-child uid (MCP_RUNNER_UID_RANGE, e.g. "200000-200999"): each
// connection's child runs as its own uid from that range, so children cannot
// read each other's files, signal each other, or read /proc/<pid>/environ of
// the runner or of another child. Enabled ONLY when the runner actually holds
// CAP_SETUID, CAP_SETGID, CAP_CHOWN and CAP_KILL (in practice: container
// `user: root` plus cap_add of exactly those four); otherwise every child
// stays the runner's own uid and this is logged at startup.
// ---------------------------------------------------------------------------
const SESSION_ROOT = process.env.MCP_RUNNER_SESSION_ROOT || path.join(os.tmpdir(), 'mcp-runner-sessions');
const SESSION_PREFIX = 'conn-';

// Always set by the runner; a caller-supplied name matching one of these
// (case-insensitively) is ignored, never forwarded.
const RUNNER_OWNED_ENV = new Set([
    'PATH',
    'HOME',
    'TMPDIR',
    'TMP',
    'TEMP',
    'NPM_CONFIG_CACHE',
    'NPM_CONFIG_USERCONFIG',
    'NPM_CONFIG_GLOBALCONFIG',
    'NPM_CONFIG_PREFIX',
    'UV_CACHE_DIR',
    'UV_TOOL_DIR',
    'UV_TOOL_BIN_DIR',
    'UV_PYTHON_INSTALL_DIR',
    'UV_PYTHON_BIN_DIR',
    'PIP_CACHE_DIR',
    'XDG_CACHE_HOME',
    'XDG_CONFIG_HOME',
    'XDG_DATA_HOME',
    'XDG_STATE_HOME',
    'XDG_RUNTIME_DIR',
]);

/** The directory layout of one connection's sandbox (pure). */
function sessionLayout(dir) {
    const home = path.join(dir, 'home');
    return {
        dir,
        home,
        tmp: path.join(dir, 'tmp'),
        npmCache: path.join(home, '.npm'),
        cache: path.join(home, '.cache'),
        config: path.join(home, '.config'),
        data: path.join(home, '.local', 'share'),
        state: path.join(home, '.local', 'state'),
    };
}

/**
 * Pure: the complete env for a child. `callerEnv` has already passed
 * checkEnv. Image passthroughs are overridable defaults; RUNNER_OWNED_ENV
 * names always come from `layout` / `baseEnv`, whatever the caller sent.
 */
function buildChildEnv(callerEnv, layout, baseEnv = process.env) {
    const childEnv = {};
    for (const k of IMAGE_ENV_PASSTHROUGH) {
        if (baseEnv[k]) childEnv[k] = baseEnv[k];
    }
    if (callerEnv) {
        for (const [k, v] of Object.entries(callerEnv)) {
            if (!RUNNER_OWNED_ENV.has(k.toUpperCase())) childEnv[k] = v;
        }
    }
    Object.assign(childEnv, {
        PATH: baseEnv.PATH || '',
        HOME: layout.home,
        TMPDIR: layout.tmp,
        npm_config_cache: layout.npmCache,
        UV_CACHE_DIR: path.join(layout.cache, 'uv'),
        XDG_CACHE_HOME: layout.cache,
        XDG_CONFIG_HOME: layout.config,
        XDG_DATA_HOME: layout.data,
        XDG_STATE_HOME: layout.state,
    });
    return childEnv;
}

/** Pure: "200000-200999" -> {start, end}; null when unset; throws when malformed/unsafe. */
function parseUidRange(spec, ownUid = null) {
    if (spec === undefined || spec === null || String(spec).trim() === '') return null;
    const m = /^\s*(\d+)\s*-\s*(\d+)\s*$/.exec(String(spec));
    if (!m) throw new Error(`MCP_RUNNER_UID_RANGE must look like "200000-200999", got "${spec}"`);
    const start = Number(m[1]);
    const end = Number(m[2]);
    if (start < 10000) throw new Error('MCP_RUNNER_UID_RANGE must start at 10000 or above (clear of real system/user ids)');
    if (end < start) throw new Error('MCP_RUNNER_UID_RANGE end is below its start');
    if (end > 2147483646) throw new Error('MCP_RUNNER_UID_RANGE end is out of range');
    if (ownUid !== null && ownUid >= start && ownUid <= end) {
        throw new Error(`MCP_RUNNER_UID_RANGE contains the runner's own uid ${ownUid}`);
    }
    return { start, end };
}

// Linux capability bit numbers (include/uapi/linux/capability.h).
const CAP_BITS = { CAP_CHOWN: 0, CAP_KILL: 5, CAP_SETGID: 6, CAP_SETUID: 7 };
const UID_MODE_CAPS = ['CAP_SETUID', 'CAP_SETGID', 'CAP_CHOWN', 'CAP_KILL'];

/** Pure: which of `wanted` are missing from the CapEff line of a /proc/<pid>/status text. */
function missingCaps(statusText, wanted = UID_MODE_CAPS) {
    const m = /^CapEff:\s*([0-9a-fA-F]+)\s*$/m.exec(statusText || '');
    if (!m) return wanted.slice();
    const eff = BigInt(`0x${m[1]}`);
    return wanted.filter((c) => ((eff >> BigInt(CAP_BITS[c])) & 1n) === 0n);
}

/**
 * uid allocator for the per-child uid mode. Least-recently-released first,
 * so a uid is reused as late as possible. A uid whose processes could not all
 * be killed on release is quarantined (never handed out again this run).
 */
function createUidPool({ start, end }) {
    const free = [];
    for (let u = start; u <= end; u++) free.push(u);
    const inUse = new Set();
    const quarantined = new Set();
    return {
        acquire() {
            const uid = free.shift();
            if (uid === undefined) return null;
            inUse.add(uid);
            return uid;
        },
        release(uid, { clean = true } = {}) {
            if (!inUse.delete(uid)) return;
            if (clean) free.push(uid);
            else quarantined.add(uid);
        },
        stats() {
            return { free: free.length, inUse: inUse.size, quarantined: quarantined.size };
        },
    };
}

/** Linux only: pids (other than ours) whose real uid is `uid`, via /proc. Best-effort. */
function pidsOfUid(uid) {
    const pids = [];
    let entries;
    try {
        entries = fs.readdirSync('/proc');
    } catch (_e) {
        return pids;
    }
    for (const name of entries) {
        if (!/^\d+$/.test(name)) continue;
        try {
            const status = fs.readFileSync(`/proc/${name}/status`, 'utf8');
            if (/^State:\s+[ZX]/m.test(status)) continue; // zombie/dead: holds nothing, can't be killed
            const m = /^Uid:\s+(\d+)/m.exec(status);
            if (m && Number(m[1]) === uid) pids.push(Number(name));
        } catch (_e) {
            // Exited meanwhile, or hidden by hidepid.
        }
    }
    return pids;
}

function resolveUidMode() {
    let range;
    try {
        range = parseUidRange(process.env.MCP_RUNNER_UID_RANGE,
            typeof process.getuid === 'function' ? process.getuid() : null);
    } catch (e) {
        return { enabled: false, reason: `${e.message} - per-child uid isolation OFF` };
    }
    if (!range) return { enabled: false, reason: 'MCP_RUNNER_UID_RANGE unset - per-child uid isolation OFF, every child runs as the runner uid' };
    if (process.platform !== 'linux') return { enabled: false, reason: 'per-child uid isolation needs Linux - OFF' };
    let status = '';
    try {
        status = fs.readFileSync('/proc/self/status', 'utf8');
    } catch (_e) {
        // Treated as no capabilities.
    }
    const missing = missingCaps(status);
    if (missing.length) {
        return {
            enabled: false,
            reason: `MCP_RUNNER_UID_RANGE is set but the runner lacks ${missing.join(', ')} - per-child uid isolation OFF, every child runs as the runner uid`,
        };
    }
    return { enabled: true, pool: createUidPool(range), reason: `per-child uid isolation ON, uids ${range.start}-${range.end}` };
}

const UID_MODE = resolveUidMode();

function ensureSessionRoot() {
    fs.mkdirSync(SESSION_ROOT, { recursive: true, mode: UID_MODE.enabled ? 0o711 : 0o700 });
    // mkdir's mode is ignored for an existing dir and masked by umask.
    try {
        fs.chmodSync(SESSION_ROOT, UID_MODE.enabled ? 0o711 : 0o700);
    } catch (_e) {
        // Not ours (e.g. a pre-created mount) - left as is.
    }
}

/** Creates one connection's private sandbox; chowned to `uid` in uid mode. */
function createSession(uid) {
    ensureSessionRoot();
    const dir = fs.mkdtempSync(path.join(SESSION_ROOT, SESSION_PREFIX));
    const layout = sessionLayout(dir);
    for (const d of [layout.home, layout.tmp, layout.npmCache, layout.cache, layout.config, layout.data, layout.state]) {
        fs.mkdirSync(d, { recursive: true, mode: 0o700 });
    }
    if (uid !== null) {
        const chownTree = (p) => {
            fs.chownSync(p, uid, uid);
            if (fs.lstatSync(p).isDirectory()) {
                for (const e of fs.readdirSync(p)) chownTree(path.join(p, e));
            }
        };
        chownTree(dir);
    }
    return layout;
}

/**
 * Removes a sandbox. In uid mode the files belong to the child's uid, which
 * the runner (no CAP_DAC_OVERRIDE) cannot delete, so the contents are removed
 * by a short `rm` run AS that uid and only the emptied top dir by the runner.
 */
function removeSession(layout, uid, done = () => {}) {
    if (!layout) return done();
    if (uid === null) {
        try {
            fs.rmSync(layout.dir, { recursive: true, force: true, maxRetries: 3 });
        } catch (e) {
            log('could not remove session dir', layout.dir, e.message);
        }
        return done();
    }
    let rm;
    try {
        rm = spawn('/bin/rm', ['-rf', '--', 'home', 'tmp'], {
            cwd: layout.dir, uid, gid: uid, env: { PATH: '/usr/bin:/bin' }, stdio: 'ignore',
        });
    } catch (e) {
        log('could not remove session dir', layout.dir, e.message);
        return done();
    }
    rm.on('error', (e) => {
        log('could not remove session dir', layout.dir, e.message);
        done();
    });
    rm.on('close', () => {
        try {
            fs.rmdirSync(layout.dir);
        } catch (e) {
            log('could not remove session dir', layout.dir, e.message);
        }
        done();
    });
}

/** Startup: drop sandboxes left by a previous (crashed) run. Best-effort. */
function sweepStaleSessions() {
    let entries = [];
    try {
        entries = fs.readdirSync(SESSION_ROOT);
    } catch (_e) {
        return;
    }
    for (const name of entries) {
        if (!name.startsWith(SESSION_PREFIX)) continue;
        try {
            fs.rmSync(path.join(SESSION_ROOT, name), { recursive: true, force: true });
        } catch (e) {
            log('stale session dir not removed (owned by a child uid?):', name, e.message);
        }
    }
}

function log(...args) {
    console.log(new Date().toISOString(), '[mcp-stdio-runner]', ...args);
}

function timingSafeEqualStrings(a, b) {
    const bufA = Buffer.from(String(a == null ? '' : a));
    const bufB = Buffer.from(String(b == null ? '' : b));
    if (bufA.length !== bufB.length) {
        // Compare against itself so a length mismatch still costs a real
        // comparison, rather than returning early on length alone.
        crypto.timingSafeEqual(bufA, bufA);
        return false;
    }
    return crypto.timingSafeEqual(bufA, bufB);
}

// See handleConnection's setKeepAlive call - must stay well under Azure
// Load Balancer's 4-minute idle timeout for an outbound flow.
const MAX_QUEUED_STDIN_BYTES = 1024 * 1024;
const SOCKET_KEEPALIVE_IDLE_MS = 60 * 1000;

function handleConnection(socket) {
    // 2026-09-10: the runner half of the MCP-connection flap fix - see the
    // long comment on the matching setKeepAlive in core/mcp-stdio-proxy/
    // proxy.js. An idle MCP session's relay socket was being dropped by
    // Azure's NAT after 4 minutes with no FIN/RST, so core's next listTools()
    // hung for its full 60s timeout and the registration was marked ERROR on
    // a 15-minute loop. Keepalive here is redundant with the proxy's own
    // (traffic from either end resets the same flow timer) and deliberately
    // so: this runner also serves clients we don't control the other end of.
    socket.setKeepAlive(true, SOCKET_KEEPALIVE_IDLE_MS);
    let child = null;
    let spawned = false;
    let killTimer = null;
    let stderrBuf = '';
    let session = null; // sessionLayout of this connection's sandbox
    let childUid = null; // per-child uid (uid mode only)
    let released = false;
    let entry = null;
    let pending = false; // queued for a slot; ignore further control frames
    let cancelWait = null;
    // stdin frames that arrive while queued (the proxy writes them right after
    // the control frame, before we reply); flushed in order once the child spawns.
    let queuedStdin = [];
    let queuedStdinBytes = 0;

    const send = (type, payload) => {
        if (socket.writable) socket.write(encodeFrame(type, payload));
    };
    const sendControl = (obj) => send(TYPE_CONTROL, JSON.stringify(obj));

    // POSIX: the child leads its own process group (spawn detached), so a
    // signal to -pid also reaches whatever it forked (npx -> node, uvx ->
    // python). Falls back to the direct child elsewhere (Windows dev hosts).
    const signalChild = (sig) => {
        if (!child) return;
        if (process.platform !== 'win32' && child.pid) {
            try {
                process.kill(-child.pid, sig);
                return;
            } catch (_e) {
                // Group already gone - fall through to the direct child.
            }
        }
        try {
            child.kill(sig);
        } catch (_e) {
            // Already gone.
        }
    };

    const killChild = () => {
        if (!child || child.exitCode !== null || child.signalCode !== null) return;
        signalChild('SIGTERM');
        killTimer = setTimeout(() => signalChild('SIGKILL'), KILL_GRACE_MS);
        if (typeof killTimer.unref === 'function') killTimer.unref();
    };

    // Runs once, after the child has exited (or was never started): kills any
    // leftover process of the child's group/uid, deletes the sandbox, and
    // returns the uid to the pool (quarantined if something survived).
    const releaseSandbox = () => {
        if (released) return;
        released = true;
        if (killTimer) clearTimeout(killTimer);
        if (child && child.pid && process.platform !== 'win32') {
            try {
                process.kill(-child.pid, 'SIGKILL');
            } catch (_e) {
                // Group already empty - the normal case.
            }
        }
        const uid = childUid;
        if (uid === null) {
            removeSession(session, null);
            return;
        }
        // Anything still running as this uid escaped the process group
        // (setsid/double fork): kill it, give the kernel a moment, re-check.
        for (const pid of pidsOfUid(uid)) {
            try {
                process.kill(pid, 'SIGKILL');
            } catch (_e) {
                // Raced with its exit.
            }
        }
        const settle = setTimeout(() => {
            const uidClean = pidsOfUid(uid).length === 0;
            if (!uidClean) log('uid', uid, 'still has live processes after release - quarantined');
            removeSession(session, uid, () => UID_MODE.pool.release(uid, { clean: uidClean }));
        }, 250);
        if (typeof settle.unref === 'function') settle.unref();
    };

    const cleanup = () => {
        if (killTimer) clearTimeout(killTimer);
        killChild();
    };

    function handleSpawnRequest(payload) {
        let req;
        try {
            req = JSON.parse(payload.toString('utf8'));
        } catch (_e) {
            sendControl({ type: 'error', message: 'invalid control frame JSON' });
            socket.end();
            return;
        }
        const { command, args, env, secret, tenantId } = req || {};

        if (!SHARED_SECRET || !timingSafeEqualStrings(secret, SHARED_SECRET)) {
            sendControl({ type: 'error', message: 'unauthorized' });
            socket.end();
            return;
        }
        // Full McpStdioCommandGuard mirror - launcher, args (eval/module-
        // loading flags, shell metacharacters) and env denylist. Rejected,
        // never silently stripped.
        const rejection = validateSpawnRequest(command, args, env);
        if (rejection) {
            log('rejected spawn request:', rejection);
            sendControl({ type: 'error', message: rejection });
            socket.end();
            return;
        }

        const tenant = normalizeTenant(tenantId);
        const violation = capViolation(tenant);
        if (violation) {
            if (draining || QUEUE_WAIT_MS <= 0) {
                log('refusing spawn for tenant', tenant, '-', violation);
                sendControl({ type: 'error', message: violation });
                socket.end();
                return;
            }
            log('queueing spawn for tenant', tenant, '-', violation);
            pending = true;
            cancelWait = enqueueWaiter(
                tenant,
                () => {
                    pending = false;
                    cancelWait = null;
                    startChild(command, args, env, tenant);
                },
                (msg) => {
                    pending = false;
                    cancelWait = null;
                    log('refusing queued spawn for tenant', tenant, '-', msg);
                    sendControl({ type: 'error', message: msg });
                    socket.end();
                }
            );
            return;
        }
        startChild(command, args, env, tenant);
    }

    function startChild(command, args, env, tenant) {
        // PATH/HOME always come from THIS container's own environment, never
        // the caller-supplied one - the caller (core/mcp-stdio-proxy) used to
        // forward whatever PATH/HOME ITS OWN process happened to have (core's
        // filesystem layout - e.g. HOME=/home/agentic - not this container's),
        // and this method applied caller env on top, so those leaked-through
        // values silently WON. Confirmed live, 2026-08-13: a spawned `uv`
        // resolved Python against system 3.11 instead of the managed 3.12
        // this container actually has, because HOME pointed at a directory
        // (core's /home/agentic) that doesn't even exist here, so uv found
        // none of its real managed installs. core/mcp-stdio-proxy/proxy.js
        // now filters PATH/HOME out before forwarding (the real fix, since
        // that's genuinely never a legitimate part of a registration's own
        // config) - this reassignment AFTER applying the caller's env is
        // defense-in-depth so this side can't be broken the same way again
        // even if that filter is ever removed or a future caller forgets it.
        // (Original reason PATH/HOME are set at all still holds: an
        // unqualified command like "npx"/"uvx" can't be resolved without
        // SOME PATH - a tenant's configured env is just their own server's
        // API keys, never PATH, so this container's own value is correct
        // 100% of the time now, not just the common case.)
        //
        // SEC-012: HOME is no longer the container's shared /home/mcprunner
        // but this connection's own private sandbox (see createSession); the
        // same "caller can't override it" rule now covers HOME, TMPDIR, the
        // npm/uv caches and the XDG dirs (RUNNER_OWNED_ENV, any letter case).
        // PATH is still exactly this container's own.
        if (UID_MODE.enabled) {
            childUid = UID_MODE.pool.acquire();
            if (childUid === null) {
                log('refused spawn: uid pool exhausted', JSON.stringify(UID_MODE.pool.stats()));
                sendControl({ type: 'error', message: 'runner busy: no free sandbox uid' });
                socket.end();
                return;
            }
        }
        try {
            session = createSession(childUid);
        } catch (e) {
            log('could not create session sandbox:', e.message);
            sendControl({ type: 'error', message: `spawn failed: could not create sandbox: ${e.message}` });
            releaseSandbox();
            socket.end();
            return;
        }
        const childEnv = buildChildEnv(env, session);

        // Cap a node child's V8 heap so one runaway package cannot eat the
        // whole container. Only touches NODE_OPTIONS when the caller has not
        // already chosen a heap size; ignored by non-node children.
        if (NODE_MAX_OLD_SPACE_MB > 0 && !/--max-old-space-size/.test(childEnv.NODE_OPTIONS || '')) {
            childEnv.NODE_OPTIONS = `${childEnv.NODE_OPTIONS ? childEnv.NODE_OPTIONS + ' ' : ''}--max-old-space-size=${NODE_MAX_OLD_SPACE_MB}`;
        }

        const spawnOptions = {
            env: childEnv,
            // cwd deliberately NOT the sandbox: it stays the runner's WORKDIR
            // (/app), which relative registrations rely on (the static
            // `filesystem` built-in's MCP_FILESYSTEM_ROOT=./mcp-data).
            stdio: ['pipe', 'pipe', 'pipe'],
            // Own process group on POSIX so the whole tree can be signalled.
            detached: process.platform !== 'win32',
        };
        if (childUid !== null) {
            spawnOptions.uid = childUid;
            spawnOptions.gid = childUid;
        }
        try {
            // env is fully replaced, never merged with this process's own env
            // - the child gets nothing from the runner's environment beyond
            // PATH and IMAGE_ENV_PASSTHROUGH (and the shared secret is no
            // longer in process.env at all - see loadSharedSecret).
            child = spawn(command, args, spawnOptions);
        } catch (e) {
            sendControl({ type: 'error', message: `spawn failed: ${e.message}` });
            child = null;
            releaseSandbox();
            socket.end();
            return;
        }
        spawned = true;
        // Sandbox teardown on 'exit' (not 'close': a daemonized grandchild
        // holding stdout open would delay 'close' forever) and on a spawn
        // that never produced a process (async 'error' such as ENOENT).
        child.on('exit', releaseSandbox);
        child.on('error', () => {
            if (!child.pid) releaseSandbox();
        });
        for (const buf of queuedStdin) {
            if (child.stdin.writable) child.stdin.write(buf);
        }
        queuedStdin = [];
        queuedStdinBytes = 0;
        const now = Date.now();
        entry = { id: nextChildId++, tenant, startedAt: now, lastActivity: now, kill: cleanup };
        children.set(entry.id, entry);

        child.stdout.on('data', (chunk) => {
            entry.lastActivity = Date.now();
            send(TYPE_STDOUT, chunk);
        });

        child.stderr.on('data', (chunk) => {
            stderrBuf += chunk.toString('utf8');
            let idx;
            while ((idx = stderrBuf.indexOf('\n')) >= 0) {
                const line = stderrBuf.slice(0, idx);
                stderrBuf = stderrBuf.slice(idx + 1);
                sendControl({ type: 'stderr', line });
            }
        });

        child.on('error', (e) => {
            if (!child.pid) releaseChild(entry.id); // spawn itself failed; no exit event will follow
            sendControl({ type: 'error', message: `child process error: ${e.message}` });
        });

        child.on('exit', (code, signal) => {
            releaseChild(entry.id);
            if (stderrBuf.length) {
                sendControl({ type: 'stderr', line: stderrBuf });
                stderrBuf = '';
            }
            sendControl({ type: 'exit', code: code === null ? -1 : code, signal: signal || null });
            // Give the exit frame a moment to flush before either side tears
            // the socket down.
            setTimeout(() => socket.end(), 50);
        });

        sendControl({ type: 'spawned', pid: child.pid });
        log('spawned', command, JSON.stringify(args), 'pid', child.pid);
    }

    const reader = new FrameReader(
        (type, payload) => {
            if (type === TYPE_STDIN) {
                if (pending) {
                    if (queuedStdinBytes + payload.length > MAX_QUEUED_STDIN_BYTES) {
                        if (cancelWait) cancelWait();
                        pending = false;
                        cancelWait = null;
                        queuedStdin = [];
                        queuedStdinBytes = 0;
                        sendControl({ type: 'error', message: 'stdin buffer limit exceeded while queued' });
                        socket.end();
                        return;
                    }
                    queuedStdin.push(Buffer.from(payload));
                    queuedStdinBytes += payload.length;
                    return;
                }
                if (entry) entry.lastActivity = Date.now();
                if (child && child.stdin.writable) child.stdin.write(payload);
                return;
            }
            if (type === TYPE_CONTROL) {
                if (pending) return;
                if (!spawned) {
                    handleSpawnRequest(payload);
                    return;
                }
                let msg;
                try {
                    msg = JSON.parse(payload.toString('utf8'));
                } catch (_e) {
                    return;
                }
                if (msg && msg.type === 'kill') cleanup();
                return;
            }
            // Unknown frame type from a client - ignore rather than crash.
        },
        (err) => {
            log('frame error, closing connection:', err.message);
            socket.destroy();
        }
    );

    socket.on('data', (chunk) => reader.push(chunk));
    socket.on('close', () => {
        if (cancelWait) cancelWait();
        if (spawned) log('connection closed, reaping child (pid', child && child.pid, ') if still alive');
        cleanup();
    });
    socket.on('error', (e) => {
        log('socket error:', e.message);
        if (cancelWait) cancelWait();
        cleanup();
    });
}

const server = net.createServer(handleConnection);
server.on('error', (e) => log('server error:', e.message));

const healthServer = http.createServer((req, res) => {
    const [path, query] = String(req.url).split('?');
    if (path === '/health') {
        // Default stays the plain-text 'ok' existing probes expect; JSON with
        // child counts is opt-in via Accept: application/json or ?format=json.
        const wantsJson = (req.headers.accept || '').includes('application/json') || query === 'format=json';
        // 503 while draining so a load balancer / orchestrator stops routing
        // new spawn requests here; otherwise 200 as before.
        const status = draining ? 503 : 200;
        if (wantsJson) {
            res.writeHead(status, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({
                status: draining ? 'draining' : 'ok',
                draining,
                children: children.size,
                maxChildren: MAX_CHILDREN,
                maxChildrenPerTenant: MAX_CHILDREN_PER_TENANT,
                queued: waiters.length,
                perTenant: tenantCounts(),
            }));
            return;
        }
        res.writeHead(status, { 'Content-Type': 'text/plain' });
        res.end(draining ? 'draining' : 'ok');
        return;
    }
    res.writeHead(404);
    res.end();
});

// Idle / max-lifetime reaper. A child whose relay socket is still open but
// that has moved no bytes for IDLE_TTL_MS is killed; its exit frame makes the
// relay (and so core's MCP client) see a normal exit and reconnect.
let sweepTimer = null;
function sweep(now = Date.now()) {
    for (const c of children.values()) {
        const idle = IDLE_TTL_MS > 0 && now - c.lastActivity > IDLE_TTL_MS;
        const old = MAX_LIFETIME_MS > 0 && now - c.startedAt > MAX_LIFETIME_MS;
        if (idle || old) {
            log('reaping child', c.id, 'tenant', c.tenant, idle ? '(idle TTL)' : '(max lifetime)');
            c.kill();
        }
    }
}

function start() {
    if (!sweepTimer && SWEEP_INTERVAL_MS > 0) {
        sweepTimer = setInterval(() => sweep(), SWEEP_INTERVAL_MS);
        if (typeof sweepTimer.unref === 'function') sweepTimer.unref();
    }
    if (SECRET_LOAD.error) log(`WARNING: ${SECRET_LOAD.error} - every spawn request will be refused (fail-closed)`);
    else if (!SHARED_SECRET) {
        log('WARNING: MCP_STDIO_SIDECAR_SHARED_SECRET is unset - every spawn request will be refused (fail-closed)');
    } else if (SECRET_LOAD.source === 'env') {
        log(`shared secret read from the environment; prefer ${SECRET_FILE_ENV} (a mounted file) - see README`);
    }
    log(UID_MODE.reason);
    try {
        ensureSessionRoot();
        sweepStaleSessions();
    } catch (e) {
        log(`WARNING: session root ${SESSION_ROOT} unusable (${e.message}) - every spawn will fail until it is writable`);
    }
    server.listen(TCP_PORT, () => log(`listening on :${TCP_PORT}`));
    healthServer.listen(HEALTH_PORT, () => log(`health endpoint on :${HEALTH_PORT}/health`));
}

// Stops accepting, SIGTERMs every child (cleanup() escalates to SIGKILL after
// KILL_GRACE_MS) and resolves once they have all exited or a hard deadline passes.
function shutdown() {
    log('shutting down');
    draining = true;
    if (sweepTimer) clearInterval(sweepTimer);
    sweepTimer = null;
    server.close();
    // healthServer stays up during the drain so /health can report 503
    // 'draining'; it is closed once the children are gone.
    for (const w of waiters.splice(0)) {
        clearTimeout(w.timer);
        w.fail('runner is shutting down');
    }
    for (const c of Array.from(children.values())) c.kill();
    return new Promise((resolve) => {
        const deadline = Date.now() + KILL_GRACE_MS + 1000;
        const tick = () => {
            if (children.size === 0 || Date.now() > deadline) {
                healthServer.close();
                return resolve();
            }
            setTimeout(tick, 25);
        };
        tick();
    });
}

if (require.main === module) {
    start();
    process.on('SIGTERM', () => shutdown().then(() => process.exit(0)));
    process.on('SIGINT', () => shutdown().then(() => process.exit(0)));
}

module.exports = {
    server,
    healthServer,
    start,
    shutdown,
    ALLOWED_COMMANDS,
    BLOCKED_ENV_NAMES,
    BLOCKED_ENV_PREFIXES,
    IMAGE_ENV_PASSTHROUGH,
    TCP_PORT,
    HEALTH_PORT,
    validateSpawnRequest,
    checkEnv,
    // SEC-012
    RUNNER_OWNED_ENV,
    SESSION_ROOT,
    UID_MODE,
    loadSharedSecret,
    buildChildEnv,
    sessionLayout,
    parseUidRange,
    missingCaps,
    createUidPool,
    // Resource limits (per-process caps, queueing, idle reaper)
    children,
    sweep,
    waiters,
};
