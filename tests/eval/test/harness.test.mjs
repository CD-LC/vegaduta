import test from "node:test";
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { loadSuite, validateSuite, CATEGORIES } from "../lib/suite.mjs";
import { validateAdapter, validateRunResult } from "../lib/adapter.mjs";
import { runSuite } from "../lib/runner.mjs";
import { buildResults, renderMarkdown } from "../lib/report.mjs";
import { parseChatSse, toolCallsFromSteps, STEP_EVENT_MARKER, STREAM_ERROR_MARKER } from "../lib/sse.mjs";
import { usageFromSummary, resultFromWorkflowRun } from "../adapters/vegaduta.mjs";
import { handle, callTool } from "../fixtures/eval-tools-mcp.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const suitePath = resolve(here, "../tasks/suite.v1.json");

test("shipped suite is valid, ~20 tasks, covers every category, unique ids", () => {
  const suite = loadSuite(suitePath);
  assert.equal(suite.tasks.length, 20);
  for (const c of CATEGORIES) assert.ok(suite.tasks.some((t) => t.category === c), `category ${c}`);
  assert.equal(new Set(suite.tasks.map((t) => t.id)).size, suite.tasks.length);
});

test("shipped suite: tool-use tasks all declare tool expectations; approval tasks all declare a guardrail", () => {
  const suite = loadSuite(suitePath);
  for (const t of suite.tasks.filter((x) => x.category === "tool-use")) assert.ok(t.tools, t.id);
  for (const t of suite.tasks.filter((x) => x.category === "approval")) assert.ok(t.guardrail?.approval, t.id);
});

test("shipped suite: every expected tool exists in the fixture MCP server", () => {
  const known = new Set(handle({ id: 1, method: "tools/list" }).result.tools.map((t) => t.name));
  for (const t of loadSuite(suitePath).tasks) {
    for (const c of t.tools?.calls ?? []) assert.ok(known.has(c.tool), `${t.id}: ${c.tool}`);
    for (const f of t.tools?.forbidden ?? []) assert.ok(known.has(f), `${t.id}: ${f}`);
  }
});

test("validateSuite flags the common mistakes", () => {
  const bad = { name: "x", version: "1", tasks: [{ id: "a", category: "nope", input: "", role: "", checks: [{ type: "regex", pattern: "(" }] }, { id: "a", category: "qa", input: "q", role: "agent:qa" }] };
  const errs = validateSuite(bad).join("\n");
  assert.match(errs, /semver/);
  assert.match(errs, /bad category/);
  assert.match(errs, /input must be/);
  assert.match(errs, /missing role/);
  assert.match(errs, /invalid regex/);
  assert.match(errs, /duplicate task id/);
  assert.match(errs, /nothing to score/);
});

test("validateAdapter / validateRunResult", () => {
  assert.equal(validateAdapter({ name: "x", init() {}, describe() {}, runTask() {} }).length, 0);
  assert.ok(validateAdapter({ name: "x" }).length >= 3);
  assert.equal(validateRunResult({ text: "a", toolCalls: [{ tool: "t" }], usage: { totalTokens: 1 } }).length, 0);
  assert.equal(validateRunResult({ error: "x" }).length, 0);
  assert.ok(validateRunResult({}).length > 0);
  assert.ok(validateRunResult({ text: "a", toolCalls: [{ args: {} }] }).length > 0);
  assert.ok(validateRunResult({ text: "a", usage: { costUsd: "1" } }).length > 0);
});

function fakeAdapter(impl) {
  return { name: "fake", init: async () => ({}), describe: () => ({ target: "fake://" }), runTask: impl };
}
const miniSuite = {
  name: "mini",
  version: "1.0.0",
  tasks: [
    { id: "a", category: "qa", role: "agent:qa", input: "q", checks: [{ type: "contains_all", values: ["yes"] }] },
    { id: "b", category: "tool-use", role: "agent:tools", input: "q", checks: [{ type: "contains_all", values: ["6"] }], tools: { calls: [{ tool: "eval_add" }] } },
  ],
};

test("runSuite: times with the injected clock, repeats, scores, and survives adapter failures", async () => {
  let t = 0;
  const now = () => (t += 50); // every call advances 50 ms, so each run measures exactly 50
  const adapter = fakeAdapter(async (task) => {
    if (task.id === "b") throw new Error("kaboom");
    return { text: "yes", usage: { totalTokens: 5, costUsd: 0.001 } };
  });
  const seen = [];
  const recs = await runSuite({ suite: miniSuite, adapter, ctx: {}, repeats: 2, now, onRun: (r) => seen.push(r.taskId) });
  assert.equal(recs.length, 4);
  assert.deepEqual(seen, ["a", "a", "b", "b"]);
  assert.ok(recs.every((r) => r.latencyMs === 50));
  assert.equal(recs.filter((r) => r.taskId === "a").every((r) => r.success), true);
  const b = recs.filter((r) => r.taskId === "b");
  assert.ok(b.every((r) => r.success === false && /adapter threw: kaboom/.test(r.error)));
});

test("runSuite: malformed RunResult is a recorded failure, --only filters, unknown id throws", async () => {
  const adapter = fakeAdapter(async () => ({ nope: 1 }));
  const recs = await runSuite({ suite: miniSuite, adapter, ctx: {}, only: ["a"] });
  assert.equal(recs.length, 1);
  assert.match(recs[0].error, /malformed RunResult/);
  await assert.rejects(runSuite({ suite: miniSuite, adapter, ctx: {}, only: ["zzz"] }), /unknown task id/);
  await assert.rejects(runSuite({ suite: miniSuite, adapter: { name: "x" }, ctx: {} }), /invalid adapter/);
});

test("report: results document and markdown carry provenance, n/a, and failures", async () => {
  const adapter = fakeAdapter(async (task) => (task.id === "a" ? { text: "yes" } : { text: "6", toolCalls: [{ tool: "eval_add" }], usage: { totalTokens: 11, costUsd: 0.5, unpricedCalls: 1 } }));
  const recs = await runSuite({ suite: miniSuite, adapter, ctx: {}, now: ((t) => () => (t += 10))(0) });
  const meta = { system: "fake", target: "fake://", model: null, suite: { name: "mini", version: "1.0.0", taskCount: 2 }, repeats: 1, startedAt: "s", finishedAt: "f", harnessCommit: null };
  const res = buildResults(meta, recs);
  assert.equal(res.schema, "vegaduta-eval-results/1");
  assert.equal(res.overall.successRate, 1);
  assert.equal(res.overall.costUsd.pricedRuns, 0); // unpriced call: cost not trusted
  assert.equal(res.byCategory["tool-use"].toolAccuracy.exactRate, 1);
  const md = renderMarkdown(res);
  assert.match(md, /not LLM-judged|not an LLM judge/);
  assert.match(md, /\| \*\*overall\*\* \| 2 \| 100\.0% \|/);
  assert.match(md, /n\/a/);
  assert.match(md, /No failing runs/);
  assert.ok(!JSON.stringify(res).toLowerCase().includes("token\":\"")); // no credential-like fields
  const failing = renderMarkdown(buildResults(meta, await runSuite({ suite: miniSuite, adapter: fakeAdapter(async () => ({ text: "no" })), ctx: {} })));
  assert.match(failing, /## Failing runs \(2\)/);
  assert.match(failing, /`a`: contains_all: missing: yes/);
});

test("sse: reply text, step events, stream errors; tool calls extracted in order", () => {
  const body = [
    "data:Hel\n\n",
    `data:${STEP_EVENT_MARKER}{"type":"tool_call","id":1,"tool":"eval_add","args":{"a":1,"b":2}}\n\n`,
    `data:${STEP_EVENT_MARKER}{"type":"tool_result","id":1,"tool":"eval_add","preview":"3","ms":4}\n\n`,
    "data:lo \n\n",
    `data:${STEP_EVENT_MARKER}{not json\n\n`,
    `data:${STREAM_ERROR_MARKER} model unavailable\n\n`,
  ].join("");
  const p = parseChatSse(body);
  assert.equal(p.text, "Hello ");
  assert.equal(p.stepEvents.length, 3);
  assert.deepEqual(p.streamErrors, ["model unavailable"]);
  assert.deepEqual(toolCallsFromSteps(p.stepEvents), [{ tool: "eval_add", args: { a: 1, b: 2 } }]);
});

test("vegaduta adapter mappers: usage summary and workflow trace", () => {
  assert.equal(usageFromSummary(undefined), undefined);
  assert.deepEqual(usageFromSummary({ promptTokens: 3, completionTokens: 4, totalTokens: 7, totalCostUsd: 0.01, unpricedCalls: 0 }), { promptTokens: 3, completionTokens: 4, totalTokens: 7, costUsd: 0.01, unpricedCalls: 0 });
  const r = resultFromWorkflowRun({
    trace: [
      { nodeId: "n1", outputJson: "{\"x\":1}", tokensIn: 10, tokensOut: 5, costUsd: 0.001, toolCalls: [{ toolName: "eval_add", input: "{\"a\":1}" }] },
      { nodeId: "n2", summary: "final 42 ODD", outputJson: "42 ODD", tokensIn: 4, tokensOut: 1 },
    ],
  });
  assert.equal(r.text, "42 ODD\nfinal 42 ODD");
  assert.deepEqual(r.toolCalls, [{ tool: "eval_add", args: "{\"a\":1}" }]);
  assert.equal(r.usage.totalTokens, 20);
  assert.equal(r.usage.unpricedCalls, 1); // n2 had tokens but no cost
  assert.equal(resultFromWorkflowRun({ trace: [] }).usage, undefined);
});

test("fixture MCP tools are deterministic and match the suite's expected answers", () => {
  assert.equal(callTool("eval_add", { a: 1234, b: 4321 }), "5555");
  assert.equal(callTool("eval_convert_temperature", { value: 100, from: "celsius", to: "fahrenheit" }), "212");
  assert.equal(JSON.parse(callTool("eval_lookup_order", { order_id: "ORD-1001" })).status, "shipped");
  const total = ["ORD-1001", "ORD-1003"].reduce((s, id) => s + JSON.parse(callTool("eval_lookup_order", { order_id: id })).total, 0);
  assert.equal(total, 49.75);
  assert.equal(JSON.parse(callTool("eval_lookup_order", { order_id: "ORD-1002" })).status, "processing");
  assert.match(callTool("eval_send_email", { to: "a@b" }), /no-op/);
  assert.throws(() => callTool("nope"));
  assert.equal(handle({ jsonrpc: "2.0", method: "notifications/initialized" }), null);
  assert.equal(handle({ jsonrpc: "2.0", id: 9, method: "bogus" }).error.code, -32601);
  assert.equal(handle({ id: 2, method: "tools/call", params: { name: "eval_add", arguments: { a: 2, b: 3 } } }).result.content[0].text, "5");
});
