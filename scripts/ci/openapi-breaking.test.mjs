// Tests for the OpenAPI breaking-change gate, against a hand-made fixture pair
// (testdata/openapi-breaking/base.json -> head.json) that has one of each break and a set of
// changes that must NOT be flagged. Run with: node --test scripts/ci/openapi-breaking.test.mjs
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { diffSpecs, parseAllowlist } from "./openapi-breaking.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const script = join(here, "openapi-breaking.mjs");
const base = join(here, "testdata", "openapi-breaking", "base.json");
const head = join(here, "testdata", "openapi-breaking", "head.json");
const load = (f) => JSON.parse(readFileSync(f, "utf8"));

const EXPECTED = [
  "enum-narrowed GET /api/agents parameter query:status",
  "operation-id-changed POST /api/agents",
  "operation-removed DELETE /api/agents/{id}",
  "parameter-required GET /api/agents parameter query:status",
  "path-removed /api/legacy",
  "request-body-required POST /api/notes",
  "request-property-required schemas.AgentRequest.model",
  "request-property-required schemas.AgentRequest.ownerId",
  "response-body-removed GET /api/agents/{agentId}/export response 200 application/json",
  "response-property-removed schemas.Agent.name",
  "type-changed schemas.Agent.temperature",
];

test("the fixture pair yields exactly one finding per breaking edit", () => {
  assert.deepEqual(diffSpecs(load(base), load(head)).map((f) => f.key), EXPECTED);
});

test("compatible edits are not flagged", () => {
  // Covered by the exact list above, spelled out: a renamed path variable, a new optional
  // parameter, a new path, a 200 -> 201 success code, a new response property, a widened enum on
  // an input, a required property with a default, and a request type widened to [string, null].
  const keys = diffSpecs(load(base), load(head)).map((f) => f.key).join("\n");
  for (const pattern of [/offset/, /new-thing/, /maxTokens/, /\.mode$/m, /createdAt/, /schemas\.Agent\.id/, /\/api\/notes request/]) {
    assert.doesNotMatch(keys, pattern);
  }
});

test("a spec compared with itself is clean", () => {
  assert.deepEqual(diffSpecs(load(head), load(head)), []);
});

test("an output type may narrow but not widen; an input may widen but not narrow", () => {
  const spec = (inType, outType) => ({
    paths: {
      "/x": {
        post: {
          operationId: "X_post",
          requestBody: { content: { "application/json": { schema: { type: inType } } } },
          responses: { 200: { description: "", content: { "application/json": { schema: { type: outType } } } } },
        },
      },
    },
  });
  assert.deepEqual(diffSpecs(spec("string", ["string", "null"]), spec(["string", "null"], "string")), []);
  assert.deepEqual(
    diffSpecs(spec(["string", "null"], "string"), spec("string", ["string", "null"])).map((f) => f.key),
    ["type-changed POST /x request", "type-changed POST /x response 200"],
  );
});

test("allowlist entries need a reason", () => {
  const { entries, errors } = parseAllowlist("# comment\n\npath-removed /a  # gone on purpose\npath-removed /b\n");
  assert.deepEqual([...entries.keys()], ["path-removed /a"]);
  assert.equal(errors.length, 1);
  assert.match(errors[0], /line 4/);
});

function run(allowlistText) {
  const dir = mkdtempSync(join(tmpdir(), "openapi-breaking-"));
  try {
    const allowlist = join(dir, "allowlist.txt");
    writeFileSync(allowlist, allowlistText);
    return spawnSync(process.execPath, [script, "--old", base, "--new", head, "--allowlist", allowlist], { encoding: "utf8" });
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

test("CLI exits 1 on breaking changes and prints each key", () => {
  const r = run("");
  assert.equal(r.status, 1);
  for (const key of EXPECTED) assert.ok(r.stderr.includes(key), `missing ${key}`);
});

test("CLI exits 0 when every finding is allowlisted with a reason", () => {
  const r = run(EXPECTED.map((k) => `${k}  # fixture`).join("\n"));
  assert.equal(r.status, 0, r.stderr);
  assert.match(r.stdout, /no breaking changes/);
});

test("CLI exits 1 on an allowlist entry without a reason, even when it matches", () => {
  const r = run(EXPECTED.map((k, i) => (i === 0 ? k : `${k}  # fixture`)).join("\n"));
  assert.equal(r.status, 1);
  assert.match(r.stderr, /no "# reason"/);
});
