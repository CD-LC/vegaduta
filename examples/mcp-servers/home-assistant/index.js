#!/usr/bin/env node
'use strict';

/**
 * Reference MCP server for Home Assistant (VegaDuta roadmap #9.4).
 *
 * Zero npm dependencies, deliberately: the sandbox this was built in had no
 * network access to the npm registry (registry.npmjs.org returned 403
 * host_not_allowed at build time), so pulling in @modelcontextprotocol/sdk
 * wasn't possible to install *or verify*. Rather than write untestable code
 * against a package that was never actually resolved, this hand-rolls the
 * small newline-delimited JSON-RPC 2.0 subset of the MCP stdio transport
 * spec that a tool-only server needs (initialize / notifications/initialized
 * / tools/list / tools/call / ping). This is a real, spec-documented wire
 * protocol (https://modelcontextprotocol.io) - implementing it directly
 * means every line of this file could actually be run and tested end-to-end
 * against a mock Home Assistant backend (see test/server.test.js), instead
 * of only being brace/paren-balance-checked the way most of this session's
 * other Java changes had to be (no Maven/JDK toolchain in that sandbox).
 * If/when @modelcontextprotocol/sdk becomes installable, swapping this
 * hand-rolled transport for the real SDK's StdioServerTransport is a
 * mechanical change - the tool definitions/handlers below don't need to
 * move.
 *
 * Registration: this is a STDIO MCP server, launched via
 * `node index.js` with two required env vars - see README.md and
 * docs/core/mcp/REGISTERING-A-CUSTOM-MCP-SERVER.md for exactly how to register
 * it as a dynamic per-tenant connection (POST /api/mcp/registry).
 *
 * Required env vars:
 *   HASS_URL   - e.g. "http://homeassistant.local:8123" (no trailing slash)
 *   HASS_TOKEN - a Home Assistant long-lived access token
 *                (Profile -> Security -> Long-Lived Access Tokens)
 *
 * Every tool call this server makes to Home Assistant is a plain REST call
 * against HA's own documented `/api/*` surface
 * (https://developers.home-assistant.io/docs/api/rest/) - no HA-side
 * add-on or custom component required.
 */

const readline = require('readline');

const HASS_URL = (process.env.HASS_URL || '').replace(/\/+$/, '');
const HASS_TOKEN = process.env.HASS_TOKEN || '';

if (!HASS_URL || !HASS_TOKEN) {
  // Fail fast and loud on stderr - DynamicMcpConnectionManager.connect()
  // treats a non-zero-exit / immediately-closed subprocess as a connection
  // failure and records connectionError from whatever this process prints,
  // so this message is what a tenant admin will actually see in the
  // registry UI if they forget to set either var.
  process.stderr.write(
    'home-assistant MCP server: HASS_URL and HASS_TOKEN environment variables are both required.\n'
  );
  process.exit(1);
}

const PROTOCOL_VERSION = '2024-11-05';
const SERVER_INFO = { name: 'home-assistant', version: '0.1.0' };

/** Tool catalog. inputSchema is plain JSON Schema (draft-07 subset) - no zod, see file header. */
const TOOLS = [
  {
    name: 'list_entities',
    description:
      "List this tenant's Home Assistant entities and their current state. Optionally filter by " +
      'domain (the part of an entity_id before the dot, e.g. "light", "switch", "lock", "climate", ' +
      '"sensor"). Returns a compact summary (entity_id, state, friendly_name) per entity, not full ' +
      'attributes - call get_entity_state for one entity\'s full detail.',
    inputSchema: {
      type: 'object',
      properties: {
        domain: {
          type: 'string',
          description: 'Optional entity domain to filter to, e.g. "light" or "lock". Omit to list every entity.',
        },
      },
      additionalProperties: false,
    },
  },
  {
    name: 'get_entity_state',
    description:
      'Get the full current state and attributes of one Home Assistant entity by its entity_id ' +
      '(e.g. "light.living_room", "lock.front_door").',
    inputSchema: {
      type: 'object',
      properties: {
        entity_id: { type: 'string', description: 'Full entity id, e.g. "light.living_room".' },
      },
      required: ['entity_id'],
      additionalProperties: false,
    },
  },
  {
    name: 'call_service',
    description:
      'Call a Home Assistant service to change something in the tenant\'s smart-home setup - e.g. ' +
      'domain="light" service="turn_on" entity_id="light.living_room", or domain="lock" ' +
      'service="unlock" entity_id="lock.front_door". This can be destructive/security-sensitive ' +
      '(unlocking doors, disarming alarms, turning off cameras) - the platform gates individual ' +
      '"mcp_tool.mcp__home-assistant__call_service" calls through its existing approval-policy ' +
      'mechanism (PUT /api/approvals/policy/**; dynamic-connector tools are namespaced ' +
      'mcp__<server>__<tool>, the <server> segment following the registration name), so a tenant ' +
      'admin can require human approval for this tool without any change to this server.',
    inputSchema: {
      type: 'object',
      properties: {
        domain: { type: 'string', description: 'Service domain, e.g. "light", "lock", "climate", "switch".' },
        service: { type: 'string', description: 'Service name within the domain, e.g. "turn_on", "unlock", "set_temperature".' },
        entity_id: { type: 'string', description: 'Target entity id, e.g. "lock.front_door". Omit for services that target no specific entity.' },
        data: {
          type: 'object',
          description: 'Extra service-call fields Home Assistant expects for this service, e.g. {"temperature": 21} for climate.set_temperature.',
        },
      },
      required: ['domain', 'service'],
      additionalProperties: false,
    },
  },
  {
    name: 'get_history',
    description:
      'Fetch the recorded state history of one Home Assistant entity over a time window - e.g. every ' +
      'state change of "sensor.living_room_temperature" since this morning. Returns the parsed array ' +
      'of state snapshots (each with state, last_changed, attributes) exactly as HA\'s ' +
      '/api/history/period endpoint reports them. Times are ISO 8601 strings, e.g. ' +
      '"2026-08-17T00:00:00+00:00".',
    inputSchema: {
      type: 'object',
      properties: {
        entity_id: { type: 'string', description: 'Full entity id to fetch history for, e.g. "sensor.living_room_temperature".' },
        start_time: { type: 'string', description: 'ISO 8601 timestamp marking the start of the window, e.g. "2026-08-17T00:00:00+00:00".' },
        end_time: { type: 'string', description: 'Optional ISO 8601 timestamp marking the end of the window. Omit for "until now".' },
      },
      required: ['entity_id', 'start_time'],
      additionalProperties: false,
    },
  },
  {
    name: 'get_logbook',
    description:
      'Fetch Home Assistant\'s human-readable logbook entries (who/what changed, when) starting from a ' +
      'given time, optionally narrowed to one entity. Useful for answering "what happened in the ' +
      'house today?" style questions. Times are ISO 8601 strings.',
    inputSchema: {
      type: 'object',
      properties: {
        start_time: { type: 'string', description: 'ISO 8601 timestamp marking the start of the window, e.g. "2026-08-17T00:00:00+00:00".' },
        entity_id: { type: 'string', description: 'Optional entity id to narrow the logbook to, e.g. "lock.front_door". Omit for all entities.' },
      },
      required: ['start_time'],
      additionalProperties: false,
    },
  },
  {
    name: 'list_services',
    description:
      'List every service Home Assistant currently exposes, as a compact per-domain summary ' +
      '(domain + service names only, e.g. {"domain": "light", "services": ["turn_on", "turn_off", ' +
      '"toggle"]}). Use this to discover what call_service can do before calling it - the full ' +
      'field-level detail HA returns is deliberately omitted to keep the payload small.',
    inputSchema: {
      type: 'object',
      properties: {},
      additionalProperties: false,
    },
  },
  {
    name: 'render_template',
    description:
      'Render a Home Assistant Jinja2 template server-side and return the resulting plain-text ' +
      'string - e.g. "{{ states(\'sensor.outdoor_temp\') }} degrees" or any expression from ' +
      'https://www.home-assistant.io/docs/configuration/templating/. This is the idiomatic way to ' +
      'compute derived values (averages, counts, formatted summaries) inside HA instead of fetching ' +
      'raw states and computing here.',
    inputSchema: {
      type: 'object',
      properties: {
        template: { type: 'string', description: 'The Jinja2 template source to render, e.g. "{{ states(\'sun.sun\') }}".' },
      },
      required: ['template'],
      additionalProperties: false,
    },
  },
  {
    name: 'fire_event',
    description:
      'Fire a custom event on Home Assistant\'s event bus, with an optional JSON data payload - e.g. ' +
      'event_type="vegaduta_agent_notify" event_data={"message": "laundry done"}. Automations in HA ' +
      'can listen for these events, which makes this the generic hook for triggering tenant-authored ' +
      'automations that this server has no dedicated tool for.',
    inputSchema: {
      type: 'object',
      properties: {
        event_type: { type: 'string', description: 'The event type to fire, e.g. "vegaduta_agent_notify".' },
        event_data: {
          type: 'object',
          description: 'Optional JSON object sent as the event\'s data payload. Omit for an event with no data.',
        },
      },
      required: ['event_type'],
      additionalProperties: false,
    },
  },
  {
    name: 'get_camera_snapshot',
    description:
      'Fetch the current still image from a Home Assistant camera entity (e.g. ' +
      '"camera.front_yard") and return it as an image the agent can look at. Uses HA\'s ' +
      '/api/camera_proxy endpoint, so it works for any camera integration HA itself can render.',
    inputSchema: {
      type: 'object',
      properties: {
        entity_id: { type: 'string', description: 'Camera entity id, e.g. "camera.front_yard".' },
      },
      required: ['entity_id'],
      additionalProperties: false,
    },
  },
];

function haHeaders() {
  return {
    Authorization: `Bearer ${HASS_TOKEN}`,
    'Content-Type': 'application/json',
  };
}

/** Thin wrapper standardizing HA's REST error shape into a plain Error with a useful message. */
async function haFetch(path, init) {
  const res = await fetch(`${HASS_URL}${path}`, { ...init, headers: haHeaders() });
  const text = await res.text();
  let body;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = text;
  }
  if (!res.ok) {
    const detail = body && body.message ? body.message : typeof body === 'string' ? body : JSON.stringify(body);
    throw new Error(`Home Assistant returned ${res.status} ${res.statusText}${detail ? `: ${detail}` : ''}`);
  }
  return body;
}

/**
 * Binary-response sibling of haFetch, for endpoints like /api/camera_proxy
 * that return raw image bytes: haFetch's res.text() would mangle them, so
 * this reads the body as an ArrayBuffer instead and hands back the Buffer
 * plus the response content-type. Errors reuse haFetch's message shape.
 */
async function haFetchBinary(path, init) {
  const res = await fetch(`${HASS_URL}${path}`, { ...init, headers: haHeaders() });
  if (!res.ok) {
    const text = await res.text();
    let detail = text;
    try {
      const parsed = JSON.parse(text);
      if (parsed && parsed.message) detail = parsed.message;
    } catch {
      // keep raw text
    }
    throw new Error(`Home Assistant returned ${res.status} ${res.statusText}${detail ? `: ${detail}` : ''}`);
  }
  const arrayBuffer = await res.arrayBuffer();
  return {
    buffer: Buffer.from(arrayBuffer),
    contentType: res.headers.get('content-type') || '',
  };
}

async function listEntities({ domain }) {
  const states = await haFetch('/api/states', { method: 'GET' });
  const filtered = domain ? states.filter((s) => s.entity_id.startsWith(`${domain}.`)) : states;
  return filtered.map((s) => ({
    entity_id: s.entity_id,
    state: s.state,
    friendly_name: s.attributes && s.attributes.friendly_name ? s.attributes.friendly_name : null,
  }));
}

async function getEntityState({ entity_id }) {
  if (!entity_id) throw new Error('entity_id is required');
  return haFetch(`/api/states/${encodeURIComponent(entity_id)}`, { method: 'GET' });
}

async function callService({ domain, service, entity_id, data }) {
  if (!domain) throw new Error('domain is required');
  if (!service) throw new Error('service is required');
  const body = { ...(data || {}) };
  if (entity_id) body.entity_id = entity_id;
  return haFetch(`/api/services/${encodeURIComponent(domain)}/${encodeURIComponent(service)}`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

async function getHistory({ entity_id, start_time, end_time }) {
  if (!entity_id) throw new Error('entity_id is required');
  if (!start_time) throw new Error('start_time is required');
  const query = new URLSearchParams({ filter_entity_id: entity_id });
  if (end_time) query.set('end_time', end_time);
  return haFetch(`/api/history/period/${encodeURIComponent(start_time)}?${query.toString()}`, {
    method: 'GET',
  });
}

async function getLogbook({ start_time, entity_id }) {
  if (!start_time) throw new Error('start_time is required');
  const query = entity_id ? `?${new URLSearchParams({ entity: entity_id }).toString()}` : '';
  return haFetch(`/api/logbook/${encodeURIComponent(start_time)}${query}`, { method: 'GET' });
}

async function listServices() {
  const domains = await haFetch('/api/services', { method: 'GET' });
  // HA's payload nests full field metadata per service; compact it to just
  // domain + service names so the model isn't handed pages of schema.
  return (Array.isArray(domains) ? domains : []).map((d) => ({
    domain: d.domain,
    services: d.services && typeof d.services === 'object' ? Object.keys(d.services) : [],
  }));
}

async function renderTemplate({ template }) {
  if (!template) throw new Error('template is required');
  const rendered = await haFetch('/api/template', {
    method: 'POST',
    body: JSON.stringify({ template }),
  });
  // /api/template answers with PLAIN TEXT, not JSON. haFetch's
  // JSON.parse-with-fallback usually leaves that as a string, but a rendering
  // like "21.5" or "true" *does* parse - normalize back to the raw string
  // either way, since that's what the endpoint actually produced.
  return typeof rendered === 'string' ? rendered : JSON.stringify(rendered);
}

async function fireEvent({ event_type, event_data }) {
  if (!event_type) throw new Error('event_type is required');
  return haFetch(`/api/events/${encodeURIComponent(event_type)}`, {
    method: 'POST',
    body: JSON.stringify(event_data || {}),
  });
}

async function getCameraSnapshot({ entity_id }) {
  if (!entity_id) throw new Error('entity_id is required');
  const { buffer, contentType } = await haFetchBinary(
    `/api/camera_proxy/${encodeURIComponent(entity_id)}`,
    { method: 'GET' }
  );
  // Pre-shaped MCP image content block - the tools/call dispatcher passes
  // __rawResult through untouched instead of JSON-stringifying it into a
  // text block via toToolResult.
  return {
    __rawResult: {
      content: [
        {
          type: 'image',
          data: buffer.toString('base64'),
          mimeType: contentType && contentType.split(';')[0].trim() ? contentType.split(';')[0].trim() : 'image/jpeg',
        },
      ],
      isError: false,
    },
  };
}

const HANDLERS = {
  list_entities: listEntities,
  get_entity_state: getEntityState,
  call_service: callService,
  get_history: getHistory,
  get_logbook: getLogbook,
  list_services: listServices,
  render_template: renderTemplate,
  fire_event: fireEvent,
  get_camera_snapshot: getCameraSnapshot,
};

/** Wraps a tool result/error into the MCP tools/call content shape. */
function toToolResult(value) {
  return { content: [{ type: 'text', text: JSON.stringify(value, null, 2) }], isError: false };
}

function toToolError(err) {
  return { content: [{ type: 'text', text: `Error: ${err.message || String(err)}` }], isError: true };
}

// --- JSON-RPC 2.0 over newline-delimited stdio -----------------------------

function send(message) {
  process.stdout.write(`${JSON.stringify(message)}\n`);
}

function sendResult(id, result) {
  send({ jsonrpc: '2.0', id, result });
}

function sendError(id, code, message) {
  send({ jsonrpc: '2.0', id, error: { code, message } });
}

async function handleRequest(msg) {
  const { id, method, params } = msg;
  try {
    switch (method) {
      case 'initialize':
        sendResult(id, {
          protocolVersion: PROTOCOL_VERSION,
          capabilities: { tools: {} },
          serverInfo: SERVER_INFO,
        });
        return;
      case 'ping':
        sendResult(id, {});
        return;
      case 'tools/list':
        sendResult(id, { tools: TOOLS });
        return;
      case 'tools/call': {
        const toolName = params && params.name;
        const args = (params && params.arguments) || {};
        const handler = HANDLERS[toolName];
        if (!handler) {
          // Per the MCP 2024-11-05 spec, an unknown tool name is a PROTOCOL
          // error (-32602 Invalid params), not a tool-execution error - only
          // failures of a real tool's execution belong in isError:true results.
          sendError(id, -32602, `Unknown tool "${toolName}"`);
          return;
        }
        try {
          const value = await handler(args);
          // Escape hatch mirroring toToolResult: a handler that has already
          // built its own MCP content shape (e.g. get_camera_snapshot's image
          // block) returns { __rawResult: <shape> } and it's sent verbatim.
          if (value && typeof value === 'object' && value.__rawResult) {
            sendResult(id, value.__rawResult);
          } else {
            sendResult(id, toToolResult(value));
          }
        } catch (err) {
          sendResult(id, toToolError(err));
        }
        return;
      }
      default:
        // Unrecognized method - if it carries an id it's a request needing a
        // response (per JSON-RPC 2.0, "method not found"); notifications
        // (no id) are silently ignored, matching notifications/initialized,
        // notifications/cancelled, etc., none of which this tool-only
        // server needs to act on.
        if (id !== undefined) {
          sendError(id, -32601, `Method not found: ${method}`);
        }
    }
  } catch (err) {
    if (id !== undefined) {
      sendError(id, -32603, err.message || String(err));
    }
  }
}

function main() {
  const rl = readline.createInterface({ input: process.stdin, terminal: false });
  rl.on('line', (line) => {
    const trimmed = line.trim();
    if (!trimmed) return;
    let msg;
    try {
      msg = JSON.parse(trimmed);
    } catch (err) {
      // Malformed line - can't know the request id, so there's nothing
      // meaningful to reply with per JSON-RPC 2.0; log and move on rather
      // than crashing the whole connection over one bad line.
      process.stderr.write(`home-assistant MCP server: failed to parse line as JSON: ${err.message}\n`);
      return;
    }
    handleRequest(msg).catch((err) => {
      process.stderr.write(`home-assistant MCP server: unhandled error: ${err.stack || err}\n`);
    });
  });
  rl.on('close', () => process.exit(0));
}

if (require.main === module) {
  main();
}

module.exports = {
  TOOLS,
  HANDLERS,
  listEntities,
  getEntityState,
  callService,
  getHistory,
  getLogbook,
  listServices,
  renderTemplate,
  fireEvent,
  getCameraSnapshot,
};
