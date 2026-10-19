#!/usr/bin/env node
// Breaking-change gate for the committed REST contract (docs/api/openapi.json). Plain Node (ESM), no
// dependencies. Compares the working-tree spec with the spec at the merge base and fails on a change
// that can break an existing client:
//
//   path-removed               a path (by template; `{id}` and `{agentId}` are the same path) is gone
//   operation-removed          a verb on a surviving path is gone
//   operation-id-changed       an operation's operationId changed (the SDK method name derives from it)
//   parameter-required         a parameter is new and required, or was optional and is now required
//   request-body-required      a request body is now required
//   request-media-type-removed the request body no longer accepts a media type it accepted
//   request-property-required  a request property is newly required (one with a `default` is exempt:
//                              the server fills it, e.g. a Java primitive)
//   response-body-removed      a success (2xx) response no longer has the body it had
//   response-property-removed  a success-response property is gone
//   enum-narrowed              an enum lost values (anywhere), or an input gained an enum it lacked
//   type-changed               a schema's `type` changed (an input may widen, an output may narrow)
//
//   node scripts/ci/openapi-breaking.mjs                    # against the merge base with the base branch
//   node scripts/ci/openapi-breaking.mjs --base <ref>       # against the merge base with <ref>
//   node scripts/ci/openapi-breaking.mjs --old a.json --new b.json   # two files, no git
//   node scripts/ci/openapi-breaking.mjs --allowlist <file>          # another allowlist
//
// Base ref: --base, else OPENAPI_BREAKING_BASE_REF, else origin/$GITHUB_BASE_REF (a PR in Actions),
// else main or origin/main. When the base cannot be resolved, or the spec did not exist there, the gate
// warns and passes (same policy as flyway-lint.sh).
//
// Escape hatch: scripts/ci/openapi-breaking-allowlist.txt, one finding key per line followed by a
// mandatory `# reason`. The key is printed with every finding. Exit 0 clean, 1 breaking, 2 usage error.

import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const VERBS = ["get", "put", "post", "delete", "options", "head", "patch", "trace"];
const SPEC_PATH = "docs/api/openapi.json";

/** Pure: two OpenAPI documents in, a sorted, de-duplicated list of `{ rule, where, detail, key }` out. */
export function diffSpecs(oldSpec, newSpec) {
  const ctx = { oldSpec, newSpec, findings: new Map(), compared: new Set() };
  const oldOps = indexOperations(oldSpec);
  const newOps = indexOperations(newSpec);
  const newPaths = new Set([...newOps.values()].map((o) => o.template));
  const reportedPaths = new Set();
  for (const [key, before] of oldOps) {
    const after = newOps.get(key);
    if (!after) {
      if (!newPaths.has(before.template)) {
        if (!reportedPaths.has(before.template)) {
          reportedPaths.add(before.template);
          add(ctx, "path-removed", before.path, "every operation on this path is gone");
        }
      } else {
        add(ctx, "operation-removed", `${before.verb.toUpperCase()} ${before.path}`, `operationId ${before.op.operationId ?? "(none)"}`);
      }
      continue;
    }
    compareOperation(ctx, before, after);
  }
  return [...ctx.findings.values()].sort((a, b) => a.key.localeCompare(b.key));
}

function add(ctx, rule, where, detail) {
  const key = `${rule} ${where}`;
  if (!ctx.findings.has(key)) ctx.findings.set(key, { rule, where, detail, key });
}

/** `VERB template` -> operation, where the template blanks path-variable names. */
function indexOperations(spec) {
  const out = new Map();
  for (const [path, item] of Object.entries(spec.paths ?? {})) {
    const template = path.replace(/\{[^}]*\}/g, "{}");
    for (const verb of VERBS) {
      const op = item?.[verb];
      if (op) out.set(`${verb} ${template}`, { path, template, verb, op, pathParams: item.parameters ?? [] });
    }
  }
  return out;
}

function compareOperation(ctx, before, after) {
  const where = `${after.verb.toUpperCase()} ${after.path}`;
  const oldId = before.op.operationId;
  const newId = after.op.operationId;
  if (oldId && oldId !== newId) add(ctx, "operation-id-changed", where, `${oldId} -> ${newId ?? "(none)"}`);

  const oldParams = parameterIndex(ctx.oldSpec, before);
  const newParams = parameterIndex(ctx.newSpec, after);
  for (const [key, param] of newParams) {
    const prior = oldParams.get(key);
    const label = `${where} parameter ${key}`;
    if (param.required && !param.key.startsWith("path:") && (!prior || !prior.required) && param.schema?.default === undefined) {
      add(ctx, "parameter-required", label, prior ? "was optional, now required" : "new required parameter");
    }
    if (prior?.schema && param.schema) compareSchema(ctx, prior.schema, param.schema, label, "in");
  }

  const oldBody = deref(ctx.oldSpec, before.op.requestBody);
  const newBody = deref(ctx.newSpec, after.op.requestBody);
  if (newBody?.required && !oldBody?.required) {
    add(ctx, "request-body-required", where, oldBody ? "was optional, now required" : "new required request body");
  }
  for (const [media, content] of Object.entries(oldBody?.content ?? {})) {
    const next = newBody?.content?.[media];
    if (!next) {
      if (newBody) add(ctx, "request-media-type-removed", `${where} request ${media}`, "no longer accepted");
      continue;
    }
    if (content.schema && next.schema) compareSchema(ctx, content.schema, next.schema, `${where} request`, "in");
  }

  const newSuccess = successCodes(after.op);
  for (const code of successCodes(before.op)) {
    const oldResp = deref(ctx.oldSpec, before.op.responses[code]);
    const target = after.op.responses?.[code] ? code : newSuccess[0];
    const newResp = target ? deref(ctx.newSpec, after.op.responses[target]) : undefined;
    for (const [media, content] of Object.entries(oldResp?.content ?? {})) {
      if (!content.schema) continue;
      const next = newResp?.content?.[media];
      const label = `${where} response ${code}`;
      if (!next?.schema) {
        add(ctx, "response-body-removed", `${label} ${media}`, target ? `no ${media} body on ${target}` : "no success response");
        continue;
      }
      compareSchema(ctx, content.schema, next.schema, label, "out");
    }
  }
}

function successCodes(op) {
  return Object.keys(op.responses ?? {}).filter((c) => /^2(\d\d|XX)$/.test(c)).sort();
}

/** Path parameters are keyed by position (renaming `{id}` breaks no client); the rest by `in:name`. */
function parameterIndex(spec, entry) {
  const merged = new Map();
  for (const raw of [...entry.pathParams, ...(entry.op.parameters ?? [])]) {
    const p = deref(spec, raw);
    if (p?.name && p.in) merged.set(`${p.in}:${p.in === "header" ? p.name.toLowerCase() : p.name}`, p);
  }
  const names = [...entry.path.matchAll(/\{([^}:]*)/g)].map((m) => m[1]);
  const out = new Map();
  for (const [k, p] of merged) {
    const key = p.in === "path" && names.includes(p.name) ? `path:#${names.indexOf(p.name)}` : k;
    out.set(key, { ...p, key });
  }
  return out;
}

function deref(spec, node, depth = 0) {
  if (!node || typeof node !== "object" || typeof node.$ref !== "string" || depth > 32) return node;
  return deref(spec, resolvePointer(spec, node.$ref), depth + 1);
}

function resolvePointer(spec, ref) {
  if (!ref.startsWith("#/")) return undefined;
  let cur = spec;
  for (const part of ref.slice(2).split("/")) {
    cur = cur?.[part.replace(/~1/g, "/").replace(/~0/g, "~")];
  }
  return cur;
}

function refName(node) {
  return typeof node?.$ref === "string" ? node.$ref.split("/").pop() : null;
}

/**
 * Walks two schemas side by side. `dir` is "in" (request bodies and parameters: the client writes it)
 * or "out" (success responses: the client reads it). A shared component is compared once per direction
 * and its findings are reported at `schemas.<Name>` rather than once per operation that uses it.
 */
function compareSchema(ctx, oldNode, newNode, where, dir) {
  const oldName = refName(oldNode);
  const newName = refName(newNode);
  if (newName) {
    const memo = `${oldName ?? "(inline)"}>${newName}|${dir}`;
    if (oldName && ctx.compared.has(memo)) return;
    if (oldName) ctx.compared.add(memo);
    where = `schemas.${newName}`;
  }
  const a = deref(ctx.oldSpec, oldNode);
  const b = deref(ctx.newSpec, newNode);
  if (!a || !b || typeof a !== "object" || typeof b !== "object") return;

  const ta = typeSet(a);
  const tb = typeSet(b);
  if (ta && tb && !sameSet(ta, tb)) {
    const widened = [...ta].every((t) => tb.has(t));
    const narrowed = [...tb].every((t) => ta.has(t));
    if (!((dir === "in" && widened) || (dir === "out" && narrowed))) {
      add(ctx, "type-changed", where, `${[...ta].join("|")} -> ${[...tb].join("|")}`);
    }
  }

  if (Array.isArray(b.enum)) {
    if (Array.isArray(a.enum)) {
      const kept = new Set(b.enum.map(String));
      const lost = a.enum.filter((v) => !kept.has(String(v)));
      if (lost.length) add(ctx, "enum-narrowed", where, `removed ${lost.map((v) => JSON.stringify(v)).join(", ")}`);
    } else if (dir === "in") {
      add(ctx, "enum-narrowed", where, `now restricted to ${b.enum.map((v) => JSON.stringify(v)).join(", ")}`);
    }
  }

  const pa = a.properties ?? {};
  const pb = b.properties ?? {};
  if (dir === "out" && (b.properties || b.type === "object")) {
    for (const name of Object.keys(pa)) {
      if (!(name in pb)) add(ctx, "response-property-removed", `${where}.${name}`, "property is gone from the response");
    }
  }
  if (dir === "in") {
    const before = new Set(a.required ?? []);
    for (const name of b.required ?? []) {
      if (before.has(name) || deref(ctx.newSpec, pb[name])?.default !== undefined) continue;
      add(ctx, "request-property-required", `${where}.${name}`, name in pa ? "was optional, now required" : "new required property");
    }
  }
  for (const name of Object.keys(pa)) {
    if (name in pb) compareSchema(ctx, pa[name], pb[name], `${where}.${name}`, dir);
  }
  if (a.items && b.items) compareSchema(ctx, a.items, b.items, `${where}[]`, dir);
  if (isObj(a.additionalProperties) && isObj(b.additionalProperties)) {
    compareSchema(ctx, a.additionalProperties, b.additionalProperties, `${where}{}`, dir);
  }
}

function isObj(v) {
  return v !== null && typeof v === "object";
}

function typeSet(s) {
  if (s.type === undefined) return null;
  return new Set([].concat(s.type).map(String));
}

function sameSet(a, b) {
  return a.size === b.size && [...a].every((x) => b.has(x));
}

/** Pure: allowlist text in, `{ entries: Map<key, reason>, errors: string[] }` out. */
export function parseAllowlist(text) {
  const entries = new Map();
  const errors = [];
  text.split(/\r?\n/).forEach((line, i) => {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#")) return;
    const hash = trimmed.indexOf("#");
    const key = (hash < 0 ? trimmed : trimmed.slice(0, hash)).trim().replace(/\s+/g, " ");
    const reason = hash < 0 ? "" : trimmed.slice(hash + 1).trim();
    if (!reason) errors.push(`line ${i + 1}: entry has no "# reason": ${key}`);
    else entries.set(key, reason);
  });
  return { entries, errors };
}

function git(args, cwd) {
  return execFileSync("git", args, { cwd, encoding: "utf8", stdio: ["ignore", "pipe", "ignore"], maxBuffer: 256 << 20 });
}

function resolveBase(explicit, cwd) {
  const usable = (r) => r && !/^0+$/.test(r);
  const candidates = [];
  if (usable(explicit)) candidates.push(explicit);
  else if (usable(process.env.OPENAPI_BREAKING_BASE_REF)) candidates.push(process.env.OPENAPI_BREAKING_BASE_REF);
  else if (process.env.GITHUB_BASE_REF) candidates.push(`origin/${process.env.GITHUB_BASE_REF}`);
  else candidates.push("main", "origin/main");
  for (const ref of candidates) {
    try {
      git(["rev-parse", "--verify", "--quiet", `${ref}^{commit}`], cwd);
      return { ref, mergeBase: git(["merge-base", ref, "HEAD"], cwd).trim() };
    } catch {
      // try the next candidate
    }
  }
  return { ref: candidates.join(" or "), mergeBase: null };
}

function parseArgs(argv) {
  const root = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
  const args = { root, allowlist: join(root, "scripts", "ci", "openapi-breaking-allowlist.txt") };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--base") args.base = argv[++i];
    else if (a === "--old") args.old = resolve(argv[++i] ?? "");
    else if (a === "--new") args.new = resolve(argv[++i] ?? "");
    else if (a === "--allowlist") args.allowlist = resolve(argv[++i] ?? "");
    else if (a === "--help" || a === "-h") args.help = true;
    else throw new Error(`unknown argument: ${a}`);
  }
  return args;
}

function main() {
  let args;
  try {
    args = parseArgs(process.argv.slice(2));
  } catch (e) {
    console.error(e.message);
    process.exit(2);
  }
  if (args.help) {
    console.log("usage: node scripts/ci/openapi-breaking.mjs [--base <ref> | --old <a.json>] [--new <b.json>] [--allowlist <file>]");
    return;
  }
  const newFile = args.new ?? join(args.root, SPEC_PATH);
  if (!existsSync(newFile)) {
    console.error(`openapi-breaking: ${newFile} not found`);
    process.exit(2);
  }
  let oldText;
  let against;
  if (args.old) {
    oldText = readFileSync(args.old, "utf8");
    against = args.old;
  } else {
    const { ref, mergeBase } = resolveBase(args.base, args.root);
    if (!mergeBase) {
      console.warn(`openapi-breaking: WARNING - could not resolve base ref ${ref} (or a merge base with HEAD); skipping. Set OPENAPI_BREAKING_BASE_REF or fetch the base branch.`);
      return;
    }
    try {
      oldText = git(["show", `${mergeBase}:${SPEC_PATH}`], args.root);
    } catch {
      console.log(`openapi-breaking: ${SPEC_PATH} does not exist at ${mergeBase}; nothing to compare.`);
      return;
    }
    against = `merge base ${mergeBase.slice(0, 12)} (base ref: ${ref})`;
  }

  const findings = diffSpecs(JSON.parse(oldText), JSON.parse(readFileSync(newFile, "utf8")));
  const { entries, errors } = existsSync(args.allowlist)
    ? parseAllowlist(readFileSync(args.allowlist, "utf8"))
    : { entries: new Map(), errors: [] };
  for (const e of errors) console.error(`openapi-breaking: allowlist ${e}`);

  const allowed = findings.filter((f) => entries.has(f.key));
  const breaking = findings.filter((f) => !entries.has(f.key));
  const used = new Set(allowed.map((f) => f.key));
  for (const f of allowed) console.log(`allowed: ${f.key}  (${entries.get(f.key)})`);
  for (const key of entries.keys()) {
    if (!used.has(key)) console.warn(`openapi-breaking: allowlist entry matches nothing against this base (remove it once merged): ${key}`);
  }
  if (breaking.length) {
    const label = args.new ? newFile : SPEC_PATH;
    console.error(`openapi-breaking: ${breaking.length} breaking change(s) in ${label} against ${against}:`);
    for (const f of breaking) console.error(`  ${f.key}\n      ${f.detail}`);
    console.error(
      "Keep the old shape (add, don't remove or tighten), or, when the break is deliberate, add the key\n" +
        "above to scripts/ci/openapi-breaking-allowlist.txt with a `# reason` and a CHANGELOG entry.",
    );
  }
  if (breaking.length || errors.length) process.exit(1);
  console.log(`openapi-breaking: no breaking changes against ${against}.`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main();
}
