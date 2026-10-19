'use strict';

/**
 * Real, executable integration test for event-bridge.js - same posture as
 * test/server.test.js (framework-free, `node test/event-bridge.test.js`,
 * non-zero exit on failure, zero npm dependencies).
 *
 * Stands up:
 *   (a) a hand-rolled WebSocket SERVER (http.createServer + the 'upgrade'
 *       event) speaking Home Assistant's auth/subscribe/event protocol.
 *       Server->client frames are unmasked (the simple side of RFC 6455);
 *       client frames are unmasked here before reading, and the test asserts
 *       the bridge masked every one of them as RFC 6455 5.3 requires.
 *   (b) a plain HTTP server standing in for the VegaDuta workflow webhook
 *       (POST /webhook/workflows/{token}), recording every delivery and able
 *       to fail one on demand with a 500.
 *
 * Then spawns event-bridge.js as a real child process pointed at both and
 * asserts the full push pipeline: auth handshake with the right token,
 * subscribe_events per configured type, matching events delivered with the
 * expected {"input", "event"} body, filtered-out entities NOT delivered,
 * non-state_changed events always delivered, and the process surviving a
 * webhook 500. One event payload is padded past 65535 bytes to exercise the
 * client's 64-bit extended-length frame parsing.
 */

const crypto = require('crypto');
const http = require('http');
const path = require('path');
const { spawn } = require('child_process');

const FAKE_TOKEN = 'test-ha-token-xyz789';
const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

// --- Assert helpers (copied from test/server.test.js, deliberately not
// imported across test files - each test file stays standalone) -------------

let failures = 0;
/** Stable stringify (keys sorted recursively) so assertEqual doesn't false-fail on object key order. */
function stableStringify(value) {
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(',')}]`;
  if (value && typeof value === 'object') {
    const keys = Object.keys(value).sort();
    return `{${keys.map((k) => `${JSON.stringify(k)}:${stableStringify(value[k])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}
function assertEqual(actual, expected, label) {
  const a = stableStringify(actual);
  const e = stableStringify(expected);
  if (a !== e) {
    failures++;
    console.error(`FAIL: ${label}\n  expected: ${e}\n  actual:   ${a}`);
  } else {
    console.log(`PASS: ${label}`);
  }
}
function assertTrue(cond, label) {
  if (!cond) {
    failures++;
    console.error(`FAIL: ${label}`);
  } else {
    console.log(`PASS: ${label}`);
  }
}

/** Poll `cond` every 25ms until true or the (generous-but-bounded) deadline. */
async function waitFor(cond, timeoutMs = 10000) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    if (cond()) return true;
    if (Date.now() >= deadline) return false;
    await new Promise((r) => setTimeout(r, 25));
  }
}

// --- Minimal RFC 6455 framing for the SERVER side ---------------------------

/** Server->client frames are unmasked - just header + payload. */
function serverTextFrame(text) {
  const payload = Buffer.from(text, 'utf8');
  const len = payload.length;
  let header;
  if (len < 126) {
    header = Buffer.from([0x81, len]);
  } else if (len < 65536) {
    header = Buffer.alloc(4);
    header[0] = 0x81;
    header[1] = 126;
    header.writeUInt16BE(len, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x81;
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(len), 2);
  }
  return Buffer.concat([header, payload]);
}

/**
 * Parse complete frames out of `buf`, unmasking client frames to read them.
 * Calls onFrame({ fin, opcode, masked, payload }); returns bytes consumed.
 */
function parseClientFrames(buf, onFrame) {
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
      payloadLen = Number(buf.readBigUInt64BE(cursor));
      cursor += 8;
    }
    let maskKey = null;
    if (masked) {
      if (buf.length - cursor < 4) break;
      maskKey = buf.subarray(cursor, cursor + 4);
      cursor += 4;
    }
    if (buf.length - cursor < payloadLen) break;
    let payload = Buffer.from(buf.subarray(cursor, cursor + payloadLen));
    if (maskKey) {
      for (let i = 0; i < payloadLen; i++) payload[i] ^= maskKey[i & 3];
    }
    onFrame({ fin, opcode, masked, payload });
    offset = cursor + payloadLen;
  }
  return offset;
}

// --- Fake Home Assistant WebSocket server -----------------------------------

function startFakeHaWebSocket() {
  const state = {
    authTokens: [], // every access_token the bridge sent in an auth message
    subscriptions: [], // { id, event_type } per subscribe_events received
    unmaskedClientFrames: 0, // RFC violation counter - must stay 0
    socket: null, // most recent authenticated connection
  };

  const server = http.createServer((req, res) => {
    res.writeHead(404);
    res.end();
  });

  server.on('upgrade', (req, socket) => {
    if (req.url !== '/api/websocket') {
      socket.destroy();
      return;
    }
    const key = req.headers['sec-websocket-key'];
    const accept = crypto.createHash('sha1').update(key + WS_GUID).digest('base64');
    socket.write(
      'HTTP/1.1 101 Switching Protocols\r\n' +
        'Upgrade: websocket\r\n' +
        'Connection: Upgrade\r\n' +
        `Sec-WebSocket-Accept: ${accept}\r\n\r\n`
    );
    state.socket = socket;

    let recvBuf = Buffer.alloc(0);
    socket.on('data', (chunk) => {
      recvBuf = Buffer.concat([recvBuf, chunk]);
      const consumed = parseClientFrames(recvBuf, (frame) => {
        if (!frame.masked) state.unmaskedClientFrames++;
        if (frame.opcode !== 0x1) return; // only text frames matter to this fake
        let msg;
        try {
          msg = JSON.parse(frame.payload.toString('utf8'));
        } catch {
          return;
        }
        if (msg.type === 'auth') {
          state.authTokens.push(msg.access_token);
          if (msg.access_token === FAKE_TOKEN) {
            socket.write(serverTextFrame(JSON.stringify({ type: 'auth_ok', ha_version: '2026.7.0' })));
          } else {
            socket.write(serverTextFrame(JSON.stringify({ type: 'auth_invalid', message: 'Invalid access token' })));
          }
        } else if (msg.type === 'subscribe_events') {
          state.subscriptions.push({ id: msg.id, event_type: msg.event_type });
          socket.write(serverTextFrame(JSON.stringify({ id: msg.id, type: 'result', success: true, result: null })));
        }
      });
      if (consumed > 0) recvBuf = recvBuf.subarray(consumed);
    });
    socket.on('error', () => {});

    // Kick off the HA handshake exactly as a real HA instance does.
    socket.write(serverTextFrame(JSON.stringify({ type: 'auth_required', ha_version: '2026.7.0' })));
  });

  /** Push one HA event to the connected bridge, tagged with its subscription id. */
  state.emitEvent = (event) => {
    const sub = state.subscriptions.find((s) => s.event_type === event.event_type);
    const id = sub ? sub.id : 1;
    state.socket.write(serverTextFrame(JSON.stringify({ id, type: 'event', event })));
  };

  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => resolve({ server, state }));
  });
}

// --- Fake VegaDuta workflow webhook ------------------------------------------

function startFakeWebhook() {
  const state = {
    calls: [], // { url, body } per delivery, recorded even when we answer 500
    failNext: false,
  };
  const server = http.createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      let body = null;
      try {
        body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
      } catch {
        // keep null - the assertion on the body will fail loudly
      }
      state.calls.push({ url: req.url, body });
      if (state.failNext) {
        state.failNext = false;
        res.writeHead(500, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ error: 'simulated workflow outage' }));
        return;
      }
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ runId: 'fake-run' }));
    });
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => resolve({ server, state }));
  });
}

// --- The test ----------------------------------------------------------------

const BRIDGE_PATH = path.join(__dirname, '..', 'event-bridge.js');

function spawnBridge(env) {
  const child = spawn(process.execPath, [BRIDGE_PATH], {
    env: { ...process.env, ...env },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let stderrBuf = '';
  let stdoutBuf = '';
  child.stderr.on('data', (d) => {
    stderrBuf += d.toString();
  });
  child.stdout.on('data', (d) => {
    stdoutBuf += d.toString();
  });
  return { child, getStderr: () => stderrBuf, getStdout: () => stdoutBuf };
}

async function testMissingEnvFailsFast() {
  // No VEGADUTA_WEBHOOK_URL -> must exit 1 with a clear stderr message.
  const { child, getStderr } = spawnBridge({
    HASS_URL: 'http://127.0.0.1:9',
    HASS_TOKEN: 'whatever',
    VEGADUTA_WEBHOOK_URL: '',
  });
  try {
    const exited = await waitFor(() => child.exitCode !== null, 10000);
    assertTrue(exited && child.exitCode === 1, 'missing VEGADUTA_WEBHOOK_URL exits 1');
    assertTrue(
      getStderr().includes('VEGADUTA_WEBHOOK_URL'),
      'missing-env stderr names the missing variable'
    );
  } finally {
    child.kill();
  }
}

async function testFullPipeline() {
  const ha = await startFakeHaWebSocket();
  const webhook = await startFakeWebhook();
  const haUrl = `http://127.0.0.1:${ha.server.address().port}`;
  const webhookUrl = `http://127.0.0.1:${webhook.server.address().port}/webhook/workflows/test-token-1234`;

  const { child, getStderr, getStdout } = spawnBridge({
    HASS_URL: haUrl,
    HASS_TOKEN: FAKE_TOKEN,
    VEGADUTA_WEBHOOK_URL: webhookUrl,
    HASS_EVENT_TYPES: 'state_changed, automation_triggered',
    HASS_ENTITY_FILTER: 'lock., binary_sensor.motion_hall',
  });

  try {
    // 1. Auth handshake completes with the configured token.
    assertTrue(await waitFor(() => ha.state.authTokens.length >= 1), 'bridge sends an auth message');
    assertEqual(ha.state.authTokens[0], FAKE_TOKEN, 'auth message carries HASS_TOKEN');

    // 2. One subscribe_events per configured event type, each acked.
    assertTrue(await waitFor(() => ha.state.subscriptions.length >= 2), 'bridge subscribes after auth_ok');
    assertEqual(
      ha.state.subscriptions.map((s) => s.event_type).sort(),
      ['automation_triggered', 'state_changed'],
      'subscribe_events sent for each configured event type'
    );
    const subIds = ha.state.subscriptions.map((s) => s.id);
    assertEqual(new Set(subIds).size, subIds.length, 'each subscription uses a distinct id');
    assertEqual(ha.state.unmaskedClientFrames, 0, 'every client frame was masked (RFC 6455 5.3)');

    // 3. Filtered-out entity first (must NOT be forwarded), then a matching
    //    one. The filter decision is synchronous inside the bridge, so if
    //    light.kitchen were wrongly forwarded its POST would start before the
    //    lock's - it never showing up (here and in the final count) is the
    //    negative assertion.
    ha.state.emitEvent({
      event_type: 'state_changed',
      data: {
        entity_id: 'light.kitchen',
        old_state: { entity_id: 'light.kitchen', state: 'off' },
        new_state: { entity_id: 'light.kitchen', state: 'on' },
      },
      origin: 'LOCAL',
      time_fired: '2026-08-17T10:00:00.000000+00:00',
    });
    const lockEvent = {
      event_type: 'state_changed',
      data: {
        entity_id: 'lock.front_door',
        old_state: { entity_id: 'lock.front_door', state: 'locked' },
        new_state: { entity_id: 'lock.front_door', state: 'unlocked' },
      },
      origin: 'LOCAL',
      time_fired: '2026-08-17T10:00:01.000000+00:00',
      context: { id: 'ctx-1', parent_id: null, user_id: null },
    };
    ha.state.emitEvent(lockEvent);

    assertTrue(await waitFor(() => webhook.state.calls.length >= 1), 'matching event reaches the webhook');
    assertEqual(webhook.state.calls[0].url, '/webhook/workflows/test-token-1234', 'delivery hits the full webhook path');
    assertEqual(
      webhook.state.calls[0].body,
      {
        input: 'Home Assistant: lock.front_door changed from locked to unlocked',
        event: lockEvent,
      },
      'delivery body is {input: summary, event: raw HA event}'
    );

    // 4. Non-state_changed events always forward, even with an entity filter set.
    ha.state.emitEvent({
      event_type: 'automation_triggered',
      data: { name: 'Night mode', entity_id: 'automation.night_mode' },
      origin: 'LOCAL',
      time_fired: '2026-08-17T10:00:02.000000+00:00',
    });
    assertTrue(await waitFor(() => webhook.state.calls.length >= 2), 'non-state_changed event reaches the webhook');
    assertEqual(
      webhook.state.calls[1].body.input,
      'Home Assistant event: automation_triggered',
      'non-state_changed event gets the generic summary'
    );

    // 5. A webhook 500 is logged and survived - the next delivery still lands.
    //    This event also pads past 65535 bytes to exercise the bridge's
    //    64-bit extended-length frame parsing.
    webhook.state.failNext = true;
    ha.state.emitEvent({
      event_type: 'state_changed',
      data: {
        entity_id: 'binary_sensor.motion_hall',
        old_state: { entity_id: 'binary_sensor.motion_hall', state: 'off' },
        new_state: {
          entity_id: 'binary_sensor.motion_hall',
          state: 'on',
          attributes: { padding: 'x'.repeat(70000) },
        },
      },
      origin: 'LOCAL',
      time_fired: '2026-08-17T10:00:03.000000+00:00',
    });
    assertTrue(await waitFor(() => webhook.state.calls.length >= 3), 'delivery attempted despite upcoming 500 (64-bit-length frame parsed)');
    assertEqual(
      webhook.state.calls[2].body.input,
      'Home Assistant: binary_sensor.motion_hall changed from off to on',
      'exact-entity_id filter entry matches (and >64KB frame decoded intact)'
    );
    assertTrue(
      await waitFor(() => getStderr().includes('webhook delivery failed: 500')),
      'webhook 500 is logged to stderr'
    );

    ha.state.emitEvent({
      event_type: 'state_changed',
      data: {
        entity_id: 'lock.back_door',
        old_state: { entity_id: 'lock.back_door', state: 'unlocked' },
        new_state: { entity_id: 'lock.back_door', state: 'locked' },
      },
      origin: 'LOCAL',
      time_fired: '2026-08-17T10:00:04.000000+00:00',
    });
    assertTrue(await waitFor(() => webhook.state.calls.length >= 4), 'bridge keeps delivering after a webhook 500');
    assertTrue(child.exitCode === null, 'process stays alive after a webhook 500');

    // 6. Nothing leaked: exactly the 4 expected deliveries, the filtered
    //    entity never among them, and stdout stayed silent (this is not an
    //    MCP stdio server - stdout must carry nothing).
    assertEqual(webhook.state.calls.length, 4, 'exactly the expected deliveries arrived - filtered event was not forwarded');
    assertTrue(
      webhook.state.calls.every((c) => !(c.body && c.body.event && c.body.event.data && c.body.event.data.entity_id === 'light.kitchen')),
      'the filtered-out entity (light.kitchen) never reached the webhook'
    );
    assertEqual(getStdout(), '', 'bridge writes nothing to stdout');
  } finally {
    child.kill();
    ha.server.close();
    webhook.server.close();
    if (ha.state.socket) ha.state.socket.destroy();
  }
}

async function main() {
  // Whole-run watchdog so a hung socket can never wedge CI - generous but bounded.
  const watchdog = setTimeout(() => {
    console.error('FAIL: test watchdog fired - the run exceeded 90s');
    process.exit(1);
  }, 90000);

  await testMissingEnvFailsFast();
  await testFullPipeline();

  clearTimeout(watchdog);
  if (failures > 0) {
    console.error(`\n${failures} assertion(s) failed.`);
    process.exit(1);
  }
  console.log('\nAll assertions passed.');
}

main().catch((err) => {
  console.error('Test harness crashed:', err);
  process.exit(1);
});
