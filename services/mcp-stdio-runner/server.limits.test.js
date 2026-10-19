'use strict';

// Separate file (= separate process under `node --test`) because the limits
// are read from env at require time. Covers: opt-in NODE_OPTIONS heap cap,
// MAX_LIFETIME reaper, queueing at the global cap, and the draining signal.

const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const net = require('net');
const http = require('http');
const { TYPE_CONTROL, TYPE_STDOUT, TYPE_STDIN, encodeFrame, FrameReader } = require('./framing');

const path = require('path');

const CHILD = path.join(__dirname, 'fixture-child.js');
const TEST_PORT = 18297;
const TEST_HEALTH_PORT = 18298;
const TEST_SECRET = 'limits-test-secret';

process.env.MCP_STDIO_RUNNER_PORT = String(TEST_PORT);
process.env.MCP_STDIO_RUNNER_HEALTH_PORT = String(TEST_HEALTH_PORT);
process.env.MCP_STDIO_SIDECAR_SHARED_SECRET = TEST_SECRET;
process.env.MCP_STDIO_RUNNER_KILL_GRACE_MS = '200';
process.env.MCP_STDIO_RUNNER_MAX_CHILDREN = '2';
process.env.MCP_STDIO_RUNNER_QUEUE_WAIT_MS = '4000';
process.env.MCP_STDIO_RUNNER_MAX_LIFETIME_MS = String(60 * 60 * 1000);
process.env.MCP_STDIO_RUNNER_NODE_MAX_OLD_SPACE_MB = '256';

const runner = require('./server');
before(() => runner.start());
after(() => runner.shutdown());

function spawnOverWire({ args, env = {} }) {
    return new Promise((resolve, reject) => {
        const socket = net.createConnection(TEST_PORT, '127.0.0.1');
        const events = [];
        const reader = new FrameReader((type, payload) => {
            if (type === TYPE_CONTROL) events.push({ msg: JSON.parse(payload.toString('utf8')) });
            else if (type === TYPE_STDOUT) events.push({ data: Buffer.from(payload) });
        }, reject);
        socket.on('data', (c) => reader.push(c));
        socket.on('error', reject);
        socket.on('connect', () => {
            socket.write(encodeFrame(TYPE_CONTROL, JSON.stringify({ command: 'node', args, env, secret: TEST_SECRET })));
            resolve({ socket, events });
        });
    });
}
const waitFor = async (events, pred, ms = 5000) => {
    const dl = Date.now() + ms;
    for (;;) {
        const f = events.find(pred);
        if (f) return f;
        if (Date.now() > dl) throw new Error('timed out');
        await new Promise((r) => setTimeout(r, 20));
    }
};
const type = (t) => (e) => e.msg && e.msg.type === t;
const SLEEP = [CHILD, 'idle'];
const stdoutOf = (events) => Buffer.concat(events.filter((e) => e.data).map((e) => e.data)).toString('utf8');
const get = (path) => new Promise((resolve, reject) => {
    http.get(`http://127.0.0.1:${TEST_HEALTH_PORT}${path}`, (res) => {
        let b = '';
        res.on('data', (d) => (b += d));
        res.on('end', () => resolve({ status: res.statusCode, body: b }));
    }).on('error', reject);
});

test('NODE_OPTIONS heap cap is injected when opted in', async () => {
    const w = await spawnOverWire({ args: [CHILD, 'print-node-options'] });
    await waitFor(w.events, type('exit'));
    assert.equal(stdoutOf(w.events), '--max-old-space-size=256');
    w.socket.destroy();
});

test('a caller-supplied NODE_OPTIONS is rejected, so the heap cap is never merged with caller options', async () => {
    // The OSS runner's env denylist refuses NODE_OPTIONS outright; the heap cap
    // is the only way it ever reaches a child.
    const w = await spawnOverWire({
        args: [CHILD, 'print-node-options'],
        env: { NODE_OPTIONS: '--max-old-space-size=2048' },
    });
    const err = await waitFor(w.events, type('error'));
    assert.match(err.msg.message, /NODE_OPTIONS/);
    assert.equal(w.events.some(type('spawned')), false);
    w.socket.destroy();
});

test('MAX_LIFETIME reaper kills a chatty (non-idle) child past its lifetime', async () => {
    const w = await spawnOverWire({ args: [CHILD, 'tick'] });
    await waitFor(w.events, type('spawned'));
    runner.sweep(Date.now() + 30 * 60 * 1000);
    await new Promise((r) => setTimeout(r, 200));
    assert.equal(w.events.some(type('exit')), false, 'not yet past MAX_LIFETIME');
    runner.sweep(Date.now() + 2 * 60 * 60 * 1000);
    await waitFor(w.events, type('exit'));
    w.socket.destroy();
});

test('idle reaper is off by default (no IDLE_TTL configured)', async () => {
    const w = await spawnOverWire({ args: SLEEP });
    await waitFor(w.events, type('spawned'));
    // Past lifetime would kill it; use a time before MAX_LIFETIME but far past any default idle TTL.
    runner.sweep(Date.now() + 50 * 60 * 1000);
    await new Promise((r) => setTimeout(r, 200));
    assert.equal(w.events.some(type('exit')), false);
    w.socket.destroy();
});

test('a spawn over the global cap is queued, then admitted when a slot frees', async () => {
    const a = await spawnOverWire({ args: SLEEP });
    const b = await spawnOverWire({ args: SLEEP });
    await waitFor(a.events, type('spawned'));
    await waitFor(b.events, type('spawned'));
    const c = await spawnOverWire({ args: SLEEP });
    await new Promise((r) => setTimeout(r, 300));
    assert.equal(c.events.some(type('error')), false, 'queued, not refused');
    assert.equal(c.events.some(type('spawned')), false);
    assert.equal(runner.waiters.length, 1);
    a.socket.destroy(); // frees a slot
    await waitFor(c.events, type('spawned'));
    b.socket.destroy();
    c.socket.destroy();
    const dl = Date.now() + 3000;
    while (runner.children.size > 0 && Date.now() < dl) await new Promise((r) => setTimeout(r, 20));
});

test('a queued client that disconnects is dropped from the queue', async () => {
    const a = await spawnOverWire({ args: SLEEP });
    const b = await spawnOverWire({ args: SLEEP });
    await waitFor(a.events, type('spawned'));
    await waitFor(b.events, type('spawned'));
    const c = await spawnOverWire({ args: SLEEP });
    await new Promise((r) => setTimeout(r, 200));
    assert.equal(runner.waiters.length, 1);
    c.socket.destroy();
    await new Promise((r) => setTimeout(r, 200));
    assert.equal(runner.waiters.length, 0);
    a.socket.destroy();
    b.socket.destroy();
    const dl = Date.now() + 3000;
    while (runner.children.size > 0 && Date.now() < dl) await new Promise((r) => setTimeout(r, 20));
});

test('stdin sent while queued is delivered to the child once it spawns', async () => {
    const a = await spawnOverWire({ args: SLEEP });
    const b = await spawnOverWire({ args: SLEEP });
    await waitFor(a.events, type('spawned'));
    await waitFor(b.events, type('spawned'));
    const c = await spawnOverWire({ args: [CHILD, 'echo-stdin'] });
    c.socket.write(encodeFrame(TYPE_STDIN, 'hello '));
    c.socket.write(encodeFrame(TYPE_STDIN, 'world\n'));
    await new Promise((r) => setTimeout(r, 200));
    assert.equal(runner.waiters.length, 1);
    a.socket.destroy();
    await waitFor(c.events, type('spawned'));
    const dl = Date.now() + 5000;
    while (!stdoutOf(c.events).includes('hello world\n') && Date.now() < dl) await new Promise((r) => setTimeout(r, 20));
    assert.equal(stdoutOf(c.events), 'hello world\n');
    b.socket.destroy();
    c.socket.destroy();
    const dl2 = Date.now() + 3000;
    while (runner.children.size > 0 && Date.now() < dl2) await new Promise((r) => setTimeout(r, 20));
});

// Must stay last: it puts the shared runner into the draining state.
test('draining: /health reports 503 draining and new connections are not accepted', async () => {
    assert.equal((await get('/health')).status, 200);
    const w = await spawnOverWire({ args: SLEEP });
    await waitFor(w.events, type('spawned'));
    const done = runner.shutdown();
    const plain = await get('/health');
    assert.equal(plain.status, 503);
    assert.equal(plain.body, 'draining');
    const j = await get('/health?format=json');
    assert.equal(j.status, 503);
    assert.equal(JSON.parse(j.body).draining, true);
    await assert.rejects(spawnOverWire({ args: SLEEP }), /ECONNREFUSED/);
    await done;
    w.socket.destroy();
});
