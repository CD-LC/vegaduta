#!/usr/bin/env node
/**
 * Real, executable integration test for the Streamable-HTTP gateway. No
 * framework, same house pattern as the connectors' own tests.
 *
 * It boots the actual server.js as a child process, points it at the real
 * fixture-echo connector, and speaks real HTTP + JSON-RPC to it - so what is
 * proven here is the same code path a production STREAMABLE_HTTP registration
 * takes, not a mock of it.
 *
 * Run: node test/gateway.test.js
 */

'use strict';

const { spawn } = require('child_process');
const path = require('path');
const http = require('http');

const PORT = 18099 + (process.pid % 500);
const BASE = `http://127.0.0.1:${PORT}`;
const TOKEN = 'test-token-do-not-use-anywhere-real';
const SERVER = path.join(__dirname, '..', 'server.js');
const CONNECTOR_ROOT = path.join(__dirname, 'fixtures');

let passed = 0;
let failed = 0;

function check(cond, label) {
  if (cond) {
    console.log(`PASS: ${label}`);
    passed++;
  } else {
    console.error(`FAIL: ${label}`);
    failed++;
  }
}

function request(method, urlPath, body, headers = {}) {
  return new Promise((resolve, reject) => {
    const payload = body === undefined ? null : JSON.stringify(body);
    const req = http.request(
      `${BASE}${urlPath}`,
      {
        method,
        headers: {
          ...(payload ? { 'Content-Type': 'application/json' } : {}),
          ...headers,
        },
      },
      (res) => {
        let data = '';
        res.setEncoding('utf8');
        res.on('data', (c) => (data += c));
        res.on('end', () => {
          let parsed = null;
          try {
            parsed = data ? JSON.parse(data) : null;
          } catch (e) {
            /* non-JSON body is legitimate for 405 */
          }
          resolve({ status: res.statusCode, headers: res.headers, body: parsed, raw: data });
        });
      }
    );
    req.on('error', reject);
    if (payload) req.write(payload);
    req.end();
  });
}

async function waitForHealth(timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const r = await request('GET', '/health', undefined, { Authorization: `Bearer ${TOKEN}` });
      if (r.status === 200) return;
    } catch (e) {
      /* not up yet */
    }
    await new Promise((r) => setTimeout(r, 200));
  }
  throw new Error('gateway did not become healthy in time');
}

async function main() {
  const child = spawn(process.execPath, [SERVER], {
    env: {
      ...process.env,
      PORT: String(PORT),
      SERVERS: 'fixture-echo',
      CONNECTOR_ROOT,
      GATEWAY_TOKEN: TOKEN,
    },
    stdio: ['ignore', 'ignore', 'pipe'],
  });
  child.stderr.setEncoding('utf8');
  const logs = [];
  child.stderr.on('data', (d) => logs.push(d));

  const cleanup = () => {
    try {
      child.kill('SIGTERM');
    } catch (e) {
      /* already gone */
    }
  };
  process.on('exit', cleanup);

  try {
    await waitForHealth();

    // --- health -----------------------------------------------------------
    const health = await request('GET', '/health');
    check(health.status === 200, 'GET /health returns 200 without a token');
    // This assertion used to require `mode === 'sandbox'`, i.e. a blanket
    // claim that nothing real was reachable through this gateway. That claim
    // became FALSE when per-tenant credentials landed, so the field was
    // removed rather than left to reassure a reader wrongly. What replaces it
    // is per-connector and therefore still true: a `credentialed` flag, and a
    // note that says isolation is between tenants, not between a tenant and
    // this gateway.
    check(health.body && health.body.mode === undefined,
      'health no longer makes a blanket sandbox claim - it stopped being true');
    check(
      typeof health.body.note === 'string' && /credential/i.test(health.body.note),
      'health explains the credential posture instead'
    );
    check(
      health.body.servers.every((s) => typeof s.credentialed === 'boolean'),
      'health reports credentialed per connector, not globally'
    );
    check(
      health.body.servers.every((s) => Number.isFinite(s.callTimeoutMs)),
      'health reports each connector call timeout, since they now differ'
    );
    check(
      Array.isArray(health.body.servers) &&
        health.body.servers.some((s) => s.name === 'fixture-echo' && s.available),
      'health lists fixture-echo as available'
    );

    // --- auth -------------------------------------------------------------
    const noAuth = await request('POST', '/mcp/fixture-echo', { jsonrpc: '2.0', id: 1, method: 'tools/list' });
    check(noAuth.status === 401, 'POST without a bearer token is rejected 401');

    const badAuth = await request(
      'POST', '/mcp/fixture-echo',
      { jsonrpc: '2.0', id: 1, method: 'tools/list' },
      { Authorization: 'Bearer wrong-token-entirely-different-length' }
    );
    check(badAuth.status === 401, 'POST with the wrong bearer token is rejected 401');

    const auth = { Authorization: `Bearer ${TOKEN}` };

    // --- initialize -------------------------------------------------------
    const init = await request(
      'POST', '/mcp/fixture-echo',
      {
        jsonrpc: '2.0', id: 1, method: 'initialize',
        params: { protocolVersion: '2024-11-05', capabilities: {}, clientInfo: { name: 'test', version: '1' } },
      },
      auth
    );
    check(init.status === 200, 'initialize returns 200');
    check(init.body && init.body.result && init.body.result.serverInfo.name === 'fixture-echo',
      'initialize reports the real connector serverInfo, not the gateway');
    check(Boolean(init.headers['mcp-session-id']),
      'initialize response carries an Mcp-Session-Id header');

    // A second initialize must also succeed - the shared child is handshaked
    // once by the gateway and later clients are answered from that result.
    const init2 = await request(
      'POST', '/mcp/fixture-echo',
      { jsonrpc: '2.0', id: 99, method: 'initialize', params: { protocolVersion: '2024-11-05', capabilities: {}, clientInfo: { name: 't2', version: '1' } } },
      auth
    );
    check(init2.status === 200 && init2.body.result && init2.body.id === 99,
      'a second client can initialize against the same shared connector child');

    // --- notification -----------------------------------------------------
    const note = await request(
      'POST', '/mcp/fixture-echo',
      { jsonrpc: '2.0', method: 'notifications/initialized' },
      auth
    );
    check(note.status === 202, 'a notification-only POST answers 202 with no body');

    // --- tools/list -------------------------------------------------------
    const list = await request('POST', '/mcp/fixture-echo', { jsonrpc: '2.0', id: 2, method: 'tools/list' }, auth);
    const names = ((list.body || {}).result || {}).tools || [];
    check(list.body.id === 2, 'the response id matches the id the client sent, not the gateway remap');
    check(names.some((t) => t.name === 'check_eligibility'), 'tools/list includes check_eligibility');
    check(names.some((t) => t.name === 'connector_mode'), 'tools/list includes connector_mode');

    // --- tools/call -------------------------------------------------------
    const call = await request(
      'POST', '/mcp/fixture-echo',
      { jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'check_eligibility', arguments: { patient_ref: 'SYNTH-003' } } },
      auth
    );
    const text = (((call.body || {}).result || {}).content || [{}])[0].text || '';
    check(call.status === 200 && !call.body.error, 'tools/call succeeds over HTTP');
    check(text.includes('coverage_terminated'),
      'the terminated-coverage fixture round-trips through the HTTP transport unchanged');
    check(text.includes('"mode": "sandbox"'),
      'the connector still reports sandbox mode when reached over HTTP');

    // --- connector_mode ---------------------------------------------------
    const mode = await request(
      'POST', '/mcp/fixture-echo',
      { jsonrpc: '2.0', id: 4, method: 'tools/call', params: { name: 'connector_mode', arguments: {} } },
      auth
    );
    const modeText = (((mode.body || {}).result || {}).content || [{}])[0].text || '';
    check(modeText.toLowerCase().includes('sandbox'),
      'connector_mode reports sandbox - the gateway passes no API key to the child');

    // --- concurrency ------------------------------------------------------
    // Ids are remapped onto one shared child, so two overlapping calls must not
    // cross their answers. This is the bug the remap exists to prevent.
    const [a, b] = await Promise.all([
      request('POST', '/mcp/fixture-echo',
        { jsonrpc: '2.0', id: 'alpha', method: 'tools/call', params: { name: 'check_eligibility', arguments: { patient_ref: 'SYNTH-001' } } }, auth),
      request('POST', '/mcp/fixture-echo',
        { jsonrpc: '2.0', id: 'beta', method: 'tools/call', params: { name: 'check_eligibility', arguments: { patient_ref: 'SYNTH-003' } } }, auth),
    ]);
    const aText = (((a.body || {}).result || {}).content || [{}])[0].text || '';
    const bText = (((b.body || {}).result || {}).content || [{}])[0].text || '';
    check(a.body.id === 'alpha' && b.body.id === 'beta', 'concurrent requests keep their own ids');
    check(aText.includes('SYNTH-001') && bText.includes('SYNTH-003'),
      'concurrent requests do not cross answers on the shared connector child');

    // --- routing ----------------------------------------------------------
    const unknown = await request('POST', '/mcp/does-not-exist', { jsonrpc: '2.0', id: 5, method: 'tools/list' }, auth);
    check(unknown.status === 404, 'an unknown connector name returns 404');

    const get = await request('GET', '/mcp/fixture-echo', undefined, auth);
    check(get.status === 405, 'GET on the MCP endpoint returns 405 (no server-initiated stream)');

    const del = await request('DELETE', '/mcp/fixture-echo', undefined, auth);
    check(del.status === 200, 'DELETE (session teardown) is accepted');

    // --- SSE content negotiation ------------------------------------------
    // The Java SDK's HttpClientStreamableHttpTransport - what core actually
    // uses - advertises text/event-stream and hung on the application/json
    // form, failing every production registration with "MCP initialization
    // timed out". These assertions exist so that regression cannot return
    // silently: it does not surface as an error, it surfaces as a gateway that
    // looks perfectly healthy while no agent can call anything.
    const sse = await new Promise((resolve, reject) => {
      const body = JSON.stringify({
        jsonrpc: '2.0', id: 42, method: 'tools/call',
        params: { name: 'check_eligibility', arguments: { patient_ref: 'SYNTH-001' } },
      });
      const req = http.request(`${BASE}/mcp/fixture-echo`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'application/json, text/event-stream',
          ...auth,
        },
      }, (res) => {
        let d = '';
        res.setEncoding('utf8');
        res.on('data', (c) => (d += c));
        res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, raw: d }));
      });
      req.on('error', reject);
      req.write(body);
      req.end();
    });

    check(sse.status === 200, 'SSE-accepting POST returns 200');
    check((sse.headers['content-type'] || '').includes('application/json'),
      'a client advertising text/event-stream STILL gets application/json');
    check(!sse.raw.startsWith('event:'),
      'the gateway never answers with an SSE frame - the Java client rejects one outright');
    let ssePayload = null;
    try {
      ssePayload = JSON.parse(sse.raw);
    } catch (e) { /* assertion below reports it */ }
    check(ssePayload && ssePayload.id === 42, 'the response keeps the client id');
    check(ssePayload && !ssePayload.error && JSON.stringify(ssePayload).includes('SYNTH-001'),
      'the tool result is intact for an SSE-advertising client');

    // --- protocol-version negotiation -------------------------------------
    // The connectors speak 2024-11-05 over stdio, which predates Streamable
    // HTTP. The gateway must answer for the TRANSPORT, echoing a modern version
    // back, or a streamable-http client concludes it is talking to a legacy
    // HTTP+SSE server and waits for a transport that never arrives.
    async function initWith(version) {
      const r = await request('POST', '/mcp/fixture-echo', {
        jsonrpc: '2.0', id: 77, method: 'initialize',
        params: { protocolVersion: version, capabilities: {}, clientInfo: { name: 'ver', version: '1' } },
      }, auth);
      return ((r.body || {}).result || {}).protocolVersion;
    }

    check(await initWith('2025-03-26') === '2025-03-26',
      'a 2025-03-26 client is answered 2025-03-26, not the connector\'s 2024-11-05');
    check(await initWith('2025-06-18') === '2025-06-18',
      'a 2025-06-18 client is answered 2025-06-18');
    check(await initWith('2024-11-05') === '2024-11-05',
      'a legacy 2024-11-05 client still gets 2024-11-05');
    check(await initWith('1999-01-01') === '2024-11-05',
      'an unsupported version falls back to the connector\'s own, rather than echoing nonsense');

    // --- endpoint-suffix tolerance ----------------------------------------
    // The MCP Java SDK appends its own endpoint (default "/mcp") to the URL it
    // is given, so a connector registered at /mcp/fixture-echo is actually
    // requested at /mcp/fixture-echo/mcp. Getting this wrong produces a silent
    // 404 that surfaces only as "MCP initialization timed out".
    const suffixed = await request('POST', '/mcp/fixture-echo/mcp',
      { jsonrpc: '2.0', id: 101, method: 'tools/list' }, auth);
    check(suffixed.status === 200,
      'POST /mcp/<server>/mcp is accepted (the SDK appends its own endpoint)');
    check(((suffixed.body || {}).result || {}).tools?.length > 0,
      'the suffixed path reaches the same connector and returns its tools');

    const doubleSuffixed = await request('POST', '/mcp/fixture-echo/mcp/mcp',
      { jsonrpc: '2.0', id: 102, method: 'tools/list' }, auth);
    check(doubleSuffixed.status === 404,
      'tolerance stops at ONE /mcp suffix - it is not an open prefix match');

    // --- bare /mcp routed by token ----------------------------------------
    // The MCP Java SDK discards the registered URL's path and requests the
    // origin plus its own `/mcp` endpoint, so every connector arrives on the
    // same path and only the bearer token can say which one is wanted. If this
    // regresses, registrations fail as "initialization timed out" with no clue.
    const crypto = require('crypto');
    const derived = crypto.createHash('sha256')
      .update(`${TOKEN}:fixture-echo`).digest('hex').slice(0, 40);

    const byToken = await request('POST', '/mcp',
      { jsonrpc: '2.0', id: 200, method: 'tools/list' },
      { Authorization: `Bearer ${derived}` });
    check(byToken.status === 200,
      'a bare POST /mcp with a derived connector token is accepted');
    check((((byToken.body || {}).result || {}).tools || []).some((x) => x.name === 'check_eligibility'),
      'the derived token routes /mcp to the right connector');

    const masterOnBare = await request('POST', '/mcp',
      { jsonrpc: '2.0', id: 201, method: 'tools/list' }, auth);
    check(masterOnBare.status === 401,
      'the MASTER token cannot route a bare /mcp - it is ambiguous across connectors');

    const wrongDerived = crypto.createHash('sha256')
      .update(`${TOKEN}:not-a-connector`).digest('hex').slice(0, 40);
    const bogus = await request('POST', '/mcp',
      { jsonrpc: '2.0', id: 202, method: 'tools/list' },
      { Authorization: `Bearer ${wrongDerived}` });
    check(bogus.status === 401, 'an unrecognised derived token is rejected');

    const crossUse = await request('POST', '/mcp/fixture-echo',
      { jsonrpc: '2.0', id: 203, method: 'tools/list' },
      { Authorization: `Bearer ${derived}` });
    check(crossUse.status === 200,
      'a derived token also works on its own connector explicit path');

    // --- bad input --------------------------------------------------------
    const bad = await new Promise((resolve, reject) => {
      const req = http.request(`${BASE}/mcp/fixture-echo`, { method: 'POST', headers: { 'Content-Type': 'application/json', ...auth } }, (res) => {
        let d = '';
        res.on('data', (c) => (d += c));
        res.on('end', () => resolve({ status: res.statusCode, raw: d }));
      });
      req.on('error', reject);
      req.write('{not json');
      req.end();
    });
    check(bad.status === 400, 'malformed JSON returns 400, not a crash');

    // --- per-tenant credentials -------------------------------------------
    //
    // These assert the property that was missing and that blocked serving any
    // credentialed connector from here: a credential reaches the child, and
    // two different credentials never reach the SAME child.
    //
    // fixture-echo makes this testable without a fake upstream: it decides
    // sandbox-vs-live from FIXTURE_ECHO_API_KEY at import time and reports the
    // answer through its own connector_mode tool. So the tool's answer is
    // direct evidence of what the child's process environment actually
    // contained - not a gateway self-report.

    const modeOf = async (extraHeaders) => {
      const r = await request(
        'POST', '/mcp/fixture-echo',
        { jsonrpc: '2.0', id: 900, method: 'tools/call', params: { name: 'connector_mode', arguments: {} } },
        { ...auth, ...extraHeaders }
      );
      const text = (((r.body || {}).result || {}).content || [{}])[0].text || '';
      try {
        return JSON.parse(text);
      } catch (e) {
        return { _unparseable: text.slice(0, 200) };
      }
    };

    const sandboxMode = await modeOf({});
    check(
      sandboxMode.mode === 'sandbox' || sandboxMode.sandbox === true,
      'with no credential headers the connector still runs in sandbox mode (unchanged behaviour)'
    );

    const liveMode = await modeOf({ 'X-Connector-Env-FIXTURE_ECHO_API_KEY': 'tenant-one-real-key' });
    check(
      liveMode.mode !== 'sandbox' && liveMode.sandbox !== true,
      'an X-Connector-Env-* header reaches the child process environment'
    );

    // Same header again -> same child (no new spawn), a different value -> a
    // different child. This is the isolation property itself.
    await modeOf({ 'X-Connector-Env-FIXTURE_ECHO_API_KEY': 'tenant-two-real-key' });
    const health2 = await request('GET', '/health');
    const oa = health2.body.servers.find((x) => x.name === 'fixture-echo');
    check(
      oa.distinctCredentialSets === 2,
      `two distinct credential sets get two distinct children (got ${oa.distinctCredentialSets})`
    );

    const again = await modeOf({ 'X-Connector-Env-FIXTURE_ECHO_API_KEY': 'tenant-one-real-key' });
    check(again.mode !== 'sandbox', 'a repeat of the first credential set still works');
    const health3 = await request('GET', '/health');
    check(
      health3.body.servers.find((x) => x.name === 'fixture-echo').distinctCredentialSets === 2,
      'a repeated credential set reuses its child rather than spawning another'
    );

    // A denylisted name must not reach the child. NODE_OPTIONS is the sharp
    // one: it can load arbitrary code into the child, so accepting it would
    // turn a credential header into remote code execution.
    const withNodeOptions = await modeOf({
      'X-Connector-Env-NODE_OPTIONS': '--require /tmp/evil.js',
    });
    check(
      withNodeOptions.mode === 'sandbox' || withNodeOptions.sandbox === true,
      'a denylisted NODE_OPTIONS header is dropped, and does not even key a new child'
    );
    const health4 = await request('GET', '/health');
    check(
      health4.body.servers.find((x) => x.name === 'fixture-echo').distinctCredentialSets === 2,
      'a request whose only env header was denylisted maps to the credential-less child'
    );
    check(
      logs.join('').includes('rejected credential header NODE_OPTIONS'),
      'the rejection is logged by NAME so an operator can see what was dropped'
    );
    check(
      !logs.join('').includes('tenant-one-real-key')
        && !logs.join('').includes('tenant-two-real-key'),
      'NO credential VALUE is ever written to the log'
    );

    // A name that is not a plausible env var is refused too.
    await modeOf({ 'X-Connector-Env-not-an-env-var-name': 'x' });
    check(
      logs.join('').includes('rejected credential header with non-env name')
        || logs.join('').includes('rejected credential header NOT-AN-ENV-VAR-NAME'),
      'a malformed env name is refused rather than passed through'
    );
  } finally {
    cleanup();
  }

  console.log('');
  if (failed > 0) {
    console.error(`${failed} FAILED, ${passed} passed`);
    if (logs.length) console.error('--- gateway stderr ---\n' + logs.join(''));
    process.exit(1);
  }
  console.log(`ALL PASSED (${passed} checks)`);
  process.exit(0);
}

main().catch((err) => {
  console.error('test harness error:', err);
  process.exit(1);
});
