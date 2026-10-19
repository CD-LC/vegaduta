'use strict';

// End-to-end test of the actual mechanism the whole sidecar design leans on:
// `core` spawns this proxy as a REAL local child process (exactly what
// DynamicMcpConnectionManager.wrapForSidecar does via
// EnvIsolatedStdioClientTransport), the proxy connects to a live
// mcp-stdio-runner, and stdin/stdout round-trip byte-for-byte through both
// hops. Runs the real mcp-stdio-runner/server.js in-process (require + start)
// as the sidecar side, and `node proxy.js` as a genuine child_process - not a
// mock of either half.

const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { spawn } = require('node:child_process');

const TEST_PORT = 18197;
const TEST_HEALTH_PORT = 18198;
const TEST_SECRET = 'proxy-test-shared-secret';

process.env.MCP_STDIO_RUNNER_PORT = String(TEST_PORT);
process.env.MCP_STDIO_RUNNER_HEALTH_PORT = String(TEST_HEALTH_PORT);
process.env.MCP_STDIO_SIDECAR_SHARED_SECRET = TEST_SECRET;
process.env.MCP_STDIO_RUNNER_KILL_GRACE_MS = '200';

const fs = require('node:fs');
// The runner moved to services/ in the OSS layout; keep the legacy sibling path as a fallback.
const runnerPath = [
    path.join(__dirname, '..', '..', 'services', 'mcp-stdio-runner', 'server.js'),
    path.join(__dirname, '..', '..', 'mcp-stdio-runner', 'server.js'),
].find((p) => fs.existsSync(p));
const runner = require(runnerPath);

before(() => runner.start());
after(() => runner.shutdown());

function runProxy(env, { input } = {}) {
    return new Promise((resolve, reject) => {
        const child = spawn(process.execPath, [path.join(__dirname, 'proxy.js')], {
            env: { ...env },
            stdio: ['pipe', 'pipe', 'pipe'],
        });
        let stdout = Buffer.alloc(0);
        let stderr = '';
        child.stdout.on('data', (c) => {
            stdout = Buffer.concat([stdout, c]);
        });
        child.stderr.on('data', (c) => {
            stderr += c.toString('utf8');
        });
        child.on('error', reject);
        child.on('exit', (code) => resolve({ code, stdout, stderr }));
        if (input !== undefined) child.stdin.write(input);
    });
}

// Echoes the FIRST stdin chunk back uppercased, then exits - deliberately
// does not rely on stdin EOF. Real MCP servers never see stdin close either
// (they read a continuous stream of newline-delimited JSON-RPC messages for
// the life of the connection), and neither the proxy nor the sidecar forward
// an end-of-stdin signal today (nothing in this design needs them to), so a
// test target that blocks on EOF (e.g. `fs.readFileSync(0)`) would hang
// forever here rather than exercising a real bug.
const ECHO_UPPER_SCRIPT =
    'process.stdin.on("data", d => { process.stdout.write(d.toString().toUpperCase()); process.exit(0); })';

// The runner (like core's McpStdioCommandGuard) refuses `node -e <code>`, so
// each test target is written to a real script file and run as `node <file>`.
const os = require('node:os');
let scriptCounter = 0;
function scriptArgs(source) {
    const file = path.join(os.tmpdir(), `mcp-proxy-test-${process.pid}-${scriptCounter++}.js`);
    fs.writeFileSync(file, source);
    return JSON.stringify([file]);
}

function baseEnv(overrides = {}) {
    return {
        PATH: process.env.PATH,
        MCP_SIDECAR_URL: `127.0.0.1:${TEST_PORT}`,
        MCP_SIDECAR_SECRET: TEST_SECRET,
        MCP_SIDECAR_TARGET_COMMAND: 'node',
        MCP_SIDECAR_TARGET_ARGS_JSON: scriptArgs(ECHO_UPPER_SCRIPT),
        ...overrides,
    };
}

test('relays stdin through the sidecar to the real child and its stdout back, byte-for-byte', async () => {
    const { code, stdout } = await runProxy(baseEnv(), { input: 'hello from core\n' });
    assert.equal(code, 0);
    assert.equal(stdout.toString('utf8'), 'HELLO FROM CORE\n');
});

test('exits non-zero and reports the reason when the shared secret is wrong', async () => {
    const { code, stderr } = await runProxy(baseEnv({ MCP_SIDECAR_SECRET: 'wrong-secret' }));
    assert.notEqual(code, 0);
    assert.match(stderr, /\[mcp-stdio-proxy\]/);
    assert.match(stderr, /unauthorized/);
});

test('exits non-zero when the sidecar is unreachable', async () => {
    const { code, stderr } = await runProxy(baseEnv({ MCP_SIDECAR_URL: '127.0.0.1:1' }));
    assert.notEqual(code, 0);
    assert.match(stderr, /\[mcp-stdio-proxy\]/);
});

test('propagates the real child\'s non-zero exit code', async () => {
    const { code } = await runProxy(
        baseEnv({ MCP_SIDECAR_TARGET_ARGS_JSON: scriptArgs('process.exit(9)') })
    );
    assert.equal(code, 9);
});

test('tolerates a scheme prefix on MCP_SIDECAR_URL', async () => {
    const { code, stdout } = await runProxy(baseEnv({ MCP_SIDECAR_URL: `tcp://127.0.0.1:${TEST_PORT}` }), {
        input: 'scheme test\n',
    });
    assert.equal(code, 0);
    assert.equal(stdout.toString('utf8'), 'SCHEME TEST\n');
});

test('a bogus PATH/HOME on this process never reaches the real spawned child', async () => {
    // End-to-end regression test, 2026-08-13 live incident: this process's
    // own PATH/HOME (here, deliberately set to values describing a
    // DIFFERENT filesystem entirely - simulating `core`'s real HOME=
    // /home/agentic leaking into the sidecar, which doesn't exist there) used
    // to get forwarded by proxy.js and then WIN inside server.js's spawn -
    // confirmed live via a2atlassian resolving Python against the wrong
    // interpreter because HOME pointed nowhere real inside that container.
    // Exercises the REAL two-hop pipeline (a real proxy.js child process,
    // the real server.js spawning the real final target) end-to-end, not a
    // unit-level assertion on either side alone.
    const bogusHome = '/definitely/not/this/machines/real/home';
    const echoEnvScript =
        'process.stdin.on("data", d => { process.stdout.write(JSON.stringify({ home: process.env.HOME })); process.exit(0); })';
    const { code, stdout } = await runProxy(
        baseEnv({
            HOME: bogusHome,
            MCP_SIDECAR_TARGET_ENV_JSON: JSON.stringify({ HOME: bogusHome, home: bogusHome }),
            MCP_SIDECAR_TARGET_ARGS_JSON: scriptArgs(echoEnvScript),
        }),
        { input: 'go\n' }
    );
    assert.equal(code, 0);
    const seen = JSON.parse(stdout.toString('utf8'));
    assert.notEqual(seen.home, bogusHome, 'the bogus HOME must never reach the real spawned child');
    // Since SEC-012 the runner gives each connection its own sandbox HOME, so
    // the child's HOME is the runner's choice - just never the relay's/caller's.
    assert.ok(seen.home, 'the runner must set a HOME for the child');
});

// M6 residual (2026-08-14): a real MCP server that sends one unterminated
// JSON-RPC line of unbounded size used to be forwarded to our stdout
// unconditionally, which the SDK's StdioClientTransport then reads via an
// unbounded BufferedReader.readLine() inside core's shared JVM. The two
// tests below exercise the real two-hop pipeline (a real proxy.js child, a
// real spawned target process) to confirm the cap actually engages, and
// that it doesn't false-positive on a large-but-real, newline-terminated
// response anywhere near the same size.
test('refuses to forward a single JSON-RPC line exceeding the byte cap', async () => {
    const overCapBytes = 16 * 1024 * 1024 + 1024;
    const script = `process.stdin.on("data", d => { process.stdout.write("x".repeat(${overCapBytes})); });`;
    const { code, stderr } = await runProxy(
        baseEnv({ MCP_SIDECAR_TARGET_ARGS_JSON: scriptArgs(script) }),
        { input: 'go\n' }
    );
    assert.notEqual(code, 0);
    assert.match(stderr, /\[mcp-stdio-proxy\]/);
    assert.match(stderr, /exceeding 16777216 bytes without a newline/);
});

test('still relays a large-but-newline-terminated line just under the byte cap', async () => {
    const underCapBytes = 16 * 1024 * 1024 - 1024;
    const script =
        `process.stdin.on("data", d => { process.stdout.write("x".repeat(${underCapBytes}) + "\\n"); process.exit(0); })`;
    const { code, stdout } = await runProxy(
        baseEnv({ MCP_SIDECAR_TARGET_ARGS_JSON: scriptArgs(script) }),
        { input: 'go\n' }
    );
    assert.equal(code, 0);
    assert.equal(stdout.length, underCapBytes + 1);
});

// SEC-030: the tenant env reaches the runner only as data (the control
// frame), never through the relay's own process environment.
test('the target env from MCP_SIDECAR_TARGET_ENV_JSON reaches the real child', async () => {
    const script =
        'process.stdin.on("data", d => { process.stdout.write(JSON.stringify({ t: process.env.TENANT_TOKEN || null })); process.exit(0); })';
    const { code, stdout } = await runProxy(
        baseEnv({
            MCP_SIDECAR_TARGET_ENV_JSON: JSON.stringify({ TENANT_TOKEN: 'tok-123' }),
            MCP_SIDECAR_TARGET_ARGS_JSON: scriptArgs(script),
        }),
        { input: 'go\n' }
    );
    assert.equal(code, 0);
    assert.deepEqual(JSON.parse(stdout.toString('utf8')), { t: 'tok-123' });
});

test('a variable in the relay\'s own process env is never forwarded to the child', async () => {
    const script =
        'process.stdin.on("data", d => { process.stdout.write(JSON.stringify({ leaked: process.env.RELAY_ONLY_VAR || null })); process.exit(0); })';
    const { code, stdout } = await runProxy(
        baseEnv({
            RELAY_ONLY_VAR: 'core-side-value',
            MCP_SIDECAR_TARGET_ARGS_JSON: scriptArgs(script),
        }),
        { input: 'go\n' }
    );
    assert.equal(code, 0);
    assert.deepEqual(JSON.parse(stdout.toString('utf8')), { leaked: null });
});

test('an invalid MCP_SIDECAR_TARGET_ENV_JSON fails the relay closed', async () => {
    const { code, stderr } = await runProxy(baseEnv({ MCP_SIDECAR_TARGET_ENV_JSON: '["not","an","object"]' }));
    assert.notEqual(code, 0);
    assert.match(stderr, /\[mcp-stdio-proxy\] MCP_SIDECAR_TARGET_ENV_JSON must be a JSON object/);
});

test('MCP_SIDECAR_TENANT_ID reaches the runner as tenantId on the control frame and is not forwarded to the child', async () => {
    const seenTenants = [];
    const orig = runner.children;
    // Spawn a long-ish child, then read the runner's own registry for its tenant.
    const script =
        'process.stdin.on("data", d => { process.stdout.write(JSON.stringify({ t: process.env.MCP_SIDECAR_TENANT_ID || null })); setTimeout(() => process.exit(0), 300); })';
    const p = runProxy(
        baseEnv({ MCP_SIDECAR_TENANT_ID: 'tenant-xyz', MCP_SIDECAR_TARGET_ARGS_JSON: scriptArgs(script) }),
        { input: 'go\n' }
    );
    const dl = Date.now() + 3000;
    while (Date.now() < dl && seenTenants.length === 0) {
        for (const c of orig.values()) seenTenants.push(c.tenant);
        await new Promise((r) => setTimeout(r, 10));
    }
    const { code, stdout } = await p;
    assert.equal(code, 0);
    assert.ok(seenTenants.includes('tenant-xyz'), `runner saw tenants: ${seenTenants}`);
    assert.equal(JSON.parse(stdout.toString('utf8')).t, null);
});
