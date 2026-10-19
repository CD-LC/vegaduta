'use strict';

/**
 * Real, executable integration test - not a brace-balance sweep.
 *
 * Spawns index.js as an actual child process (exactly how
 * DynamicMcpConnectionManager will launch it: `node index.js` with
 * HASS_URL/HASS_TOKEN env vars), speaks the real newline-delimited
 * JSON-RPC 2.0 MCP protocol to its stdin/stdout, and points HASS_URL at a
 * throwaway local HTTP server standing in for Home Assistant so
 * list_entities/get_entity_state/call_service exercise the server's actual
 * HTTP-calling code, not a mock of it.
 *
 * Run with: node test/server.test.js
 * Exits non-zero on any failure (no test framework dependency - matches
 * this session's inability to npm-install anything, and mirrors this
 * repo's own code-intel/tests/test_auth.py precedent of a framework-free
 * test file).
 */

const http = require('http');
const path = require('path');
const { spawn } = require('child_process');

const FAKE_TOKEN = 'test-token-abc123';

let failures = 0;
/** Stable stringify (keys sorted recursively) so assertEqual doesn't false-fail on object key order, which JSON.stringify preserves as insertion order but which this server's own logic has no reason to guarantee. */
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

// --- Fake Home Assistant --------------------------------------------------

const FAKE_STATES = [
  { entity_id: 'light.living_room', state: 'on', attributes: { friendly_name: 'Living Room Light' } },
  { entity_id: 'light.kitchen', state: 'off', attributes: { friendly_name: 'Kitchen Light' } },
  { entity_id: 'lock.front_door', state: 'locked', attributes: { friendly_name: 'Front Door' } },
];

const FAKE_HISTORY = [
  [
    { entity_id: 'light.living_room', state: 'off', last_changed: '2026-08-17T06:00:00+00:00' },
    { entity_id: 'light.living_room', state: 'on', last_changed: '2026-08-17T07:30:00+00:00' },
  ],
];

const FAKE_LOGBOOK = [
  { when: '2026-08-17T07:30:00+00:00', name: 'Front Door', message: 'was unlocked', entity_id: 'lock.front_door' },
];

const FAKE_SERVICES = [
  { domain: 'light', services: { turn_on: { fields: { brightness: {} } }, turn_off: { fields: {} } } },
  { domain: 'lock', services: { lock: { fields: {} }, unlock: { fields: {} } } },
];

// Minimal-but-real JPEG byte signature (SOI + APP0 "JFIF" + EOI) - enough to
// prove the binary path round-trips bytes without corruption.
const FAKE_JPEG = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01, 0xff, 0xd9]);

function startFakeHomeAssistant() {
  const server = http.createServer((req, res) => {
    const auth = req.headers['authorization'];
    if (auth !== `Bearer ${FAKE_TOKEN}`) {
      res.writeHead(401, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ message: 'Unauthorized' }));
      return;
    }
    let bodyChunks = [];
    req.on('data', (c) => bodyChunks.push(c));
    req.on('end', () => {
      const body = bodyChunks.length ? JSON.parse(Buffer.concat(bodyChunks).toString('utf8')) : null;

      if (req.method === 'GET' && req.url === '/api/states') {
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(JSON.stringify(FAKE_STATES));
        return;
      }
      if (req.method === 'GET' && req.url.startsWith('/api/states/')) {
        const entityId = decodeURIComponent(req.url.slice('/api/states/'.length));
        const found = FAKE_STATES.find((s) => s.entity_id === entityId);
        if (!found) {
          res.writeHead(404, { 'content-type': 'application/json' });
          res.end(JSON.stringify({ message: 'Entity not found' }));
          return;
        }
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ ...found, last_changed: '2026-07-19T00:00:00+00:00' }));
        return;
      }
      if (req.method === 'POST' && req.url === '/api/services/lock/unlock') {
        assertEqual(body, { entity_id: 'lock.front_door' }, 'call_service POSTs the expected body to HA');
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(JSON.stringify([{ entity_id: 'lock.front_door', state: 'unlocked' }]));
        return;
      }
      if (req.method === 'POST' && req.url === '/api/services/climate/set_temperature') {
        assertEqual(body, { entity_id: 'climate.hallway', temperature: 21 }, 'call_service merges data fields alongside entity_id');
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(JSON.stringify([{ entity_id: 'climate.hallway', state: '21' }]));
        return;
      }
      // New-tool routes carry query strings, so parse once here.
      const parsedUrl = new URL(req.url, 'http://fake-ha.test');

      if (req.method === 'GET' && parsedUrl.pathname.startsWith('/api/history/period/')) {
        const filterEntity = parsedUrl.searchParams.get('filter_entity_id');
        if (filterEntity === 'light.living_room') {
          assertEqual(
            decodeURIComponent(parsedUrl.pathname.slice('/api/history/period/'.length)),
            '2026-08-17T00:00:00+00:00',
            'get_history puts start_time in the URL path'
          );
          assertEqual(parsedUrl.searchParams.get('end_time'), '2026-08-17T12:00:00+00:00', 'get_history passes end_time as a query param');
          res.writeHead(200, { 'content-type': 'application/json' });
          res.end(JSON.stringify(FAKE_HISTORY));
          return;
        }
        res.writeHead(404, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ message: 'Entity not found' }));
        return;
      }
      if (req.method === 'GET' && parsedUrl.pathname.startsWith('/api/logbook/')) {
        assertEqual(parsedUrl.searchParams.get('entity'), 'lock.front_door', 'get_logbook passes entity_id as the entity query param');
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(JSON.stringify(FAKE_LOGBOOK));
        return;
      }
      if (req.method === 'GET' && parsedUrl.pathname === '/api/services') {
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(JSON.stringify(FAKE_SERVICES));
        return;
      }
      if (req.method === 'POST' && parsedUrl.pathname === '/api/template') {
        if (!body || typeof body.template !== 'string') {
          res.writeHead(400, { 'content-type': 'application/json' });
          res.end(JSON.stringify({ message: 'template body missing' }));
          return;
        }
        // /api/template returns PLAIN TEXT, not JSON - that's the whole point
        // of this route existing in the fake.
        res.writeHead(200, { 'content-type': 'text/plain' });
        res.end('The living room light is on.');
        return;
      }
      if (req.method === 'POST' && parsedUrl.pathname === '/api/events/vegaduta_agent_notify') {
        assertEqual(body, { message: 'laundry done' }, 'fire_event POSTs event_data as the request body');
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ message: 'Event vegaduta_agent_notify fired.' }));
        return;
      }
      if (req.method === 'GET' && parsedUrl.pathname.startsWith('/api/camera_proxy/')) {
        const cameraId = decodeURIComponent(parsedUrl.pathname.slice('/api/camera_proxy/'.length));
        if (cameraId !== 'camera.front_yard') {
          res.writeHead(404, { 'content-type': 'application/json' });
          res.end(JSON.stringify({ message: 'Camera not found' }));
          return;
        }
        res.writeHead(200, { 'content-type': 'image/jpeg' });
        res.end(FAKE_JPEG);
        return;
      }

      res.writeHead(404, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ message: 'no such fake route' }));
    });
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => resolve(server));
  });
}

// --- MCP stdio client harness ----------------------------------------------

function startServerProcess(haUrl) {
  const child = spawn(process.execPath, [path.join(__dirname, '..', 'index.js')], {
    env: { ...process.env, HASS_URL: haUrl, HASS_TOKEN: FAKE_TOKEN },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  let stderrBuf = '';
  child.stderr.on('data', (d) => {
    stderrBuf += d.toString();
  });
  let buf = '';
  const pending = new Map();
  let nextId = 1;
  child.stdout.on('data', (chunk) => {
    buf += chunk.toString();
    let idx;
    while ((idx = buf.indexOf('\n')) !== -1) {
      const line = buf.slice(0, idx);
      buf = buf.slice(idx + 1);
      if (!line.trim()) continue;
      const msg = JSON.parse(line);
      if (msg.id !== undefined && pending.has(msg.id)) {
        pending.get(msg.id)(msg);
        pending.delete(msg.id);
      }
    }
  });

  function request(method, params) {
    const id = nextId++;
    return new Promise((resolve) => {
      pending.set(id, resolve);
      child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`);
    });
  }
  function notify(method, params) {
    child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', method, params })}\n`);
  }

  return { child, request, notify, getStderr: () => stderrBuf };
}

async function main() {
  const ha = await startFakeHomeAssistant();
  const haUrl = `http://127.0.0.1:${ha.address().port}`;
  const { child, request, notify } = startServerProcess(haUrl);

  try {
    const initResult = await request('initialize', {
      protocolVersion: '2024-11-05',
      capabilities: {},
      clientInfo: { name: 'test-harness', version: '0.0.1' },
    });
    assertEqual(initResult.result.serverInfo.name, 'home-assistant', 'initialize returns server info');
    notify('notifications/initialized', {});

    const toolsList = await request('tools/list', {});
    const toolNames = toolsList.result.tools.map((t) => t.name).sort();
    assertEqual(
      toolNames,
      [
        'call_service',
        'fire_event',
        'get_camera_snapshot',
        'get_entity_state',
        'get_history',
        'get_logbook',
        'list_entities',
        'list_services',
        'render_template',
      ],
      'tools/list returns all nine tools'
    );

    const allEntities = await request('tools/call', { name: 'list_entities', arguments: {} });
    const allParsed = JSON.parse(allEntities.result.content[0].text);
    assertEqual(allParsed.length, 3, 'list_entities with no filter returns every fake entity');

    const lightsOnly = await request('tools/call', { name: 'list_entities', arguments: { domain: 'light' } });
    const lightsParsed = JSON.parse(lightsOnly.result.content[0].text);
    assertEqual(lightsParsed.map((e) => e.entity_id).sort(), ['light.kitchen', 'light.living_room'], 'list_entities filters by domain');

    const oneEntity = await request('tools/call', { name: 'get_entity_state', arguments: { entity_id: 'lock.front_door' } });
    const oneParsed = JSON.parse(oneEntity.result.content[0].text);
    assertEqual(oneParsed.state, 'locked', 'get_entity_state returns the fake entity\'s real state');

    const missingEntity = await request('tools/call', { name: 'get_entity_state', arguments: { entity_id: 'lock.does_not_exist' } });
    assertTrue(missingEntity.result.isError === true, 'get_entity_state on an unknown entity surfaces as a tool error, not a crash');

    const unlock = await request('tools/call', {
      name: 'call_service',
      arguments: { domain: 'lock', service: 'unlock', entity_id: 'lock.front_door' },
    });
    assertTrue(unlock.result.isError !== true, 'call_service(lock.unlock) succeeds against the fake HA backend');

    const setTemp = await request('tools/call', {
      name: 'call_service',
      arguments: { domain: 'climate', service: 'set_temperature', entity_id: 'climate.hallway', data: { temperature: 21 } },
    });
    assertTrue(setTemp.result.isError !== true, 'call_service merges extra data fields alongside entity_id');

    const unknownTool = await request('tools/call', { name: 'not_a_real_tool', arguments: {} });
    assertTrue(
      unknownTool.error && unknownTool.error.code === -32602 && unknownTool.result === undefined,
      'calling an unknown tool name returns a JSON-RPC -32602 protocol error, not a tool result'
    );

    // --- get_history --------------------------------------------------------
    const history = await request('tools/call', {
      name: 'get_history',
      arguments: {
        entity_id: 'light.living_room',
        start_time: '2026-08-17T00:00:00+00:00',
        end_time: '2026-08-17T12:00:00+00:00',
      },
    });
    assertTrue(history.result.isError !== true, 'get_history succeeds against the fake HA backend');
    const historyParsed = JSON.parse(history.result.content[0].text);
    assertEqual(historyParsed, FAKE_HISTORY, 'get_history returns the parsed history array verbatim');

    const missingHistory = await request('tools/call', {
      name: 'get_history',
      arguments: { entity_id: 'sensor.does_not_exist', start_time: '2026-08-17T00:00:00+00:00' },
    });
    assertTrue(missingHistory.result.isError === true, 'get_history for an unknown entity surfaces the HA 404 as a tool error');

    // --- get_logbook --------------------------------------------------------
    const logbook = await request('tools/call', {
      name: 'get_logbook',
      arguments: { start_time: '2026-08-17T00:00:00+00:00', entity_id: 'lock.front_door' },
    });
    assertTrue(logbook.result.isError !== true, 'get_logbook succeeds against the fake HA backend');
    const logbookParsed = JSON.parse(logbook.result.content[0].text);
    assertEqual(logbookParsed, FAKE_LOGBOOK, 'get_logbook returns the parsed logbook entries');

    const logbookNoStart = await request('tools/call', { name: 'get_logbook', arguments: {} });
    assertTrue(logbookNoStart.result.isError === true, 'get_logbook without start_time is rejected as a tool error');

    // --- list_services ------------------------------------------------------
    const services = await request('tools/call', { name: 'list_services', arguments: {} });
    assertTrue(services.result.isError !== true, 'list_services succeeds against the fake HA backend');
    const servicesParsed = JSON.parse(services.result.content[0].text);
    assertEqual(
      servicesParsed,
      [
        { domain: 'light', services: ['turn_on', 'turn_off'] },
        { domain: 'lock', services: ['lock', 'unlock'] },
      ],
      'list_services compacts the verbose HA payload to domain + service names'
    );

    // --- render_template ----------------------------------------------------
    const rendered = await request('tools/call', {
      name: 'render_template',
      arguments: { template: "{{ states('light.living_room') }} rendered sentence" },
    });
    assertTrue(rendered.result.isError !== true, 'render_template succeeds against the fake HA backend');
    // toToolResult JSON.stringify's the handler's string return, so the text
    // block is the quoted raw string.
    assertEqual(JSON.parse(rendered.result.content[0].text), 'The living room light is on.', 'render_template returns the raw plain-text rendering');

    const renderedNoTemplate = await request('tools/call', { name: 'render_template', arguments: {} });
    assertTrue(renderedNoTemplate.result.isError === true, 'render_template without a template is rejected as a tool error');

    // --- fire_event ---------------------------------------------------------
    const fired = await request('tools/call', {
      name: 'fire_event',
      arguments: { event_type: 'vegaduta_agent_notify', event_data: { message: 'laundry done' } },
    });
    assertTrue(fired.result.isError !== true, 'fire_event succeeds against the fake HA backend');

    const firedNoType = await request('tools/call', { name: 'fire_event', arguments: {} });
    assertTrue(firedNoType.result.isError === true, 'fire_event without event_type is rejected as a tool error');

    // --- get_camera_snapshot ------------------------------------------------
    const snapshot = await request('tools/call', {
      name: 'get_camera_snapshot',
      arguments: { entity_id: 'camera.front_yard' },
    });
    assertTrue(snapshot.result.isError !== true, 'get_camera_snapshot succeeds against the fake HA backend');
    const imageBlock = snapshot.result.content[0];
    assertEqual(imageBlock.type, 'image', 'get_camera_snapshot returns an MCP image content block, not text');
    assertEqual(imageBlock.mimeType, 'image/jpeg', 'get_camera_snapshot propagates the response content-type');
    assertTrue(Buffer.from(imageBlock.data, 'base64').equals(FAKE_JPEG), 'get_camera_snapshot round-trips the exact JPEG bytes through base64');

    const missingCamera = await request('tools/call', {
      name: 'get_camera_snapshot',
      arguments: { entity_id: 'camera.does_not_exist' },
    });
    assertTrue(missingCamera.result.isError === true, 'get_camera_snapshot on an unknown camera surfaces the HA 404 as a tool error');
  } finally {
    child.kill();
    ha.close();
  }

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
