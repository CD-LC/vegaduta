'use strict';

const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');

process.env.WORKSPACE_RUNNER_KILL_GRACE_MS = '200';
// away from the default 13210+ range, which a running stack may already be forwarding on this host
process.env.WORKSPACE_RUNNER_PREVIEW_PORT_FIRST = '43210';
const { createServer, resolveFolder, authorized, loadSecret, TailBuffer } = require('./server');

const SECRET = 'test-secret-value';
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ws-runner-'));
const outside = fs.mkdtempSync(path.join(os.tmpdir(), 'ws-outside-'));
fs.mkdirSync(path.join(root, 'proj'));
fs.symlinkSync(outside, path.join(root, 'escape'));

let server;
let base;

before(async () => {
    server = createServer(SECRET, root);
    await new Promise((r) => server.listen(0, '127.0.0.1', r));
    base = `http://127.0.0.1:${server.address().port}`;
});

after(() => {
    server.close();
    fs.rmSync(root, { recursive: true, force: true });
    fs.rmSync(outside, { recursive: true, force: true });
});

function exec(body, secret = SECRET) {
    return fetch(`${base}/exec`, {
        method: 'POST',
        headers: { 'content-type': 'application/json', authorization: `Bearer ${secret}` },
        body: JSON.stringify(body),
    }).then(async (r) => ({ status: r.status, body: await r.json() }));
}

test('runs a command in the attached folder and reports the exit code', async () => {
    const { status, body } = await exec({ folder: 'proj', command: 'pwd; echo err 1>&2; exit 3' });
    assert.equal(status, 200);
    assert.equal(body.exitCode, 3);
    assert.match(body.output, /proj/);
    assert.match(body.output, /err/);
    assert.equal(body.folder, 'proj');
});

test('a folder outside the workspace is refused, including through a symlink', async () => {
    for (const folder of ['..', '../..', '/etc', 'escape', 'missing']) {
        const { status } = await exec({ folder, command: 'true' });
        assert.equal(status, 400, folder);
    }
});

test('a wrong or missing secret is refused', async () => {
    assert.equal((await exec({ command: 'true' }, 'nope')).status, 401);
    assert.equal(authorized(undefined, SECRET), false);
    assert.equal(authorized('Bearer x', ''), false);
});

test('a command past its timeout is stopped with its whole process group', async () => {
    const started = Date.now();
    const { body } = await exec({ folder: 'proj', command: 'sleep 30 & sleep 30', timeoutSeconds: 1 });
    assert.equal(body.timedOut, true);
    assert.ok(Date.now() - started < 10000);
});

test('long output keeps the end and says it was cut', () => {
    const b = new TailBuffer(1000);
    b.push('a'.repeat(5000));
    b.push('THE ERROR');
    const v = b.value();
    assert.equal(b.truncated, true);
    assert.ok(v.endsWith('THE ERROR'));
    assert.ok(v.startsWith('[... earlier output cut ...]'));
});

test('resolveFolder: root itself is allowed, files are not folders', () => {
    fs.writeFileSync(path.join(root, 'file.txt'), 'x');
    assert.equal(resolveFolder(root, '').rel, '');
    assert.equal(resolveFolder(root, 'file.txt'), null);
    assert.equal(resolveFolder(root, 42), null);
});

test('loadSecret prefers the file and trims it', () => {
    assert.equal(loadSecret({ WORKSPACE_RUNNER_SECRET_FILE: '/x', WORKSPACE_RUNNER_SECRET: 'env' }, () => 'file\n'), 'file');
    assert.equal(loadSecret({ WORKSPACE_RUNNER_SECRET: ' env ' }), 'env');
    assert.equal(loadSecret({}), '');
});

test('a background server keeps running after the call, is listed, and is stopped on request', async () => {
    const auth = { authorization: `Bearer ${SECRET}`, 'content-type': 'application/json' };
    const started = await fetch(`${base}/exec`, {
        method: 'POST',
        headers: auth,
        body: JSON.stringify({ folder: 'proj', command: 'echo up $PORT; sleep 30', background: true }),
    }).then((r) => r.json());
    assert.equal(started.background, true);
    assert.equal(started.running, true);
    assert.match(started.output, new RegExp('up ' + started.port));
    const listed = await fetch(`${base}/dev`, { headers: auth }).then((r) => r.json());
    assert.ok(listed.previews.some((p) => p.port === started.port && p.running));
    // it is up but serves nothing on that port, so there is nothing to preview yet
    assert.ok(listed.previews.every((p) => p.ready === false));
    const stopped = await fetch(`${base}/dev/stop`, { method: 'POST', headers: auth, body: JSON.stringify({ port: started.port }) }).then((r) => r.json());
    assert.equal(stopped.stopped, true);
    const after = await fetch(`${base}/dev`, { headers: auth }).then((r) => r.json());
    assert.ok(!after.previews.some((p) => p.port === started.port));
});

test('listing previews needs the secret', async () => {
    const r = await fetch(`${base}/dev`);
    assert.equal(r.status, 401);
});

test('a preview is ready only once the server answers with a non-empty page', async () => {
    const auth = { authorization: `Bearer ${SECRET}`, 'content-type': 'application/json' };
    const started = await fetch(`${base}/exec`, {
        method: 'POST',
        headers: auth,
        body: JSON.stringify({ folder: 'proj', command: `node -e "require('http').createServer((q,r)=>r.end('<h1>hi</h1>')).listen(process.env.PORT)"`, background: true }),
    }).then((r) => r.json());
    let ready = false;
    for (let i = 0; i < 20 && !ready; i++) {
        const listed = await fetch(`${base}/dev`, { headers: auth }).then((r) => r.json());
        ready = listed.previews.some((p) => p.port === started.port && p.ready === true);
        if (!ready) await new Promise((r) => setTimeout(r, 250));
    }
    await fetch(`${base}/dev/stop`, { method: 'POST', headers: auth, body: JSON.stringify({ port: started.port }) });
    assert.equal(ready, true);
});
