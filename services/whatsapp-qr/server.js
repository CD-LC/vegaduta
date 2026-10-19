'use strict';

// whatsapp-qr bridge - links a tenant's own WhatsApp number to VegaDuta core as a
// companion device (WhatsApp multi-device protocol, via @whiskeysockets/baileys).
// This is the outbound-only path: the bridge dials out to WhatsApp, so a core on a
// laptop needs no public URL - the same shape as the Signal channel's signal-cli.
//
// Core talks to this service over plain HTTP with a shared Bearer secret; it is
// never reachable from outside the deployment's own network (the compose file
// publishes no port). The WhatsApp session credentials live in DATA_DIR - back it
// up and never share it: it IS the linked device.
//
// API (all calls need `Authorization: Bearer $WHATSAPP_BRIDGE_SECRET`, /health aside):
//   POST   /sessions                 {sessionKey}            -> {status, qr, phoneNumber}   (idempotent ensure)
//   GET    /sessions/{key}           -> {status, qr, phoneNumber}
//   DELETE /sessions/{key}           -> logout, wipe the linked-device credentials
//   POST   /sessions/{key}/send      {to, text}              -> {ok:true}
//   GET    /sessions/{key}/events?timeout=<s>                -> {status, events:[...]}  (long-poll)
//
// status: QR_PENDING | CONNECTED | RECONNECTING | LOGGED_OUT. qr is the raw pairing
// string while QR_PENDING (the console renders it); events carry inbound messages:
//   {id, chatJid, senderJid, senderNumber, pushName, text, timestampMs}
// Groups, broadcasts, status posts and the account's own echoes are never queued.

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const PORT = parseInt(process.env.WHATSAPP_BRIDGE_PORT || '8080', 10);
const SECRET = (process.env.WHATSAPP_BRIDGE_SECRET || '').trim();
const DATA_DIR = process.env.WHATSAPP_BRIDGE_DATA_DIR || '/data';
const QUEUE_CAP = parseInt(process.env.WHATSAPP_BRIDGE_QUEUE_CAP || '500', 10);
const MAX_POLL_SECONDS = 25;
const MAX_BODY_BYTES = 64 * 1024;
const MAX_TEXT_CHARS = 8000;
const RECONNECT_MS = 3000;

const LOGGED_OUT_CODE = 401;

// ---------------------------------------------------------------------------
// Sessions
// ---------------------------------------------------------------------------

function safeAuthDirName(sessionKey) {
  // Never let a tenant-chosen key become a path.
  return 'auth-' + crypto.createHash('sha256').update(sessionKey).digest('hex').slice(0, 24);
}

function digits(value) {
  return (value || '').replace(/[^0-9]/g, '');
}

/** `234...@s.whatsapp.net` / `234...:12@s.whatsapp.net` -> `234...`; `...@lid` keeps its suffix. */
function jidToAddress(jid) {
  if (!jid) return null;
  const [user, server] = jid.split('@');
  if (!user || !server) return null;
  const number = user.split(':')[0];
  if (server === 'lid') return number + '@lid';
  if (server === 's.whatsapp.net') return number;
  return null;
}

/** The `to` field from core: bare digits, or the stored `<digits>@lid` external id. -> send JID. */
function toJid(to) {
  const v = (to || '').trim();
  if (/^[0-9]{5,20}$/.test(v)) return v + '@s.whatsapp.net';
  if (/^[0-9]{5,30}@lid$/.test(v)) return v;
  if (/^[0-9]{5,30}@s\.whatsapp\.net$/.test(v)) return v;
  return null;
}

function isAddressableChat(jid) {
  return typeof jid === 'string' && (jid.endsWith('@s.whatsapp.net') || jid.endsWith('@lid'));
}

/** Best-effort text for the agent: captions count; pure media becomes an attachment note. */
function extractText(message) {
  let m = message.message || {};
  // Unwrap disappearing/view-once envelopes (rare from strangers, cheap to support).
  if (m.ephemeralMessage) m = m.ephemeralMessage.message || m;
  if (m.viewOnceMessageV2) m = m.viewOnceMessageV2.message || m;
  if (m.viewOnceMessage) m = m.viewOnceMessage.message || m;
  const mediaKind =
    ['imageMessage', 'videoMessage', 'audioMessage', 'documentMessage', 'stickerMessage']
      .find((k) => m[k]) || null;
  const text =
    m.conversation ||
    (m.extendedTextMessage && m.extendedTextMessage.text) ||
    (mediaKind && m[mediaKind].caption) ||
    '';
  if (text && text.trim()) return text.trim().slice(0, MAX_TEXT_CHARS);
  if (mediaKind) return '[attachment: ' + mediaKind.replace('Message', '').toLowerCase() + ']';
  return '';
}

function createSessionManager({ dataDir, socketFactory, log }) {
  const sessions = new Map();

  function recordEvent(session, event) {
    session.queue.push(event);
    while (session.queue.length > QUEUE_CAP) session.queue.shift();
    flushWaiters(session);
  }

  function setStatus(session, status, patch) {
    session.status = status;
    Object.assign(session, patch || {});
    flushWaiters(session);
  }

  function flushWaiters(session) {
    const waiters = session.waiters;
    session.waiters = [];
    for (const w of waiters) {
      clearTimeout(w.timer);
      w.resolve(view(session));
    }
  }

  function view(session) {
    const events = session.queue.splice(0, session.queue.length);
    return {
      status: session.status,
      qr: session.status === 'QR_PENDING' ? session.qr : null,
      phoneNumber: session.phoneNumber,
      events,
    };
  }

  function onMessage(session, msg) {
    const key = msg.key || {};
    if (key.fromMe) return;
    const chatJid = key.remoteJid;
    if (!isAddressableChat(chatJid)) return; // drops groups, broadcasts, newsletters, status
    const text = extractText(msg);
    if (!text) return;
    const address = jidToAddress(chatJid);
    recordEvent(session, {
      id: String(key.id || ''),
      chatJid,
      senderJid: chatJid,
      senderNumber: address && address.endsWith('@lid') ? null : address,
      pushName: typeof msg.pushName === 'string' ? msg.pushName.slice(0, 100) : null,
      text,
      timestampMs: (msg.messageTimestamp ? Number(msg.messageTimestamp) * 1000 : Date.now()),
    });
  }

  async function start(session) {
    const authDir = path.join(dataDir, safeAuthDirName(session.key));
    fs.mkdirSync(authDir, { recursive: true });
    let socket;
    try {
      socket = await socketFactory(authDir, session.key);
    } catch (e) {
      log({ level: 'error', msg: 'socket factory failed', session: session.key, err: String(e && e.message || e) });
      session.retries += 1;
      session.retryTimer = setTimeout(() => start(session), Math.min(RECONNECT_MS * session.retries, 30000));
      return;
    }
    const { sock, saveCreds } = socket;
    session.sock = sock;
    session.retries = 0;
    if (saveCreds) sock.ev.on('creds.update', saveCreds);
    sock.ev.on('messages.upsert', ({ messages, type }) => {
      if (type !== 'notify') return; // 'append' is history sync - never replay into the agent
      for (const m of messages || []) onMessage(session, m);
    });
    sock.ev.on('connection.update', (update) => {
      if (update.qr) {
        setStatus(session, 'QR_PENDING', { qr: update.qr });
      }
      if (update.connection === 'open') {
        setStatus(session, 'CONNECTED', {
          qr: null,
          phoneNumber: jidToAddress(sock.user && sock.user.id) || session.phoneNumber,
        });
        log({ level: 'info', msg: 'session connected', session: session.key, phone: session.phoneNumber });
      }
      if (update.connection === 'close') {
        const statusCode =
          update.lastDisconnect && update.lastDisconnect.error &&
          update.lastDisconnect.error.output && update.lastDisconnect.error.output.statusCode;
        session.sock = null;
        if (statusCode === LOGGED_OUT_CODE) {
          // The phone unlinked us (or logout() ran): the stored credentials are dead.
          setStatus(session, 'LOGGED_OUT', { qr: null });
          log({ level: 'info', msg: 'session logged out', session: session.key });
        } else {
          setStatus(session, 'RECONNECTING', {});
          session.retryTimer = setTimeout(() => start(session), RECONNECT_MS);
        }
      }
    });
  }

  async function ensure(sessionKey) {
    let session = sessions.get(sessionKey);
    if (session) return session;
    session = {
      key: sessionKey,
      sock: null,
      status: 'QR_PENDING',
      qr: null,
      phoneNumber: null,
      queue: [],
      waiters: [],
      retries: 0,
      retryTimer: null,
    };
    sessions.set(sessionKey, session);
    await start(session);
    return session;
  }

  // Restart-safe resume: auth dirs persist on disk but their names are hashes, so a
  // rebooted bridge lazily recreates a socket the first time core calls POST
  // /sessions for the key it holds - no directory scan needed here.

  async function logout(session) {
    if (session.retryTimer) clearTimeout(session.retryTimer);
    const sock = session.sock;
    try {
      if (sock) await sock.logout();
    } catch (e) {
      // Logout best effort - the auth dir is wiped either way.
    }
    try {
      fs.rmSync(path.join(dataDir, safeAuthDirName(session.key)), { recursive: true, force: true });
    } catch (e) {
      // best effort
    }
    setStatus(session, 'LOGGED_OUT', { qr: null });
    sessions.delete(session.key);
  }

  function waitEvents(session, timeoutMs) {
    return new Promise((resolve) => {
      // Wait while the queue is empty: any new event OR status transition (a QR
      // scan completing, a logout, a reconnect) wakes the poll early, and the
      // caller re-reads `status` from the same response.
      if (session.queue.length > 0) {
        resolve(view(session));
        return;
      }
      const waiter = {
        resolve: () => resolve(view(session)),
        timer: setTimeout(() => {
          session.waiters = session.waiters.filter((w) => w !== waiter);
          resolve(view(session));
        }, timeoutMs),
      };
      session.waiters.push(waiter);
    });
  }

  return { ensure, view, waitEvents, logout, get: (k) => sessions.get(k) };
}

// ---------------------------------------------------------------------------
// Real Baileys socket factory (injectable for tests)
// ---------------------------------------------------------------------------

function baileysSocketFactory(log) {
  let cached = null;
  async function load() {
    if (cached) return cached;
    const baileys = require('@whiskeysockets/baileys');
    const makeWASocket = baileys.default || baileys.makeWASocket;
    cached = { makeWASocket, useMultiFileAuthState: baileys.useMultiFileAuthState, fetchLatestBaileysVersion: baileys.fetchLatestBaileysVersion };
    return cached;
  }
  return async (authDir) => {
    const lib = await load();
    const { state, saveCreds } = await lib.useMultiFileAuthState(authDir);
    let version;
    try {
      version = (await lib.fetchLatestBaileysVersion()).version;
    } catch (e) {
      version = undefined; // baileys ships a default; a failed lookup must not stop pairing
    }
    const sock = lib.makeWASocket({
      version,
      auth: state,
      printQRInTerminal: false,
      browser: ['VegaDuta', 'Chrome', '1.0.0'],
      generateHighQualityLinkPreview: false,
      logger: baileysPino(log),
    });
    return { sock, saveCreds };
  };
}

/** Baileys logs through pino; map it to one-line JSON so it lands in the service's log stream. */
function baileysPino(log) {
  const noop = () => {};
  const write = (level) => (obj, msg) => {
    try {
      log({ level, msg: 'baileys', detail: typeof obj === 'string' ? obj : msg || JSON.stringify(obj).slice(0, 300) });
    } catch (e) { /* never throw from a log line */ }
  };
  return {
    level: 'warn',
    trace: noop,
    debug: noop,
    info: noop,
    warn: write('warn'),
    error: write('error'),
    fatal: write('error'),
    child: () => baileysPino(log),
  };
}

// ---------------------------------------------------------------------------
// HTTP surface
// ---------------------------------------------------------------------------

function json(res, code, body) {
  const data = JSON.stringify(body);
  res.writeHead(code, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data) });
  res.end(data);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY_BYTES) {
        reject(new Error('body too large'));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function authorized(req, secret) {
  const header = req.headers['authorization'] || '';
  const presented = header.startsWith('Bearer ') ? header.slice(7) : '';
  return presented.length > 0 &&
    presented.length === secret.length &&
    crypto.timingSafeEqual(Buffer.from(presented), Buffer.from(secret));
}

function createServer({ manager, secret, log }) {
  return http.createServer(async (req, res) => {
    try {
      const url = new URL(req.url, 'http://localhost');
      const parts = url.pathname.split('/').filter(Boolean);

      if (req.method === 'GET' && url.pathname === '/health') {
        return json(res, 200, { status: 'ok' });
      }
      if (!authorized(req, secret)) {
        return json(res, 401, { error: 'unauthorized' });
      }

      // POST /sessions
      if (req.method === 'POST' && parts.length === 1 && parts[0] === 'sessions') {
        let body = {};
        try {
          body = JSON.parse((await readBody(req)) || '{}');
        } catch (e) {
          return json(res, 400, { error: 'invalid JSON body' });
        }
        const sessionKey = String(body.sessionKey || '').trim();
        if (!/^[\w-]{8,80}$/.test(sessionKey)) {
          return json(res, 400, { error: 'sessionKey must be 8-80 word characters' });
        }
        const session = await manager.ensure(sessionKey);
        const v = manager.view(session);
        return json(res, 200, { status: v.status, qr: v.qr, phoneNumber: v.phoneNumber });
      }

      if (parts.length === 2 && parts[0] === 'sessions') {
        const key = parts[1];
        const session = manager.get(key);
        if (!session) return json(res, 404, { error: 'unknown session' });

        if (req.method === 'GET') {
          const v = manager.view(session);
          return json(res, 200, { status: v.status, qr: v.qr, phoneNumber: v.phoneNumber });
        }
        if (req.method === 'DELETE') {
          await manager.logout(session);
          return json(res, 204, {});
        }
      }

      if (parts.length === 3 && parts[0] === 'sessions') {
        const key = parts[1];
        const action = parts[2];
        const session = manager.get(key);
        if (!session) return json(res, 404, { error: 'unknown session' });

        if (req.method === 'GET' && action === 'events') {
          const timeout = Math.max(1, Math.min(parseInt(url.searchParams.get('timeout') || '15', 10) || 15, MAX_POLL_SECONDS));
          const result = await manager.waitEvents(session, timeout * 1000);
          return json(res, 200, result);
        }

        if (req.method === 'POST' && action === 'send') {
          if (session.status !== 'CONNECTED' || !session.sock) {
            return json(res, 409, { error: 'session is not connected', status: session.status });
          }
          let body = {};
          try {
            body = JSON.parse((await readBody(req)) || '{}');
          } catch (e) {
            return json(res, 400, { error: 'invalid JSON body' });
          }
          const jid = toJid(body.to);
          const text = String(body.text || '');
          if (!jid) return json(res, 400, { error: 'to must be a number or <digits>@lid' });
          if (!text.trim() || text.length > MAX_TEXT_CHARS) {
            return json(res, 400, { error: 'text must be 1-' + MAX_TEXT_CHARS + ' characters' });
          }
          await session.sock.sendMessage(jid, { text });
          return json(res, 200, { ok: true });
        }
      }

      return json(res, 404, { error: 'not found' });
    } catch (e) {
      log({ level: 'error', msg: 'request failed', err: String(e && e.message || e) });
      return json(res, 500, { error: 'internal error' });
    }
  });
}

// ---------------------------------------------------------------------------

function jsonLog(obj) {
  process.stdout.write(JSON.stringify({ ts: new Date().toISOString(), ...obj }) + '\n');
}

async function main() {
  if (!SECRET) {
    jsonLog({ level: 'fatal', msg: 'WHATSAPP_BRIDGE_SECRET is required - the bridge holds linked-device credentials' });
    process.exit(1);
  }
  fs.mkdirSync(DATA_DIR, { recursive: true });
  const manager = createSessionManager({ dataDir: DATA_DIR, socketFactory: baileysSocketFactory(jsonLog), log: jsonLog });
  const server = createServer({ manager, secret: SECRET, log: jsonLog });
  server.listen(PORT, () => jsonLog({ level: 'info', msg: 'whatsapp-qr bridge listening', port: PORT, dataDir: DATA_DIR }));
}

if (require.main === module) {
  main().catch((e) => {
    jsonLog({ level: 'fatal', msg: 'startup failed', err: String(e && e.message || e) });
    process.exit(1);
  });
}

module.exports = { createServer, createSessionManager, toJid, jidToAddress, extractText, safeAuthDirName };
