#!/usr/bin/env node
'use strict';

/**
 * Home Assistant -> VegaDuta workflow event bridge (roadmap #9.4, "push" half).
 *
 * The MCP server next door (index.js) is the "pull" half: an agent asks HA a
 * question, HA answers. This process is the "push" half: it subscribes to
 * Home Assistant's WebSocket event stream and forwards matching events to a
 * VegaDuta workflow webhook (POST /webhook/workflows/{token}, see
 * core/workflow/WorkflowWebhookController), so an IoT alert - a door
 * unlocking, a motion sensor firing - can trigger an agent/workflow WITHOUT
 * the tenant writing any HA-side automation.
 *
 * Zero npm dependencies, same reasoning as index.js's header: nothing here
 * may assume npm install ever ran. That includes the WebSocket client -
 * Node 20 (the platform's bundled runtime) has no stable global WebSocket
 * class (that landed in Node 21+), so this hand-rolls the small RFC 6455
 * subset a single-purpose client needs:
 *   - HTTP/1.1 GET with Upgrade/Connection headers + random Sec-WebSocket-Key,
 *     validating the 101 response's Sec-WebSocket-Accept echo
 *   - client->server frames masked (mandatory per RFC 6455 5.3)
 *   - server->client text frames parsed, incl. 16-bit and 64-bit extended
 *     payload lengths
 *   - ping (0x9) answered with pong (0xA); close (0x8) treated as a
 *     disconnect; binary (0x2) ignored
 *   - NO extensions offered (no permessage-deflate) and no fragmentation
 *     support: a fin=0 frame gets a stderr warning and is dropped. HA sends
 *     whole JSON messages as single unfragmented text frames in practice.
 *
 * HA WebSocket auth/subscribe protocol
 * (https://developers.home-assistant.io/docs/api/websocket/):
 *   server: {"type":"auth_required"}
 *   client: {"type":"auth","access_token":"..."}
 *   server: {"type":"auth_ok"} (or "auth_invalid" -> fatal, exit 1)
 *   client: {"id":N,"type":"subscribe_events","event_type":"..."} per type
 *   server: {"id":N,"type":"result","success":true}
 *   server: {"id":N,"type":"event","event":{...}} forever after
 *
 * Env config:
 *   HASS_URL             required - e.g. "http://homeassistant.local:8123"
 *                        (same var as index.js; http(s) is mapped to ws(s))
 *   HASS_TOKEN           required - HA long-lived access token (same as index.js)
 *   VEGADUTA_WEBHOOK_URL required - FULL workflow webhook URL, e.g.
 *                        "https://api.staging.example.com/webhook/workflows/<token>"
 *   HASS_EVENT_TYPES     optional - comma-separated HA event types to
 *                        subscribe to; default "state_changed"
 *   HASS_ENTITY_FILTER   optional - comma-separated entity_ids and/or domain
 *                        prefixes ending in "." (e.g. "lock.,binary_sensor.motion_hall").
 *                        When set, only state_changed events whose entity_id
 *                        matches are forwarded; other event types always forward.
 *
 * This is a standalone long-running process (`node event-bridge.js`), NOT an
 * MCP stdio server - stdout is deliberately never written to (so it could be
 * piped anywhere without noise); every log line goes to stderr. It reconnects
 * on socket close/error with exponential backoff (1s doubling to 60s max,
 * reset after a successful auth_ok), and a failed webhook POST is logged and
 * dropped - one bad delivery never crashes the bridge.
 */

const crypto = require('crypto');
const http = require('http');
const https = require('https');

// --- Config / fail-fast ------------------------------------------------------

const HASS_URL = (process.env.HASS_URL || '').replace(/\/+$/, '');
const HASS_TOKEN = process.env.HASS_TOKEN || '';
const VEGADUTA_WEBHOOK_URL = process.env.VEGADUTA_WEBHOOK_URL || '';
const EVENT_TYPES = (process.env.HASS_EVENT_TYPES || 'state_changed')
  .split(',')
  .map((s) => s.trim())
  .filter(Boolean);
const ENTITY_FILTER = (process.env.HASS_ENTITY_FILTER || '')
  .split(',')
  .map((s) => s.trim())
  .filter(Boolean);

function log(msg) {
  process.stderr.write(`home-assistant event bridge: ${msg}\n`);
}

if (require.main === module) {
  // Same fail-fast-and-loud posture as index.js: the message on stderr is
  // what an operator actually sees when a var is missing.
  if (!HASS_URL || !HASS_TOKEN || !VEGADUTA_WEBHOOK_URL) {
    log('HASS_URL, HASS_TOKEN and VEGADUTA_WEBHOOK_URL environment variables are all required.');
    process.exit(1);
  }
  if (EVENT_TYPES.length === 0) {
    log('HASS_EVENT_TYPES is set but contains no event types.');
    process.exit(1);
  }
}

// --- Event filtering / forwarding -------------------------------------------

/**
 * True when a state_changed event's entity_id passes HASS_ENTITY_FILTER.
 * Filter entries ending in "." are domain prefixes ("lock." matches every
 * lock.*); anything else is an exact entity_id match. An empty filter
 * matches everything. Only state_changed is filtered - any other subscribed
 * event type has no entity_id concept and always forwards.
 */
function entityMatchesFilter(entityId) {
  if (ENTITY_FILTER.length === 0) return true;
  if (!entityId) return false;
  return ENTITY_FILTER.some((entry) =>
    entry.endsWith('.') ? entityId.startsWith(entry) : entityId === entry
  );
}

/** One-line human-readable summary - this becomes the workflow Trigger node's input text. */
function summarizeEvent(event) {
  if (event.event_type === 'state_changed') {
    const data = event.data || {};
    const entityId = data.entity_id || 'unknown entity';
    const oldState = data.old_state ? data.old_state.state : null;
    const newState = data.new_state ? data.new_state.state : null;
    if (oldState != null && newState != null) {
      return `Home Assistant: ${entityId} changed from ${oldState} to ${newState}`;
    }
    if (newState != null) {
      return `Home Assistant: ${entityId} is now ${newState}`;
    }
    if (oldState != null) {
      return `Home Assistant: ${entityId} (was ${oldState}) was removed`;
    }
    return `Home Assistant: ${entityId} changed`;
  }
  return `Home Assistant event: ${event.event_type}`;
}

/**
 * POST one event to the VegaDuta workflow webhook. The controller's
 * extractInput() reads the "input" key for the Trigger node's output text;
 * the raw HA event rides alongside for workflows that want the full detail.
 * Failures are logged and swallowed - never crash the bridge over one POST.
 */
async function forwardEvent(event) {
  const body = { input: summarizeEvent(event), event };
  try {
    const res = await fetch(VEGADUTA_WEBHOOK_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      log(`webhook delivery failed: ${res.status} ${res.statusText}${text ? ` - ${text.slice(0, 200)}` : ''}`);
    }
  } catch (err) {
    log(`webhook delivery failed: ${err.message || err}`);
  }
}

function handleEvent(event) {
  if (!event || typeof event !== 'object') return;
  if (event.event_type === 'state_changed') {
    const entityId = event.data && event.data.entity_id;
    if (!entityMatchesFilter(entityId)) return;
  }
  // Fire-and-forget: forwardEvent never rejects (it catches internally), and
  // ordering across deliveries is the webhook's concern, not ours.
  forwardEvent(event);
}

// --- Minimal RFC 6455 WebSocket client ---------------------------------------

const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

/** Frame + mask a client->server payload (masking is mandatory for clients, RFC 6455 5.3). */
function encodeFrame(opcode, payload) {
  const len = payload.length;
  let header;
  if (len < 126) {
    header = Buffer.from([0x80 | opcode, 0x80 | len]);
  } else if (len < 65536) {
    header = Buffer.alloc(4);
    header[0] = 0x80 | opcode;
    header[1] = 0x80 | 126;
    header.writeUInt16BE(len, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x80 | opcode;
    header[1] = 0x80 | 127;
    header.writeBigUInt64BE(BigInt(len), 2);
  }
  const mask = crypto.randomBytes(4);
  const masked = Buffer.allocUnsafe(len);
  for (let i = 0; i < len; i++) masked[i] = payload[i] ^ mask[i & 3];
  return Buffer.concat([header, mask, masked]);
}

/**
 * Incremental frame parser. Feed it raw socket bytes; it invokes callbacks
 * per complete frame. Server->client frames are unmasked per RFC 6455, but
 * a mask bit is honored anyway (harmless, and it keeps the parser symmetric).
 * Returns bytes consumed so the caller keeps the unconsumed tail.
 */
function parseFrames(buf, onFrame) {
  let offset = 0;
  for (;;) {
    if (buf.length - offset < 2) break;
    const b0 = buf[offset];
    const b1 = buf[offset + 1];
    const fin = (b0 & 0x80) !== 0;
    const opcode = b0 & 0x0f;
    const masked = (b1 & 0x80) !== 0;
    let payloadLen = b1 & 0x7f;
    let cursor = offset + 2;
    if (payloadLen === 126) {
      if (buf.length - cursor < 2) break;
      payloadLen = buf.readUInt16BE(cursor);
      cursor += 2;
    } else if (payloadLen === 127) {
      if (buf.length - cursor < 8) break;
      const big = buf.readBigUInt64BE(cursor);
      cursor += 8;
      if (big > BigInt(Number.MAX_SAFE_INTEGER)) {
        // No sane HA message is anywhere near 2^53 bytes - treat as protocol
        // garbage and let the connection-level error handling reconnect.
        throw new Error(`unreasonable WebSocket frame length ${big}`);
      }
      payloadLen = Number(big);
    }
    let maskKey = null;
    if (masked) {
      if (buf.length - cursor < 4) break;
      maskKey = buf.subarray(cursor, cursor + 4);
      cursor += 4;
    }
    if (buf.length - cursor < payloadLen) break;
    let payload = buf.subarray(cursor, cursor + payloadLen);
    if (maskKey) {
      const unmasked = Buffer.allocUnsafe(payloadLen);
      for (let i = 0; i < payloadLen; i++) unmasked[i] = payload[i] ^ maskKey[i & 3];
      payload = unmasked;
    }
    onFrame({ fin, opcode, payload });
    offset = cursor + payloadLen;
  }
  return offset;
}

// --- Connection lifecycle -----------------------------------------------------

const BACKOFF_INITIAL_MS = 1000;
const BACKOFF_MAX_MS = 60000;
let backoffMs = BACKOFF_INITIAL_MS;
let shuttingDown = false;

function scheduleReconnect(reason) {
  if (shuttingDown) return;
  log(`connection lost (${reason}) - reconnecting in ${Math.round(backoffMs / 1000)}s`);
  setTimeout(connect, backoffMs);
  backoffMs = Math.min(backoffMs * 2, BACKOFF_MAX_MS);
}

function connect() {
  if (shuttingDown) return;

  let url;
  try {
    url = new URL(HASS_URL);
  } catch (err) {
    log(`HASS_URL is not a valid URL: ${err.message}`);
    process.exit(1);
  }
  const secure = url.protocol === 'https:' || url.protocol === 'wss:';
  const transport = secure ? https : http;
  const wsKey = crypto.randomBytes(16).toString('base64');
  const expectedAccept = crypto.createHash('sha1').update(wsKey + WS_GUID).digest('base64');

  // "settled" guards the error/close/upgrade races: exactly one outcome
  // (established connection dying, or handshake failure) schedules exactly
  // one reconnect.
  let reconnected = false;
  const failOnce = (reason) => {
    if (reconnected) return;
    reconnected = true;
    scheduleReconnect(reason);
  };

  const req = transport.request({
    host: url.hostname,
    port: url.port || (secure ? 443 : 80),
    path: '/api/websocket',
    method: 'GET',
    headers: {
      Connection: 'Upgrade',
      Upgrade: 'websocket',
      'Sec-WebSocket-Version': '13',
      'Sec-WebSocket-Key': wsKey,
      // Deliberately NO Sec-WebSocket-Extensions header: this client
      // supports none (no permessage-deflate), so it must not offer any.
    },
  });

  req.on('error', (err) => failOnce(`handshake error: ${err.message}`));
  req.on('response', (res) => {
    // Server answered with a normal HTTP response instead of upgrading.
    failOnce(`server refused WebSocket upgrade with HTTP ${res.statusCode}`);
    res.resume();
  });

  req.on('upgrade', (res, socket, head) => {
    if (res.statusCode !== 101 || res.headers['sec-websocket-accept'] !== expectedAccept) {
      failOnce(`invalid WebSocket handshake (status ${res.statusCode}, accept mismatch)`);
      socket.destroy();
      return;
    }
    log(`connected to ${url.hostname}:${url.port || (secure ? 443 : 80)}/api/websocket`);

    let recvBuf = head && head.length ? Buffer.from(head) : Buffer.alloc(0);
    let nextId = 1;

    const sendJson = (obj) => {
      socket.write(encodeFrame(0x1, Buffer.from(JSON.stringify(obj), 'utf8')));
    };

    const onMessage = (text) => {
      let msg;
      try {
        msg = JSON.parse(text);
      } catch (err) {
        log(`ignoring non-JSON text frame: ${err.message}`);
        return;
      }
      switch (msg.type) {
        case 'auth_required':
          sendJson({ type: 'auth', access_token: HASS_TOKEN });
          break;
        case 'auth_ok':
          // Fully authenticated - this is the "successful connection" marker
          // that resets the backoff ladder.
          backoffMs = BACKOFF_INITIAL_MS;
          log(`authenticated (HA ${msg.ha_version || 'unknown version'}); subscribing to: ${EVENT_TYPES.join(', ')}`);
          for (const eventType of EVENT_TYPES) {
            sendJson({ id: nextId++, type: 'subscribe_events', event_type: eventType });
          }
          break;
        case 'auth_invalid':
          // Wrong/expired token is not retryable - reconnecting would just
          // loop forever on the same rejection. Fatal, matching index.js's
          // fail-fast posture on bad credentials.
          log(`Home Assistant rejected HASS_TOKEN: ${msg.message || 'auth_invalid'}`);
          shuttingDown = true;
          socket.destroy();
          process.exit(1);
          break;
        case 'result':
          if (msg.success !== true) {
            log(`subscription request id=${msg.id} failed: ${JSON.stringify(msg.error || msg)}`);
          }
          break;
        case 'event':
          handleEvent(msg.event);
          break;
        default:
          // pong replies to our pings, and anything future - ignore quietly.
          break;
      }
    };

    socket.on('data', (chunk) => {
      recvBuf = recvBuf.length ? Buffer.concat([recvBuf, chunk]) : chunk;
      let consumed;
      try {
        consumed = parseFrames(recvBuf, (frame) => {
          if (!frame.fin) {
            // Fragmentation is out of scope by design (see file header).
            log('dropping unsupported fragmented WebSocket frame (fin=0)');
            return;
          }
          switch (frame.opcode) {
            case 0x1: // text
              onMessage(frame.payload.toString('utf8'));
              break;
            case 0x9: // ping -> pong, echoing the payload per RFC 6455 5.5.3
              socket.write(encodeFrame(0xa, frame.payload));
              break;
            case 0x8: // close - the 'close' handler below reconnects
              socket.destroy();
              break;
            case 0x2: // binary - ignored by design
            case 0xa: // pong
            default:
              break;
          }
        });
      } catch (err) {
        log(`WebSocket protocol error: ${err.message}`);
        socket.destroy();
        return;
      }
      if (consumed > 0) recvBuf = recvBuf.subarray(consumed);
    });

    socket.on('error', (err) => failOnce(`socket error: ${err.message}`));
    socket.on('close', () => failOnce('socket closed'));
  });

  req.end();
}

if (require.main === module) {
  connect();
}

// Exported for tests (mirrors index.js's module.exports pattern).
module.exports = { entityMatchesFilter, summarizeEvent, encodeFrame, parseFrames };
