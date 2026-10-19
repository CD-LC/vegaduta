#!/usr/bin/env node
'use strict';
// Test fixture: zero-dependency stdio MCP connector with deterministic tools.
// Reads FIXTURE_ECHO_API_KEY at start-up; unset/empty/"SANDBOX" => sandbox mode.
const readline = require('readline');

const KEY = process.env.FIXTURE_ECHO_API_KEY || '';
const SANDBOX = !KEY || KEY.trim().toUpperCase() === 'SANDBOX';
const SERVER_INFO = { name: 'fixture-echo', version: '0.0.1' };

const TOOLS = [
  {
    name: 'check_eligibility',
    description: 'Deterministic synthetic lookup by ref.',
    inputSchema: { type: 'object', properties: { patient_ref: { type: 'string' } }, required: ['patient_ref'] },
  },
  { name: 'connector_mode', description: 'Reports sandbox vs live.', inputSchema: { type: 'object', properties: {} } },
];

const text = (obj) => ({ content: [{ type: 'text', text: JSON.stringify(obj, null, 2) }] });

function callTool(name, args) {
  if (name === 'connector_mode') return text({ mode: SANDBOX ? 'sandbox' : 'live', sandbox: SANDBOX });
  if (name === 'check_eligibility') {
    const ref = String((args || {}).patient_ref || '');
    return text({
      mode: SANDBOX ? 'sandbox' : 'live',
      patient_ref: ref,
      status: ref === 'SYNTH-003' ? 'coverage_terminated' : 'active',
    });
  }
  return null;
}

function handle(msg) {
  if (msg.id === undefined) return null; // notification
  const reply = (result) => ({ jsonrpc: '2.0', id: msg.id, result });
  switch (msg.method) {
    case 'initialize':
      return reply({ protocolVersion: '2024-11-05', capabilities: { tools: {} }, serverInfo: SERVER_INFO });
    case 'tools/list':
      return reply({ tools: TOOLS });
    case 'tools/call': {
      const r = callTool((msg.params || {}).name, (msg.params || {}).arguments);
      return r ? reply(r) : { jsonrpc: '2.0', id: msg.id, error: { code: -32602, message: 'unknown tool' } };
    }
    case 'ping':
      return reply({});
    default:
      return { jsonrpc: '2.0', id: msg.id, error: { code: -32601, message: 'method not found' } };
  }
}

readline.createInterface({ input: process.stdin }).on('line', (line) => {
  if (!line.trim()) return;
  let msg;
  try { msg = JSON.parse(line); } catch (e) { return; }
  const out = handle(msg);
  if (out) process.stdout.write(JSON.stringify(out) + '\n');
});
