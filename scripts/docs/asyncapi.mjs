#!/usr/bin/env node
// Generates docs/api/asyncapi.json (AsyncAPI 3.0) for the platform's event surfaces: Server-Sent
// Event streams, outbound webhooks and the voice WebSocket relay. Plain Node, no dependencies.
//
//   node scripts/docs/asyncapi.mjs           write docs/api/asyncapi.json
//   node scripts/docs/asyncapi.mjs --check   exit 1 when the committed file is stale, or when a
//                                            documented workflow-run event name no longer appears
//                                            in core/workflow's sources
//
// Sources, so nothing here is hand-maintained twice:
// - SSE operations and their schemas: docs/api/openapi.json (operations flagged x-sse)
// - webhook delivery: the "webhooks" object of the same spec (WorkflowEvent + signature headers)
// - Twilio Conversation Relay messages: contracts/inbound/twilio-conversation-relay/schemas
// The workflow-run stream is the one SSE stream with named events; its names are listed below and
// --check verifies each still exists in core/workflow (WorkflowRunStreamHub, NodeStreamSink).

import { existsSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const OUT = join(root, "docs/api/asyncapi.json");
const RUN_EVENTS_PATH = "/api/workflows/{workflowId}/runs/{runId}/events";
const RUN_EVENTS = [
  ["snapshot", "Sent first on (re)connect: the run's state so far."],
  ["run.started", "The run began."],
  ["run.resumed", "A paused run resumed."],
  ["run.awaiting", "The run paused for input or approval."],
  ["run.finished", "The run reached a terminal status."],
  ["node.started", "A node began."],
  ["node.completed", "A node finished."],
  ["node.token", "A streamed text chunk from an agent or LLM node."],
  ["node.tool_call", "A node's agent called a tool."],
  ["node.tool_result", "A tool returned."],
  ["node.tool_error", "A tool failed."],
  ["node.agent_event", "Another agent step event."],
  ["error", "The stream itself failed; the client should reconnect with Last-Event-ID."],
];

function slug(s) {
  return s.replace(/[{}]/g, "").replace(/[^A-Za-z0-9]+/g, "_").replace(/^_|_$/g, "");
}

/** Copies every schema reachable from `node` (OpenAPI #/components/schemas refs) into `out`. */
function collectRefs(spec, node, out) {
  if (Array.isArray(node)) {
    for (const n of node) collectRefs(spec, n, out);
  } else if (node && typeof node === "object") {
    const ref = node.$ref;
    if (typeof ref === "string" && ref.startsWith("#/components/schemas/")) {
      const name = ref.slice("#/components/schemas/".length);
      if (!out[name]) {
        out[name] = spec.components.schemas[name];
        collectRefs(spec, out[name], out);
      }
    }
    for (const v of Object.values(node)) collectRefs(spec, v, out);
  }
}

function build() {
  const spec = JSON.parse(readFileSync(join(root, "docs/api/openapi.json"), "utf8"));
  const channels = {};
  const operations = {};
  const messages = {};
  const schemas = {};

  // 1. Server-Sent Event streams.
  for (const [path, item] of Object.entries(spec.paths).sort(([a], [b]) => a.localeCompare(b))) {
    for (const [verb, op] of Object.entries(item)) {
      if (!op || !op["x-sse"]) continue;
      const ch = slug(op.operationId);
      const parameters = {};
      for (const m of path.matchAll(/\{([^}]+)\}/g)) parameters[m[1]] = { description: `Path variable ${m[1]}.` };
      const channelMessages = {};
      if (path === RUN_EVENTS_PATH) {
        for (const [event, description] of RUN_EVENTS) {
          const name = `workflowRun_${slug(event)}`;
          messages[name] = {
            name: event,
            title: event,
            summary: description,
            contentType: "application/json",
            payload: {
              type: "object",
              properties: {
                seq: { type: "integer", description: "Event id; send the last one seen as Last-Event-ID to resume." },
                type: { type: "string", const: event },
                nodeId: { type: ["string", "null"] },
                ts: { type: "string", format: "date-time" },
                data: { type: "object", additionalProperties: true },
              },
            },
          };
          channelMessages[name] = { $ref: `#/components/messages/${name}` };
        }
      } else {
        const name = `${ch}_chunk`;
        const payload = op["x-sse-data-schema"] ?? { type: "string", description: "One chunk of the streamed reply." };
        collectRefs(spec, payload, schemas);
        messages[name] = {
          name: "message",
          summary: "One Server-Sent Event; data carries the next chunk.",
          contentType: op["x-sse-data-schema"] ? "application/json" : "text/plain",
          payload,
        };
        channelMessages[name] = { $ref: `#/components/messages/${name}` };
      }
      channels[ch] = {
        address: path,
        title: op.summary,
        description: `${verb.toUpperCase()} ${path} answered as text/event-stream. ${op.description ?? ""}`.trim(),
        ...(Object.keys(parameters).length ? { parameters } : {}),
        messages: channelMessages,
        bindings: { http: { method: verb.toUpperCase() } },
      };
      operations[ch] = {
        action: "send",
        channel: { $ref: `#/channels/${ch}` },
        summary: `Stream from ${op.operationId}`,
        messages: Object.keys(channelMessages).map((n) => ({ $ref: `#/channels/${ch}/messages/${n}` })),
        ...(op.security?.length ? { security: [{ $ref: "#/components/securitySchemes/bearerAuth" }] } : {}),
      };
    }
  }

  // 2. Outbound webhooks.
  for (const [name, item] of Object.entries(spec.webhooks ?? {})) {
    const op = item.post;
    const body = op.requestBody.content["application/json"].schema;
    collectRefs(spec, body, schemas);
    const headers = { type: "object", properties: {}, required: [] };
    for (const p of op.parameters.filter((x) => x.in === "header")) {
      headers.properties[p.name] = { ...p.schema, description: p.description };
      if (p.required) headers.required.push(p.name);
    }
    const msg = `webhook_${slug(name)}`;
    messages[msg] = { name, summary: op.summary, description: op.description, contentType: "application/json", headers, payload: body };
    channels[`webhook_${slug(name)}`] = {
      address: null,
      title: "Tenant webhook subscription URL",
      description: "POSTed to the URL of each matching subscription (/api/webhooks). Any 2xx acknowledges; anything else is retried.",
      messages: { [msg]: { $ref: `#/components/messages/${msg}` } },
      bindings: { http: { method: "POST" } },
    };
    operations[`deliver_${slug(name)}`] = {
      action: "send",
      channel: { $ref: `#/channels/webhook_${slug(name)}` },
      summary: op.summary,
      messages: [{ $ref: `#/channels/webhook_${slug(name)}/messages/${msg}` }],
    };
  }

  // 3. Twilio Conversation Relay WebSocket (inbound messages from Twilio).
  const relayDir = join(root, "contracts/inbound/twilio-conversation-relay/schemas");
  const relayMessages = {};
  for (const file of readdirSync(relayDir).filter((f) => f.endsWith(".schema.json")).sort()) {
    const type = file.replace(".schema.json", "");
    const schema = JSON.parse(readFileSync(join(relayDir, file), "utf8"));
    delete schema.$schema;
    delete schema.$id;
    const name = `voiceRelay_${slug(type)}`;
    messages[name] = { name: type, summary: schema.title ?? `${type} message`, contentType: "application/json", payload: schema };
    relayMessages[name] = { $ref: `#/components/messages/${name}` };
  }
  channels.voiceRelay = {
    address: "/webhook/twilio/voice-relay",
    title: "Twilio Conversation Relay",
    description:
      "WebSocket opened by Twilio for a voice call. Message schemas are derived from VoiceRelayWebSocketHandler; see contracts/README.md.",
    messages: relayMessages,
    bindings: { ws: {} },
  };
  operations.voiceRelayReceive = {
    action: "receive",
    channel: { $ref: "#/channels/voiceRelay" },
    summary: "Messages Twilio sends during a call",
    messages: Object.keys(relayMessages).map((n) => ({ $ref: `#/channels/voiceRelay/messages/${n}` })),
  };

  const sortKeys = (o) => Object.fromEntries(Object.entries(o).sort(([a], [b]) => a.localeCompare(b)));
  return {
    asyncapi: "3.0.0",
    info: {
      title: "VegaDuta event API",
      version: spec.info.version,
      description:
        "Event surfaces of the VegaDuta platform: Server-Sent Event streams, outbound webhooks and the voice relay WebSocket. Generated from docs/api/openapi.json and contracts/ by scripts/docs/asyncapi.mjs; do not edit by hand.",
      license: spec.info.license,
    },
    servers: {
      api: {
        host: "{host}",
        protocol: "https",
        description: "Your deployment's API origin.",
        variables: { host: { default: "api.example.com", description: "Host (and port) of your VegaDuta API." } },
      },
    },
    defaultContentType: "application/json",
    channels: sortKeys(channels),
    operations: sortKeys(operations),
    components: {
      messages: sortKeys(messages),
      schemas: sortKeys(schemas),
      securitySchemes: {
        bearerAuth: { type: "http", scheme: "bearer", bearerFormat: "JWT or vdak_ tenant API key" },
      },
    },
  };
}

/** OpenAPI component refs stay valid: AsyncAPI also keeps schemas under #/components/schemas. */
function render(doc) {
  return JSON.stringify(doc, null, 2) + "\n";
}

function workflowSources() {
  const dir = join(root, "core/workflow/src/main/java/com/agenticai/core/workflow");
  const out = [];
  const walk = (d) => {
    for (const e of readdirSync(d, { withFileTypes: true })) {
      const p = join(d, e.name);
      if (e.isDirectory()) walk(p);
      else if (e.name.endsWith(".java")) out.push(readFileSync(p, "utf8"));
    }
  };
  if (existsSync(dir)) walk(dir);
  return out.join("\n");
}

const text = render(build());
if (process.argv.includes("--check")) {
  const problems = [];
  const src = workflowSources();
  for (const [event] of RUN_EVENTS) if (!src.includes(`"${event}"`)) problems.push(`workflow run event "${event}" not found in core/workflow`);
  if (!existsSync(OUT) || readFileSync(OUT, "utf8") !== text) problems.push("docs/api/asyncapi.json is stale. Run: node scripts/docs/asyncapi.mjs");
  if (problems.length) {
    console.error(problems.join("\n"));
    process.exit(1);
  }
  console.log("docs/api/asyncapi.json is current.");
} else {
  writeFileSync(OUT, text);
  console.log("wrote docs/api/asyncapi.json");
}
