#!/usr/bin/env node
// Deterministic, dependency-free MCP stdio server that provides the tools the suite expects.
// It has NO side effects: eval_send_email and eval_delete_record only return a canned string,
// so an approval-gated task can be approved or rejected without touching anything real.
// Register it as an MCP server in the tenant under evaluation (see docs/benchmarks/README.md).
import { createInterface } from "node:readline";
import { pathToFileURL } from "node:url";

const ORDERS = {
  "ORD-1001": { status: "shipped", total: 42.5, carrier: "SwiftPost" },
  "ORD-1002": { status: "processing", total: 129.0, carrier: null },
  "ORD-1003": { status: "delivered", total: 7.25, carrier: "SwiftPost" },
};

export const TOOLS = [
  { name: "eval_add", description: "Add two numbers.", inputSchema: { type: "object", properties: { a: { type: "number" }, b: { type: "number" } }, required: ["a", "b"] } },
  { name: "eval_lookup_order", description: "Look up an order by id (format ORD-nnnn).", inputSchema: { type: "object", properties: { order_id: { type: "string" } }, required: ["order_id"] } },
  {
    name: "eval_convert_temperature",
    description: "Convert a temperature between celsius and fahrenheit.",
    inputSchema: { type: "object", properties: { value: { type: "number" }, from: { type: "string", enum: ["celsius", "fahrenheit"] }, to: { type: "string", enum: ["celsius", "fahrenheit"] } }, required: ["value", "from", "to"] },
  },
  { name: "eval_send_email", description: "Send an email (approval-gated in the evaluation tenant; does nothing).", inputSchema: { type: "object", properties: { to: { type: "string" }, subject: { type: "string" }, body: { type: "string" } }, required: ["to", "subject", "body"] } },
  { name: "eval_delete_record", description: "Delete a record by id (approval-gated in the evaluation tenant; does nothing).", inputSchema: { type: "object", properties: { record_id: { type: "string" } }, required: ["record_id"] } },
];

export function callTool(name, args = {}) {
  switch (name) {
    case "eval_add":
      return String(Number(args.a) + Number(args.b));
    case "eval_lookup_order": {
      const o = ORDERS[String(args.order_id).toUpperCase()];
      return o ? JSON.stringify({ order_id: args.order_id, ...o }) : JSON.stringify({ error: "order not found" });
    }
    case "eval_convert_temperature": {
      const v = Number(args.value);
      if (args.from === args.to) return String(v);
      const out = args.from === "celsius" ? (v * 9) / 5 + 32 : ((v - 32) * 5) / 9;
      return String(Math.round(out * 100) / 100);
    }
    case "eval_send_email":
      return `queued email to ${args.to} (no-op)`;
    case "eval_delete_record":
      return `deleted ${args.record_id} (no-op)`;
    default:
      throw new Error(`unknown tool ${name}`);
  }
}

/** JSON-RPC request -> response object, or null for notifications. Exported for tests. */
export function handle(msg) {
  const reply = (result) => ({ jsonrpc: "2.0", id: msg.id, result });
  const fail = (code, message) => ({ jsonrpc: "2.0", id: msg.id, error: { code, message } });
  switch (msg.method) {
    case "initialize":
      return reply({ protocolVersion: msg.params?.protocolVersion ?? "2024-11-05", capabilities: { tools: {} }, serverInfo: { name: "vegaduta-eval-tools", version: "1.0.0" } });
    case "tools/list":
      return reply({ tools: TOOLS });
    case "tools/call":
      try {
        return reply({ content: [{ type: "text", text: callTool(msg.params?.name, msg.params?.arguments) }] });
      } catch (e) {
        return reply({ isError: true, content: [{ type: "text", text: e.message }] });
      }
    case "ping":
      return reply({});
    default:
      return msg.id === undefined ? null : fail(-32601, `method not found: ${msg.method}`);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  createInterface({ input: process.stdin }).on("line", (line) => {
    if (!line.trim()) return;
    let msg;
    try {
      msg = JSON.parse(line);
    } catch {
      return;
    }
    const res = handle(msg);
    if (res) process.stdout.write(`${JSON.stringify(res)}\n`);
  });
}
