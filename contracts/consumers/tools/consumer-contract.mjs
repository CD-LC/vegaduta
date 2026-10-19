// Consumer-contract helpers (Node, no dependencies), shared by the consumer-side tests of the
// generated TypeScript SDK (sdk/test/consumer-contract.test.mjs). The Python twin is
// consumer_contract.py next to this file; keep the two in step.
//
// A consumer contract is contracts/consumers/<consumer>/<area>.contract.json:
//   { "consumer", "provider": "core",
//     "interactions": [ { "id", "method", "path" (OpenAPI templated), "status",
//                         "request":  { "requiredFields": ["$.name", ...] },
//                         "response": { "requiredFields": ["$.id", "$.items[*].id", ...] } } ] }
//
// The consumer side checks two things: every interaction exists in docs/api/openapi.json with its
// fields declared, and the client never calls anything that is not an interaction. Whether core
// really answers with those fields is the provider verifier's job (core/app).

import { readdirSync, readFileSync, existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..", "..");
export const CONSUMERS_DIR = join(REPO_ROOT, "contracts", "consumers");
export const SPEC_PATH = join(REPO_ROOT, "docs", "api", "openapi.json");

const METHODS = new Set(["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"]);
const FIELD_RE = /^\$(?:\.[A-Za-z_][\w-]*|\[\*\])*$/;

/** Every interaction of one consumer, each tagged with the file it came from. */
export function loadContracts(consumer) {
  const dir = join(CONSUMERS_DIR, consumer);
  if (!existsSync(dir)) return [];
  return readdirSync(dir)
    .filter((f) => f.endsWith(".contract.json"))
    .sort()
    .map((f) => ({ file: join(dir, f), doc: JSON.parse(readFileSync(join(dir, f), "utf8")) }));
}

/** Shape errors of one contract document (empty when it follows the shared format). */
export function shapeErrors(doc, consumer) {
  const errs = [];
  if (doc.consumer !== consumer) errs.push(`consumer is ${JSON.stringify(doc.consumer)}, expected ${consumer}`);
  if (doc.provider !== "core") errs.push(`provider must be "core"`);
  if (!Array.isArray(doc.interactions) || doc.interactions.length === 0) errs.push("interactions must be a non-empty array");
  const ids = new Set();
  for (const it of doc.interactions ?? []) {
    const where = `interaction ${JSON.stringify(it.id)}`;
    if (typeof it.id !== "string" || !it.id) errs.push(`${where}: id is required`);
    if (ids.has(it.id)) errs.push(`${where}: duplicate id`);
    ids.add(it.id);
    if (!METHODS.has(it.method)) errs.push(`${where}: method ${it.method} is not an HTTP method`);
    if (typeof it.path !== "string" || !it.path.startsWith("/")) errs.push(`${where}: path must start with /`);
    if (!Number.isInteger(it.status)) errs.push(`${where}: status must be an integer`);
    for (const side of ["request", "response"]) {
      const fields = it[side]?.requiredFields;
      if (!Array.isArray(fields)) errs.push(`${where}: ${side}.requiredFields must be an array`);
      else for (const f of fields) if (!FIELD_RE.test(f)) errs.push(`${where}: ${side} field ${f} is not $.a / $.a[*].b`);
    }
  }
  return errs;
}

export function loadSpec() {
  return JSON.parse(readFileSync(SPEC_PATH, "utf8"));
}

export const DRIFT_PATH = join(CONSUMERS_DIR, "tools", "spec-status-drift.json");

/** Known wrong success statuses in the spec (see spec-status-drift.json). */
export function loadStatusDrift() {
  return existsSync(DRIFT_PATH) ? JSON.parse(readFileSync(DRIFT_PATH, "utf8")).drift ?? [] : [];
}

/** Problems with the drift list itself: an entry the spec has caught up with, or whose proof is gone. */
export function statusDriftProblems(spec, drift = loadStatusDrift()) {
  const problems = [];
  for (const d of drift) {
    const where = `spec-status-drift ${d.method} ${d.path}`;
    const op = spec.paths?.[d.path]?.[String(d.method).toLowerCase()];
    if (!op) problems.push(`${where}: operation is not in docs/api/openapi.json`);
    else {
      if (op.responses?.[String(d.coreStatus)]) problems.push(`${where}: the spec now documents ${d.coreStatus}; remove the entry`);
      if (!op.responses?.[String(d.specStatus)]) problems.push(`${where}: the spec no longer documents ${d.specStatus}`);
    }
    const src = join(REPO_ROOT, d.source ?? "");
    if (!d.source || !existsSync(src)) problems.push(`${where}: source ${d.source} does not exist`);
    else if (!readFileSync(src, "utf8").includes(d.sourceContains)) {
      problems.push(`${where}: ${d.source} no longer contains the line proving ${d.coreStatus}`);
    }
  }
  return problems;
}

function deref(spec, schema, seen = new Set()) {
  let s = schema ?? {};
  while (s && s.$ref) {
    if (seen.has(s.$ref)) return {};
    seen.add(s.$ref);
    const parts = s.$ref.replace(/^#\//, "").split("/");
    s = parts.reduce((node, p) => node?.[p], spec) ?? {};
  }
  return s;
}

function isUntyped(s) {
  return !s.type && !s.properties && !s.items && !s.allOf && !s.oneOf && !s.anyOf && !s.$ref;
}

/** "ok" | "untyped" (schema is {} so the spec cannot say) | "missing" */
export function resolveField(spec, schema, field) {
  const tokens = field.slice(1).match(/\.[A-Za-z_][\w-]*|\[\*\]/g) ?? [];
  let candidates = [deref(spec, schema)];
  for (const tok of tokens) {
    const next = [];
    for (const raw of candidates) {
      const s = deref(spec, raw);
      if (isUntyped(s)) return "untyped";
      const variants = [s, ...(s.allOf ?? []), ...(s.oneOf ?? []), ...(s.anyOf ?? [])].map((v) => deref(spec, v));
      for (const v of variants) {
        if (tok === "[*]") {
          if (v.type === "array" && v.items) next.push(v.items);
        } else {
          const name = tok.slice(1);
          if (v.properties?.[name]) next.push(v.properties[name]);
          else if (v.additionalProperties && typeof v.additionalProperties === "object") next.push(v.additionalProperties);
        }
      }
    }
    if (next.length === 0) return "missing";
    candidates = next;
  }
  return "ok";
}

/** Problems of one interaction against the spec; `untyped` lists fields the spec cannot vouch for. */
export function checkAgainstSpec(spec, it, drift = loadStatusDrift()) {
  const problems = [];
  const untyped = [];
  const op = spec.paths?.[it.path]?.[it.method.toLowerCase()];
  if (!op) return { problems: [`${it.method} ${it.path} is not in docs/api/openapi.json`], untyped };
  const reqSchema = op.requestBody?.content?.["application/json"]?.schema;
  for (const f of it.request.requiredFields) {
    if (!reqSchema) problems.push(`request field ${f}: operation has no JSON request body`);
    else {
      const r = resolveField(spec, reqSchema, f);
      if (r === "missing") problems.push(`request field ${f} is not declared in the request schema`);
      if (r === "untyped") untyped.push(`request ${f}`);
    }
  }
  let resp = op.responses?.[String(it.status)];
  if (!resp) {
    // core sends it.status but the reflection-built spec documents another one (spec-status-drift.json)
    const d = drift.find((x) => x.method === it.method && x.path === it.path && x.coreStatus === it.status);
    resp = d ? op.responses?.[String(d.specStatus)] : undefined;
  }
  if (!resp) problems.push(`status ${it.status} is not a documented response`);
  const respSchema = deref(spec, resp)?.content?.["application/json"]?.schema;
  for (const f of it.response.requiredFields) {
    if (!respSchema) problems.push(`response field ${f}: response ${it.status} has no JSON body`);
    else {
      const r = resolveField(spec, respSchema, f);
      if (r === "missing") problems.push(`response field ${f} is not declared in the response schema`);
      if (r === "untyped") untyped.push(`response ${f}`);
    }
  }
  return { problems, untyped };
}

/** Does a concrete request path ("/api/agents/abc/chat") match an OpenAPI template? */
export function pathMatches(template, concrete) {
  const re = new RegExp(
    "^" + template.split(/(\{[^}]+\})/).map((p) => (p.startsWith("{") ? "[^/]+" : p.replace(/[.*+?^$()|[\]\\]/g, "\\$&"))).join("") + "$",
  );
  return re.test(concrete);
}

/** The interaction a recorded call hits, or undefined. */
export function findInteraction(interactions, method, path) {
  return interactions.find((it) => it.method === method && pathMatches(it.path, path));
}

/** Fenced code blocks of one language from a Markdown file. */
export function codeBlocks(markdown, langs) {
  const out = [];
  const re = /^```(\w+)\n([\s\S]*?)^```/gm;
  let m;
  while ((m = re.exec(markdown))) if (langs.includes(m[1])) out.push(m[2]);
  return out;
}
