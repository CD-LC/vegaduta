'use strict';

// workspace-runner: runs the Assistant's shell commands (local_shell / local_shell_read) for core,
// inside its own container, in a folder the person attached.
//
// Why a separate container: core holds APP_ENCRYPTION_KEY, provider keys and a database
// connection; a build command (mvn, npm, a project's own scripts) is untrusted code. Here it runs
// with only the projects folder mounted (WORKSPACE_ROOT, /workspace in the image), its own HOME for
// build caches, and no platform secret. core never mounts the Docker socket to get this.
//
// API (internal network only, bearer secret):
//   GET  /health                -> {"status":"UP"}
//   POST /exec  {folder, command, timeoutSeconds, maxOutputChars}
//        folder: relative to WORKSPACE_ROOT ("" = the root); resolved and refused if it leaves it
//        -> {exitCode, output, truncated, timedOut, folder}
//        with "background": true the command is a dev server: it is started detached on a preview
//        port (PORT in its environment, bound to 0.0.0.0 by convention), the call returns after a
//        short settle time with {background, running, port, pid, output}, and the server keeps running.
//   GET  /dev                   -> {previews:[{port, pid, folder, command, startedAt, running}]}
//   POST /dev/stop  {port}      -> {stopped}
//
// The command runs as `/bin/sh -c <command>` in its own process group; on timeout the whole group
// gets SIGTERM, then SIGKILL after KILL_GRACE_MS. Output is stdout and stderr interleaved; past the
// cap the START is dropped (build errors are at the end) and `truncated` is set.

const http = require('http');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const { spawn } = require('child_process');

const PORT = envInt('WORKSPACE_RUNNER_PORT', 8090);
const ROOT = path.resolve(process.env.WORKSPACE_ROOT || '/workspace');
const MAX_TIMEOUT_S = envInt('WORKSPACE_RUNNER_MAX_TIMEOUT_SECONDS', 1800);
const MAX_OUTPUT = envInt('WORKSPACE_RUNNER_MAX_OUTPUT_CHARS', 200000);
const MAX_CONCURRENT = envInt('WORKSPACE_RUNNER_MAX_CONCURRENT', 4);
const KILL_GRACE_MS = envInt('WORKSPACE_RUNNER_KILL_GRACE_MS', 5000);
const MAX_BODY = 256 * 1024;
// Dev servers the Assistant starts get a port from this range; compose publishes the same range on
// the host's loopback so the console can show the page. Past the range, the oldest is not reused:
// the Assistant must stop one first.
const PREVIEW_PORT_FIRST = envInt('WORKSPACE_RUNNER_PREVIEW_PORT_FIRST', 13210);
const PREVIEW_PORT_COUNT = envInt('WORKSPACE_RUNNER_PREVIEW_PORT_COUNT', 10);
const SETTLE_MS = envInt('WORKSPACE_RUNNER_SETTLE_MS', 4000);
const BACKGROUND_LOG_CHARS = 20000;

function envInt(name, dflt) {
    const n = parseInt(process.env[name] || '', 10);
    return Number.isFinite(n) && n > 0 ? n : dflt;
}

/** Secret from WORKSPACE_RUNNER_SECRET_FILE (preferred: never in an environment) or WORKSPACE_RUNNER_SECRET. */
function loadSecret(env = process.env, readFile = fs.readFileSync) {
    const file = env.WORKSPACE_RUNNER_SECRET_FILE;
    if (file) {
        try {
            return String(readFile(file)).trim();
        } catch {
            return '';
        }
    }
    return (env.WORKSPACE_RUNNER_SECRET || '').trim();
}

function authorized(header, secret) {
    if (!secret || typeof header !== 'string' || !header.startsWith('Bearer ')) return false;
    const given = Buffer.from(header.slice(7));
    const want = Buffer.from(secret);
    return given.length === want.length && crypto.timingSafeEqual(given, want);
}

/**
 * The absolute folder for `folder` (relative to root), or null when it is not a directory inside
 * root. Symlinks are resolved first, so a link pointing out of the root is refused too.
 */
function resolveFolder(root, folder) {
    if (folder != null && typeof folder !== 'string') return null;
    const rel = (folder || '').replace(/^\/+/, '');
    if (rel.includes('\0')) return null;
    let real;
    let realRoot;
    try {
        realRoot = fs.realpathSync(root);
        real = fs.realpathSync(path.resolve(realRoot, rel));
    } catch {
        return null;
    }
    if (real !== realRoot && !real.startsWith(realRoot + path.sep)) return null;
    try {
        if (!fs.statSync(real).isDirectory()) return null;
    } catch {
        return null;
    }
    return { abs: real, rel: path.relative(realRoot, real) };
}

/** Keeps the last `cap` chars (the end of a build log is where the error is). */
class TailBuffer {
    constructor(cap) {
        this.cap = cap;
        this.text = '';
        this.truncated = false;
    }
    push(chunk) {
        this.text += chunk;
        if (this.text.length > this.cap * 2) this.trim();
    }
    trim() {
        if (this.text.length > this.cap) {
            this.text = this.text.slice(this.text.length - this.cap);
            this.truncated = true;
        }
    }
    value() {
        this.trim();
        return this.truncated ? '[... earlier output cut ...]\n' + this.text : this.text;
    }
}

let running = 0;

function runCommand({ cwd, command, timeoutSeconds, maxOutputChars }) {
    return new Promise((resolve) => {
        const out = new TailBuffer(maxOutputChars);
        const env = {
            PATH: process.env.PATH,
            HOME: process.env.HOME || '/home/runner',
            LANG: 'C.UTF-8',
            JAVA_HOME: process.env.JAVA_HOME || '',
            MAVEN_OPTS: process.env.MAVEN_OPTS || '',
            GRADLE_USER_HOME: process.env.GRADLE_USER_HOME || '',
            CI: 'true',
        };
        const child = spawn('/bin/sh', ['-c', command], { cwd, env, detached: true, stdio: ['ignore', 'pipe', 'pipe'] });
        let timedOut = false;
        let killTimer = null;
        const killGroup = (sig) => {
            try {
                process.kill(-child.pid, sig);
            } catch {
                /* already gone */
            }
        };
        const deadline = setTimeout(() => {
            timedOut = true;
            killGroup('SIGTERM');
            killTimer = setTimeout(() => killGroup('SIGKILL'), KILL_GRACE_MS);
        }, timeoutSeconds * 1000);
        child.stdout.setEncoding('utf8');
        child.stderr.setEncoding('utf8');
        child.stdout.on('data', (d) => out.push(d));
        child.stderr.on('data', (d) => out.push(d));
        child.on('error', (e) => {
            clearTimeout(deadline);
            resolve({ exitCode: -1, output: 'Could not start the command: ' + e.message, truncated: false, timedOut: false });
        });
        child.on('close', (code, signal) => {
            clearTimeout(deadline);
            if (killTimer) clearTimeout(killTimer);
            // Children left in the group (a backgrounded server) do not outlive the call.
            killGroup('SIGKILL');
            resolve({
                exitCode: code == null ? (signal ? 128 : -1) : code,
                output: out.value(),
                truncated: out.truncated,
                timedOut,
            });
        });
    });
}

/** port -> {child, pid, folder, command, startedAt, running, log}. */
const previews = new Map();

function freePreviewPort() {
    for (let p = PREVIEW_PORT_FIRST; p < PREVIEW_PORT_FIRST + PREVIEW_PORT_COUNT; p++) {
        const held = previews.get(p);
        if (!held || !held.running) return p;
    }
    return null;
}

function listPreviews() {
    return [...previews.entries()]
        .map(([port, v]) => ({ port, pid: v.pid, folder: v.folder, command: v.command, startedAt: v.startedAt, running: v.running }))
        .sort((a, b) => a.port - b.port);
}

/**
 * Whether the dev server on `port` answers GET / with a successful, non-empty page. A server that is up
 * but serves nothing (404, a bare directory listing error, an empty body) is not worth opening a Preview
 * pane for.
 */
function probeReady(port) {
    return new Promise((resolve) => {
        const req = http.get({ host: '127.0.0.1', port, path: '/', timeout: 1500 }, (res) => {
            let bytes = 0;
            res.on('data', (c) => { bytes += c.length; });
            res.on('end', () => resolve(res.statusCode >= 200 && res.statusCode < 400 && bytes > 0));
            res.on('error', () => resolve(false));
        });
        req.on('timeout', () => { req.destroy(); resolve(false); });
        req.on('error', () => resolve(false));
    });
}

async function listPreviewsWithReadiness() {
    return Promise.all(listPreviews().map(async (p) => ({ ...p, ready: p.running ? await probeReady(p.port) : false })));
}

function stopPreview(port) {
    const v = previews.get(port);
    if (!v) return false;
    if (v.running) {
        try {
            process.kill(-v.pid, 'SIGTERM');
        } catch {
            /* already gone */
        }
        setTimeout(() => {
            try {
                process.kill(-v.pid, 'SIGKILL');
            } catch {
                /* gone */
            }
        }, KILL_GRACE_MS).unref();
    }
    previews.delete(port);
    return true;
}

/** Starts a dev server that outlives the request; waits SETTLE_MS (or until it exits) for its first output. */
function startBackground({ cwd, folder, command, port }) {
    return new Promise((resolve) => {
        const out = new TailBuffer(BACKGROUND_LOG_CHARS);
        const env = {
            PATH: process.env.PATH,
            HOME: process.env.HOME || '/home/runner',
            LANG: 'C.UTF-8',
            JAVA_HOME: process.env.JAVA_HOME || '',
            MAVEN_OPTS: process.env.MAVEN_OPTS || '',
            GRADLE_USER_HOME: process.env.GRADLE_USER_HOME || '',
            PORT: String(port),
            HOST: '0.0.0.0',
            HOSTNAME: '0.0.0.0',
        };
        const child = spawn('/bin/sh', ['-c', command], { cwd, env, detached: true, stdio: ['ignore', 'pipe', 'pipe'] });
        const entry = { child, pid: child.pid, folder, command, startedAt: new Date().toISOString(), running: true, log: out };
        previews.set(port, entry);
        child.stdout.setEncoding('utf8');
        child.stderr.setEncoding('utf8');
        child.stdout.on('data', (d) => out.push(d));
        child.stderr.on('data', (d) => out.push(d));
        let settled = false;
        const finish = () => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            resolve({ background: true, port, pid: child.pid, running: entry.running, output: out.value(), exitCode: entry.exitCode ?? null });
        };
        const timer = setTimeout(finish, SETTLE_MS);
        child.on('error', (e) => {
            entry.running = false;
            out.push('Could not start the command: ' + e.message);
            finish();
        });
        child.on('close', (code) => {
            entry.running = false;
            entry.exitCode = code;
            try {
                process.kill(-child.pid, 'SIGKILL');
            } catch {
                /* gone */
            }
            finish();
        });
    });
}

function send(res, status, body) {
    const json = JSON.stringify(body);
    res.writeHead(status, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(json) });
    res.end(json);
}

function readJson(req) {
    return new Promise((resolve, reject) => {
        let size = 0;
        const chunks = [];
        req.on('data', (c) => {
            size += c.length;
            if (size > MAX_BODY) {
                reject(new Error('request too large'));
                req.destroy();
                return;
            }
            chunks.push(c);
        });
        req.on('end', () => {
            try {
                resolve(JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}'));
            } catch {
                reject(new Error('body is not JSON'));
            }
        });
        req.on('error', reject);
    });
}

function createServer(secret = loadSecret(), root = ROOT) {
    return http.createServer(async (req, res) => {
        if (req.method === 'GET' && req.url === '/health') {
            return send(res, 200, { status: 'UP', root });
        }
        const known = (req.method === 'POST' && (req.url === '/exec' || req.url === '/dev/stop')) || (req.method === 'GET' && req.url === '/dev');
        if (!known) {
            return send(res, 404, { error: 'not found' });
        }
        if (!authorized(req.headers.authorization, secret)) {
            return send(res, 401, { error: 'unauthorized' });
        }
        if (req.method === 'GET') {
            return send(res, 200, { previews: await listPreviewsWithReadiness() });
        }
        if (req.url === '/dev/stop') {
            let stopBody;
            try {
                stopBody = await readJson(req);
            } catch (e) {
                return send(res, 400, { error: e.message });
            }
            return send(res, 200, { stopped: stopPreview(parseInt(stopBody.port, 10)) });
        }
        let body;
        try {
            body = await readJson(req);
        } catch (e) {
            return send(res, 400, { error: e.message });
        }
        const command = body.command;
        if (typeof command !== 'string' || !command.trim() || command.includes('\0')) {
            return send(res, 400, { error: 'command is required' });
        }
        const folder = resolveFolder(root, body.folder);
        if (!folder) {
            return send(res, 400, { error: 'folder is not a folder inside the workspace' });
        }
        if (running >= MAX_CONCURRENT) {
            return send(res, 429, { error: `busy: ${MAX_CONCURRENT} commands already running` });
        }
        if (body.background === true) {
            const port = freePreviewPort();
            if (port == null) {
                return send(res, 429, { error: `all ${PREVIEW_PORT_COUNT} preview ports are in use; stop a server first` });
            }
            stopPreview(port);
            const started = await startBackground({ cwd: folder.abs, folder: folder.rel, command, port });
            return send(res, 200, { ...started, folder: folder.rel });
        }
        const timeoutSeconds = Math.min(Math.max(parseInt(body.timeoutSeconds, 10) || 60, 1), MAX_TIMEOUT_S);
        const maxOutputChars = Math.min(Math.max(parseInt(body.maxOutputChars, 10) || 20000, 1000), MAX_OUTPUT);
        running++;
        try {
            const result = await runCommand({ cwd: folder.abs, command, timeoutSeconds, maxOutputChars });
            return send(res, 200, { ...result, folder: folder.rel });
        } finally {
            running--;
        }
    });
}

module.exports = { stopPreview, listPreviews, createServer, resolveFolder, authorized, loadSecret, TailBuffer };

if (require.main === module) {
    const secret = loadSecret();
    if (!secret) {
        console.error('workspace-runner: no secret (WORKSPACE_RUNNER_SECRET_FILE or WORKSPACE_RUNNER_SECRET); refusing to start');
        process.exit(1);
    }
    if (!fs.existsSync(ROOT)) {
        console.error(`workspace-runner: WORKSPACE_ROOT ${ROOT} does not exist`);
        process.exit(1);
    }
    createServer(secret).listen(PORT, () => console.log(`workspace-runner listening on ${PORT}, root ${ROOT}`));
}
