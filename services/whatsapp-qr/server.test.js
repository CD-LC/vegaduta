'use strict';

// Tests for the whatsapp-qr bridge HTTP surface and session manager, against a
// fake Baileys socket (no network, no real WhatsApp): `node --test`.

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const { createServer, createSessionManager, toJid, jidToAddress, extractText } = require('./server');

const SECRET = 'test-secret-0123456789';

// A minimal EventEmitter-shaped fake socket: ev.on registers, ev.emit fires.
function fakeSocket() {
  const handlers = new Map();
  const sent = [];
  return {
    user: null,
    sent,
    loggedOut: false,
    ev: {
      on(name, fn) {
        handlers.set(name, fn);
      },
      emit(name, payload) {
        if (handlers.has(name)) handlers.get(name)(payload);
      },
    },
    async sendMessage(jid, content) {
      sent.push({ jid, content });
      return { key: { id: 'x' } };
    },
    async logout() {
      this.loggedOut = true;
    },
  };
}

function fakeFactory(socket) {
  const calls = [];
  return {
    calls,
    factory: async () => ({ sock: socket, saveCreds: () => {} }),
  };
}

function tmpDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'waqr-test-'));
}

function noopLog() {}

async function startServer(manager) {
  const server = createServer({ manager, secret: SECRET, log: noopLog });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  return { server, base };
}

async function call(base, method, p, { body, auth = true } = {}) {
  const res = await fetch(base + p, {
    method,
    headers: {
      ...(auth ? { Authorization: `Bearer ${SECRET}` } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  let json = null;
  const text = await res.text();
  try {
    json = JSON.parse(text);
  } catch (e) {
    /* 204s are empty */
  }
  return { status: res.status, json };
}

// ---------------------------------------------------------------- helpers

test('toJid accepts numbers and lid ids only', () => {
  assert.equal(toJid('4915112345678'), '4915112345678@s.whatsapp.net');
  assert.equal(toJid('12345@lid'), '12345@lid');
  assert.equal(toJid('67890@s.whatsapp.net'), '67890@s.whatsapp.net');
  assert.equal(toJid('+49 151 1234'), null);
  assert.equal(toJid('evil@g.us'), null);
  assert.equal(toJid(''), null);
  assert.equal(toJid(null), null);
});

test('jidToAddress strips device suffix and keeps @lid', () => {
  assert.equal(jidToAddress('4915112345678:12@s.whatsapp.net'), '4915112345678');
  assert.equal(jidToAddress('778899@lid'), '778899@lid');
  assert.equal(jidToAddress('group@g.us'), null);
  assert.equal(jidToAddress('bad'), null);
});

test('extractText covers plain, extended, captions and media notes', () => {
  assert.equal(extractText({ message: { conversation: 'hi' } }), 'hi');
  assert.equal(extractText({ message: { extendedTextMessage: { text: 'hello' } } }), 'hello');
  assert.equal(extractText({ message: { imageMessage: { caption: 'look' } } }), 'look');
  assert.equal(extractText({ message: { imageMessage: {} } }), '[attachment: image]');
  assert.equal(
    extractText({ message: { ephemeralMessage: { message: { conversation: 'poof' } } } }),
    'poof'
  );
  assert.equal(extractText({ message: {} }), '');
});

// ---------------------------------------------------------------- HTTP

test('auth is required on every endpoint except /health', async () => {
  const socket = fakeSocket();
  const { factory } = fakeFactory(socket);
  const manager = createSessionManager({ dataDir: tmpDir(), socketFactory: factory, log: noopLog });
  const { server, base } = await startServer(manager);
  try {
    assert.equal((await call(base, 'GET', '/health', { auth: false })).status, 200);
    assert.equal((await call(base, 'POST', '/sessions', { body: { sessionKey: 'aaaaaaaa-bbbb' }, auth: false })).status, 401);
    assert.equal((await call(base, 'GET', '/sessions/x', { auth: false })).status, 401);
  } finally {
    server.close();
  }
});

test('pairing lifecycle: create -> qr -> connected -> send -> logout', async () => {
  const socket = fakeSocket();
  const { factory } = fakeFactory(socket);
  const manager = createSessionManager({ dataDir: tmpDir(), socketFactory: factory, log: noopLog });
  const { server, base } = await startServer(manager);
  const key = 'sessionkey-0001';
  try {
    const created = await call(base, 'POST', '/sessions', { body: { sessionKey: key } });
    assert.equal(created.status, 200);
    assert.equal(created.json.status, 'QR_PENDING');

    socket.ev.emit('connection.update', { qr: '2@fakewaqrcode' });
    const pending = await call(base, 'GET', `/sessions/${key}`);
    assert.equal(pending.json.status, 'QR_PENDING');
    assert.equal(pending.json.qr, '2@fakewaqrcode');

    socket.user = { id: '4915112345678:3@s.whatsapp.net' };
    socket.ev.emit('connection.update', { connection: 'open' });
    const connected = await call(base, 'GET', `/sessions/${key}`);
    assert.equal(connected.json.status, 'CONNECTED');
    assert.equal(connected.json.phoneNumber, '4915112345678');
    assert.equal(connected.json.qr, null);

    const sent = await call(base, 'POST', `/sessions/${key}/send`, { body: { to: '4915100000000', text: 'hello' } });
    assert.equal(sent.status, 200);
    assert.deepEqual(socket.sent[0].jid, '4915100000000@s.whatsapp.net');

    await call(base, 'DELETE', `/sessions/${key}`);
    assert.equal(socket.loggedOut, true);
    assert.equal((await call(base, 'GET', `/sessions/${key}`)).status, 404);
  } finally {
    server.close();
  }
});

test('send is refused before the session is connected', async () => {
  const socket = fakeSocket();
  const manager = createSessionManager({ dataDir: tmpDir(), socketFactory: fakeFactory(socket).factory, log: noopLog });
  const { server, base } = await startServer(manager);
  try {
    await call(base, 'POST', '/sessions', { body: { sessionKey: 'sessionkey-0002' } });
    const res = await call(base, 'POST', '/sessions/sessionkey-0002/send', { body: { to: '4915100000000', text: 'hi' } });
    assert.equal(res.status, 409);
  } finally {
    server.close();
  }
});

test('inbound messages queue, own echoes and groups are dropped, events long-poll drains', async () => {
  const socket = fakeSocket();
  const manager = createSessionManager({ dataDir: tmpDir(), socketFactory: fakeFactory(socket).factory, log: noopLog });
  const { server, base } = await startServer(manager);
  const key = 'sessionkey-0003';
  try {
    await call(base, 'POST', '/sessions', { body: { sessionKey: key } });
    socket.user = { id: '4915112345678:3@s.whatsapp.net' };
    socket.ev.emit('connection.update', { connection: 'open' });

    socket.ev.emit('messages.upsert', {
      type: 'notify',
      messages: [
        { key: { id: 'A1', remoteJid: '4915100000000@s.whatsapp.net', fromMe: false }, pushName: 'Ann', messageTimestamp: 1000, message: { conversation: 'hi' } },
        { key: { id: 'A2', remoteJid: '4915112345678@s.whatsapp.net', fromMe: true }, messageTimestamp: 1000, message: { conversation: 'echo' } },
        { key: { id: 'A3', remoteJid: '1234@g.us', participant: '4915@s.whatsapp.net', fromMe: false }, messageTimestamp: 1000, message: { conversation: 'group' } },
        { key: { id: 'A4', remoteJid: '99887@lid', fromMe: false }, pushName: 'Lid', messageTimestamp: 1001, message: { conversation: 'hidden' } },
      ],
    });

    const res = await call(base, 'GET', `/sessions/${key}/events?timeout=1`);
    assert.equal(res.status, 200);
    assert.equal(res.json.status, 'CONNECTED');
    assert.equal(res.json.events.length, 2);
    assert.equal(res.json.events[0].chatJid, '4915100000000@s.whatsapp.net');
    assert.equal(res.json.events[0].senderNumber, '4915100000000');
    assert.equal(res.json.events[0].text, 'hi');
    assert.equal(res.json.events[1].chatJid, '99887@lid');
    assert.equal(res.json.events[1].senderNumber, null);
    // Drained: a second poll returns no events.
    const again = await call(base, 'GET', `/sessions/${key}/events?timeout=1`);
    assert.equal(again.json.events.length, 0);
    // History sync ('append') is never queued.
    socket.ev.emit('messages.upsert', { type: 'append', messages: [{ key: { id: 'H1', remoteJid: '4915@s.whatsapp.net', fromMe: false }, message: { conversation: 'old' } }] });
    const after = await call(base, 'GET', `/sessions/${key}/events?timeout=1`);
    assert.equal(after.json.events.length, 0);
  } finally {
    server.close();
  }
});

test('long-poll wakes early on a status transition', async () => {
  const socket = fakeSocket();
  const manager = createSessionManager({ dataDir: tmpDir(), socketFactory: fakeFactory(socket).factory, log: noopLog });
  const { server, base } = await startServer(manager);
  const key = 'sessionkey-0004';
  try {
    await call(base, 'POST', '/sessions', { body: { sessionKey: key } });
    const poll = call(base, 'GET', `/sessions/${key}/events?timeout=10`);
    await new Promise((r) => setTimeout(r, 100)); // let the poll register its waiter
    const t0 = Date.now();
    socket.ev.emit('connection.update', { qr: '2@newqr' });
    const res = await poll;
    assert.ok(Date.now() - t0 < 5000, 'poll should wake on the transition, not run the full timeout');
    assert.equal(res.json.status, 'QR_PENDING');
    assert.equal(res.json.qr, '2@newqr');
  } finally {
    server.close();
  }
});

test('logged-out close marks the session and stops reconnecting to a dead device', async () => {
  const socket = fakeSocket();
  const f = fakeFactory(socket);
  const dataDir = tmpDir();
  const manager = createSessionManager({ dataDir, socketFactory: f.factory, log: noopLog });
  const key = 'sessionkey-0005';
  await manager.ensure(key);
  socket.ev.emit('connection.update', { connection: 'close', lastDisconnect: { error: { output: { statusCode: 401 } } } });
  assert.equal(manager.get(key).status, 'LOGGED_OUT');
  assert.equal(f.calls.length, 0); // factory count unchanged - no reconnect attempted
});
