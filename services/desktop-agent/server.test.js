'use strict';

// Route/auth/validation tests against the real server with the platform actions
// stubbed - the GUI calls themselves need a real desktop, which the test host
// does not have.

process.env.DESKTOP_AGENT_SECRET = 'test-secret';
process.env.DESKTOP_AGENT_DATA_DIR = require('node:os').tmpdir() + '/da-test-' + process.pid;

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const { createServer, actions, appleQuote } = require('./server.js');

let server;
let base;

const calls = [];

function stub(name, fn) {
  actions[name] = fn;
}

before(async () => {
  for (const route of Object.keys(actions)) {
    const key = route;
    stub(key, async (b) => {
      calls.push({ route: key, body: b });
      return { stubbed: true };
    });
  }
  server = createServer();
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  base = `http://127.0.0.1:${server.address().port}`;
});

after(() => server.close());

async function req(method, path, body, auth = 'Bearer test-secret') {
  const res = await fetch(base + path, {
    method,
    headers: { authorization: auth, 'content-type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return { status: res.status, json: await res.json() };
}

// For the binary /frame route - no JSON parse, keep the headers.
async function raw(path, auth = 'Bearer test-secret') {
  const res = await fetch(base + path, { headers: { authorization: auth } });
  return {
    status: res.status,
    headers: res.headers,
    body: Buffer.from(await res.arrayBuffer()),
  };
}

test('health answers for a bearer but never without one (CodeQL: user-controlled bypass)', async () => {
  const unauth = await req('GET', '/health', undefined, '');
  assert.equal(unauth.status, 401);
  const r = await req('GET', '/health');
  assert.equal(r.status, 200);
  assert.equal(r.json.ok, true);
});

test('every other route needs the bearer secret', async () => {
  for (const [method, path] of [
    ['POST', '/screenshot'], ['POST', '/ui'], ['POST', '/click'],
    ['POST', '/notify'], ['GET', '/clipboard'], ['POST', '/clipboard'],
    ['GET', '/frame'],
  ]) {
    const r = await req(method, path, method === 'POST' ? {} : undefined, '');
    assert.equal(r.status, 401, `${method} ${path} should be 401`);
  }
  const wrong = await req('POST', '/screenshot', {}, 'Bearer nope');
  assert.equal(wrong.status, 401);
});

test('routes dispatch to their action with the parsed body', async () => {
  calls.length = 0;
  const r = await req('POST', '/click', { x: 120, y: 340, button: 'right' });
  assert.equal(r.status, 200);
  assert.equal(r.json.ok, true);
  assert.equal(r.json.stubbed, true);
  assert.deepEqual(calls, [{ route: 'POST /click', body: { x: 120, y: 340, button: 'right' } }]);
});

test('unknown routes answer 404', async () => {
  const r = await req('POST', '/no-such-route', {});
  assert.equal(r.status, 404);
});

test('action errors map to their status', async () => {
  stub('POST /type', async () => {
    throw Object.assign(new Error('text is required'), { status: 400 });
  });
  const r = await req('POST', '/type', {});
  assert.equal(r.status, 400);
  assert.equal(r.json.error, 'text is required');

  stub('POST /ui', async () => {
    throw Object.assign(new Error('no accessibility'), { status: 501 });
  });
  const r2 = await req('POST', '/ui', {});
  assert.equal(r2.status, 501);
});

test('GET /capabilities requires auth like every other route (CodeQL: user-controlled bypass)', async () => {
  const unauth = await req('GET', '/capabilities', undefined, '');
  assert.equal(unauth.status, 401);
  const r = await req('GET', '/capabilities');
  assert.equal(r.status, 200);
  assert.ok(Array.isArray(r.json.screenshotFormats));
  assert.ok(r.json.screenshotFormats.length > 0);
  assert.ok(r.json.screenshotFormats.every((f) => ['jpeg', 'png'].includes(f)));
  assert.equal(typeof r.json.ui, 'boolean');
  assert.equal(r.json.clipboard, true);
  assert.equal(r.json.stream, false);
});

test('GET /frame returns image bytes with the frame headers', async () => {
  const payload = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3]);
  let seen;
  stub('GET /frame', async (params) => {
    seen = params;
    return { frame: { bytes: payload, format: 'jpeg' } };
  });
  const r = await raw('/frame?scale=0.5&max=800');
  assert.equal(r.status, 200);
  assert.equal(r.headers.get('content-type'), 'image/jpeg');
  assert.equal(r.headers.get('x-frame-format'), 'jpeg');
  assert.deepEqual(r.body, payload);
  assert.deepEqual(seen, { scale: '0.5', max: '800' });
});

test('GET /frame maps a png frame to image/png', async () => {
  const payload = Buffer.from([0x89, 0x50, 0x4e, 0x47]);
  stub('GET /frame', async () => ({ frame: { bytes: payload, format: 'png' } }));
  const r = await raw('/frame');
  assert.equal(r.status, 200);
  assert.equal(r.headers.get('content-type'), 'image/png');
  assert.equal(r.headers.get('x-frame-format'), 'png');
});

test('GET /frame serializes captures - a concurrent call gets 429', async () => {
  let release;
  let entered;
  const gate = new Promise((r) => { release = r; });
  const inside = new Promise((r) => { entered = r; });
  stub('GET /frame', async () => {
    entered();
    await gate;
    return { frame: { bytes: Buffer.from('x'), format: 'jpeg' } };
  });
  const pending = fetch(`${base}/frame`, {
    headers: { authorization: 'Bearer test-secret' },
  });
  await inside; // the first capture holds the busy flag now
  const r = await req('GET', '/frame');
  assert.equal(r.status, 429);
  assert.equal(r.json.error, 'busy');
  release();
  const res = await pending;
  assert.equal(res.status, 200);
});

test('GET /frame failure answers 500 json and frees the slot', async () => {
  stub('GET /frame', async () => {
    throw new Error('capture exploded');
  });
  const r = await req('GET', '/frame');
  assert.equal(r.status, 500);
  assert.equal(r.json.error, 'capture exploded');
  // The busy flag must be released or the next frame would see 429.
  stub('GET /frame', async () => ({
    frame: { bytes: Buffer.from('y'), format: 'jpeg' },
  }));
  const r2 = await raw('/frame');
  assert.equal(r2.status, 200);
});

test('appleQuote escapes quotes and backslashes', () => {
  assert.equal(appleQuote('say "hi"\\path'), 'say \\"hi\\"\\\\path');
});
