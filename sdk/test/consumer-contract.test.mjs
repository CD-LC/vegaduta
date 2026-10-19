// Consumer-contract tests for the generated TypeScript SDK (consumer "sdk-typescript").
// Run with: node --test sdk/test/*.test.mjs
//
// contracts/consumers/sdk-typescript/*.contract.json lists the core endpoints the SDK's documented
// usage (the TypeScript blocks of sdk/README.md, which docs/sdk/clients.md embeds) relies on.
// These tests fail when
//   - a contract is malformed, or names an endpoint/field docs/api/openapi.json does not declare;
//   - a README example calls an operation that is not in the contract (static: README call ->
//     generated client method -> HTTP method + path);
//   - running the README examples against the real generated client sends a request that is not in
//     the contract, or omits a field the contract says it sends (needs Node 22.18+/24 to load .ts).
// The provider side (core really answers with these fields) is verified in core/app.

import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import module from "node:module";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath, pathToFileURL } from "node:url";

import {
  checkAgainstSpec,
  codeBlocks,
  findInteraction,
  loadContracts,
  loadSpec,
  shapeErrors,
  statusDriftProblems,
} from "../../contracts/consumers/tools/consumer-contract.mjs";

const CONSUMER = "sdk-typescript";
const here = dirname(fileURLToPath(import.meta.url));
const sdkRoot = join(here, "..");
const contracts = loadContracts(CONSUMER);
const interactions = contracts.flatMap((c) => c.doc.interactions);
const readme = readFileSync(join(sdkRoot, "README.md"), "utf8");
const tsBlocks = codeBlocks(readme, ["ts", "typescript"]);

/** Generated client: client property -> { method name -> { method, path } }. */
function clientOperations() {
  const src = readFileSync(join(sdkRoot, "typescript", "src", "generated", "client.ts"), "utf8");
  const classOps = new Map();
  for (const chunk of src.split(/^export class /m).slice(1)) {
    const name = chunk.match(/^(\w+)/)[1];
    const ops = new Map();
    // JSDoc "* `POST /api/agents`" (more lines may follow), then "*/", then "name(".
    const re = /\*\s+`([A-Z]+) ([^`]+)`[^]*?\*\/\s*\n\s*(\w+)\(/g;
    let m;
    while ((m = re.exec(chunk))) ops.set(m[3], { method: m[1], path: m[2] });
    classOps.set(name, ops);
  }
  const props = new Map();
  for (const m of src.matchAll(/this\.(\w+) = new (\w+)\(this\.http\)/g)) props.set(m[1], classOps.get(m[2]) ?? new Map());
  return props;
}

/** Simple JSONPath ($.a.b, $[*].a, $.items[*].id) presence check against a value. */
function hasField(value, field) {
  const tokens = field.slice(1).match(/\.[A-Za-z_][\w-]*|\[\*\]/g) ?? [];
  let nodes = [value];
  for (const tok of tokens) {
    const next = [];
    for (const n of nodes) {
      if (tok === "[*]") {
        if (Array.isArray(n)) next.push(...n);
      } else if (n && typeof n === "object" && Object.hasOwn(n, tok.slice(1))) next.push(n[tok.slice(1)]);
    }
    if (next.length === 0) return false;
    nodes = next;
  }
  return true;
}

test("sdk-typescript has consumer contracts in the shared format", () => {
  assert.ok(contracts.length > 0, "no contracts/consumers/sdk-typescript/*.contract.json");
  for (const c of contracts) assert.deepEqual(shapeErrors(c.doc, CONSUMER), [], c.file);
  const ids = interactions.map((i) => i.id);
  assert.equal(new Set(ids).size, ids.length, `interaction ids repeat across files: ${ids}`);
});

test("every sdk-typescript interaction is declared in docs/api/openapi.json", (t) => {
  const spec = loadSpec();
  for (const it of interactions) {
    const { problems, untyped } = checkAgainstSpec(spec, it);
    assert.deepEqual(problems, [], `${it.id}: ${it.method} ${it.path}`);
    // An untyped (`{}`) schema cannot vouch for a field; the provider verifier still checks it.
    for (const u of untyped) t.diagnostic(`${it.id}: ${u} is untyped in the spec; left to the provider verifier`);
  }
});

test("the known spec status drift is still real (spec-status-drift.json)", () => {
  assert.deepEqual(statusDriftProblems(loadSpec()), []);
});

test("a contract status the spec does not document fails unless it is a recorded drift", () => {
  const spec = loadSpec();
  const create = interactions.find((i) => i.id === "agent-create");
  assert.equal(create.status, 201, "core answers POST /api/agents with 201 (AgentController.create)");
  // The spec documents 201 now (@ResponseStatus on AgentController.create), so no drift entry is needed.
  assert.deepEqual(checkAgainstSpec(spec, create, []).problems, []);
  // Simulate the old drift: the spec says 200 where core sends 201.
  const stale = structuredClone(spec);
  const responses = stale.paths["/api/agents"].post.responses;
  responses["200"] = responses["201"];
  delete responses["201"];
  assert.equal(checkAgainstSpec(stale, create, []).problems[0], "status 201 is not a documented response");
  const entry = { method: "POST", path: "/api/agents", coreStatus: 201, specStatus: 200 };
  assert.deepEqual(checkAgainstSpec(stale, create, [entry]).problems, []);
  assert.equal(checkAgainstSpec(spec, { ...create, status: 202 }).problems[0], "status 202 is not a documented response");
});

test("README TypeScript examples only call operations in the contract (static)", () => {
  const ops = clientOperations();
  assert.ok(ops.size > 0, "could not read the generated client");
  const seen = [];
  for (const block of tsBlocks) {
    for (const m of block.matchAll(/\b\w+\.(\w+)\.(\w+)\(/g)) {
      const group = ops.get(m[1]);
      if (!group) continue; // not a client call (e.g. process.stdout.write)
      const op = group.get(m[2]);
      assert.ok(op, `README calls ${m[1]}.${m[2]}(), which the generated client does not have`);
      seen.push(`${m[1]}.${m[2]}`);
      assert.ok(
        findInteraction(interactions, op.method, op.path.replace(/\{[^}]+\}/g, "x")),
        `README calls ${m[1]}.${m[2]}() = ${op.method} ${op.path}, which is not in contracts/consumers/${CONSUMER}`,
      );
    }
  }
  assert.ok(seen.length > 0, "found no client calls in the README TypeScript blocks");
});

// --- dynamic: run the README examples against the real generated client -------------------------

let canRun = false;
if (process.features?.typescript && typeof module.registerHooks === "function") {
  const srcDir = pathToFileURL(join(sdkRoot, "typescript", "src")).href;
  // The generated sources use extensionless relative imports ("../runtime"), as tsc expects.
  module.registerHooks({
    resolve(specifier, context, nextResolve) {
      if (specifier.startsWith(".") && !/\.[cm]?[jt]s$/.test(specifier) && context.parentURL?.startsWith(srcDir)) {
        return nextResolve(`${specifier}.ts`, context);
      }
      return nextResolve(specifier, context);
    },
  });
  canRun = true;
}
const skip = canRun ? false : "needs Node with TypeScript type stripping and module.registerHooks (22.18+ / 24)";

test("running the README TypeScript examples only hits contracted endpoints", { skip }, async () => {
  const indexUrl = pathToFileURL(join(sdkRoot, "typescript", "src", "index.ts")).href;
  const calls = [];
  const origFetch = globalThis.fetch;
  const origEnv = { ...process.env };
  const dir = mkdtempSync(join(tmpdir(), "vegaduta-sdk-contract-"));
  process.env.VEGADUTA_BASE_URL = "https://core.contract.test";
  process.env.VEGADUTA_TOKEN = "contract-token";
  process.env.VEGADUTA_GRANT_KEY = "contract-grant";
  globalThis.__contractSink = () => true;
  globalThis.fetch = async (input, init = {}) => {
    const url = new URL(String(input));
    const headers = new Headers(init.headers);
    let body;
    if (typeof init.body === "string" && headers.get("content-type")?.includes("json")) body = JSON.parse(init.body);
    calls.push({ method: init.method ?? "GET", path: url.pathname, body });
    // answer with the status the contract says core sends (201 for agent-create), 200 otherwise
    const status = findInteraction(interactions, init.method ?? "GET", url.pathname)?.status ?? 200;
    if (headers.get("accept") === "text/event-stream") {
      return new Response("data:ok\n\n", { status: 200, headers: { "content-type": "text/event-stream" } });
    }
    const json = JSON.stringify({ id: "00000000-0000-0000-0000-000000000001", reply: "ok" });
    return new Response(json, { status, headers: { "content-type": "application/json" } });
  };
  try {
    for (const [i, block] of tsBlocks.entries()) {
      const before = calls.length;
      let code = block
        .replace(/^import .* from "vegaduta-sdk";\s*$/gm, "")
        .replace(/process\.stdout\.write\(/g, "globalThis.__contractSink(")
        .replace(/console\.log\(/g, "globalThis.__contractSink(");
      const prelude = [`import { VegadutaClient } from ${JSON.stringify(indexUrl)};`];
      if (!/\bconst agentId\b/.test(code)) prelude.push('const agentId = "00000000-0000-0000-0000-000000000001";');
      if (!/\bconst client\b/.test(code)) prelude.push("const client = new VegadutaClient({});");
      code = `${prelude.join("\n")}\n${code}\nexport {};\n`;
      const file = join(dir, `readme-block-${i}.ts`);
      writeFileSync(file, code);
      await import(pathToFileURL(file).href);
      assert.ok(calls.length > before, `README TypeScript block ${i} made no request:\n${block}`);
    }
  } finally {
    globalThis.fetch = origFetch;
    delete globalThis.__contractSink;
    process.env = origEnv;
    rmSync(dir, { recursive: true, force: true });
  }
  for (const call of calls) {
    const it = findInteraction(interactions, call.method, call.path);
    assert.ok(it, `the SDK called ${call.method} ${call.path}, which is not in contracts/consumers/${CONSUMER}`);
    for (const f of it.request.requiredFields) {
      assert.ok(hasField(call.body, f), `${it.id}: the SDK did not send ${f} (body ${JSON.stringify(call.body)})`);
    }
  }
});

test("the hand-written runtime hard-codes no endpoint paths outside the contract", () => {
  const src = readFileSync(join(sdkRoot, "typescript", "src", "runtime.ts"), "utf8");
  for (const m of src.matchAll(/["'`](\/(?:api|v1|\.well-known)\/[^"'`\s]*)["'`]/g)) {
    assert.ok(
      interactions.some((it) => it.path === m[1]),
      `runtime.ts references ${m[1]}, which is not in contracts/consumers/${CONSUMER}`,
    );
  }
});
