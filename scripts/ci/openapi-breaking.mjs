#!/usr/bin/env node
// Breaking-change gate for the committed REST contract (docs/api/openapi.json). Plain Node (ESM), no
// dependencies. Compares the working-tree spec with the spec at the merge base and fails on a change
// that can break an existing client:
//
//   path-removed               a path (by template; `{id}` and `{agentId}` are the same path) is gone
//   operation-removed          a verb on a surviving path is gone
//   operation-id-changed       an operation's operationId changed (the SDK method name derives from it)
//   parameter-required         a parameter is new and required, or was optional and is now required
//   parameter-removed          a query, header or cookie parameter is gone (its feature is lost)
//   security-tightened         an operation that needed no credentials now needs some, or stops accepting
//                              a security scheme it accepted (`security`, falling back to the document's)
//   access-tightened           an operation now needs a role or permission it did not (`x-roles`,
//                              `x-permissions`), or a role/permission that sufficed no longer does
//   request-body-required      a request body is now required
//   request-media-type-removed the request body no longer accepts a media type it accepted
//   request-property-required  a request property is newly required (one with a `default` is exempt:
//                              the server fills it, e.g. a Java primitive)
//   request-property-removed   a request property is gone (the server now ignores what clients send),
//                              unless the schema says `additionalProperties: true`
//   constraint-tightened       an input gained or tightened minLength/maxLength, minimum/maximum, minItems/
//                              maxItems, pattern or format
//   composition-narrowed       an input's oneOf/anyOf lost an alternative
//   response-body-removed      a success (2xx) response no longer has the body it had
//   response-property-removed  a success-response property is gone
//   response-required-relaxed  a success-response property that was always present may now be missing
//   constraint-loosened        a success response lost or loosened a bound or pattern, or changed format
//   composition-widened        a success response's oneOf/anyOf gained an alternative
//   enum-narrowed              an enum lost values (anywhere), or an input gained an enum it lacked
//   type-changed               a schema's `type` changed (an input may widen, an output may narrow)
//
// Not gated: a success status that moves within 2xx (200 to 201) is compared as the same response, so the
// body rules above still apply to it; docs/api/DEPRECATION.md says so.
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

  compareAccess(ctx, before, after, where);

  const oldParams = parameterIndex(ctx.oldSpec, before);
  const newParams = parameterIndex(ctx.newSpec, after);
  for (const key of oldParams.keys()) {
    if (!newParams.has(key) && !key.startsWith("path:")) add(ctx, "parameter-removed", `${where} parameter ${key}`, "no longer in the operation");
  }
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

/** Credentials: `null` when anyone may call (no `security`, `[]`, or an alternative that is `{}`), else the scheme names. */
function requiredSchemes(spec, op) {
  const security = op.security ?? spec.security;
  if (!Array.isArray(security) || security.length === 0 || security.some((r) => Object.keys(r ?? {}).length === 0)) return null;
  return new Set(security.flatMap((r) => Object.keys(r)));
}

function compareAccess(ctx, before, after, where) {
  const was = requiredSchemes(ctx.oldSpec, before.op);
  const now = requiredSchemes(ctx.newSpec, after.op);
  if (!was && now) {
    add(ctx, "security-tightened", where, `was public, now requires ${[...now].join(" or ")}`);
  } else if (was && now) {
    const lost = [...was].filter((n) => !now.has(n));
    if (lost.length) add(ctx, "security-tightened", where, `no longer accepts ${lost.join(", ")}`);
  }

  // x-roles is any-of; x-permissions is { anyOf, allOf }: allowed = holds every allOf and, when anyOf is set, one of it.
  const reasons = [];
  const roles = [before.op["x-roles"] ?? [], after.op["x-roles"] ?? []];
  const lostRoles = roles[0].filter((r) => !roles[1].includes(r));
  if (roles[1].length && (!roles[0].length || lostRoles.length)) {
    reasons.push(roles[0].length ? `role ${lostRoles.join(", ")} no longer suffices` : `now needs role ${roles[1].join(" or ")}`);
  }
  const [oldPerm, newPerm] = [before.op, after.op].map((o) => ({ any: o["x-permissions"]?.anyOf ?? [], all: o["x-permissions"]?.allOf ?? [] }));
  const extra = newPerm.all.filter((p) => !oldPerm.all.includes(p));
  if (extra.length) reasons.push(`now also needs ${extra.join(", ")}`);
  const lostAny = oldPerm.any.filter((p) => !newPerm.any.includes(p));
  if (newPerm.any.length && (!oldPerm.any.length || lostAny.length)) {
    reasons.push(oldPerm.any.length ? `permission ${lostAny.join(", ")} no longer suffices` : `now needs one of ${newPerm.any.join(", ")}`);
  }
  if (reasons.length) add(ctx, "access-tightened", where, reasons.join("; "));
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
  const a = flatten(ctx.oldSpec, oldNode);
  const b = flatten(ctx.newSpec, newNode);
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

  compareConstraints(ctx, a, b, where, dir);
  compareComposition(ctx, a, b, where, dir);

  const pa = a.properties ?? {};
  const pb = b.properties ?? {};
  if (dir === "out" && (b.properties || b.type === "object")) {
    for (const name of Object.keys(pa)) {
      if (!(name in pb)) add(ctx, "response-property-removed", `${where}.${name}`, "property is gone from the response");
    }
    const after = new Set(b.required ?? []);
    for (const name of a.required ?? []) {
      if (name in pb && !after.has(name)) add(ctx, "response-required-relaxed", `${where}.${name}`, "was always present, now optional");
    }
  }
  if (dir === "in") {
    if (b.additionalProperties !== true && !isObj(b.additionalProperties) && (b.properties || b.type === "object")) {
      for (const name of Object.keys(pa)) {
        if (!(name in pb)) add(ctx, "request-property-removed", `${where}.${name}`, "the server no longer reads this property");
      }
    }
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

const LOWER_BOUNDS = ["minLength", "minimum", "exclusiveMinimum", "minItems", "minProperties"];
const UPPER_BOUNDS = ["maxLength", "maximum", "exclusiveMaximum", "maxItems", "maxProperties"];
// A format that only widens an input (or only narrows an output) is not a break.
const FORMAT_WIDENS = new Set(["int32>int64", "float>double"]);

/** An input must not accept less than it did; an output must not promise less than it did. */
function compareConstraints(ctx, a, b, where, dir) {
  const num = (v) => (typeof v === "number" ? v : undefined);
  const [rule, verb] = dir === "in" ? ["constraint-tightened", "tightened"] : ["constraint-loosened", "loosened"];
  const stricter = (oldV, newV, lower) => {
    if (oldV === undefined && newV === undefined) return false;
    // `from` is what the side that is allowed to be strict promised; `to` what it promises now.
    const [from, to] = dir === "in" ? [oldV, newV] : [newV, oldV];
    if (to === undefined) return false;
    return from === undefined || (lower ? to > from : to < from);
  };
  for (const [keys, lower] of [[LOWER_BOUNDS, true], [UPPER_BOUNDS, false]]) {
    for (const key of keys) {
      if (stricter(num(a[key]), num(b[key]), lower)) add(ctx, rule, `${where} ${key}`, `${key} ${a[key] ?? "unset"} -> ${b[key] ?? "unset"} (${verb})`);
    }
  }
  const changed = (oldV, newV) => (dir === "in" ? newV !== undefined && newV !== oldV : oldV !== undefined && newV !== oldV);
  if (typeof a.pattern === "string" || typeof b.pattern === "string") {
    if (changed(a.pattern, b.pattern)) add(ctx, rule, `${where} pattern`, `pattern ${a.pattern ?? "unset"} -> ${b.pattern ?? "unset"} (${verb})`);
  }
  if (typeof a.format === "string" || typeof b.format === "string") {
    const widened = dir === "in" ? FORMAT_WIDENS.has(`${a.format}>${b.format}`) : FORMAT_WIDENS.has(`${b.format}>${a.format}`);
    if (changed(a.format, b.format) && !widened) add(ctx, rule, `${where} format`, `format ${a.format ?? "unset"} -> ${b.format ?? "unset"} (${verb})`);
  }
}

/** oneOf/anyOf alternatives, matched by `$ref` name or `type`: an input may gain some, an output may lose some. */
function compareComposition(ctx, a, b, where, dir) {
  const id = (spec, n) => refName(n) ?? JSON.stringify(deref(spec, n)?.type ?? n);
  for (const key of ["oneOf", "anyOf"]) {
    if (!Array.isArray(a[key]) && !Array.isArray(b[key])) continue;
    const oldIds = new Set((a[key] ?? []).map((n) => id(ctx.oldSpec, n)));
    const newIds = new Set((b[key] ?? []).map((n) => id(ctx.newSpec, n)));
    if (dir === "in" && Array.isArray(a[key])) {
      const lost = [...oldIds].filter((x) => !newIds.has(x));
      if (lost.length) add(ctx, "composition-narrowed", where, `${key} no longer accepts ${lost.join(", ")}`);
    }
    if (dir === "out" && Array.isArray(b[key])) {
      const gained = [...newIds].filter((x) => !oldIds.has(x));
      if (gained.length) add(ctx, "composition-widened", where, `${key} now also answers ${gained.join(", ")}`);
    }
    for (const x of oldIds) {
      const na = a[key].find((n) => id(ctx.oldSpec, n) === x);
      const nb = (b[key] ?? []).find((n) => id(ctx.newSpec, n) === x);
      if (na && nb) compareSchema(ctx, na, nb, `${where}<${x.replace(/"/g, "")}>`, dir);
    }
  }
}

/** The schema with `$ref` resolved and `allOf` members merged in (properties and required are unions). */
function flatten(spec, node, depth = 0) {
  const s = deref(spec, node);
  if (!isObj(s) || !Array.isArray(s.allOf) || depth > 16) return s;
  const { allOf, ...rest } = s;
  const out = { ...rest };
  for (const member of allOf) {
    const m = flatten(spec, member, depth + 1);
    if (!isObj(m)) continue;
    for (const [k, v] of Object.entries(m)) {
      if (k === "properties") out.properties = { ...v, ...out.properties };
      else if (k === "required") out.required = [...new Set([...(out.required ?? []), ...v])];
      else if (!(k in out)) out[k] = v;
    }
  }
  return out;
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
