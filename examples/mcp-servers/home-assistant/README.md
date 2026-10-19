# mcp-server-home-assistant

Reference MCP connector for [Home Assistant](https://www.home-assistant.io/), built per
`VEGADUTA-MASTER-ROADMAP-2026-07-19.md` §9.4 ("IoT — cameras, lighting, alert systems —
decision: same MCP path, Home Assistant as the reference connector"). This is the
first-party example of the pattern described in
`core/mcp/REGISTERING-A-CUSTOM-MCP-SERVER.md` — read that first if you haven't; this README
only covers what's specific to Home Assistant.

Zero npm dependencies (see `index.js`'s header comment for why — the sandbox this was built
in had no registry access to actually install/verify `@modelcontextprotocol/sdk`, so the
small newline-delimited JSON-RPC 2.0 subset of the MCP stdio transport spec is hand-rolled
here instead of pulled in as a dependency neither installable nor testable at build time).
Every request Home Assistant sees is a plain call against its own documented
[REST API](https://developers.home-assistant.io/docs/api/rest/) (plus, for the event
bridge, its documented
[WebSocket API](https://developers.home-assistant.io/docs/api/websocket/)) — no custom HA
component or add-on required on the Home Assistant side.

Two processes live in this directory:

- **`index.js`** — the MCP stdio server (the "pull" half): an agent asks HA a question or
  acts on it. Launched per-tenant by `DynamicMcpConnectionManager`.
- **`event-bridge.js`** — a standalone long-running process (the "push" half): subscribes
  to HA's WebSocket event stream and forwards matching events to a VegaDuta workflow
  webhook, so an IoT alert can trigger a workflow without any HA-side automation config.

## What this covers — the "pull" half (§9.1)

Nine tools. Read/inspect:

- `list_entities` — list every entity (optionally filtered by domain — `light`, `lock`,
  `climate`, etc.) with its current state and friendly name.
- `get_entity_state` — full state + attributes for one entity by id.
- `get_history` — recorded state history of one entity over a time window
  (`GET /api/history/period/<start_time>?filter_entity_id=<entity_id>&end_time=...`);
  `entity_id` and `start_time` (ISO 8601) required, `end_time` optional ("until now").
- `get_logbook` — HA's human-readable logbook entries from a start time, optionally
  narrowed to one entity (`GET /api/logbook/<start_time>?entity=...`). Good for
  "what happened in the house today?" questions.
- `list_services` — every service HA currently exposes, compacted to
  `{domain, services: [names]}` per domain (full field-level metadata is deliberately
  dropped to keep the payload small). Use it to discover what `call_service` can do.
- `render_template` — render a Jinja2 template server-side (`POST /api/template`) and
  return the raw plain-text result. Note: the handler always returns the raw text the
  endpoint produced, even when that text happens to parse as JSON (e.g. `"21.5"`).
- `get_camera_snapshot` — current still image from a camera entity via
  `GET /api/camera_proxy/<entity_id>`, returned as a real MCP **image** content block
  (`{type: "image", data: <base64>, mimeType: ...}`, mime type taken from the response
  content-type, defaulting to `image/jpeg`) — not a text blob, so vision-capable agents
  can actually look at it.

Act on the house:

- `call_service` — call any Home Assistant service (`light.turn_on`, `lock.unlock`,
  `climate.set_temperature`, anything HA itself supports) against an entity.
- `fire_event` — fire a custom event on HA's event bus
  (`POST /api/events/<event_type>`, optional JSON `event_data`). Tenant-authored HA
  automations can listen for these, making it the generic hook for anything this server
  has no dedicated tool for.

### Guardrails and human approval for destructive calls

`call_service` (and to a lesser degree `fire_event`) can be genuinely destructive or
security-sensitive (unlocking a door, disarming an alarm, turning off a camera). **You
don't need to build any custom guardrail logic for this** — every tool registered through
`core/mcp`'s dynamic connector path is automatically wrapped in `GuardedToolCallback`
(per-turn step ceiling, output sanitization, optional independent verification for
`RIGOROUS` agents) and `LoggingToolCallback` (full audit trail to `tool_call_log`).

A tenant admin can additionally require human approval before a specific tool runs via the
platform's existing approval-policy endpoint — but note the **action type is namespaced**:
dynamic-connector tools are renamed to `mcp__<server>__<tool>` (by
`NamespacedMcpToolCallback`) *before* the approval check sees them, so for a server
registered with `"name": "home-assistant"` the correct action type is

```
PUT /api/approvals/policy/mcp_tool.mcp__home-assistant__call_service
```

— **not** `mcp_tool.call_service`, which would never match anything. The `<server>`
segment comes from the registration's `name` field (sanitized to `[A-Za-z0-9_-]`), so a
registration under a different name changes the action type accordingly.

Also be precise about *which agents* this gates: the policy override reliably gates agents
at the default **SEMI_AUTONOMOUS** autonomy tier (policy-driven); **SUPERVISED** agents
gate every tool call regardless of policy; a **FULLY_AUTONOMOUS** agent bypasses the
approval-policy check entirely. Tenants relying on this gate should not mark agents with
smart-home access as FULLY_AUTONOMOUS. Approved actions are genuinely re-run via the
replay-grant mechanism (not just audited). See `REGISTERING-A-CUSTOM-MCP-SERVER.md`'s
"Governance" section for how and why the wrapping is automatic.

## What this covers — the "push"/alert half (§9.1, §9.4): the event bridge

The roadmap's §9.4 asks for "wire Home Assistant's event stream into §9.1's push path
(eventbus → workflow trigger) so an IoT alert can kick off an agent/workflow immediately."
That is now `event-bridge.js`: a zero-dependency standalone process that subscribes to
HA's WebSocket event stream (`/api/websocket`) with a hand-rolled minimal RFC 6455 client
(Node 20 has no stable global `WebSocket` class — that's Node 21+ — hence hand-rolled;
masked client frames, 16/64-bit extended lengths, ping→pong, no extensions, no
fragmentation support), speaks HA's auth/subscribe protocol, filters events, and POSTs
each match to a VegaDuta workflow webhook.

### Env config

| Variable | Required | Meaning |
| --- | --- | --- |
| `HASS_URL` | yes | e.g. `http://homeassistant.local:8123` — same var as `index.js`; `http(s)` is mapped to `ws(s)` |
| `HASS_TOKEN` | yes | HA long-lived access token — same var as `index.js`. `auth_invalid` from HA is treated as fatal (exit 1), since retrying a bad token would loop forever |
| `VEGADUTA_WEBHOOK_URL` | yes | FULL workflow webhook URL, e.g. `https://api.staging.example.com/webhook/workflows/<token>` |
| `HASS_EVENT_TYPES` | no | comma-separated HA event types to subscribe to; default `state_changed` |
| `HASS_ENTITY_FILTER` | no | comma-separated entity_ids and/or domain prefixes ending in `.` (e.g. `lock.,binary_sensor.motion_hall`). When set, only `state_changed` events whose entity_id matches are forwarded; other subscribed event types always forward |

### Running it

This is **not** an MCP server and is not registered through `/api/mcp/registry` — it's a
plain long-lived process:

```
HASS_URL=... HASS_TOKEN=... VEGADUTA_WEBHOOK_URL=... node event-bridge.js
```

Run it under whatever keeps long-running processes alive in your setup (a systemd unit, a
container with `restart: unless-stopped`, pm2, an HA add-on host — anything). It never
writes to stdout (all logging goes to stderr), reconnects on socket close/error with
exponential backoff (1s doubling to 60s, reset after a successful `auth_ok`), and a
failed webhook POST is logged and dropped — one bad delivery never crashes the bridge.

### Payload contract

Each forwarded event is POSTed as:

```json
{ "input": "Home Assistant: lock.front_door changed from locked to unlocked",
  "event": { ...the raw HA event... } }
```

`WorkflowWebhookController.extractInput()` reads the `input` key as the Trigger node's
output text; the raw HA event rides alongside for workflows that want full detail (any
extra keys are tolerated — and if `input` were absent, the controller would JSON-serialize
the whole body into the trigger input instead).

### Getting the webhook URL (per-Trigger-node, not per-workflow)

The webhook endpoint is `POST /webhook/workflows/{token}` (opaque shared-secret token,
only its SHA-256 hash is stored server-side); there's also an OIDC-secured variant at
`POST /webhook/workflows/oidc/{workflowId}` for callers that can present a bearer JWT with
a `tenant_id` claim and the `workflow-trigger` realm role (hand-assigned in Keycloak).

Webhook enablement, the auth mode (`SHARED_SECRET` vs `OAUTH2`), and the token are
configured **on the workflow's Trigger node** — each Trigger node in the graph has its own
independent token — not as workflow-level `triggerChannel`/`webhookAuthMode` fields. The
workflow must also be **Activated** (status `ACTIVE`) or the call returns 409. The token
path dedupes redeliveries via the `X-Delivery-Id` / `X-Webhook-Id` / `X-Request-Id`
headers (in that order, falling back to a SHA-256 of the body) and returns 409 for
duplicates — harmless for the setups below, but useful to know when debugging.

### The HA-automation alternative (still works)

Before the bridge existed, the documented path was to let Home Assistant's own automation
engine do the pushing via its
[`rest_command`](https://www.home-assistant.io/integrations/rest_command/) integration.
That path still works unchanged and needs nothing running on the VegaDuta side beyond the
same webhook — it's the right choice when the tenant already lives in HA's automation
editor, wants HA-side conditions/templating on the trigger, or can't run another process:

```yaml
automation:
  - alias: "Notify VegaDuta on front door unlock"
    trigger:
      - platform: state
        entity_id: lock.front_door
        to: "unlocked"
    action:
      - service: rest_command.vegaduta_workflow_trigger

rest_command:
  vegaduta_workflow_trigger:
    url: "https://<your-vegaduta-host>/webhook/workflows/<token>"
    method: POST
    headers:
      content-type: "application/json"
    payload: '{"input": "Front door was unlocked."}'
```

The event bridge replaces this *workaround* for the common case — no per-alert HA-side
YAML, one process forwards whatever the filter matches — but the two coexist fine (they're
both just POSTs to the same endpoint; redelivery dedup keys them independently).

## Registering the MCP connector

```
POST /api/mcp/registry
{
  "name": "home-assistant",
  "type": "STDIO",
  "description": "Controls and queries Home Assistant entities for this tenant's smart-home setup.",
  "enabled": true,
  "command": "node",
  "args": ["/absolute/path/to/mcp-servers/home-assistant/index.js"],
  "env": {
    "HASS_URL": "http://homeassistant.local:8123",
    "HASS_TOKEN": "<a Home Assistant long-lived access token>"
  }
}
```

The `HASS_TOKEN` is generated from the tenant's own Home Assistant instance under
Profile → Security → Long-Lived Access Tokens. It's encrypted at rest by
`McpRegistryService` the same as every other dynamic connection's `env` map — see the
registration guide for the full credential-handling story.

There is also a curated catalog entry (`home-assistant` in `McpCatalogService`) that
pre-fills this registration with `args: ["/opt/mcp-servers/home-assistant/index.js"]` —
that path exists inside the core/mcp-stdio-runner containers via a read-only
`../mcp-servers:/opt/mcp-servers:ro` bind mount in `deploy/docker-compose.yml`, so
containers must have been (re)created with that mount for the catalog entry to connect.

## Testing

Two framework-free suites, both real integration tests that spawn the process under test
as an actual child process — no `npm install` is ever required (zero runtime and zero dev
dependencies):

```
node test/server.test.js        # 33 assertions - the MCP server
node test/event-bridge.test.js  # 21 assertions - the event bridge
```

(`npm test` runs only the first; run both explicitly.) `server.test.js` speaks the real
MCP JSON-RPC protocol to `index.js`'s stdin/stdout against a throwaway local HTTP server
standing in for Home Assistant — every one of the nine tools is exercised against real
HTTP calls (including a binary `image/jpeg` camera route asserting an exact byte
round-trip through base64, and a plain-text `/api/template` route), with happy-path,
HA-error-path, and input-validation assertions per tool. `event-bridge.test.js` spawns
`event-bridge.js` against a hand-rolled local WebSocket server (which asserts every client
frame was masked per RFC 6455) plus a fake webhook HTTP server, covering the full
auth→subscribe→event→POST pipeline, a >64KB frame (64-bit length path), a filtered-out
entity never arriving, survival of a webhook 500, and fail-fast on missing env vars. Both
suites exit non-zero on any failure. All 33 + 21 assertions pass as of this writing.

## Honest caveats

- **Never run against a real Home Assistant instance** (none was available to test
  against) — this applies to both `index.js` and `event-bridge.js`. Every interaction is
  verified against HA's documented REST/WebSocket API shapes and against local mock
  servers, not a live HA deployment. Recommend a real end-to-end smoke test against an
  actual Home Assistant instance before treating either as production-ready. (The curated
  catalog entry's description says the same.)
- Target runtime is **Node 20** (the platform's bundled runtime); the test suites here
  were last executed under Node 24. Static review found no >Node-20 APIs (no global
  `WebSocket` — the bridge hand-rolls RFC 6455; `fetch`, `readline`, `crypto`, `Buffer`
  BigUInt64 methods, `res.arrayBuffer()` all exist in Node 20), but the suites have not
  literally been run on a Node 20 binary.
- Event-bridge specifics not covered by its test: the reconnect backoff *timing ladder*
  (reconnection single-fires via a settled-guard, but the test doesn't kill the socket
  and time the 1s/2s/… delays), and the TLS path (`https`/`wss` shares the same code via
  the transport switch but the test uses plain `http` on 127.0.0.1).
- Built and tested entirely without network access to the npm registry (see `index.js`'s
  header) — if `@modelcontextprotocol/sdk` becomes available, swapping the hand-rolled
  transport in `index.js` for the SDK's `StdioServerTransport` is a mechanical change; the
  tool definitions/handlers (`TOOLS`, `HANDLERS`, and the per-tool functions) don't need
  to move.
- Nine tools now, but Home Assistant's API surface is still larger (areas/devices/config
  endpoints, calendars, intents). This remains a *reference* connector demonstrating the
  pattern, not an exhaustive HA API wrapper; add tools the same way the existing nine are
  written if more coverage is needed.
