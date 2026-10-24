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
const rulesBase = join(here, "testdata", "openapi-breaking", "rules-base.json");
const rulesHead = join(here, "testdata", "openapi-breaking", "rules-head.json");
const realSpec = join(here, "..", "..", "docs", "api", "openapi.json");
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

// rules-base.json -> rules-head.json: one edit per rule below, next to edits that must stay clean.
const EXPECTED_RULES = [
  "access-tightened GET /api/perm-all-added",
  "access-tightened GET /api/perm-any-lost",
  "access-tightened GET /api/role-added",
  "access-tightened GET /api/role-lost",
  "composition-narrowed schemas.Payload",
  "composition-widened schemas.Result",
  "constraint-loosened schemas.ThingResponse.big format",
  "constraint-loosened schemas.ThingResponse.limit maxLength",
  "constraint-loosened schemas.ThingResponse.slug pattern",
  "constraint-loosened schemas.ThingResponse.stamp format",
  "constraint-tightened schemas.ThingRequest.code pattern",
  "constraint-tightened schemas.ThingRequest.count minimum",
  "constraint-tightened schemas.ThingRequest.name maxLength",
  "constraint-tightened schemas.ThingRequest.tags minItems",
  "constraint-tightened schemas.ThingRequest.when format",
  "parameter-removed GET /api/search parameter header:x-trace",
  "parameter-removed GET /api/search parameter query:page",
  "request-property-removed schemas.ThingRequest.nickname",
  "response-property-removed schemas.Extended.extra",
  "response-required-relaxed schemas.ThingResponse.label",
  "security-tightened GET /api/lost-scheme",
  "security-tightened GET /api/was-public",
];

test("every access, parameter, property, constraint and composition rule fires once on its own edit", () => {
  assert.deepEqual(diffSpecs(load(rulesBase), load(rulesHead)).map((f) => f.key), EXPECTED_RULES);
});

test("compatible edits next to those breaks are not flagged", () => {
  const keys = diffSpecs(load(rulesBase), load(rulesHead)).map((f) => f.key).join("\n");
  for (const [what, pattern] of [
    ["an operation that became public", /now-public/],
    ["an operation that accepts one more scheme", /more-schemes/],
    ["a role requirement dropped", /role-dropped/],
    ["one more permission that suffices (anyOf)", /perm-any-added/],
    ["a parameter added", /sort/],
    ["a property removed where additionalProperties is true", /legacy/],
    ["an input limit relaxed (maxLength 10 -> 100, minLength 5 -> 0)", /ThingRequest\.(title|note)/],
    ["an input format widened (int32 -> int64)", /ThingRequest\.size/],
    ["an output bound tightened, an output format narrowed, a format added", /ThingResponse\.(capped|small|precise)/],
    ["an input oneOf that gained an alternative", /RelaxedRequest/],
  ]) {
    assert.doesNotMatch(keys, pattern, what);
  }
});

test("a spec compared with itself is clean, and the real spec does not trip the rules on itself", () => {
  assert.deepEqual(diffSpecs(load(rulesHead), load(rulesHead)), []);
  assert.deepEqual(diffSpecs(load(realSpec), load(realSpec)), []);
});

test("a security requirement is read from the operation, else the document", () => {
  const spec = (opSecurity, docSecurity) => ({
    ...(docSecurity ? { security: docSecurity } : {}),
    paths: { "/x": { get: { operationId: "X_get", ...(opSecurity ? { security: opSecurity } : {}), responses: { 200: { description: "" } } } } },
  });
  const bearer = [{ bearerAuth: [] }];
  assert.deepEqual(diffSpecs(spec(undefined, undefined), spec(undefined, bearer)).map((f) => f.key), ["security-tightened GET /x"]);
  assert.deepEqual(diffSpecs(spec(undefined, bearer), spec([], bearer)), []);
  assert.deepEqual(diffSpecs(spec(bearer, undefined), spec([{}, ...bearer], undefined)), []);
  assert.deepEqual(diffSpecs(spec([], undefined), spec([{}], undefined)), []);
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

test("CLI exits 1 on the access and constraint rules too", () => {
  const dir = mkdtempSync(join(tmpdir(), "openapi-breaking-"));
  try {
    const allowlist = join(dir, "allowlist.txt");
    writeFileSync(allowlist, "");
    const r = spawnSync(process.execPath, [script, "--old", rulesBase, "--new", rulesHead, "--allowlist", allowlist], { encoding: "utf8" });
    assert.equal(r.status, 1);
    for (const key of EXPECTED_RULES) assert.ok(r.stderr.includes(key), `missing ${key}`);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
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
